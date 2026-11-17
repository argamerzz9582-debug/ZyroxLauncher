package git.artdeell.mojo.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import git.artdeell.mojo.utils.DownloadUtils;

public class LocalMetadataCacheTest {
    private static final String TEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void sync_freshCacheAndConditional304_avoidsRedundantDownloads() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000L);
        AtomicInteger callCount = new AtomicInteger();
        List<Map<String, String>> capturedHeaders = Collections.synchronizedList(new ArrayList<>());

        LocalMetadataCache.HttpTransport transport = (url, headers, cto, rto) -> {
            capturedHeaders.add(headers);
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return new LocalMetadataCache.HttpResponse(
                        200, "payload-v1", "\"etag-1\"", "Mon, 16 Nov 2026 10:00:00 GMT", null, "OK");
            }
            assertEquals("\"etag-1\"", headers.get("If-None-Match"));
            assertEquals("Mon, 16 Nov 2026 10:00:00 GMT", headers.get("If-Modified-Since"));
            return new LocalMetadataCache.HttpResponse(
                    304, null, "\"etag-1\"", "Mon, 16 Nov 2026 10:00:00 GMT", null, "Not Modified");
        };

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("cache"), transport, now::get, ms -> {},
                "ZyroxTest/1.0.3", 5_000, 5_000, 3);

        // 1. Cold fetch downloads and caches atomically
        LocalMetadataCache.SyncResult<String> r1 = cache.sync("manifest", TEST_URL, false, 60_000L, s -> s);
        assertEquals("payload-v1", r1.data);
        assertEquals(LocalMetadataCache.SyncSource.UPDATED_FROM_NETWORK, r1.source);
        assertEquals(1, callCount.get());
        assertFalse(new File(cache.getCacheDir(), "manifest.tmp").exists());

        // 2. Within cooldown window: served directly from local cache with zero network calls
        now.addAndGet(15_000L);
        LocalMetadataCache.SyncResult<String> r2 = cache.sync("manifest", TEST_URL, false, 60_000L, s -> s);
        assertEquals("payload-v1", r2.data);
        assertEquals(LocalMetadataCache.SyncSource.FRESH_CACHE, r2.source);
        assertEquals(1, callCount.get());

        // 3. Past cooldown: sends ETag/If-Modified-Since and handles HTTP 304 cleanly
        now.addAndGet(120_000L);
        LocalMetadataCache.SyncResult<String> r3 = cache.sync("manifest", TEST_URL, false, 60_000L, s -> s);
        assertEquals("payload-v1", r3.data);
        assertEquals(LocalMetadataCache.SyncSource.NOT_MODIFIED, r3.source);
        assertTrue(r3.isFromNetwork());
        assertEquals(2, callCount.get());
        LocalMetadataCache.CacheMeta meta = cache.readMeta("manifest");
        assertNotNull(meta);
        assertEquals(now.get(), meta.fetchedAtMs);
    }

    @Test
    public void sync_retriesTransient503And429WithRetryAfter_thenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> sleepDelays = new ArrayList<>();

        LocalMetadataCache.HttpTransport transport = (url, headers, cto, rto) -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                return new LocalMetadataCache.HttpResponse(503, null, null, null, null, "Service Unavailable");
            }
            if (attempt == 2) {
                return new LocalMetadataCache.HttpResponse(429, null, null, null, "2", "Too Many Requests");
            }
            return new LocalMetadataCache.HttpResponse(200, "recovered-ok", "\"etag-ok\"", null, null, "OK");
        };

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("cache"), transport, () -> 50_000L, sleepDelays::add,
                "ZyroxTest/1.0.3", 5_000, 5_000, 3);

        LocalMetadataCache.SyncResult<String> res = cache.sync("retry_key", TEST_URL, true, 60_000L, s -> s);
        assertEquals("recovered-ok", res.data);
        assertEquals(3, attempts.get());
        assertEquals(2, sleepDelays.size());
        // Second retry honored Retry-After: 2 (>= 2000 ms)
        assertTrue("Expected Retry-After >= 2000ms, got " + sleepDelays.get(1), sleepDelays.get(1) >= 2_000L);
    }

    @Test
    public void sync_doesNotRetryPermanent404() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        LocalMetadataCache.HttpTransport transport = (url, headers, cto, rto) -> {
            attempts.incrementAndGet();
            return new LocalMetadataCache.HttpResponse(404, null, null, null, null, "Not Found");
        };
        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("cache"), transport, () -> 10_000L, ms -> {},
                "ZyroxTest/1.0.3", 5_000, 5_000, 3);

        try {
            cache.sync("missing", TEST_URL, true, 60_000L, s -> s);
            fail("Expected IOException for HTTP 404");
        } catch (IOException expected) {
            assertEquals(1, attempts.get());
        }
    }

    @Test
    public void sync_offlineAndCorruptRemoteResponses_preserveValidLocalCache() throws Exception {
        AtomicInteger phase = new AtomicInteger(0);
        LocalMetadataCache.HttpTransport transport = (url, headers, cto, rto) -> {
            int p = phase.get();
            if (p == 0) {
                return new LocalMetadataCache.HttpResponse(200, "GOOD_DATA", "\"e1\"", null, null, "OK");
            }
            if (p == 1) {
                throw new IOException("No network route to host");
            }
            return new LocalMetadataCache.HttpResponse(200, "CORRUPT_PAYLOAD", "\"e2\"", null, null, "OK");
        };

        LocalMetadataCache.MetadataParser<String> strictParser = raw -> {
            if (!raw.startsWith("GOOD_")) {
                throw new DownloadUtils.ParseException(new IllegalArgumentException("Invalid schema"));
            }
            return raw;
        };

        LocalMetadataCache cache = new LocalMetadataCache(
                tempFolder.newFolder("cache"), transport, () -> 99_000L, ms -> {},
                "ZyroxTest/1.0.3", 5_000, 5_000, 2);

        // Initial populate
        assertEquals("GOOD_DATA", cache.sync("catalog", TEST_URL, true, 1_000L, strictParser).data);

        // Offline fallback returns cached copy with warning
        phase.set(1);
        LocalMetadataCache.SyncResult<String> offline = cache.sync("catalog", TEST_URL, true, 1_000L, strictParser);
        assertEquals("GOOD_DATA", offline.data);
        assertEquals(LocalMetadataCache.SyncSource.OFFLINE_CACHE_FALLBACK, offline.source);
        assertNotNull(offline.networkWarning);

        // Corrupt server payload is rejected BEFORE touching disk; cached copy remains intact
        phase.set(2);
        LocalMetadataCache.SyncResult<String> afterCorrupt = cache.sync("catalog", TEST_URL, true, 1_000L, strictParser);
        assertEquals("GOOD_DATA", afterCorrupt.data);
        assertEquals(LocalMetadataCache.SyncSource.OFFLINE_CACHE_FALLBACK, afterCorrupt.source);
        assertEquals("GOOD_DATA", cache.readCached("catalog", strictParser));
    }

    @Test
    public void sync_corruptOnDiskCache_recoversFromNetworkAndClearAllPurges() throws Exception {
        File dir = tempFolder.newFolder("cache");
        try (FileOutputStream fos = new FileOutputStream(new File(dir, "broken"))) {
            fos.write("{{{truncated".getBytes(StandardCharsets.UTF_8));
        }
        LocalMetadataCache.HttpTransport transport = (url, headers, cto, rto) -> {
            assertNull(headers.get("If-None-Match"));
            return new LocalMetadataCache.HttpResponse(200, "VALID", "\"e9\"", null, null, "OK");
        };
        LocalMetadataCache.MetadataParser<String> parser = raw -> {
            if (!"VALID".equals(raw)) {
                throw new DownloadUtils.ParseException(new IllegalArgumentException("Corrupt"));
            }
            return raw;
        };

        LocalMetadataCache cache = new LocalMetadataCache(
                dir, transport, () -> 20_000L, ms -> {}, "ZyroxTest/1.0.3", 5_000, 5_000, 2);
        assertNull(cache.readCached("broken", parser));

        LocalMetadataCache.SyncResult<String> recovered = cache.sync("broken", TEST_URL, false, 60_000L, parser);
        assertEquals("VALID", recovered.data);
        assertEquals(LocalMetadataCache.SyncSource.UPDATED_FROM_NETWORK, recovered.source);

        assertTrue(cache.clearAll());
        assertNull(cache.readCached("broken", parser));
        assertNull(cache.readMeta("broken"));
    }

    @Test
    public void security_rejectsCleartextHttpAndSanitizesTraversalKeys_andHandlesConcurrency() throws Exception {
        File dir = tempFolder.newFolder("cache");
        LocalMetadataCache cache = new LocalMetadataCache(
                dir,
                (url, headers, cto, rto) -> new LocalMetadataCache.HttpResponse(200, "safe", "\"e\"", null, null, "OK"),
                System::currentTimeMillis,
                ms -> {},
                "ZyroxTest/1.0.3",
                5_000,
                5_000,
                2
        );

        try {
            cache.sync("k", "http://example.com/insecure.json", true, 1_000L, s -> s);
            fail("Expected non-HTTPS URL to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("non-HTTPS"));
        }

        String sanitized = LocalMetadataCache.sanitizeKey("../../etc/passwd");
        assertFalse(sanitized.contains(".."));
        assertFalse(sanitized.contains("/"));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> cache.sync("shared_key", TEST_URL, true, 0L, s -> s).data);
            }
            for (Future<String> future : pool.invokeAll(tasks)) {
                assertEquals("safe", future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
