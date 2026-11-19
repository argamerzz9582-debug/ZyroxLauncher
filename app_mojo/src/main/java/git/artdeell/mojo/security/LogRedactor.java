package git.artdeell.mojo.security;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * clipboard copies, command-line argument summaries, and network failure messages.
 *
 * <p>Never mutates runtime authentication state; scrubs Microsoft OAuth / XBL / XSTS / Minecraft
 * tokens, Ely.by OAuth2 &amp; authlib-injector tokens/prefetched blobs, Mojang/Yggdrasil session
 * IDs, dashed &amp; undashed profile UUIDs, JWTs, API keys, email addresses, and account storage
 * paths before displaying, copying, exporting, or sharing.</p>
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
            "--api-key",
            "--userproperties"
    ));

    // CLI flag pairs such as "--accessToken abc123" or "--accessToken=abc123"
    private static final Pattern CLI_TOKEN_PATTERN = Pattern.compile(
            "(?i)(--(?:accessToken|session|clientToken|uuid|xuid|password|token|api[-_]?key|userProperties))"
                    + "(?:\\s*=\\s*|\\s+)([^\\s,\\]\\\"']+)"
    );

    // Minecraft / Ely.by / Yggdrasil composite session string: "token:<accessToken>:<profileUuid>"
    private static final Pattern MC_SESSION_TOKEN_PATTERN = Pattern.compile(
            "(?i)(\\btoken:)([^:\\s,\\)\\\"']+)(:)([0-9a-fA-F\\-]{16,36})"
    );

    // Explicit "Session ID is <secret>" client log banner
    private static final Pattern SESSION_ID_BANNER_PATTERN = Pattern.compile(
            "(?i)(\\bSession\\s+ID\\s+is\\s+)([^\\)\\s,\\\"']+)"
    );

    // Ely.by / authlib-injector prefetched base64 JVM property
    private static final Pattern AUTHLIB_PREFETCH_PATTERN = Pattern.compile(
            "(?i)(-Dauthlibinjector\\.yggdrasil\\.prefetched=)([^\\s,\\]\\\"']+)"
    );

    // JSON / key=value / header secrets (Microsoft, Xbox Live, Ely.by, Yggdrasil, CurseForge)
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)((?:access_token|accessToken|refresh_token|refreshToken|id_token|client_secret|"
                    + "client_token|clientToken|auth_access_token|auth_session|session_id|sessionId|"
                    + "ely_token|elyToken|yggdrasil_token|device_code|user_code|xsts_token|"
                    + "xbl_token|mc_token|minecraftToken|msaToken|auth_?code|api[_-]?key|"
                    + "x-api-key|keystore_password|storePassword|keyPassword|xuid)"
                    + "\\\"?\\s*[:=]\\s*\\\"?)([^\\\"\\s,&\\}\\]]+)"
    );

    // Undashed 32-hex Minecraft / Ely.by profile UUIDs paired with UUID/profile keys
    private static final Pattern KV_UNDASHED_UUID_PATTERN = Pattern.compile(
            "(?i)((?:uuid|profile_?id|selectedProfile|player_?uuid|auth_uuid|ely_?uuid)"
                    + "\\\"?\\s*[:=]\\s*\\\"?)([0-9a-f]{32})\\b"
    );

    // HTTP Authorization headers (Bearer / Basic / XBL3.0 / OAuth)
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(Authorization\\s*[:=]\\s*(?:Bearer|Basic|XBL3\\.0|OAuth)\\s+)([^\\s,\\\"'\\r\\n]+)"
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

    // Compact JWTs (header.payload.signature) — Microsoft, Minecraft Services, Ely.by OAuth2
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"
    );

    // Microsoft OAuth M.C.../M.R3_... access & refresh tokens
    private static final Pattern MS_OAUTH_PATTERN = Pattern.compile(
            "\\bM\\.[A-Za-z0-9]{1,6}_[A-Za-z0-9._\\-!*$]{16,}\\b"
    );

    // URL query parameters holding secrets or pre-signed credentials (including Ely.by OAuth callbacks)
    private static final Pattern URL_SECRET_QUERY_PATTERN = Pattern.compile(
            "(?i)([?&](?:code|token|access_token|refresh_token|client_secret|client_token|"
                    + "session|sessionId|session_id|serverId|sig|signature|x-amz-signature|"
                    + "x-amz-credential|x-amz-security-token|api_key|apikey|key)=)([^&\\s\\\"'\\)]+)"
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
     * Redacts sensitive Microsoft/Ely.by OAuth tokens, session IDs, UUIDs, API keys, emails,
     * and account vault paths from text while preserving clean stack traces verbatim.
     */
    @NonNull
    public static String redact(@Nullable String input) {
        if (input == null || input.isEmpty()) return "";
        String out = input;
        out = MC_SESSION_TOKEN_PATTERN.matcher(out)
                .replaceAll("$1" + REDACTED + "$3" + REDACTED_UUID);
        out = SESSION_ID_BANNER_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = AUTHLIB_PREFETCH_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = CLI_TOKEN_PATTERN.matcher(out).replaceAll("$1 " + REDACTED);
        out = AUTH_HEADER_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = BEARER_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = XBL_PATTERN.matcher(out).replaceAll("$1" + REDACTED);
        out = KV_UNDASHED_UUID_PATTERN.matcher(out).replaceAll("$1" + REDACTED_UUID);
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
     * Centralized privacy-enforcing clipboard helper: guarantees {@code rawText} is always routed
     * through {@link #redact(String)} before entering the system {@link ClipboardManager}.
     */
    public static boolean copyRedactedToClipboard(@Nullable Context context,
                                                  @NonNull String label,
                                                  @Nullable String rawText) {
        if (context == null) return false;
        ClipboardManager mgr = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (mgr == null) return false;
        String safe = redact(rawText);
        mgr.setPrimaryClip(ClipData.newPlainText(label, safe));
        return true;
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
     * Streams {@code sourceLog} line-by-line into an arbitrary SAF/file {@link OutputStream} with
     * every Microsoft/Ely.by OAuth token, UUID, and session ID stripped via {@link #redact(String)}.
     */
    public static void writeRedactedStream(@NonNull File sourceLog,
                                           @NonNull OutputStream targetStream,
                                           long maxBytes) throws IOException {
        long cap = maxBytes > 0 ? maxBytes : 8L * 1024L * 1024L;
        long written = 0L;
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(targetStream, StandardCharsets.UTF_8));
        writer.write("# Zyrox Launcher Redacted Diagnostic Log "
                + "(OAuth tokens, UUIDs & session IDs automatically scrubbed)\n");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sourceLog), StandardCharsets.UTF_8))) {
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
        }
        writer.flush();
    }

    /**
     * Streams {@code sourceLog} line-by-line into {@code targetFile} with all secrets redacted,
     * capped at {@code maxBytes} so exporting huge logs can never exhaust memory or storage.
     */
    @NonNull
    public static File writeRedactedLogFile(@NonNull File sourceLog,
                                            @NonNull File targetFile,
                                            long maxBytes) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create redacted log directory: " + parent.getName());
        }
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile, false)) {
            writeRedactedStream(sourceLog, fos, maxBytes);
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
