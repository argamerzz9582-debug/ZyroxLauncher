package git.artdeell.mojo.authenticator.accounts;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts OAuth refresh tokens and Minecraft session tokens at rest using a non-exportable
 * 256-bit AES-GCM key stored in the Android hardware-backed Keystore (API 23+).
 */
final class AccountTokenCipher {
    private static final String TAG = "AccountVault";
    private static final String STORE_PROVIDER = "AndroidKeyStore";
    private static final String VAULT_ALIAS = "zyrox_account_vault_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private AccountTokenCipher() {}

    static String encrypt(@Nullable String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || "0".equals(plaintext)) {
            return plaintext == null ? "0" : plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return plaintext;
        }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != GCM_IV_LENGTH) {
                throw new GeneralSecurityException("Unexpected GCM IV length");
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length);
            payload.put(iv);
            payload.put(ciphertext);
            return PREFIX + Base64.encodeToString(payload.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Keystore encryption unavailable; falling back to private storage");
            return plaintext;
        }
    }

    static String decrypt(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) {
            return "0";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "0";
        }
        try {
            byte[] raw = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (raw.length <= GCM_IV_LENGTH) {
                return "0";
            }
            KeyStore keyStore = KeyStore.getInstance(STORE_PROVIDER);
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(VAULT_ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                return "0";
            }
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_LENGTH);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] decrypted = cipher.doFinal(raw, GCM_IV_LENGTH, raw.length - GCM_IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Unable to decrypt stored account token; re-authentication required");
            return "0";
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE_PROVIDER);
        keyStore.load(null);
        if (keyStore.containsAlias(VAULT_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(VAULT_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, STORE_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                VAULT_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }
}
