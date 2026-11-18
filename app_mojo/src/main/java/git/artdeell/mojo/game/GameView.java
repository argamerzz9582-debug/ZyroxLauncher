package git.artdeell.mojo.game;

import static git.artdeell.mojo.CallbackBridge.windowRate;
import static git.artdeell.mojo.game.GameActivity.touchCharInput;
import static git.artdeell.mojo.utils.MCOptionUtils.getMcScale;
import static git.artdeell.mojo.CallbackBridge.sendMouseButton;
import static git.artdeell.mojo.CallbackBridge.windowHeight;
import static git.artdeell.mojo.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import git.artdeell.mojo.CallbackBridge;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.gamepad.Gamepad;
import git.artdeell.mojo.input.mouse.AndroidPointerCapture;
import git.artdeell.mojo.game.platform.input.PlatformGrabListener;
import git.artdeell.mojo.game.platform.Platform;

import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.game.render.SurfaceProvider;
import git.artdeell.mojo.game.render.SurfaceViewSurfaceProvider;
import git.artdeell.mojo.game.render.TextureViewSurfaceProvider;
import git.artdeell.mojo.utils.MCOptionUtils;

import git.artdeell.mojo.R;
import git.artdeell.mojoexec.MojoExec;

import static git.artdeell.mojo.game.platform.Platform.PLATFORM;

