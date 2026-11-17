package git.artdeell.mojo.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local, deterministic sensitive-data redactor for crash reports, diagnostic logs, exported text,
 * command-line argument summaries, and network failure messages.
 *
 * <p>Never mutates runtime authentication state; scrubs tokens, OAuth codes, JWTs, API keys,
 * email addresses, and account storage file paths before displaying, logging, copying, or sharing.
 */
public final class LogRedactor {
    public static final String REDACTED = "[REDACTED]";
    public static final String REDACTED_EMAIL = "[REDACTED_EMAIL]";
    public static final String REDACTED_UUID = "[REDACTED_UUID]";
    public static final String REDACTED_PATH = "[REDACTED_ACCOUNT_PATH]";

    private static final Set<String> SENSITIVE_CLI_FLAGS = new HashSet<>(Arrays.asList(
            "--accesstoken",
            "--session",
            "--clienttoken",
            "--uuid",
            "--xuid",
            "--password",
            "--token",
            "--apikey",
            "--api-key"
    ));

    // CLI flag pairs such as "--accessToken abc123" or "--accessToken=abc123"
    private static final Pattern CLI_TOKEN_PATTERN = Pattern.compile(
            "(?i)(--(?:accessToken|session|clientToken|uuid|xuid|password|token|api[-_]?key))(?:\\s*=\\s*|\\s+)([^\\s,\\]\\\"']+)"
    );

