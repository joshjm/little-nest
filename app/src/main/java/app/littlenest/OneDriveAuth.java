package app.littlenest;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class OneDriveAuth {
    private static final String KEY = "oneDriveAuth";
    private static final AuthorizationServiceConfiguration CONFIG = new AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"));

    static boolean configured() { return !BuildConfig.ONEDRIVE_CLIENT_ID.isEmpty(); }
    static boolean connected(Context context) {
        return context.getSharedPreferences("sync-secrets", Context.MODE_PRIVATE).contains(KEY);
    }

    static Intent signIn(AuthorizationService service) {
        if (!configured()) throw new IllegalStateException("This build has no Microsoft app registration. See wiki/Sync-Setup.md.");
        AuthorizationRequest request = new AuthorizationRequest.Builder(CONFIG, BuildConfig.ONEDRIVE_CLIENT_ID,
            ResponseTypeValues.CODE, Uri.parse("app.littlenest:/oauth"))
            .setScope("openid offline_access Files.ReadWrite").setPrompt("select_account").build();
        return service.getAuthorizationRequestIntent(request);
    }

    static void finish(Context context, AuthorizationService service, Intent intent, Runnable success,
                       java.util.function.Consumer<String> failure) {
        AuthorizationResponse response = AuthorizationResponse.fromIntent(intent);
        AuthorizationException error = AuthorizationException.fromIntent(intent);
        if (response == null || error != null) {
            failure.accept("Microsoft sign-in was cancelled or failed" + (error == null ? "" : " (" + error.error + ")")
                + ". Local tracking is unaffected."); return;
        }
        AuthState state = new AuthState(response, null);
        service.performTokenRequest(response.createTokenExchangeRequest(), (tokens, exception) -> {
            if (tokens == null || exception != null) {
                failure.accept("Microsoft could not complete sign-in" + (exception == null ? "" : " (" + exception.error + ")")
                    + ". Check the publisher's app registration or try connecting again."); return;
            }
            state.update(tokens, null);
            try { SyncSecrets.put(context, KEY, state.jsonSerializeString()); success.run(); }
            catch (Exception e) { failure.accept("Cannot save Microsoft sign-in: " + e.getMessage()); }
        });
    }

    // Called only from worker threads: AppAuth callbacks use Android's main looper.
    static synchronized String token(Context context) throws Exception {
        String saved = SyncSecrets.get(context, KEY);
        if (saved.isEmpty()) throw new IllegalStateException("Connect OneDrive in Family to enable cloud sync.");
        AuthState state = AuthState.jsonDeserialize(saved);
        AuthorizationService service = new AuthorizationService(context);
        CountDownLatch completed = new CountDownLatch(1);
        String[] result = new String[2];
        try {
            state.performActionWithFreshTokens(service, (access, id, error) -> {
                try {
                    SyncSecrets.put(context, KEY, state.jsonSerializeString());
                    result[0] = access;
                    if (error != null || access == null) result[1] = "Reconnect OneDrive in Family to resume cloud sync"
                        + (error == null ? "." : " (" + error.error + ").");
                } catch (Exception e) { result[1] = "Cannot save refreshed Microsoft sign-in: " + e.getMessage(); }
                finally { completed.countDown(); }
            });
            if (!completed.await(60, TimeUnit.SECONDS)) throw new java.io.IOException("Microsoft sign-in timed out. Retry later.");
            if (result[1] != null) throw new java.io.IOException(result[1]);
            return result[0];
        } finally { service.dispose(); }
    }

    static synchronized void disconnect(Context context) throws Exception { SyncSecrets.put(context, KEY, ""); }
    private OneDriveAuth() {}
}
