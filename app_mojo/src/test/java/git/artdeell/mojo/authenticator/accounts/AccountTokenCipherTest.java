package git.artdeell.mojo.authenticator.accounts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Host-JVM tests for {@link AccountTokenCipher}. The Android Keystore is unavailable here, so the
 * AES-GCM framing ({@code seal}/{@code open}) is exercised with a software 256-bit key, and the
 * sentinel / legacy pass-through rules are exercised directly.
 */
public class AccountTokenCipherTest {

    private SecretKey key;

    @Before
    public void setUp() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @Test
    public void sealOpenRoundTripRestoresToken() throws Exception {
        byte[] token = "M.C507_BAY.2.U.refresh-token-example".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = AccountTokenCipher.seal(token, key);

        // IV (12) + ciphertext (same length as plaintext) + GCM tag (16)
        assertEquals(AccountTokenCipher.GCM_IV_LENGTH + token.length + AccountTokenCipher.GCM_TAG_BITS / 8,
                sealed.length);
        assertArrayEquals(token, AccountTokenCipher.open(sealed, key));
    }

    @Test
    public void sealNeverLeaksPlaintextAndUsesFreshIvEachTime() throws Exception {
        byte[] token = "super_secret_access_token_value".getBytes(StandardCharsets.UTF_8);
        byte[] a = AccountTokenCipher.seal(token, key);
        byte[] b = AccountTokenCipher.seal(token, key);

        String asText = new String(a, StandardCharsets.ISO_8859_1);
        assertFalse("Ciphertext must not contain the plaintext token",
                asText.contains("super_secret_access_token_value"));
        assertFalse("IV must be random per encryption (GCM nonce reuse is catastrophic)",
                Arrays.equals(Arrays.copyOf(a, AccountTokenCipher.GCM_IV_LENGTH),
                        Arrays.copyOf(b, AccountTokenCipher.GCM_IV_LENGTH)));
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        byte[] sealed = AccountTokenCipher.seal("token".getBytes(StandardCharsets.UTF_8), key);
        sealed[sealed.length - 1] ^= 0x01; // flip one bit of the auth tag
        try {
            AccountTokenCipher.open(sealed, key);
            fail("GCM must reject a modified payload");
        } catch (GeneralSecurityException expected) {
            // ok
        }
    }

    @Test
    public void wrongKeyIsRejected() throws Exception {
        byte[] sealed = AccountTokenCipher.seal("token".getBytes(StandardCharsets.UTF_8), key);
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        try {
            AccountTokenCipher.open(sealed, generator.generateKey());
            fail("Decrypting with a different key must fail");
        } catch (GeneralSecurityException expected) {
            // ok
        }
    }

    @Test
    public void truncatedPayloadIsRejectedBeforeCipherUse() {
        byte[][] payloads = {
                new byte[0],
                new byte[AccountTokenCipher.GCM_IV_LENGTH],
                new byte[AccountTokenCipher.GCM_IV_LENGTH + AccountTokenCipher.GCM_TAG_BITS / 8 - 1],
        };
        for (byte[] payload : payloads) {
            try {
                AccountTokenCipher.open(payload, key);
                fail("Truncated payload of " + payload.length + " bytes must be rejected");
            } catch (GeneralSecurityException expected) {
                // ok
            }
        }
    }

    @Test
    public void sentinelAndEmptyValuesAreNeverEncrypted() {
        assertEquals(AccountTokenCipher.NO_TOKEN, AccountTokenCipher.encrypt(null));
        assertEquals(AccountTokenCipher.NO_TOKEN, AccountTokenCipher.encrypt("0"));
        assertEquals("", AccountTokenCipher.encrypt(""));
    }

    @Test
    public void alreadyEncryptedValuesAreNotDoubleEncrypted() {
        String stored = AccountTokenCipher.PREFIX + "QUJDREVGR0hJSktMTU5PUA";
        assertEquals(stored, AccountTokenCipher.encrypt(stored));
    }

    @Test
    public void decryptHandlesMissingAndLegacyPlaintextValues() {
        assertEquals(AccountTokenCipher.NO_TOKEN, AccountTokenCipher.decrypt(null));
        assertEquals(AccountTokenCipher.NO_TOKEN, AccountTokenCipher.decrypt(""));
        // Pre-vault saves stored plaintext; they must still load (and get encrypted on next save).
        assertEquals("legacy_plain_token", AccountTokenCipher.decrypt("legacy_plain_token"));
    }
}
