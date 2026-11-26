package git.artdeell.mojo.downloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exercises the real {@link Downloader} HTTP paths against a tiny in-process HTTP/1.1 fake built
 * on {@link ServerSocket} ({@code com.sun.net.httpserver} is not on the Android unit-test
 * bootclasspath): full bodies, 404/500, honoured and ignored {@code Range} resumes, and truncated bodies.
 * Uses loopback {@code http://} only, so it runs offline and in well under a second.
 */
public class DownloaderHttpFakeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] PAYLOAD = new byte[150_000];

    static {
        for (int i = 0; i < PAYLOAD.length; i++) PAYLOAD[i] = (byte) (i * 31 + 7);
    }

    private ServerSocket server;
    private Thread acceptor;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastRange = new AtomicReference<>();

    @Before
    public void startServer() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    handle(socket);
                } catch (SocketException closed) {
                    return;
                } catch (IOException ignored) {
                    // a broken client connection must not stop the fake
                }
            }
        }, "http-fake");
        acceptor.setDaemon(true);
        acceptor.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getLocalPort();
    }

    @After
    public void stopServer() throws Exception {
        if (server != null) server.close();
        if (acceptor != null) acceptor.join(5_000L);
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String range = null;
        for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Range")) {
                range = h.substring(colon + 1).trim();
            }
        }
        requests.incrementAndGet();
        lastRange.set(range);
        String path = requestLine.split(" ")[1];
        OutputStream out = socket.getOutputStream();
        switch (path) {
            case "/ok":
            case "/norange": // ignores Range on purpose
                send(out, 200, "OK", PAYLOAD, 0, PAYLOAD.length, PAYLOAD.length, null);
                break;
            case "/missing":
                send(out, 404, "Not Found", "nope".getBytes(StandardCharsets.US_ASCII), 0, 4, 4, null);
                break;
            case "/boom":
                send(out, 500, "Internal Server Error", "err".getBytes(StandardCharsets.US_ASCII), 0, 3, 3, null);
                break;
            case "/range": {
                Matcher m = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(String.valueOf(range));
                if (!m.matches()) {
                    send(out, 200, "OK", PAYLOAD, 0, PAYLOAD.length, PAYLOAD.length, null);
                } else {
                    int from = Integer.parseInt(m.group(1));
                    int to = Integer.parseInt(m.group(2));
                    send(out, 206, "Partial Content", PAYLOAD, from, to - from + 1, to - from + 1,
                            "Content-Range: bytes " + from + "-" + to + "/" + PAYLOAD.length);
                }
                break;
            }
            case "/truncated":
                // Promise the full length, deliver 40 %, then drop the connection.
                send(out, 200, "OK", PAYLOAD, 0, PAYLOAD.length * 2 / 5, PAYLOAD.length, null);
                break;
            default:
                send(out, 404, "Not Found", new byte[0], 0, 0, 0, null);
        }
    }

    private static void send(OutputStream out, int code, String reason, byte[] body, int off, int len,
                             long declaredLength, String extraHeader) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Length: ").append(declaredLength).append("\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Connection: close\r\n");
        if (extraHeader != null) head.append(extraHeader).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body, off, len);
        out.flush();
    }

    private static final class Counter implements BytesCopiedListener {
        final AtomicLong bytes = new AtomicLong();

        @Override
        public void onBytesCopied(int nbytes) {
            bytes.addAndGet(nbytes);
        }
    }

    @Test
    public void fullDownloadWritesExactBytesAndReportsProgress() throws Exception {
        File out = new File(tmp.getRoot(), "nested/dir/file.bin");
        Counter c = new Counter();
        new Downloader("test").downloadFile(out, new URL(base + "/ok"), c);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(out.toPath()));
        assertEquals(PAYLOAD.length, c.bytes.get());
    }

    @Test
    public void httpErrorsFailLoudlyInsteadOfSavingTheErrorPage() throws Exception {
        for (String path : new String[]{"/missing", "/boom"}) {
            File out = tmp.newFile(path.substring(1) + ".bin");
            Counter c = new Counter();
            try {
                new Downloader("test").downloadFile(out, new URL(base + path), c);
                fail("expected IOException for " + path);
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("HTTP "));
            }
            assertEquals("error body must never be written as file content", 0L, out.length());
            assertEquals(0L, c.bytes.get());
        }
    }

    @Test
    public void honouredRangeResumeAppendsOnlyTheMissingTail() throws Exception {
        File part = tmp.newFile("resume.part");
        int have = 60_000;
        Files.write(part.toPath(), Arrays.copyOf(PAYLOAD, have));
        Counter c = new Counter();

        assertTrue(new Downloader("test").tryContinueDownload(part, PAYLOAD.length, new URL(base + "/range"), c));

        assertEquals("bytes=" + have + "-" + (PAYLOAD.length - 1), lastRange.get());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(part.toPath()));
        assertEquals(PAYLOAD.length - have, c.bytes.get());
    }

    @Test
    public void ignoredRangeIsReportedAsUnsupportedAndLeavesPartUntouched() throws Exception {
        File part = tmp.newFile("ignored.part");
        byte[] prefix = Arrays.copyOf(PAYLOAD, 10_000);
        Files.write(part.toPath(), prefix);
        Counter c = new Counter();

        assertFalse(new Downloader("test").tryContinueDownload(part, PAYLOAD.length, new URL(base + "/norange"), c));

        assertArrayEquals("a 200 full body must never be appended to a partial file",
                prefix, Files.readAllBytes(part.toPath()));
        assertEquals(0L, c.bytes.get());
    }

    @Test
    public void unsafeResumeStatesNeverHitTheNetwork() throws Exception {
        File empty = tmp.newFile("empty.part");
        File complete = tmp.newFile("complete.part");
        Files.write(complete.toPath(), PAYLOAD);
        Downloader d = new Downloader("test");
        URL url = new URL(base + "/range");

        assertFalse(d.tryContinueDownload(empty, PAYLOAD.length, url, null));
        assertFalse(d.tryContinueDownload(complete, PAYLOAD.length, url, null));
        assertFalse("unknown total size", d.tryContinueDownload(complete, -1L, url, null));
        assertEquals(0, requests.get());
    }

    @Test
    public void truncatedBodyIsNeverMistakenForACompleteFile() throws Exception {
        File out = tmp.newFile("truncated.bin");
        Counter c = new Counter();
        try {
            new Downloader("test").downloadFile(out, new URL(base + "/truncated"), c);
        } catch (IOException acceptable) {
            // HttpURLConnection may surface the early EOF directly; that is fine.
        }
        // Either way, the bytes on disk and the reported progress must reflect reality, so that
        // DownloadFileTask's size check rejects and retries the file instead of installing it.
        assertTrue("truncated download must be shorter than promised", out.length() < PAYLOAD.length);
        assertEquals(out.length(), c.bytes.get());
        assertArrayEquals(Arrays.copyOf(PAYLOAD, (int) out.length()), Files.readAllBytes(out.toPath()));
    }

    @Test
    public void interruptedThreadCancelsBeforeConnecting() throws Exception {
        File out = tmp.newFile("cancel.bin");
        Thread.currentThread().interrupt();
        try {
            new Downloader("test").downloadFile(out, new URL(base + "/ok"), null);
            fail("expected InterruptedIOException");
        } catch (java.io.InterruptedIOException expected) {
            // checkQueueControl consumes the interrupt flag
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, requests.get());
    }
}
