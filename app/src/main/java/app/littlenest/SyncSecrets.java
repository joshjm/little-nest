package app.littlenest;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SyncSecrets {
    private static final String ALIAS = "little-nest-sync-secrets";

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    static synchronized String get(Context context, String name) throws Exception {
        String value = context.getSharedPreferences("sync-secrets", Context.MODE_PRIVATE).getString(name, "");
        if (value.isEmpty()) return "";
        String[] parts = value.split(":", -1);
        if (parts.length != 2) throw new IllegalStateException("Sync credentials are damaged. Disconnect and pair again.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    static synchronized void put(Context context, String name, String value) throws Exception {
        var preferences = context.getSharedPreferences("sync-secrets", Context.MODE_PRIVATE);
        if (value.isEmpty()) {
            if (!preferences.edit().remove(name).commit()) throw new IllegalStateException("Cannot remove sync credentials");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
            + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        if (!preferences.edit().putString(name, encoded).commit()) throw new IllegalStateException("Cannot save sync credentials");
    }
    private SyncSecrets() {}
}
