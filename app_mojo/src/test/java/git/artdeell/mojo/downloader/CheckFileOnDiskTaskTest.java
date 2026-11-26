package git.artdeell.mojo.downloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;

/**
 * Regression tests for the Rapid Start integrity gap: a cached file with the right byte size but
 * wrong content must never be trusted unless it was previously SHA-1 verified and is unchanged.
 */
public class CheckFileOnDiskTaskTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private boolean savedVerify;
    private boolean savedRapid;

    /** Records what the task asked the host downloader to do, without any network or UI. */
    private static final class RecordingDownloader extends Downloader {
        int completed;
        int submittedForDownload;
        long addedSize;
        IOException raised;

        RecordingDownloader() {
            super("test");
        }

        @Override protected void fileComplete() { completed++; }
        @Override protected void submitFileForDownload(TaskMetadata m) { submittedForDownload++; }
        @Override protected void addSize(long bytes) { addedSize += bytes; }
        @Override protected void taskException(IOException e) { raised = e; }
    }

    @Before
    public void savePrefs() {
        savedVerify = LauncherPreferences.PREF_VERIFY_FILES;
        savedRapid = LauncherPreferences.PREF_RAPID_START;
        LauncherPreferences.PREF_VERIFY_FILES = true;
        LauncherPreferences.PREF_RAPID_START = true;
    }

    @After
    public void restorePrefs() {
        LauncherPreferences.PREF_VERIFY_FILES = savedVerify;
        LauncherPreferences.PREF_RAPID_START = savedRapid;
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private File write(String name, byte[] data) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    private static TaskMetadata meta(File f, long size, String sha1) throws Exception {
        return new TaskMetadata(f, new URL("https://libraries.minecraft.net/x.jar"), size, sha1,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES);
    }

    @Test
    public void rapidStartRejectsSameSizeCorruptFileWithoutMarker() throws Exception {
        byte[] good = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        byte[] evil = "tampered library bytes!".getBytes(StandardCharsets.UTF_8);
        assertEquals(good.length, evil.length);
        File f = write("lib.jar", evil);

        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, good.length, sha1Hex(good)), dl).run();

        assertEquals("Corrupt file must not be accepted by size alone", 0, dl.completed);
        assertEquals("Corrupt file must be re-downloaded", 1, dl.submittedForDownload);
        assertFalse(Downloader.verificationMarkerFor(f).exists());
    }

    @Test
    public void rapidStartAcceptsGoodFileAndWritesMarker() throws Exception {
        byte[] good = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", good);
        String sha = sha1Hex(good);

        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, good.length, sha), dl).run();

        assertEquals(1, dl.completed);
        assertEquals(0, dl.submittedForDownload);
        assertEquals(good.length, dl.addedSize);
        assertTrue(Downloader.isVerifiedMarkerStillValid(f));
        assertEquals(sha, Downloader.readVerifiedMarkerHash(f));
    }

    @Test
    public void modifiedFileInvalidatesMarkerAndIsRejected() throws Exception {
        byte[] good = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", good);
        String sha = sha1Hex(good);
        new CheckFileOnDiskTask(meta(f, good.length, sha), new RecordingDownloader()).run();
        assertTrue(Downloader.isVerifiedMarkerStillValid(f));

        long originalMtime = f.lastModified();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("tampered library bytes!".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(f.setLastModified(originalMtime + 5_000L));
        assertFalse("Any modification must invalidate the marker", Downloader.isVerifiedMarkerStillValid(f));

        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, good.length, sha), dl).run();
        assertEquals(0, dl.completed);
        assertEquals(1, dl.submittedForDownload);
    }

    @Test
    public void markerForDifferentHashForcesReverification() throws Exception {
        byte[] good = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", good);
        Downloader.writeVerifiedMarker(f, sha1Hex("some other version".getBytes(StandardCharsets.UTF_8)));

        byte[] expected = "different expected data".getBytes(StandardCharsets.UTF_8);
        assertEquals(good.length, expected.length);
        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, good.length, sha1Hex(expected)), dl).run();

        assertEquals(0, dl.completed);
        assertEquals(1, dl.submittedForDownload);
    }

    @Test
    public void sizeMismatchIsRejectedImmediately() throws Exception {
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", data);
        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, data.length + 10, sha1Hex(data)), dl).run();
        assertEquals(0, dl.completed);
        assertEquals(1, dl.submittedForDownload);
    }

    @Test
    public void postDownloadVerificationFailureRaisesVerificationException() throws Exception {
        byte[] got = "tampered library bytes!".getBytes(StandardCharsets.UTF_8);
        byte[] want = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", got);

        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, want.length, sha1Hex(want)), dl, true).run();

        assertEquals(0, dl.completed);
        assertEquals("Post-download failure must not loop back into another download", 0, dl.submittedForDownload);
        assertNotNull(dl.raised);
        assertTrue(dl.raised instanceof VerificationException);
    }

    @Test
    public void rapidStartDisabledStillVerifiesButWritesNoMarker() throws Exception {
        LauncherPreferences.PREF_RAPID_START = false;
        byte[] good = "authentic library bytes".getBytes(StandardCharsets.UTF_8);
        File f = write("lib.jar", good);

        RecordingDownloader dl = new RecordingDownloader();
        new CheckFileOnDiskTask(meta(f, good.length, sha1Hex(good)), dl).run();

        assertEquals(1, dl.completed);
        assertFalse(Downloader.verificationMarkerFor(f).exists());
    }

    @Test
    public void markerHelpersHandleMissingAndGarbageMarkers() throws Exception {
        File f = write("data.bin", new byte[]{1, 2, 3});
        assertFalse(Downloader.isVerifiedMarkerStillValid(f));
        assertNull(Downloader.readVerifiedMarkerHash(f));

        try (FileOutputStream out = new FileOutputStream(Downloader.verificationMarkerFor(f))) {
            out.write("deadbeef not-a-number\n".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse("Unparseable marker must never be trusted", Downloader.isVerifiedMarkerStillValid(f));
        assertEquals(new File(f.getParentFile(), "data.bin.zyrox.verified"), Downloader.verificationMarkerFor(f));
    }
}
