package git.artdeell.mojo.downloader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exercises the real {@link Downloader} HTTP paths against an in-process JDK {@link HttpServer}
 * fake: full bodies, 404/500, honoured and ignored {@code Range} resumes, and truncated bodies.
 * Uses loopback {@code http://} only, so it runs offline and in well under a second.
 */
public class DownloaderHttpFakeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] PAYLOAD = new byte[150_000];

    static {
        for (int i = 0; i < PAYLOAD.length; i++) PAYLOAD[i] = (byte) (i * 31 + 7);
    }

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastRange = new AtomicReference<>();

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            count(ex);
            send(ex, 200, PAYLOAD, PAYLOAD.length);
        });
        server.createContext("/missing", ex -> {
            count(ex);
            send(ex, 404, "nope".getBytes(), 4);
        });
        server.createContext("/boom", ex -> {
            count(ex);
            send(ex, 500, "err".getBytes(), 3);
        });
        server.createContext("/range", ex -> {
            count(ex);
            Matcher m = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(String.valueOf(lastRange.get()));
            if (!m.matches()) {
                send(ex, 200, PAYLOAD, PAYLOAD.length);
                return;
            }
            int from = Integer.parseInt(m.group(1));
            int to = Integer.parseInt(m.group(2));
            byte[] slice = Arrays.copyOfRange(PAYLOAD, from, to + 1);
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + PAYLOAD.length);
            send(ex, 206, slice, slice.length);
        });
        server.createContext("/norange", ex -> {
            count(ex);
            send(ex, 200, PAYLOAD, PAYLOAD.length); // server ignores Range
        });
        server.createContext("/truncated", ex -> {
            count(ex);
            // Promise the full length, deliver 40 %, then drop the connection.
            ex.sendResponseHeaders(200, PAYLOAD.length);
            OutputStream os = ex.getResponseBody();
            os.write(PAYLOAD, 0, PAYLOAD.length * 2 / 5);
            os.flush();
            ex.close();
        });
        server.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    private void count(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        lastRange.set(ex.getRequestHeaders().getFirst("Range"));
        ex.getRequestBody().close();
    }

    private static void send(HttpExchange ex, int code, byte[] body, int len) throws IOException {
        ex.sendResponseHeaders(code, len);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body, 0, len);
        }
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
