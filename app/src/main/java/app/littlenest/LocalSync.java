package app.littlenest;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

final class LocalSync implements AutoCloseable {
    private static final String ALIAS = "little-nest-local-tls";
    private static final String TYPE = "_littlenest._tcp.";
    private final Context context;
    private final SharedPreferences prefs;
    private final Runnable updated;
    private final ExecutorService serverWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService clientWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing = new AtomicBoolean(), resolving = new AtomicBoolean();
    private volatile boolean closed;
    private volatile SSLServerSocket server;
    private volatile SSLSocket incoming, outgoing;
    private volatile long pairingUntil;
    private volatile String pairingSecret = "";
    private String fingerprint;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;

    LocalSync(Context context, Runnable updated) {
        this.context = context.getApplicationContext(); this.updated = updated;
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    boolean paired() { return !prefs.getString("localPeer", "").isEmpty(); }
    boolean busy() { return syncing.get(); }

    void start() {
        serverWorker.execute(() -> {
            try {
                identity();
                SSLServerSocket listener = (SSLServerSocket) tls(null).getServerSocketFactory().createServerSocket(0, 4);
                listener.setNeedClientAuth(true); listener.setEnabledProtocols(new String[]{"TLSv1.2"});
                server = listener;
                if (closed) { listener.close(); return; }
                advertise(listener.getLocalPort()); discover();
                while (!closed) {
                    try (SSLSocket socket = (SSLSocket) listener.accept()) {
                        incoming = socket; socket.setSoTimeout(20_000); serve(socket);
                    } catch (Exception e) { if (!closed) failure(e); }
                    finally { incoming = null; }
                }
            } catch (Exception e) { if (!closed) failure(e); }
        });
    }

    private void identity() throws Exception {
        synchronized (LocalSync.class) {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (!keys.containsAlias(ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                // Conscrypt performs the TLS digest itself and requests raw ECDSA signing.
                generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=Little Nest local sync"))
                    .setCertificateNotBefore(new Date(0)).setCertificateNotAfter(new Date(4102444800000L)).build());
                generator.generateKeyPair();
            }
            fingerprint = pin((X509Certificate) keys.getCertificate(ALIAS));
        }
    }
    static String pin(X509Certificate certificate) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
    private SSLContext tls(String expected) throws Exception {
        KeyStore androidKeys = KeyStore.getInstance("AndroidKeyStore"); androidKeys.load(null);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(androidKeys, null);
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
            private void check(X509Certificate[] chain) throws CertificateException {
                try {
                    if (chain.length != 1) throw new CertificateException("Unexpected local certificate chain");
                    chain[0].checkValidity();
                    String actual = pin(chain[0]), peer = expected == null ? prefs.getString("localPeer", "") : expected;
                    if (!peer.isEmpty() && equal(actual, peer)) return;
                    if (expected == null && peer.isEmpty() && pairingUntil > System.currentTimeMillis()) return;
                    throw new CertificateException("Local partner identity does not match. Pair again on a trusted network.");
                } catch (CertificateException e) { throw e; }
                catch (Exception e) { throw new CertificateException("Cannot verify local partner", e); }
            }
        };
        SSLContext tls = SSLContext.getInstance("TLSv1.2");
        tls.init(factory.getKeyManagers(), new TrustManager[]{trust}, new SecureRandom()); return tls;
    }

    void invitation(java.util.function.Consumer<String> ready) { invitation(ready, this::failure); }
    void invitation(java.util.function.Consumer<String> ready, java.util.function.Consumer<Exception> failed) {
        clientWorker.execute(() -> {
            try {
                if (paired()) throw new IllegalStateException("A local partner is already paired");
                if (server == null || server.isClosed()) throw new IOException("Local sync is starting. Try again shortly.");
                byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
                pairingSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                pairingUntil = System.currentTimeMillis() + 5 * 60_000L;
                String code = new JSONObject().put("version", 1).put("family", SyncFamily.ensure(context))
                    .put("host", address()).put("port", server.getLocalPort()).put("pin", fingerprint)
                    .put("secret", pairingSecret).put("expires", pairingUntil).toString();
                ready.accept(code);
            } catch (Exception e) { cancelInvitation(); failed.accept(e); }
        });
    }
    void cancelInvitation() { pairingUntil = 0; pairingSecret = ""; }

