package git.artdeell.mojo;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Native log bridge with lightweight Java listener multiplexing so live launch progress tracking
 * and the optional in-game console overlay can coexist cleanly, while automatically detaching the
 * JNI callback when zero listeners are active (0% in-game overhead).
 */
@Keep
public class Logger {
    private static final CopyOnWriteArrayList<eventLogListener> sListeners =
            new CopyOnWriteArrayList<>();
    @Nullable
    private static volatile eventLogListener sLegacyDirectListener;
    private static volatile boolean sNativeAttached = false;

    private static final eventLogListener sDispatcher = text -> {
        if (text == null) return;
        eventLogListener direct = sLegacyDirectListener;
        if (direct != null) {
            try {
                direct.onEventLogged(text);
            } catch (Throwable ignored) {
            }
        }
        for (eventLogListener listener : sListeners) {
            try {
                listener.onEventLogged(text);
            } catch (Throwable ignored) {
            }
        }
    };

    /** Print the text to the log file if not censored */
    public static native void appendToLog(String text);

    /** Reset the log file, effectively erasing any previous logs */
    public static native void begin(String logFilePath);

    /** Small listener for anything listening to the log */
    @Keep
    public interface eventLogListener {
        void onEventLogged(String text);
    }

    /** Link a native log listener to the logger (preserved exact JNI signature). */
    public static native void setLogListener(eventLogListener logListener);

    /** Adds a multiplexed log listener and attaches the native bridge if needed. */
    public static synchronized void addLogListener(@Nullable eventLogListener listener) {
        if (listener == null) return;
        sListeners.addIfAbsent(listener);
        syncNativeBridgeLocked();
    }

    /** Removes a multiplexed log listener and detaches the native bridge when idle. */
    public static synchronized void removeLogListener(@Nullable eventLogListener listener) {
        if (listener == null) return;
        sListeners.remove(listener);
        syncNativeBridgeLocked();
    }

    private static void syncNativeBridgeLocked() {
        boolean shouldAttach = sLegacyDirectListener != null || !sListeners.isEmpty();
        if (shouldAttach == sNativeAttached) return;
        try {
            setLogListener(shouldAttach ? sDispatcher : null);
            sNativeAttached = shouldAttach;
        } catch (UnsatisfiedLinkError ignored) {
            // Safe no-op inside host JVM unit tests
        }
    }
}
