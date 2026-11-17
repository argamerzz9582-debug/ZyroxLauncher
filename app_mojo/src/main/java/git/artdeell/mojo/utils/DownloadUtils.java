package git.artdeell.mojo.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.VerificationException;
import git.artdeell.mojo.metadata.LocalMetadataCache;
import git.artdeell.mojo.metadata.VersionMetadataRepository;
import git.artdeell.mojo.security.LogRedactor;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = "ZyroxLauncher/2.0";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_STRING_BYTES = 32 * 1024 * 1024; // 32 MiB response ceiling

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoInput(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + code + ": " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream()) {
                IOUtils.copy(is, os);
            }
        } catch (IOException e) {
            throw new IOException("Unable to download from " + LogRedactor.redactUrl(url), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String downloadString(String url) throws IOException {
        HttpURLConnection conn = null;
        URL parsed = new URL(url);
        try {
            conn = (HttpURLConnection) parsed.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoInput(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + code + ": " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = ArchiveSecurity.readBytesBounded(is, MAX_STRING_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IOException("Unable to download from " + LogRedactor.redactUrl(parsed), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        File tempFile = new File(out.getParentFile(), out.getName() + ".zyrox.tmp");
        boolean ok = false;
        try (FileOutputStream fileOutputStream = new FileOutputStream(tempFile, false)) {
            download(url, fileOutputStream);
            fileOutputStream.flush();
            try {
                fileOutputStream.getFD().sync();
            } catch (IOException ignored) {
            }
            ok = true;
        } finally {
            if (!ok && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
        if (out.exists() && !out.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Unable to replace target file: " + out.getName());
        }
        if (!tempFile.renameTo(out)) {
            JSONUtils.copyFileStrict(tempFile, out);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);
        File tempFile = new File(outputFile.getParentFile(), outputFile.getName() + ".zyrox.tmp");
        URL parsed = new URL(urlInput);
        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        boolean success = false;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + code + " for " + LogRedactor.redactUrl(parsed));
            }
            long totalLen = conn.getContentLengthLong();
            int reportedLen = totalLen > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalLen;
            try (InputStream readStr = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                int current;
                long overall = 0L;
                if (buffer == null) buffer = new byte[65535];
                while ((current = readStr.read(buffer)) != -1) {
                    if (Thread.interrupted()) {
                        throw new InterruptedIOException("Download interrupted");
                    }
                    overall += current;
                    fos.write(buffer, 0, current);
                    int reportedOverall = overall > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) overall;
                    monitor.updateProgress(reportedOverall, reportedLen);
                }
                fos.flush();
                success = true;
            }
        } finally {
            conn.disconnect();
            if (!success && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
        if (outputFile.exists() && !outputFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Unable to replace target file: " + outputFile.getName());
        }
        if (!tempFile.renameTo(outputFile)) {
            JSONUtils.copyFileStrict(tempFile, outputFile);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback)
            throws IOException, ParseException {
        return VersionMetadataRepository.getInstance()
                .getCache()
                .sync(cacheName, url, false, LocalMetadataCache.DEFAULT_REVALIDATE_COOLDOWN_MS, parseCallback::process)
                .data;
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && file.isFile() && HashUtils.compareSHA1(file, sha1);
    }

    public static void ensureSha1(File outputFile, @Nullable String sha1, DownloadFunction downloadFunction)
            throws IOException {
        if (sha1 == null || sha1.trim().isEmpty()) {
            if (outputFile.exists() && outputFile.length() > 0L) return;
            downloadFunction.download();
            return;
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        while (attempts < 5 && !fileOkay) {
            if (attempts > 0) {
                if (outputFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                }
                try {
                    NetworkPolicyHelper.sleepBackoff(attempts - 1);
                } catch (InterruptedException ie) {
                    InterruptedIOException ioe = new InterruptedIOException("Verification retry interrupted");
                    ioe.initCause(ie);
                    throw ioe;
                }
            }
            attempts++;
            downloadFunction.download();
            fileOkay = verifyFile(outputFile, sha1);
        }
        if (!fileOkay) {
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            throw new VerificationException("SHA-1 verification failed after 5 download attempts for " + outputFile.getName());
        }
    }

    /**
     * Get the content length for a given URL.
     *
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     */
    public static long getContentLength(String url) {
        HttpURLConnection urlConnection = null;
        try {
            urlConnection = (HttpURLConnection) new URL(url).openConnection();
            urlConnection.setRequestMethod("HEAD");
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            urlConnection.setConnectTimeout(8000);
            urlConnection.setReadTimeout(8000);
            urlConnection.setDoInput(false);
            urlConnection.setDoOutput(false);
            urlConnection.connect();
            int responseCode = urlConnection.getResponseCode();
            if (responseCode >= 200 && responseCode <= 299) {
                return urlConnection.getContentLengthLong();
            }
        } catch (IOException e) {
            Log.w("DownloadUtils", "Failed to get content length for " + LogRedactor.redactUrl(url));
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
        return -1L;
    }

    /**
     * Ping an authlib-injector URL over HTTPS to check if it's available.
     */
    public static boolean pingInjectorUrl(String url) {
        HttpURLConnection urlConnection = null;
        try {
            urlConnection = (HttpURLConnection) new URL("https", url, "/").openConnection();
            urlConnection.setRequestMethod("HEAD");
            urlConnection.setConnectTimeout(3000);
            urlConnection.setReadTimeout(2000);
            urlConnection.setDoInput(false);
            urlConnection.setDoOutput(false);
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            urlConnection.connect();
            int responseCode = urlConnection.getResponseCode();
            if (responseCode != 200) return false;
            return urlConnection.getHeaderField("x-authlib-injector-api-location") != null;
        } catch (IOException e) {
            Log.w("DownloadUtils", "Failed to ping injector url");
            return false;
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }

    public interface DownloadFunction {
        void download() throws IOException;
    }

    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }
}
