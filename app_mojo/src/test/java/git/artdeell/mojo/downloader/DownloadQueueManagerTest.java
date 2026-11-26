package git.artdeell.mojo.downloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** State-machine, dedup, pause/cancel and cleanup tests for {@link DownloadQueueManager}. */
public class DownloadQueueManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Deterministically waits until {@code t} is parked in wait()/park() instead of sleeping for a
     * guessed duration. Yields between polls, so there is no wall-clock dependency.
     */
    private static void awaitParked(Thread t, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            Thread.State st = t.getState();
            if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) return;
            if (st == Thread.State.TERMINATED) fail("worker exited before blocking on pause");
            Thread.yield();
        }
        fail("worker never blocked on the paused record");
    }

    private static DownloadQueueManager.DownloadRecord enqueue(DownloadQueueManager q, String key) {
        return q.enqueue(key, "Title " + key, DownloadQueueManager.Category.MOD, true, null, null);
    }

    @Test
    public void concurrencyIsClampedToSupportedRange() {
        assertEquals(DownloadQueueManager.MIN_CONCURRENCY, DownloadQueueManager.clampConcurrency(-5));
        assertEquals(DownloadQueueManager.MIN_CONCURRENCY, DownloadQueueManager.clampConcurrency(0));
        assertEquals(4, DownloadQueueManager.clampConcurrency(4));
        assertEquals(DownloadQueueManager.MAX_CONCURRENCY, DownloadQueueManager.clampConcurrency(999));
    }

    @Test
    public void rangeResumeOnlyForStrictlyPartialFilesOfKnownSize() {
        assertTrue(DownloadQueueManager.canSafelyResumeRange(10, 100));
        assertFalse(DownloadQueueManager.canSafelyResumeRange(0, 100));
        assertFalse(DownloadQueueManager.canSafelyResumeRange(100, 100));
        assertFalse(DownloadQueueManager.canSafelyResumeRange(150, 100));
        assertFalse(DownloadQueueManager.canSafelyResumeRange(10, -1));
    }

    @Test
    public void activeDuplicatesAreDedupedButFinishedOnesAreNot() {
        DownloadQueueManager q = new DownloadQueueManager();
        DownloadQueueManager.DownloadRecord a = enqueue(q, "mod:sodium");
        assertSame("Same key while active must reuse the record", a, enqueue(q, "mod:sodium"));
        assertEquals(1, q.getActiveCount());

        q.markCompleted(a.id, true);
        assertEquals(0, q.getActiveCount());
        assertEquals(DownloadQueueManager.Status.COMPLETED, a.status);
        assertTrue(a.checksumVerified);
        assertNotSame("A finished key may be enqueued again", a, enqueue(q, "mod:sodium"));
    }

    @Test
    public void failureMessagesAreRedactedInHistory() {
        DownloadQueueManager q = new DownloadQueueManager();
        DownloadQueueManager.DownloadRecord r = enqueue(q, "k");
        q.markFailed(r.id, new IOException("HTTP 403 --accessToken super_secret_token_123456789"));
        assertEquals(DownloadQueueManager.Status.FAILED, r.status);
        assertFalse(r.redactedError.contains("super_secret_token_123456789"));
        assertEquals(1, q.getHistory().size());
    }

    @Test
    public void pauseOnlyAllowedWhileRunningAndResumable() {
        DownloadQueueManager q = new DownloadQueueManager();
        DownloadQueueManager.DownloadRecord r = enqueue(q, "k");
        assertFalse("QUEUED records cannot be paused", q.requestPause(r.id));
        q.updateProgress(r.id, DownloadQueueManager.Status.RUNNING, 10, 100, 0, 1, 1024);
        assertTrue(q.requestPause(r.id));
        assertEquals(DownloadQueueManager.Status.PAUSED, r.status);
        assertTrue(q.requestResume(r.id));
        assertEquals(DownloadQueueManager.Status.RUNNING, r.status);
    }

    @Test
    public void cancelWakesWorkerBlockedOnPause() throws Exception {
        DownloadQueueManager q = new DownloadQueueManager();
        DownloadQueueManager.DownloadRecord r = enqueue(q, "k");
        q.updateProgress(r.id, DownloadQueueManager.Status.RUNNING, 0, 100, 0, 1, 0);
        assertTrue(q.requestPause(r.id));

        CountDownLatch blocked = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            blocked.countDown();
            try {
                q.awaitIfPausedOrThrowIfCanceled(r);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        worker.start();
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        awaitParked(worker, 3_000L);
        assertTrue(q.requestCancel(r.id));
        worker.join(3_000L);

        assertFalse("Worker must not stay blocked after cancel", worker.isAlive());
        assertTrue(outcome.get() instanceof InterruptedException);
    }

    @Test
    public void canceledRecordThrowsImmediately() {
        DownloadQueueManager q = new DownloadQueueManager();
        DownloadQueueManager.DownloadRecord r = enqueue(q, "k");
        q.requestCancel(r.id);
        try {
            q.awaitIfPausedOrThrowIfCanceled(r);
            fail("Canceled download must throw");
        } catch (InterruptedException expected) {
            // ok
        }
    }

    @Test
    public void cleanupRemovesOnlyStaleTempFilesAndSkipsSaves() throws Exception {
        File root = tmp.newFolder("game");
        long old = System.currentTimeMillis() - 10 * 60_000L;

        File stalePart = new File(root, "partfiles/abc.part");
        File staleTmp = new File(root, ".minecraft/libraries/x.jar.zyrox.tmp");
        File freshPart = new File(root, "partfiles/fresh.part");
        File marker = new File(root, ".minecraft/libraries/x.jar.zyrox.verified");
        File savePart = new File(root, ".minecraft/saves/world/region.part");
        for (File f : new File[]{stalePart, staleTmp, freshPart, marker, savePart}) {
            assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
            assertTrue(f.createNewFile());
        }
        assertTrue(stalePart.setLastModified(old));
        assertTrue(staleTmp.setLastModified(old));
        assertTrue(marker.setLastModified(old));
        assertTrue(savePart.setLastModified(old));

        // Uses the shared singleton's active count; nothing is active in unit tests.
        int deleted = DownloadQueueManager.cleanupPartialFiles(root);

        assertEquals(2, deleted);
        assertFalse(stalePart.exists());
        assertFalse(staleTmp.exists());
        assertTrue("Fresh .part may belong to a live download", freshPart.exists());
        assertTrue("Verification markers are not temp files", marker.exists());
        assertTrue("World saves are never touched", savePart.exists());
    }
}