    // JSON / key=value / header tokens
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)((?:access_token|refresh_token|id_token|client_secret|auth_access_token|auth_session|"
                    + "xsts_token|xbl_token|mc_token|auth_?code|api[_-]?key|x-api-key|keystore_password|"
                    + "storePassword|keyPassword)\\\"?\\s*[:=]\\s*\\\"?)([^\\\"\\s,&\\}\\]]+)"
    );

    // HTTP Authorization headers (Bearer / Basic / XBL3.0)
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(Authorization\\s*[:=]\\s*(?:Bearer|Basic|XBL3\\.0)\\s+)([^\\s,\\\"'\\r\\n]+)"
    );

    // Standalone Bearer tokens
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]{12,})"
    );

    // Standalone Xbox Live XBL3.0 x=<uhs>;<token> credentials
    private static final Pattern XBL_PATTERN = Pattern.compile(
            "(?i)(\\bXBL3\\.0\\s+x=)([^\\s,\\\"'\\r\\n]+)"
    );

    // Standard RFC-4122 dashed UUIDs (account profile IDs in logs)
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
    );

    // Compact JWTs (header.payload.signature)
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"
    );

    // Microsoft OAuth M.C.../M.R3_... tokens
    private static final Pattern MS_OAUTH_PATTERN = Pattern.compile(
            "\\bM\\.[A-Za-z0-9]{1,6}_[A-Za-z0-9._\\-!*$]{16,}\\b"
    );

    // URL query parameters holding secrets or pre-signed credentials
    private static final Pattern URL_SECRET_QUERY_PATTERN = Pattern.compile(
            "(?i)([?&](?:code|token|access_token|refresh_token|sig|signature|x-amz-signature|"
                    + "x-amz-credential|x-amz-security-token|api_key|apikey|key|session)=)([^&\\s\\\"'\\)]+)"
    );

    // Account vault file paths
    private static final Pattern ACCOUNT_PATH_PATTERN = Pattern.compile(
            "(?i)(/[^\\s\\\"']*/accounts/)([^\\s\\\"']+)"
    );

    // Email addresses (scrubbed on log export/share while keeping stack traces intact)
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,12}\\b"
    );

    // CurseForge BCrypt-style $2a$/$2b$ API keys
    private static final Pattern CF_KEY_PATTERN = Pattern.compile(
            "\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{40,}"
    );

    private LogRedactor() {
    }

    /**
     * Redacts sensitive credentials, tokens, keys, emails, and account file names from text.
     */
    @NonNull
    public static String redact(@Nullable String input) {
        if (input == null || input.isEmpty()) return "";
        String out = input;
        out = CLI_TOKEN_PATTERN.matcher(out).replaceAll("$1 " + REDACTED);
        out = AUTH_HEADER_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = BEARER_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = XBL_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = KV_SECRET_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = URL_SECRET_QUERY_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = JWT_PATTERN.matcher(out).replaceAll(REDACTED);
        out = MS_OAUTH_PATTERN.matcher(out).replaceAll(REDACTED);
        out = CF_KEY_PATTERN.matcher(out).replaceAll(REDACTED);
        out = ACCOUNT_PATH_PATTERN.matcher(out).replaceAll("$1" + REDACTED_PATH);
        out = EMAIL_PATTERN.matcher(out).replaceAll(REDACTED_EMAIL);
        out = UUID_PATTERN.matcher(out).replaceAll(REDACTED_UUID);
        return out;
    }

    /**
     * Produces a safe string representation of launch arguments with secret flag values scrubbed.
     */
    @NonNull
    public static String redactArgs(@Nullable List<String> args) {
        if (args == null || args.isEmpty()) return "[]";
        List<String> safe = new ArrayList<>(args.size());
        boolean maskNext = false;
        for (String raw : args) {
            if (raw == null) {
                maskNext = false;
                continue;
            }
            if (maskNext) {
                safe.add(REDACTED);
                maskNext = false;
                continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT).trim();
            int eq = lower.indexOf('=');
            if (eq > 0) {
                String flag = lower.substring(0, eq);
                if (SENSITIVE_CLI_FLAGS.contains(flag)) {
                    safe.add(raw.substring(0, eq + 1) + REDACTED);
                    continue;
                }
            }
            if (SENSITIVE_CLI_FLAGS.contains(lower)) {
                safe.add(raw);
                maskNext = true;
                continue;
            }
            safe.add(redact(raw));
        }
        return safe.toString();
    }

    /**
     * Safely formats a URL for logging or user-facing error messages without exposing secret query parameters.
     */
    @NonNull
    public static String redactUrl(@Nullable URL url) {
        if (url == null) return "<null>";
        return redactUrl(url.toString());
    }

    /**
     * Safely formats a URL string for logging or error display without exposing secret query parameters.
     */
    @NonNull
    public static String redactUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return "<empty>";
        String cleaned = URL_SECRET_QUERY_PATTERN.matcher(url).replaceAll("$1" + REDACTED);
        // Strip userinfo (https://user:pass@host) if ever present
        int schemeEnd = cleaned.indexOf("://");
        if (schemeEnd > 0) {
            int pathStart = cleaned.indexOf('/', schemeEnd + 3);
            int authorityEnd = pathStart > 0 ? pathStart : cleaned.length();
            int atIdx = cleaned.indexOf('@', schemeEnd + 3);
            if (atIdx > 0 && atIdx < authorityEnd) {
                cleaned = cleaned.substring(0, schemeEnd + 3) + REDACTED + cleaned.substring(atIdx);
            }
        }
        return cleaned;
    }

    /**
     * Streams {@code sourceLog} line-by-line into {@code targetFile} with all secrets redacted,
     * capped at {@code maxBytes} so exporting huge logs can never exhaust memory or storage.
     */
    @NonNull
    public static File writeRedactedLogFile(@NonNull File sourceLog, @NonNull File targetFile, long maxBytes)
            throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create redacted log directory: " + parent.getName());
        }
        long cap = maxBytes > 0 ? maxBytes : 8L * 1024L * 1024L;
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        long written = 0L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sourceLog), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(tempFile, false), StandardCharsets.UTF_8))) {
            writer.write("# Zyrox Launcher Redacted Diagnostic Log (tokens & credentials automatically scrubbed)\n");
            String line;
            while ((line = reader.readLine()) != null) {
                String safeLine = redact(line);
                written += safeLine.length() + 1L;
                if (written > cap) {
                    writer.write("\n# [Log export truncated at safe size limit]\n");
                    break;
                }
                writer.write(safeLine);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw e;
        }
        if (targetFile.exists() && !targetFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Unable to update redacted log export");
        }
        if (!tempFile.renameTo(targetFile)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Unable to finalize redacted log export");
        }
        return targetFile;
    }
}