    private String address() throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        for (var network : manager.getAllNetworks()) {
            var capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || (!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            var properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (var link : properties.getLinkAddresses())
                if (link.getAddress() instanceof Inet4Address && link.getAddress().isSiteLocalAddress())
                    return link.getAddress().getHostAddress();
        }
        // Hotspot downstream interfaces are not always exposed as Android Networks.
        var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            var network = interfaces.nextElement();
            if (!network.isUp() || !network.getName().matches("(wlan|swlan|ap|softap|wifi|eth).*")) continue;
            var addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                var candidate = addresses.nextElement();
                if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) return candidate.getHostAddress();
            }
        }
        throw new IOException("Connect both phones to the same private Wi-Fi network or hotspot first");
    }

    static JSONObject parseInvitation(String value) throws Exception {
        if (value.length() > 4096) throw new IOException("Pairing code is too large");
        JSONObject code = new JSONObject(value);
        if (code.getInt("version") != 1 || code.getLong("expires") < System.currentTimeMillis())
            throw new IOException("Pairing code expired. Show a new code on your partner's phone.");
        String host = code.getString("host");
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || !java.net.InetAddress.getByName(host).isSiteLocalAddress()
            || code.getInt("port") < 1 || code.getInt("port") > 65535
            || !code.getString("pin").matches("[A-Za-z0-9_-]{43}") || !code.getString("secret").matches("[A-Za-z0-9_-]{43}"))
            throw new IOException("Invalid local pairing code");
        String family = code.getString("family");
        if (!java.util.UUID.fromString(family).toString().equals(family)) throw new IOException("Invalid family identity");
        return code;
    }
    void join(String value) {
        if (!syncing.compareAndSet(false, true)) return;
        clientWorker.execute(() -> {
            try {
                if (paired()) throw new IllegalStateException("Disconnect the existing local partner before pairing");
                JSONObject code = parseInvitation(value);
                SyncFamily.check(context, code.getString("family"));
                cancelInvitation();
                connect(code.getString("host"), code.getInt("port"), code.getString("pin"), code.getString("secret"),
                    code.getString("family"), true);
            } catch (Exception e) { failure(e); }
            finally { syncing.set(false); updated.run(); }
        });
    }
    void sync() {
        if (closed || !paired() || !syncing.compareAndSet(false, true)) return;
        clientWorker.execute(() -> {
            try {
                String host = prefs.getString("localHost", "");
                if (host.isEmpty()) throw new IOException("Waiting to discover your partner on this Wi-Fi. Open both apps.");
                connect(host, prefs.getInt("localPort", 0), prefs.getString("localPeer", ""),
                    SyncSecrets.get(context, "localSecret"), SyncFamily.ensure(context), false);
            } catch (Exception e) { failure(e); }
            finally { syncing.set(false); updated.run(); }
        });
    }
    private void connect(String host, int port, String peer, String secret, String family, boolean pairing) throws Exception {
        if (closed) throw new IOException("Local sync is stopped");
        try (SSLSocket socket = (SSLSocket) tls(peer).getSocketFactory().createSocket()) {
            outgoing = socket;
            socket.connect(new InetSocketAddress(host, port), 8000); socket.setSoTimeout(20_000);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"}); socket.startHandshake();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            LocalProtocol.write(output, new JSONObject().put("version", 1).put("family", family).put("secret", secret)
                .put("port", server == null ? 0 : server.getLocalPort()).put("pair", pairing).toString());
            String response = LocalProtocol.read(input);
            if (!response.equals("ready")) throw new IOException("Partner did not accept this pairing. Show a new code and retry.");
            if (pairing) {
                SyncFamily.accept(context, family); savePeer(peer, secret, host, port);
                LocalProtocol.write(output, "paired");
            }
            exchange(input, output, true);
        } finally { outgoing = null; }
    }
    private void serve(SSLSocket socket) throws Exception {
        socket.startHandshake();
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        JSONObject hello = new JSONObject(LocalProtocol.read(input));
        if (hello.getInt("version") != 1 || !hello.getString("family").equals(prefs.getString("syncFamily", "")))
            throw new IOException("Local family identity does not match");
        boolean pairing = hello.getBoolean("pair");
        String peer = pin((X509Certificate) socket.getSession().getPeerCertificates()[0]);
        String secret = pairing ? pairingSecret : SyncSecrets.get(context, "localSecret");
        if (secret.isEmpty() || !equal(secret, hello.getString("secret"))
            || (pairing && ((paired() && !equal(peer, prefs.getString("localPeer", ""))) || pairingUntil < System.currentTimeMillis()))
            || (!pairing && !equal(peer, prefs.getString("localPeer", ""))))
            throw new IOException("Local partner authentication failed");
        if (pairing) {
            SyncFamily.accept(context, hello.getString("family"));
            savePeer(peer, secret, socket.getInetAddress().getHostAddress(), hello.getInt("port"));
            LocalProtocol.write(output, "ready");
            if (!LocalProtocol.read(input).equals("paired")) throw new IOException("Pairing acknowledgement was interrupted. Disconnect local sync on both phones and pair again.");
            cancelInvitation();
        } else LocalProtocol.write(output, "ready");
        exchange(input, output, false);
    }
    private void exchange(DataInputStream input, DataOutputStream output, boolean initiator) throws Exception {
        Store store = Store.get(context);
        List<JSONObject> snapshot = store.operations();
        Set<String> known;
        if (initiator) {
            LocalProtocol.inventory(output, snapshot); known = LocalProtocol.inventory(input);
        } else {
            known = LocalProtocol.inventory(input); LocalProtocol.inventory(output, snapshot);
        }
        int sent, received;
        String checkpoint = "local:" + prefs.getString("localPeer", "");
        if (initiator) {
            sent = LocalProtocol.send(output, snapshot, known); received = LocalProtocol.receive(input, store, checkpoint);
        } else {
            received = LocalProtocol.receive(input, store, checkpoint); sent = LocalProtocol.send(output, snapshot, known);
        }
        LocalProtocol.write(output, "done");
        if (!LocalProtocol.read(input).equals("done")) throw new IOException("Local sync did not finish; retry is safe");
        for (JSONObject operation : snapshot) store.mark("uploaded", checkpoint, operation.getString("opId"));
        prefs.edit().putLong("localLastSync", System.currentTimeMillis()).putString("localError", "")
            .putString("localResult", "Local sync: " + sent + " sent, " + received + " received").apply();
        Reminders.scheduleAll(context); updated.run();
    }
    private void savePeer(String peer, String secret, String host, int port) throws Exception {
        SyncSecrets.put(context, "localSecret", secret);
        if (!prefs.edit().putString("localPeer", peer).putString("localHost", host).putInt("localPort", port).commit())
            throw new IOException("Cannot save local pairing");
    }
    static boolean equal(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void advertise(int port) {
        if (closed) return;
        NsdManager manager = context.getSystemService(NsdManager.class);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo service, int error) { failure(new IOException("Local discovery registration failed (" + error + ")")); }
            @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) { android.util.Log.w("LittleNest", "Local discovery unregister failed: " + error); }
            @Override public void onServiceRegistered(NsdServiceInfo service) {}
            @Override public void onServiceUnregistered(NsdServiceInfo service) {}
        };
        NsdServiceInfo service = new NsdServiceInfo(); service.setServiceType(TYPE);
        service.setServiceName("LittleNest-" + fingerprint.substring(0, 16)); service.setPort(port);
        manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
    }
    private synchronized void discover() {
        if (closed) return;
        NsdManager manager = context.getSystemService(NsdManager.class);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceLost(NsdServiceInfo service) {}
            @Override public void onStartDiscoveryFailed(String type, int error) { failure(new IOException("Local discovery failed (" + error + "). Check Wi-Fi permissions.")); }
            @Override public void onStopDiscoveryFailed(String type, int error) { android.util.Log.w("LittleNest", "Local discovery stop failed: " + error); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                String peer = prefs.getString("localPeer", "");
                if (closed || peer.isEmpty() || !service.getServiceName().startsWith("LittleNest-" + peer.substring(0, 16))
                    || !resolving.compareAndSet(false, true)) return;
                manager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                        resolving.set(false); failure(new IOException("Cannot resolve local partner (" + error + "). Retry on the same Wi-Fi."));
                    }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        resolving.set(false);
                        if (closed || !peer.equals(prefs.getString("localPeer", ""))) return;
                        prefs.edit().putString("localHost", info.getHost().getHostAddress()).putInt("localPort", info.getPort()).apply();
                        sync();
                    }
                });
            }
        };
        manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }
    void disconnect() throws Exception {
        if (busy()) throw new IllegalStateException("Wait for local sync to finish");
        cancelInvitation();
        SSLSocket active = incoming;
        if (active != null) active.close();
        SyncSecrets.put(context, "localSecret", "");
        prefs.edit().remove("localPeer").remove("localHost").remove("localPort").remove("localLastSync")
            .remove("localError").remove("localResult").apply();
    }
    private void failure(Exception error) {
        String message = error instanceof javax.net.ssl.SSLException
            ? "Could not establish an authenticated local connection. Keep both apps open on the same Wi-Fi. If this persists, disconnect local sync on both phones and pair again."
            : error instanceof java.io.EOFException
            ? "Local connection was interrupted. Retry with both apps open. If pairing failed, disconnect on both phones and show a new code."
            : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        prefs.edit().putString("localError", message).apply();
        android.util.Log.w("LittleNest", "Local sync did not finish: " + error.getClass().getSimpleName()); updated.run();
        if (BuildConfig.DEBUG && error instanceof javax.net.ssl.SSLException)
            android.util.Log.d("LittleNest", "Local TLS handshake failed", error);
    }
    @Override public synchronized void close() {
        closed = true; cancelInvitation();
        NsdManager manager = context.getSystemService(NsdManager.class);
        if (discovery != null) {
            try { manager.stopServiceDiscovery(discovery); }
            catch (IllegalArgumentException e) { android.util.Log.w("LittleNest", "Local discovery was already stopped"); }
        }
        if (registration != null) {
            try { manager.unregisterService(registration); }
            catch (IllegalArgumentException e) { android.util.Log.w("LittleNest", "Local advertisement was already stopped"); }
        }
        for (Socket socket : new Socket[]{incoming, outgoing}) if (socket != null) {
            try { socket.close(); } catch (IOException e) { android.util.Log.w("LittleNest", "Could not close local socket"); }
        }
        if (server != null) try { server.close(); } catch (IOException e) { android.util.Log.w("LittleNest", "Could not close local listener"); }
        serverWorker.shutdownNow(); clientWorker.shutdownNow();
    }
}
