package git.artdeell.mojo.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link LogRedactor} verifying that tokens, secrets, headers, emails, URL query
 * credentials, and UUIDs never leak in exported logs or crash reports.
 */
public class LogRedactorTest {

    @Test
    public void redactsCliAccessTokenAndSessionArguments() {
        List<String> args = Arrays.asList(
                "--username", "StevePlayer",
                "--accessToken", "super_secret_mojang_access_token_123456",
                "--uuid", "550e8400e29b41d4a716446655440000",
                "--xuid", "2535412345678901",
                "--version", "1.21.4"
        );
        String redacted = LogRedactor.redactArgs(args);
        assertFalse(redacted.contains("super_secret_mojang_access_token_123456"));
        assertFalse(redacted.contains("550e8400e29b41d4a716446655440000"));
        assertFalse(redacted.contains("2535412345678901"));
        assertTrue(redacted.contains("StevePlayer"));
        assertTrue(redacted.contains("1.21.4"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    public void redactsJwtBearerXboxAndJsonTokens() {
        String fakeJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlBsYXllciJ9."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String raw = "Authorization: Bearer abcdef1234567890SECRET\n"
                + "Header: XBL3.0 x=987654321;secretXboxTokenValue999\n"
                + "{\"refresh_token\":\"M.C507_BL2.secret_refresh_value_98765\"}\n"
                + "jwt=" + fakeJwt;

        String safe = LogRedactor.redact(raw);
        assertFalse(safe.contains("abcdef1234567890SECRET"));
        assertFalse(safe.contains("secretXboxTokenValue999"));
        assertFalse(safe.contains("secret_refresh_value_98765"));
        assertFalse(safe.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
        assertTrue(safe.contains("[REDACTED"));
    }

    @Test
    public void redactsUrlQuerySecretsEmailsAndUuids() {
        String url = "https://login.live.com/oauth20_desktop.srf?code=M.R3_BAY.9988776655&state=xyz";
        String safeUrl = LogRedactor.redactUrl(url);
        assertFalse(safeUrl.contains("M.R3_BAY.9988776655"));
        assertTrue(safeUrl.startsWith("https://login.live.com/oauth20_desktop.srf"));

        String line = "Account user.name+test@example.com profile 123e4567-e89b-12d3-a456-426614174000";
        String safeLine = LogRedactor.redact(line);
        assertFalse(safeLine.contains("user.name+test@example.com"));
        assertFalse(safeLine.contains("123e4567-e89b-12d3-a456-426614174000"));
        assertTrue(safeLine.contains("[REDACTED_EMAIL]"));
        assertTrue(safeLine.contains("[REDACTED_UUID]"));
    }

    @Test
    public void preservesNormalCrashStackTracesAndSha256Formatting() {
        String trace = "java.lang.OutOfMemoryError: Java heap space\n\tat net.minecraft.client.Main.main(Main.java:198)";
        assertEquals(trace, LogRedactor.redact(trace));

        byte[] sample = new byte[]{0x66, (byte) 0xB2, (byte) 0xF8, 0x22};
        assertEquals("66:B2:F8:22", AppTrustVerifier.formatSha256Colon(sample));
        assertEquals("66b2f822", AppTrustVerifier.formatSha256Hex(sample));
    }
}
