package git.artdeell.mojo.extra;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** Tests listener de-duplication and atomic consumption in {@link ExtraCore}. */
public class ExtraCoreTest {

    private static final String KEY = "zyrox_test_key";

    @After
    public void tearDown() {
        ExtraCore.removeAllExtraListenersFromValue(KEY);
        ExtraCore.removeValue(KEY);
    }

    @Test
    public void registeringSameListenerTwiceFiresOnce() {
        AtomicInteger calls = new AtomicInteger();
        ExtraListener<String> listener = (key, value) -> {
            calls.incrementAndGet();
            return false;
        };
        ExtraCore.addExtraListener(KEY, listener);
        ExtraCore.addExtraListener(KEY, listener);
        assertEquals(1, ExtraCore.liveListenerCount(KEY));

        ExtraCore.setValue(KEY, "v");
        assertEquals(1, calls.get());
    }

    @Test
    public void listenerReturningTrueIsRemovedAfterFirstValue() {
        AtomicInteger calls = new AtomicInteger();
        ExtraCore.addExtraListener(KEY, (ExtraListener<String>) (key, value) -> {
            calls.incrementAndGet();
            return true;
        });
        ExtraCore.setValue(KEY, "a");
        ExtraCore.setValue(KEY, "b");
        assertEquals(1, calls.get());
        assertEquals(0, ExtraCore.liveListenerCount(KEY));
    }

    @Test
    public void removingUnknownKeyDoesNotCreateState() {
        ExtraCore.removeExtraListenerFromValue("zyrox_never_registered", (key, value) -> false);
        ExtraCore.removeAllExtraListenersFromValue("zyrox_never_registered");
        assertEquals(0, ExtraCore.liveListenerCount("zyrox_never_registered"));
    }

    @Test
    public void consumeValueReturnsOnce() {
        ExtraCore.setValue(KEY, "payload");
        assertEquals("payload", ExtraCore.consumeValue(KEY));
        assertNull(ExtraCore.consumeValue(KEY));
        assertNull(ExtraCore.consumeValue(null));
    }
}