/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 */
public class GameView extends FrameLayout implements PlatformGrabListener, SurfaceProvider.SurfaceCallback {

    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));

    private final SurfaceProvider mSurfaceProvider = LauncherPreferences.PREF_USE_ALTERNATE_SURFACE ? new SurfaceViewSurfaceProvider() : new TextureViewSurfaceProvider();
    private boolean mRefreshOnly = true;
    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;
    GameCursorView mCursorView;

    private final InGameEventProcessor mIngameProcessor = new InGameEventProcessor(this, mSensitivityFactor);
    private final InGUIEventProcessor mInGUIProcessor = new InGUIEventProcessor(this);
    private TouchEventProcessor mCurrentTouchProcessor = mInGUIProcessor;
    private AndroidPointerCapture mPointerCapture;
    private boolean mLastGrabState = false;
    double cursorRatioX = 0;
    double cursorRatioY = 0;

    // Lightweight one-shot detector that keeps the loading GIF alive until Mojang's official
    // splash screen swaps its first real visible frame onto the rendering surface.
    private static final int PROBE_W = 48;
    private static final int PROBE_H = 27;
    private static final long PROBE_INTERVAL_MS = 120L;
    @Nullable private volatile Runnable mFirstSplashFrameListener;
    private volatile boolean mNativeBackendInitialized = false;
    private long mBackendReadyElapsedMs = 0L;
    private long mLastProbeElapsedMs = 0L;
    private int mSwappedFramesAfterInit = 0;
    @Nullable private Bitmap mProbeBitmap;
    @Nullable private int[] mProbePixels;
    @Nullable private TextureView mPendingProbeTextureView;
    private final Runnable mSurfaceViewPollRunnable = this::pollSurfaceViewForMojangSplash;
    private final Runnable mDeferredTextureProbeRunnable = () -> {
        TextureView tv = mPendingProbeTextureView;
        mPendingProbeTextureView = null;
        if (tv != null && mFirstSplashFrameListener != null) {
            onSurfaceFrameUpdated(tv);
        }
    };

    public GameView(Context context) {
        this(context, null);
    }

    public GameView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);
        setFocusableInTouchMode(true);
        Platform.addGrabListener(this);
    }

    // This is required to actually get the CursorView object
    @Override
    public void onFinishInflate(){
        super.onFinishInflate();
        mCursorView = findViewById(R.id.main_cursorview);
        Platform.setCursorImplementor(mCursorView);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setUpPointerCapture() {
        if(mPointerCapture != null) mPointerCapture.detach();
        mPointerCapture = new AndroidPointerCapture(mCursorView, this);
    }

    /** Initialize the view and all its settings
     * @param isAlreadyRunning set to true to tell the view that the game is already running
     *                         (only updates the window without calling the start listener)
     */
    public void start(boolean isAlreadyRunning) {
        if (Tools.isAndroid8OrHigher()) setUpPointerCapture();
        mInGUIProcessor.setAbstractTouchpad(mCursorView);
        mRefreshOnly = isAlreadyRunning;
        mSurface = mSurfaceProvider.create(getContext(), this);
        this.addView(mSurface);
        this.mCursorView.bringToFront();
        this.requestFocus();
    }

    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;
        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                if(Tools.isAndroid8OrHigher() &&
                        mPointerCapture != null) {
                    mPointerCapture.handleAutomaticCapture();
                    return true;
                }
            }else if(toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;

            // Mouse found
            // Avoid going through the JNI each time.
            if(Platform.isGrabbing()) return false;
            Platform.cursorX = e.getX(i) / cursorRatioX;
            Platform.cursorY = e.getY(i) / cursorRatioY;
            Platform.sendCursorPosition();
            return true; //mouse event handled successfully
        }
        if (mIngameProcessor == null || mInGUIProcessor == null) return true;
        // Optional accidental palm / bezel edge touch rejection during grabbed 3D gameplay
        if (LauncherPreferences.PREF_ZYROX_EDGE_TOUCH_GUARD
                && mCurrentTouchProcessor == mIngameProcessor
                && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float edgePx = 14f * getResources().getDisplayMetrics().density;
            float x = e.getX();
            float y = e.getY();
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0 && (x < edgePx || x > w - edgePx || y < edgePx || y > h - edgePx)) {
                return true;
            }
        }
        boolean ret = mCurrentTouchProcessor.processTouchEvent(e);
        // Keep cursor on screen if panning with IME inset
        if(LauncherPreferences.PREF_KEYBOARD_AUTOPANNING && GameActivity.mImeHeight > 0){
            int translationY = Tools.getTranslationFromCursorY(
                    (int) (Platform.cursorY * cursorRatioY + 100),
                    getHeight(),
                    GameActivity.mImeHeight,
                    0
            );
            // If the view was force panned (KeyboardPan keycode) apply an animation instead of immediate override
            // This fixes weird jumps when the user moves the cursor first time after pressing that keycode
            if(GameActivity.mForcedPanningHeight != 0) {
                mSurface.animate().setDuration(100).translationY(-translationY).start();
                mCursorView.animate().setDuration(100).translationY(-translationY).start();
                GameActivity.mForcedPanningHeight = 0;
            } else {
                mSurface.setTranslationY(-translationY);
                mCursorView.setTranslationY(-translationY);
            }
        }
        return ret;
    }

    /**
     * The event for mouse/joystick movements.
     * Note that the mouse motion here is a fallback in case if AndroidPointerCapture has not
     * grabbed the mouse yet (or if it's not supported)
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int mouseCursorIndex = -1;

        if(Gamepad.isGamepadEvent(event)){
            if(Platform.getPlatformGamepad() == null)
                Platform.createGenericGamepad(event.getDevice(), mCursorView);
            Platform.getPlatformGamepad().sendMotionEvent(event);
            return true;
        }

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                Platform.cursorX = event.getX(mouseCursorIndex) / cursorRatioX;
                Platform.cursorY = event.getY(mouseCursorIndex) / cursorRatioY;
                Platform.sendCursorPosition();
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS: sendMouseButton(event.getActionButton(),true); return true;
            case MotionEvent.ACTION_BUTTON_RELEASE: sendMouseButton(event.getActionButton(),false); return true;
            default:
                return false;
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Log.i("KeyEvent", event.toString());

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if(action == KeyEvent.ACTION_MULTIPLE) return true;
        // Ignore the cancelled up events. They occur when the user switches layouts.
        // In accordance with https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED
        if(action == KeyEvent.ACTION_UP &&
                (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            touchCharInput.dispatchKeyEvent(event);
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        if(Gamepad.isGamepadEvent(event)){
            if(Platform.getPlatformGamepad() == null)
                Platform.createGenericGamepad(event.getDevice(), mCursorView);
            Platform.getPlatformGamepad().sendKeyEvent(event);
            return true;
        }

        CallbackBridge.setModifiers(event);
        char codepoint = action == KeyEvent.ACTION_DOWN ? (char) event.getUnicodeChar(event.getMetaState()) : 0;
        if(PLATFORM.sendKeyEvent(eventKeycode, action == KeyEvent.ACTION_DOWN ? 1 : 0, CallbackBridge.getCurrentMods(), codepoint))
            return true;

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        refreshSize(false);
    }

    /** Same as refreshSize, but allows you to force an immediate size update **/
    public void refreshSize(boolean immediate) {
        if(isInLayout() && !immediate) {
            post(this::refreshSize);
            return;
        }
        // Use the width and height of the View instead of display dimensions to avoid
        // getting squiched/stretched due to inconsistencies between the layout and
        // screen dimensions.
        int newWidth = Tools.getDisplayFriendlyRes(getWidth(), LauncherPreferences.PREF_SCALE_FACTOR);
        int newHeight = Tools.getDisplayFriendlyRes(getHeight(), LauncherPreferences.PREF_SCALE_FACTOR);
        if (newHeight < 1 || newWidth < 1) {
            Log.e("MGLSurface", String.format("Impossible resolution : %dx%d", newWidth, newHeight));
            return;
        }
        windowWidth = newWidth;
        windowHeight = newHeight;

        // Update cursor ratio values
        // Mouse events are sent in the full view coordinate space, the game accepts them only in the window space
        this.cursorRatioX = (double) getWidth() / windowWidth;
        this.cursorRatioY = (double) getHeight() / windowHeight;

        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        windowRate = mSurface.getDisplay().getRefreshRate();
        MojoExec.setDisplayParams(windowWidth, windowHeight, windowRate);
        mSurfaceProvider.updateSize();
    }

    private void realStart(){
        // Initial size set. Request immedate refresh, otherwise the initial width and height for the game
        // may be broken/unknown.
        refreshSize(true);

        //Load Minecraft options:
        MCOptionUtils.set("fullscreen", "off");
        MCOptionUtils.set("overrideWidth", String.valueOf(windowWidth));
        MCOptionUtils.set("overrideHeight", String.valueOf(windowHeight));
        MCOptionUtils.save();
        getMcScale();

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        if (isGrabbing && mFirstSplashFrameListener != null) {
            fireMojangSplashReached();
        }
        if(mLastGrabState != isGrabbing) {
            Log.i("MGLSurface", "Grabbing state changed! " + mLastGrabState + " -> " + isGrabbing);
            mCurrentTouchProcessor.prepareForSwitching();
            mCurrentTouchProcessor = pickEventProcessor(isGrabbing);
            mLastGrabState = isGrabbing;
        }
    }

    private TouchEventProcessor pickEventProcessor(boolean isGrabbing) {
        return isGrabbing ? mIngameProcessor : mInGUIProcessor;
    }

    private void updateGrabState(boolean isGrabbing) {

    }

    @Override
    public void onSurfaceAvailable(Surface surface) {
        Platform.updateSurface(surface);
        if(mRefreshOnly) return;
        realStart();
        mRefreshOnly = true;
    }

    @Override
    public void onSurfaceResized() {
        if(PLATFORM != null)
            PLATFORM.surfaceUpdated();
    }

    @Override
    public void onSurfaceDestroyed() {
        if(PLATFORM != null)
            PLATFORM.surfaceDestroyed();
    }

    @Override
    public void onSurfaceFrameUpdated(@Nullable TextureView textureView) {
        if (mFirstSplashFrameListener == null || textureView == null) return;
        if (mNativeBackendInitialized) {
            mSwappedFramesAfterInit++;
        }
        long now = SystemClock.elapsedRealtime();
        long delta = now - mLastProbeElapsedMs;
        if (delta < PROBE_INTERVAL_MS) {
            mPendingProbeTextureView = textureView;
            Tools.MAIN_HANDLER.removeCallbacks(mDeferredTextureProbeRunnable);
            Tools.MAIN_HANDLER.postDelayed(mDeferredTextureProbeRunnable, PROBE_INTERVAL_MS - delta + 8L);
            return;
        }
        mLastProbeElapsedMs = now;
        mPendingProbeTextureView = null;
        Tools.MAIN_HANDLER.removeCallbacks(mDeferredTextureProbeRunnable);

        int frameState = MojangSplashDetector.FRAME_UNREADABLE;
        try {
            ensureProbeBuffers();
            if (mProbeBitmap != null && mProbePixels != null) {
                Bitmap sampled = textureView.getBitmap(mProbeBitmap);
                if (sampled != null) {
                    sampled.getPixels(mProbePixels, 0, PROBE_W, 0, 0, PROBE_W, PROBE_H);
                    frameState = MojangSplashDetector.classifyFrame(mProbePixels, PROBE_W, PROBE_H);
                    if (frameState == MojangSplashDetector.FRAME_VISIBLE_SPLASH) {
                        fireMojangSplashReached();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            frameState = MojangSplashDetector.FRAME_UNREADABLE;
        }

        long elapsed = (mNativeBackendInitialized && mBackendReadyElapsedMs > 0L)
                ? (now - mBackendReadyElapsedMs) : 0L;
        if (frameState == MojangSplashDetector.FRAME_UNREADABLE) {
            // Only fall back on frame count if a GPU driver blocks TextureView readback while
            // Minecraft's LoadingOverlay render loop is actively swapping frames.
            if (mNativeBackendInitialized && mSwappedFramesAfterInit >= 30 && elapsed >= 4500L) {
                fireMojangSplashReached();
            }
        } else if (mNativeBackendInitialized && mSwappedFramesAfterInit >= 180 && elapsed >= 60000L) {
            // Extreme safety cap for custom packs rendering a 100% pitch-black zero-logo screen.
            fireMojangSplashReached();
        }
    }

    /** Registers a one-shot UI callback fired on the first rendered frame of Mojang's splash screen. */
    public void setFirstSplashFrameListener(@Nullable Runnable listener) {
        mFirstSplashFrameListener = listener;
    }

    /** Arms first-frame splash verification once GLFW/SDL/AWT initializes its native window backend. */
    public void notifyNativeWindowBackendReady() {
        mNativeBackendInitialized = true;
        if (mBackendReadyElapsedMs == 0L) {
            mBackendReadyElapsedMs = SystemClock.elapsedRealtime();
        }
        if (mFirstSplashFrameListener == null) return;
        if (mSurface instanceof SurfaceView) {
            Tools.MAIN_HANDLER.removeCallbacks(mSurfaceViewPollRunnable);
            Tools.MAIN_HANDLER.postDelayed(mSurfaceViewPollRunnable, 220L);
        }
    }

    private void pollSurfaceViewForMojangSplash() {
        if (mFirstSplashFrameListener == null || !(mSurface instanceof SurfaceView)) return;
        SurfaceView sv = (SurfaceView) mSurface;
        long elapsed = mBackendReadyElapsedMs > 0L
                ? (SystemClock.elapsedRealtime() - mBackendReadyElapsedMs) : 0L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && sv.getHolder().getSurface() != null
                && sv.getHolder().getSurface().isValid()) {
            try {
                ensureProbeBuffers();
                if (mProbeBitmap != null && mProbePixels != null) {
                    final Bitmap target = mProbeBitmap;
                    final int[] px = mProbePixels;
                    PixelCopy.request(sv, target, copyResult -> {
                        if (mFirstSplashFrameListener == null) return;
                        if (copyResult == PixelCopy.SUCCESS && !target.isRecycled()) {
                            target.getPixels(px, 0, PROBE_W, 0, 0, PROBE_W, PROBE_H);
                            int state = MojangSplashDetector.classifyFrame(px, PROBE_W, PROBE_H);
                            if (state == MojangSplashDetector.FRAME_VISIBLE_SPLASH) {
                                fireMojangSplashReached();
                                return;
                            }
                            if (state == MojangSplashDetector.FRAME_UNREADABLE && elapsed >= 5000L) {
                                fireMojangSplashReached();
                                return;
                            }
                        } else if (elapsed >= 5000L) {
                            fireMojangSplashReached();
                            return;
                        }
                        if (elapsed >= 60000L) {
                            fireMojangSplashReached();
                        } else {
                            Tools.MAIN_HANDLER.postDelayed(mSurfaceViewPollRunnable, 220L);
                        }
                    }, Tools.MAIN_HANDLER);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // API 21–23 SurfaceView fallback: allow time for Mojang splash texture upload after glfwInit
        if (elapsed >= 3500L) {
            fireMojangSplashReached();
        } else {
            Tools.MAIN_HANDLER.postDelayed(mSurfaceViewPollRunnable, 250L);
        }
    }

    private void ensureProbeBuffers() {
        if (mProbeBitmap == null || mProbeBitmap.isRecycled()) {
            mProbeBitmap = Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888);
        }
        if (mProbePixels == null) {
            mProbePixels = new int[PROBE_W * PROBE_H];
        }
    }

    private void fireMojangSplashReached() {
        Runnable listener = mFirstSplashFrameListener;
        releaseSplashProbe();
        if (listener != null) {
            Tools.runOnUiThread(listener);
        }
    }

    /** Stops splash frame probing and frees the tiny probe buffer immediately. */
    public void releaseSplashProbe() {
        mFirstSplashFrameListener = null;
        mPendingProbeTextureView = null;
        Tools.MAIN_HANDLER.removeCallbacks(mSurfaceViewPollRunnable);
        Tools.MAIN_HANDLER.removeCallbacks(mDeferredTextureProbeRunnable);
        if (mProbeBitmap != null && !mProbeBitmap.isRecycled()) {
            mProbeBitmap.recycle();
        }
        mProbeBitmap = null;
        mProbePixels = null;
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
    public static int getWindowWidth(){
        return windowWidth;
    }
    public static int getWindowHeight(){
        return windowHeight;
    }
    public static float getWindowRate(){
        return windowRate;
    }
    public double getCursorRatioX(){
        return cursorRatioX;
    }

    public double getCursorRatioY() {
        return cursorRatioY;
    }
    public SurfaceProvider getSurfaceProvider() {
        return mSurfaceProvider;
    }
}
