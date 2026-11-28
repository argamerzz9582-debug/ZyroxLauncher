package git.artdeell.mojo.authenticator.accounts;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
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
 *
 * <p>Security policy:
 * <ul>
 *   <li>API 23+: encryption is <b>fail-closed</b>. If the Keystore cannot encrypt (even after one
 *   attempt to regenerate a corrupted key), the token is NOT written in plaintext; the sentinel
 *   {@code "0"} is persisted instead and the user simply re-authenticates on next launch.</li>
 *   <li>API 21-22: AndroidKeyStore has no AES support, so tokens are stored in the app-private
 *   accounts directory only (sandboxed by the OS). This is a documented platform limitation.</li>
 * </ul>
 */
public final class AccountTokenCipher {
    private static final String TAG = "AccountVault";
    private static final String STORE_PROVIDER = "AndroidKeyStore";
    private static final String VAULT_ALIAS = "zyrox_account_vault_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    public static final String PREFIX = "enc:v1:";
    public static final String NO_TOKEN = "0";
    static final int GCM_IV_LENGTH = 12;
    static final int GCM_TAG_BITS = 128;

    private AccountTokenCipher() {}

    @NonNull
    public static String encrypt(@Nullable String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || NO_TOKEN.equals(plaintext)) {
            return plaintext == null ? NO_TOKEN : plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Platform limitation (no Keystore AES on API 21-22): app-private storage only.
            return plaintext;
        }
        byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            return PREFIX + Base64.encodeToString(seal(clear, getOrCreateKey()), Base64.NO_WRAP);
        } catch (Exception first) {
            // The Keystore entry can become unusable (e.g. after a lock-screen reset or a device
            // restore). Drop it and retry once with a freshly generated key before failing closed.
            try {
                deleteKeyQuietly();
                return PREFIX + Base64.encodeToString(seal(clear, getOrCreateKey()), Base64.NO_WRAP);
            } catch (Exception second) {
                Log.w(TAG, "Keystore encryption unavailable; token not persisted, re-authentication required");
                return NO_TOKEN;
            }
        }
    }

    @NonNull
    public static String decrypt(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) {
            return NO_TOKEN;
        }
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext (pre-vault saves or API 21-22). It gets encrypted on the next save.
            return stored;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return NO_TOKEN;
        }
        try {
            byte[] raw = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            SecretKey key = loadExistingKey();
            if (key == null) {
                return NO_TOKEN;
            }
            return new String(open(raw, key), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Unable to decrypt stored account token; re-authentication required");
            return NO_TOKEN;
        }
    }

    /**
     * Encrypts {@code clear} with AES-GCM and returns {@code IV || ciphertext+tag}. Pure JCE so the
     * framing can be unit-tested on the host JVM with a software key.
     */
    @NonNull
    static byte[] seal(@NonNull byte[] clear, @NonNull SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_LENGTH) {
            throw new GeneralSecurityException("Unexpected GCM IV length");
        }
        byte[] ciphertext = cipher.doFinal(clear);
        ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length);
        payload.put(iv);
        payload.put(ciphertext);
        return payload.array();
    }

    /** Inverse of {@link #seal}. Throws on truncated input or any authentication-tag mismatch. */
    @NonNull
    static byte[] open(@NonNull byte[] payload, @NonNull SecretKey key) throws GeneralSecurityException {
        if (payload.length <= GCM_IV_LENGTH + (GCM_TAG_BITS / 8) - 1) {
            throw new GeneralSecurityException("Encrypted token payload is truncated");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_LENGTH);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        return cipher.doFinal(payload, GCM_IV_LENGTH, payload.length - GCM_IV_LENGTH);
    }

    @Nullable
    private static SecretKey loadExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE_PROVIDER);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(VAULT_ALIAS, null);
        return entry instanceof KeyStore.SecretKeyEntry
                ? ((KeyStore.SecretKeyEntry) entry).getSecretKey()
                : null;
    }

    private static synchronized void deleteKeyQuietly() {
        try {
            KeyStore keyStore = KeyStore.getInstance(STORE_PROVIDER);
            keyStore.load(null);
            if (keyStore.containsAlias(VAULT_ALIAS)) {
                keyStore.deleteEntry(VAULT_ALIAS);
            }
        } catch (Exception ignored) {
            // Nothing else we can do; the retry will surface the real failure.
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private static synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = loadExistingKey();
        if (existing != null) {
            return existing;
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
