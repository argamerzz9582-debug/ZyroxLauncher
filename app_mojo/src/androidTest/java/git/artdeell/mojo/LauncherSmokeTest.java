package git.artdeell.mojo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import git.artdeell.mojo.ui.ZyroxBackdrops;

/**
 * Weekly on-device smoke test (see .github/workflows/android-smoke.yml): cold-starts the app via
 * its real launcher intent and checks that it reaches a resumed screen without crashing. It also
 * verifies that every bundled backdrop clip is packaged uncompressed, which MediaPlayer requires
 * for {@code openRawResourceFd}-style streaming.
 */
@RunWith(AndroidJUnit4.class)
public class LauncherSmokeTest {

    private static final long START_TIMEOUT_MS = 45_000L;

    @Test
    public void coldStartReachesAResumedScreen() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        assertNotNull("app must declare a launcher activity", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(launch);

        Activity resumed = waitForResumedActivity(START_TIMEOUT_MS);
        assertNotNull("no activity of " + ctx.getPackageName() + " resumed within "
                + START_TIMEOUT_MS / 1000 + " s (crash or ANR on cold start?)", resumed);
        assertEquals(ctx.getPackageName(), resumed.getPackageName());
        assertTrue(!resumed.isFinishing());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(resumed::finishAffinity);
    }

    @Test
    public void backdropClipsArePackagedUncompressed() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        for (ZyroxBackdrops.Backdrop b : ZyroxBackdrops.Backdrop.values()) {
            try (AssetFileDescriptor fd = ctx.getResources().openRawResourceFd(b.videoRes)) {
                assertNotNull(b.id + " clip must be stored uncompressed in the APK", fd);
                assertTrue(b.id + " clip unexpectedly small", fd.getLength() > 1_000_000L);
            }
            assertNotNull(b.id + " poster", ctx.getDrawable(b.posterRes));
        }
    }

    private static Activity waitForResumedActivity(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        AtomicReference<Activity> found = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                if (!resumed.isEmpty()) found.set(resumed.iterator().next());
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(250L);
        }
        return null;
    }
}
