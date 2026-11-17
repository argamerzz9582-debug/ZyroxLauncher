package git.artdeell.mojo.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Lightweight, seamless, hardware-accelerated Minecraft warm-sunset mountain loading backdrop.
 *
 * <p>Renders five distinct depth-parallax layers without any per-frame heap allocations or external
 * dependencies:
 * <ol>
 *   <li>Distant stepped voxel mountain silhouettes with warm sunset rim highlights</li>
 *   <li>Midground blocky cliffs and tiered Minecraft spruce/pine forest ridges</li>
 *   <li>Foreground blocky terrain terraces, pixel grass tufts, and framing pines</li>
 *   <li>Two-tier drifting volumetric 3D blocky Minecraft shader clouds and valley mist</li>
 *   <li>Warm golden-hour blocky sun, crepuscular light rays, and subtle drifting particles</li>
 * </ol>
 *
 * <p>Designed to sit strictly behind {@code ControlLayout} touch buttons without intercepting input,
 * pause cleanly when backgrounded, fall back to a static sunset scene on any rendering error, and
 * release all cached graphics objects as soon as the Minecraft game window initializes.
 */
public class MinecraftLoadingLandscapeView extends View {
    private static final String TAG = "McLoadingLandscape";

    // Clamp per-frame delta so a long GC or pause never causes a visible jump.
    private static final float MAX_FRAME_DELTA_SEC = 0.05f;
    private static final int PARTICLE_COUNT = 34;
    private static final int CLOUD_COUNT = 9;
    private static final int STAR_COUNT = 22;
    private static final int SUN_RAY_COUNT = 5;

    // Reusable paints (zero allocations inside onDraw)
    private final Paint mSkyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunBlockPaint = new Paint();
    private final Paint mRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStarPaint = new Paint();
    private final Paint mFarMountainPaint = new Paint();
    private final Paint mFarRimPaint = new Paint();
    private final Paint mHazePaint = new Paint();
    private final Paint mMidMountainPaint = new Paint();
    private final Paint mMidShadowPaint = new Paint();
    private final Paint mMidRimPaint = new Paint();
    private final Paint mMidPinePaint = new Paint();
    private final Paint mForeTerrainPaint = new Paint();
    private final Paint mForeRimPaint = new Paint();
    private final Paint mForePinePaint = new Paint();
    private final Paint mCloudPaint = new Paint();
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVignettePaint = new Paint();
    private final Paint mFallbackPaint = new Paint();

    // Cached periodic tileable terrain paths (rebuilt only on viewport size change)
    private Path mFarMountainPath;
    private Path mFarRimPath;
    private Path mMidMountainPath;
    private Path mMidCliffShadePath;
    private Path mMidRimPath;
    private Path mMidPinePath;
    private Path mForeTerrainPath;
    private Path mForeRimPath;
    private Path mForePinePath;
    private Path[] mSunRayPaths;

    // Cached layout metrics
    private int mSceneWidth;
    private int mSceneHeight;
    private float mTileWidth;
    private float mSunCenterX;
    private float mSunCenterY;
    private float mSunSize;
    private float mSunGlowRadius;
    private float mFarFogTop;
    private float mFarFogBottom;
    private float mMidFogTop;
    private float mMidFogBottom;
    private float mLanternOffsetX;
    private float mLanternY;
    private float mBlockUnit;

    // Cached volumetric blocky cloud formations
    private final float[] mCloudBaseX = new float[CLOUD_COUNT];
    private final float[] mCloudY = new float[CLOUD_COUNT];
    private final float[] mCloudWidth = new float[CLOUD_COUNT];
    private final float[] mCloudHeight = new float[CLOUD_COUNT];
    private final float[] mCloudSpeed = new float[CLOUD_COUNT];
    private final boolean[] mCloudNearTier = new boolean[CLOUD_COUNT];

    // Cached subtle twilight stars
    private final float[] mStarX = new float[STAR_COUNT];
    private final float[] mStarY = new float[STAR_COUNT];
    private final float[] mStarSize = new float[STAR_COUNT];
    private final float[] mStarPhase = new float[STAR_COUNT];

    // Cached warm sunset dust motes / firefly embers
    private final float[] mParticleX = new float[PARTICLE_COUNT];
    private final float[] mParticleY = new float[PARTICLE_COUNT];
    private final float[] mParticleSpeedX = new float[PARTICLE_COUNT];
    private final float[] mParticleSpeedY = new float[PARTICLE_COUNT];
    private final float[] mParticleRadius = new float[PARTICLE_COUNT];
    private final float[] mParticlePhase = new float[PARTICLE_COUNT];
    private final boolean[] mParticleWarmGold = new boolean[PARTICLE_COUNT];

    // Cached gradients reused across frames
    private Shader mFarHazeShader;
    private Shader mMidHazeShader;
    private Shader mTopScrimShader;
    private Shader mBottomScrimShader;
    private Shader mCenterReadabilityShader;

    // Lifecycle & seamless timing state
    private boolean mRunning = true;
    private boolean mReleased = false;
    private boolean mUseStaticFallback = false;
    private boolean mSceneReady = false;
    private long mLastFrameMs = 0L;
    private double mElapsedSec = 0.0;

    public MinecraftLoadingLandscapeView(@NonNull Context context) {
        super(context);
        initView();
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    private void initView() {
        // Strictly non-interactive decorative background behind on-screen game controls.
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        mFarRimPaint.setStyle(Paint.Style.STROKE);
        mMidRimPaint.setStyle(Paint.Style.STROKE);
        mForeRimPaint.setStyle(Paint.Style.STROKE);
        mMidShadowPaint.setColor(0x52100910);
        mMidPinePaint.setColor(0xFF1A1219);
        mForePinePaint.setColor(0xFF0C090C);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Never consume touch events; let ControlLayout controls and surface handle all input.
        return false;
    }

    /** Pause frame updates when the Activity stops or loses visibility. */
    public void pauseAnimation() {
        mRunning = false;
        mLastFrameMs = 0L;
    }

    /** Resume smooth parallax frame updates when visible and not yet released. */
    public void resumeAnimation() {
        if (mReleased) return;
        if (!mRunning) {
            mRunning = true;
            mLastFrameMs = 0L;
            if (isAttachedToWindow() && getVisibility() == VISIBLE && !mUseStaticFallback) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Stop all animation callbacks and drop cached shaders/paths so zero loading-screen resources
     * stay resident after the Minecraft game window appears or the Activity finishes.
     */
    public void release() {
        mReleased = true;
        mRunning = false;
        mSceneReady = false;
        mLastFrameMs = 0L;

        mFarMountainPath = null;
        mFarRimPath = null;
        mMidMountainPath = null;
        mMidCliffShadePath = null;
        mMidRimPath = null;
        mMidPinePath = null;
        mForeTerrainPath = null;
        mForeRimPath = null;
        mForePinePath = null;
        mSunRayPaths = null;

        mFarHazeShader = null;
        mMidHazeShader = null;
        mTopScrimShader = null;
        mBottomScrimShader = null;
        mCenterReadabilityShader = null;

        mSkyPaint.setShader(null);
        mSunGlowPaint.setShader(null);
        mRayPaint.setShader(null);
        mFarMountainPaint.setShader(null);
        mMidMountainPaint.setShader(null);
        mForeTerrainPaint.setShader(null);
        mHazePaint.setShader(null);
        mVignettePaint.setShader(null);
        mFallbackPaint.setShader(null);
    }

    /** Test & diagnostic accessor confirming whether animation ticks are active. */
    public boolean isAnimating() {
        return mRunning && !mReleased && !mUseStaticFallback && getVisibility() == VISIBLE;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mRunning && !mReleased && getVisibility() == VISIBLE && !mUseStaticFallback) {
            mLastFrameMs = 0L;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mLastFrameMs = 0L;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            mLastFrameMs = 0L;
        } else if (mRunning && !mReleased && !mUseStaticFallback) {
            mLastFrameMs = 0L;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mReleased || w <= 0 || h <= 0) return;
        if (w == mSceneWidth && h == mSceneHeight && mSceneReady) return;
        try {
            rebuildScene(w, h);
            mSceneReady = true;
        } catch (Throwable t) {
            Log.w(TAG, "Falling back to static sunset backdrop after scene build failure", t);
            mUseStaticFallback = true;
            mSceneReady = false;
        }
    }

    private void rebuildScene(int w, int h) {
        mSceneWidth = w;
        mSceneHeight = h;

        float shortSide = Math.min(w, h);
        // Quantized voxel block unit scales harmoniously across phones, ultrawide displays & tablets.
        mBlockUnit = Math.max(4f, Math.round(shortSide / 68f));
        // Periodic horizontal tile spans >= 1.25x viewport width and aligns cleanly to voxel columns.
        int cols = Math.max(96, Math.round((w * 1.25f) / mBlockUnit));
        mTileWidth = cols * mBlockUnit;

        // 1. Warm Golden-Hour Minecraft Shader Sky Gradient
        mSkyPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h * 0.84f,
                new int[]{
                        0xFF121024, // Deep dusk indigo zenith
                        0xFF281A33, // Twilight plum upper sky
                        0xFF5D292C, // Warm crimson-umber mid sky
                        0xFFB8501E, // Rich sunset burnt orange
                        0xFFEA8B24, // Golden-hour amber glow
                        0xFFFCD271  // Warm sunlit horizon mist
                },
                new float[]{0f, 0.20f, 0.42f, 0.64f, 0.84f, 1f},
                Shader.TileMode.CLAMP
        ));

        // Static fallback gradient kept ready in case any GPU/Canvas issue arises later
        mFallbackPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h,
                new int[]{0xFF141124, 0xFF4F2428, 0xFFC2561E, 0xFF2B181E, 0xFF0E0A0D},
                new float[]{0f, 0.34f, 0.58f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));

        // 2. Blocky Minecraft Sunset Sun & Volumetric Atmospheric Bloom
        mSunCenterX = w * 0.62f;
        mSunCenterY = h * 0.46f;
        mSunSize = Math.max(mBlockUnit * 6.5f, shortSide * 0.105f);
        mSunGlowRadius = Math.max(w, h) * 0.48f;
        mSunGlowPaint.setShader(new RadialGradient(
                mSunCenterX, mSunCenterY, mSunGlowRadius,
                new int[]{
                        0x96FFE7A3,
                        0x58F59E0B,
                        0x26EA580C,
                        0x00EA580C
                },
                new float[]{0f, 0.22f, 0.54f, 1f},
                Shader.TileMode.CLAMP
        ));

        // Diagonal crepuscular volumetric god-rays fanning down from the blocky sun
        mRayPaint.setShader(new LinearGradient(
                mSunCenterX, mSunCenterY - mSunSize,
                mSunCenterX - w * 0.22f, h * 0.92f,
                new int[]{0x2EFFE8A3, 0x16F59E0B, 0x00F59E0B},
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP
        ));
        mSunRayPaths = new Path[SUN_RAY_COUNT];
        float[] rayAngles = new float[]{-0.85f, -0.42f, -0.05f, 0.34f, 0.72f};
        float[] raySpreads = new float[]{0.11f, 0.14f, 0.10f, 0.13f, 0.09f};
        float rayReach = h * 0.68f;
        for (int i = 0; i < SUN_RAY_COUNT; i++) {
            Path ray = new Path();
            float a1 = rayAngles[i] - raySpreads[i];
            float a2 = rayAngles[i] + raySpreads[i];
            ray.moveTo(mSunCenterX - mSunSize * 0.25f, mSunCenterY);
            ray.lineTo(mSunCenterX + mSunSize * 0.25f, mSunCenterY);
            ray.lineTo(mSunCenterX + (float) Math.sin(a2) * rayReach, mSunCenterY + rayReach);
            ray.lineTo(mSunCenterX + (float) Math.sin(a1) * rayReach, mSunCenterY + rayReach);
            ray.close();
            mSunRayPaths[i] = ray;
        }

        // Subtle high-altitude dusk stars (deterministic placement, zero Random allocation)
        long seed = 0x5A59524F584D43L; // "ZYROXMC"
        for (int i = 0; i < STAR_COUNT; i++) {
            seed = nextSeed(seed);
            mStarX[i] = unitFloat(seed) * w;
            seed = nextSeed(seed);
            mStarY[i] = unitFloat(seed) * (h * 0.29f);
            seed = nextSeed(seed);
            mStarSize[i] = Math.max(1.5f, mBlockUnit * (0.22f + 0.18f * unitFloat(seed)));
            seed = nextSeed(seed);
            mStarPhase[i] = unitFloat(seed) * 6.283185f;
        }

        // 3. Layer 1 — Distant Stepped Voxel Mountain Range (periodic over mTileWidth)
        int farCols = Math.max(64, cols / 2);
        float farStep = mTileWidth / farCols;
        float[] farHeights = new float[farCols];
        for (int i = 0; i < farCols; i++) {
            double p = (2.0 * Math.PI * i) / farCols;
            // Periodic harmonics guarantee exact wrap at i == 0 and i == farCols (zero seam!)
            double ridge = 0.56
                    - 0.115 * Math.pow(0.5 + 0.5 * Math.sin(p + 0.4), 1.5)
                    - 0.075 * Math.pow(0.5 + 0.5 * Math.cos(2.0 * p - 0.9), 1.4)
                    - 0.038 * Math.sin(3.0 * p + 1.7)
                    - 0.018 * Math.cos(5.0 * p + 0.3);
            // Leave a scenic sunlit saddle near center-right so the blocky sun glows between peaks
            farHeights[i] = quantizeBlock((float) (ridge * h), mBlockUnit * 1.15f);
        }
        mFarMountainPath = buildSteppedTerrainPath(farHeights, farStep, h);
        mFarRimPath = buildSteppedRimPath(farHeights, farStep);
        mFarMountainPaint.setShader(new LinearGradient(
                0f, h * 0.34f, 0f, h * 0.74f,
                new int[]{0xFF4A2838, 0xFF663336, 0xFF8C4633},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP
        ));
        mFarRimPaint.setStrokeWidth(Math.max(1.5f, mBlockUnit * 0.28f));
        mFarRimPaint.setColor(0x88FDBA74);

        // Warm atmospheric valley mist between distant and midground ranges
        mFarFogTop = h * 0.50f;
        mFarFogBottom = h * 0.75f;
        mFarHazeShader = new LinearGradient(
                0f, mFarFogTop, 0f, mFarFogBottom,
                new int[]{0x00F59E0B, 0x52FBBF68, 0x2AE07A2B, 0x002B1721},
                new float[]{0f, 0.45f, 0.80f, 1f},
                Shader.TileMode.CLAMP
        );

        // 4. Layer 2 — Volumetric 3D Blocky Minecraft Shader Clouds (two parallax depth tiers)
        float cloudSpan = w + mBlockUnit * 38f;
        for (int i = 0; i < CLOUD_COUNT; i++) {
            boolean near = (i % 3 == 0);
            mCloudNearTier[i] = near;
            seed = nextSeed(seed);
            mCloudBaseX[i] = ((i + 0.15f + 0.65f * unitFloat(seed)) / CLOUD_COUNT) * cloudSpan;
            seed = nextSeed(seed);
            mCloudY[i] = h * (near ? (0.11f + 0.17f * unitFloat(seed)) : (0.20f + 0.20f * unitFloat(seed)));
            seed = nextSeed(seed);
            mCloudWidth[i] = mBlockUnit * (near ? (14f + 10f * unitFloat(seed)) : (9f + 8f * unitFloat(seed)));
            mCloudHeight[i] = mBlockUnit * (near ? 2.2f : 1.55f);
            seed = nextSeed(seed);
            mCloudSpeed[i] = (near ? 11.5f : 6.2f) * (0.85f + 0.3f * unitFloat(seed)) * (mBlockUnit / 6f);
        }

        // 5. Layer 3 — Midground Voxel Cliffs & Blocky Spruce/Pine Forests (periodic over mTileWidth)
        float midStep = mTileWidth / cols;
        float[] midHeights = new float[cols];
        for (int i = 0; i < cols; i++) {
            double p = (2.0 * Math.PI * i) / cols;
            double ridge = 0.72
                    - 0.115 * Math.pow(0.5 + 0.5 * Math.cos(p + 1.15), 1.55)
                    - 0.068 * Math.pow(0.5 + 0.5 * Math.sin(2.0 * p + 0.35), 1.35)
                    - 0.032 * Math.cos(4.0 * p - 0.6)
                    - 0.015 * Math.sin(7.0 * p + 1.1);
            midHeights[i] = quantizeBlock((float) (ridge * h), mBlockUnit);
        }
        mMidMountainPath = buildSteppedTerrainPath(midHeights, midStep, h);
        mMidCliffShadePath = buildVoxelCliffShadePath(midHeights, midStep, mBlockUnit * 4.5f);
        mMidRimPath = buildSteppedRimPath(midHeights, midStep);
        mMidPinePath = new Path();
        mLanternOffsetX = -1f;
        for (int i = 2; i < cols - 2; i += 3) {
            seed = nextSeed(seed);
            float chance = unitFloat(seed);
            // Place dense clusters of blocky Minecraft spruce/pine trees across mountain shoulders
            float slope = Math.abs(midHeights[i + 1] - midHeights[i - 1]);
            if (chance < 0.64f && slope <= mBlockUnit * 2.2f) {
                seed = nextSeed(seed);
                int tiers = 2 + (unitFloat(seed) > 0.45f ? 1 : 0);
                float treeScale = 0.85f + 0.38f * unitFloat(nextSeed(seed));
                addBlockySpruceTree(mMidPinePath, (i + 0.5f) * midStep, midHeights[i],
                        mBlockUnit * 0.82f * treeScale, tiers);
            } else if (mLanternOffsetX < 0f && i > cols / 3 && slope == 0f) {
                // Tiny voxel ridge outpost silhouette with warm glowing amber lantern window
                float ox = i * midStep;
                float oy = midHeights[i];
                mMidPinePath.addRect(ox, oy - mBlockUnit * 2.4f, ox + mBlockUnit * 2.6f, oy, Path.Direction.CW);
                mMidPinePath.addRect(ox - mBlockUnit * 0.4f, oy - mBlockUnit * 3.1f,
                        ox + mBlockUnit * 3.0f, oy - mBlockUnit * 2.4f, Path.Direction.CW);
                mLanternOffsetX = ox + mBlockUnit * 0.9f;
                mLanternY = oy - mBlockUnit * 1.6f;
            }
        }
        mMidMountainPaint.setShader(new LinearGradient(
                0f, h * 0.50f, 0f, h * 0.88f,
                new int[]{0xFF2D1B25, 0xFF21141C, 0xFF170F15},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));
        mMidRimPaint.setStrokeWidth(Math.max(1.5f, mBlockUnit * 0.30f));
        mMidRimPaint.setColor(0x9EF59E0B);

        // Soft warm valley haze nestled between midground pines and foreground terrain
        mMidFogTop = h * 0.68f;
        mMidFogBottom = h * 0.90f;
        mMidHazeShader = new LinearGradient(
                0f, mMidFogTop, 0f, mMidFogBottom,
                new int[]{0x00EA580C, 0x30F59E0B, 0x1E9A3412, 0x00120B0F},
                new float[]{0f, 0.45f, 0.78f, 1f},
                Shader.TileMode.CLAMP
        );

        // 6. Layer 4 — Foreground Stepped Blocky Terrain, Grass Tufts & Framing Spruce Pines
        float[] foreHeights = new float[cols];
        for (int i = 0; i < cols; i++) {
            double p = (2.0 * Math.PI * i) / cols;
            double hill = 0.855
                    - 0.068 * Math.pow(0.5 + 0.5 * Math.sin(p - 0.8), 1.4)
                    - 0.042 * Math.pow(0.5 + 0.5 * Math.cos(2.0 * p + 1.5), 1.3)
                    - 0.018 * Math.sin(5.0 * p - 0.4);
            foreHeights[i] = quantizeBlock((float) (hill * h), mBlockUnit * 1.25f);
        }
        mForeTerrainPath = buildSteppedTerrainPath(foreHeights, midStep, h);
        mForeRimPath = buildSteppedRimPath(foreHeights, midStep);
        mForePinePath = new Path();
        for (int i = 1; i < cols - 2; i += 2) {
            seed = nextSeed(seed);
            float r = unitFloat(seed);
            float x = (i + 0.5f) * midStep;
            float y = foreHeights[i];
            if (r < 0.20f && i % 4 == 1) {
                seed = nextSeed(seed);
                int tiers = 3 + (unitFloat(seed) > 0.6f ? 1 : 0);
                float scale = 1.08f + 0.35f * unitFloat(nextSeed(seed));
                addBlockySpruceTree(mForePinePath, x, y + mBlockUnit * 0.3f, mBlockUnit * 1.05f * scale, tiers);
            } else if (r < 0.58f) {
                // Crisp blocky pixel grass/fern tuft atop the grass block edge
                float gw = mBlockUnit * 0.32f;
                float gh = mBlockUnit * (0.55f + 0.45f * (r));
                mForePinePath.addRect(x - gw, y - gh, x + gw, y, Path.Direction.CW);
            }
        }
        mForeTerrainPaint.setShader(new LinearGradient(
                0f, h * 0.72f, 0f, h,
                new int[]{0xFF171014, 0xFF0E0A0D, 0xFF080608},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));
        mForeRimPaint.setStrokeWidth(Math.max(1.5f, mBlockUnit * 0.32f));
        mForeRimPaint.setColor(0x6CEB8C28);

        // 7. Layer 5 — Subtle Floating Golden Atmospheric Particles / Shader Fireflies
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            seed = nextSeed(seed);
            mParticleX[i] = unitFloat(seed) * w;
            seed = nextSeed(seed);
            mParticleY[i] = h * (0.18f + 0.76f * unitFloat(seed));
            seed = nextSeed(seed);
            mParticleSpeedX[i] = (4f + 10f * unitFloat(seed)) * (mBlockUnit / 6f);
            seed = nextSeed(seed);
            mParticleSpeedY[i] = -(3f + 8f * unitFloat(seed)) * (mBlockUnit / 6f);
            seed = nextSeed(seed);
            mParticleRadius[i] = Math.max(1.4f, mBlockUnit * (0.18f + 0.26f * unitFloat(seed)));
            seed = nextSeed(seed);
            mParticlePhase[i] = unitFloat(seed) * 6.283185f;
            seed = nextSeed(seed);
            mParticleWarmGold[i] = unitFloat(seed) > 0.32f;
        }

        // 8. Soft Cinema Framing Vignettes & Readability Halo
        mTopScrimShader = new LinearGradient(
                0f, 0f, 0f, h * 0.22f,
                0x6609070C, 0x0009070C, Shader.TileMode.CLAMP
        );
        mBottomScrimShader = new LinearGradient(
                0f, h * 0.80f, 0f, h,
                0x00070508, 0x8A070508, Shader.TileMode.CLAMP
        );
        mCenterReadabilityShader = new RadialGradient(
                w * 0.5f, h * 0.5f, Math.max(shortSide * 0.34f, 120f),
                new int[]{0x420B080A, 0x1F0B080A, 0x000B080A},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mReleased) return;
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (mUseStaticFallback || !mSceneReady) {
            drawStaticFallback(canvas, w, h);
            return;
        }

        boolean animateNow = mRunning
                && !git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZYROX_REDUCED_MOTION
                && !git.artdeell.mojo.prefs.LauncherPreferences.PREF_ZYROX_LOW_EFFECTS
                && areAnimatorsEnabledSafe();
        long nowMs = SystemClock.uptimeMillis();
        if (animateNow) {
            if (mLastFrameMs > 0L) {
                float dt = (nowMs - mLastFrameMs) * 0.001f;
                if (dt > 0f) {
                    mElapsedSec += Math.min(dt, MAX_FRAME_DELTA_SEC);
                }
            }
            mLastFrameMs = nowMs;
        } else {
            mLastFrameMs = 0L;
        }

        try {
            drawParallaxSunsetScene(canvas, w, h, mElapsedSec);
        } catch (Throwable t) {
            Log.w(TAG, "Switching to static loading fallback after draw exception", t);
            mUseStaticFallback = true;
            drawStaticFallback(canvas, w, h);
            return;
        }

        if (animateNow && getVisibility() == VISIBLE && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawParallaxSunsetScene(@NonNull Canvas canvas, int w, int h, double t) {
        // 1. Warm Sunset Gradient Sky
        canvas.drawRect(0f, 0f, w, h, mSkyPaint);

        // Subtle high-sky dusk stars
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.45f + 0.55f * (float) Math.sin(t * 1.35 + mStarPhase[i]);
            int alpha = Math.round(92f * twinkle);
            mStarPaint.setColor((alpha << 24) | 0xFFFDE8C8);
            float sx = mStarX[i];
            float sy = mStarY[i];
            float ss = mStarSize[i];
            canvas.drawRect(sx, sy, sx + ss, sy + ss, mStarPaint);
        }

        // 2. Blocky Minecraft Sunset Sun + Atmospheric Shader Glow + Crepuscular Rays
        float sunPulse = 0.90f + 0.10f * (float) Math.sin(t * 0.65);
        mSunGlowPaint.setAlpha(Math.round(235f * sunPulse));
        canvas.drawCircle(mSunCenterX, mSunCenterY, mSunGlowRadius, mSunGlowPaint);

        // Iconic square blocky Minecraft shader sun (3 crisp concentric pixel blocks)
        float outerHalf = mSunSize * 0.68f;
        mSunBlockPaint.setColor(0x52F59E0B);
        canvas.drawRect(mSunCenterX - outerHalf, mSunCenterY - outerHalf,
                mSunCenterX + outerHalf, mSunCenterY + outerHalf, mSunBlockPaint);

        float midHalf = mSunSize * 0.50f;
        mSunBlockPaint.setColor(0xD8FDE68A);
        canvas.drawRect(mSunCenterX - midHalf, mSunCenterY - midHalf,
                mSunCenterX + midHalf, mSunCenterY + midHalf, mSunBlockPaint);

        float coreHalf = mSunSize * 0.36f;
        mSunBlockPaint.setColor(0xFFFFFBEB);
        canvas.drawRect(mSunCenterX - coreHalf, mSunCenterY - coreHalf,
                mSunCenterX + coreHalf, mSunCenterY + coreHalf, mSunBlockPaint);

        // Volumetric sun rays gently breathing behind clouds & mountain ridges
        if (mSunRayPaths != null) {
            for (int i = 0; i < mSunRayPaths.length; i++) {
                float rayWave = 0.58f + 0.42f * (float) Math.sin(t * 0.48 + i * 1.17);
                mRayPaint.setAlpha(Math.round(185f * rayWave));
                canvas.drawPath(mSunRayPaths[i], mRayPaint);
            }
        }

        // 3. Far-Tier Volumetric Drifting Minecraft Clouds (behind distant peaks)
        drawCloudTier(canvas, w, t, false);

        // 4. Layer 1 — Distant Mountain Silhouettes (slowest horizontal parallax)
        float scaleUnit = mBlockUnit / 6f;
        float farScroll = positiveMod(t * (2.6 * scaleUnit), mTileWidth);
        drawTiledPath(canvas, w, farScroll, mFarMountainPath, mFarMountainPaint, mTileWidth);
        drawTiledPath(canvas, w, farScroll, mFarRimPath, mFarRimPaint, mTileWidth);

        // Warm drifting valley mist between distant & midground mountain ranges
        float farMistBreath = 0.82f + 0.18f * (float) Math.sin(t * 0.42 + 0.8);
        mHazePaint.setShader(mFarHazeShader);
        mHazePaint.setAlpha(Math.round(235f * farMistBreath));
        canvas.drawRect(0f, mFarFogTop, w, mFarFogBottom, mHazePaint);

        // 5. Near-Tier Volumetric Drifting 3D Minecraft Shader Clouds
        drawCloudTier(canvas, w, t, true);

        // 6. Layer 2 — Midground Voxel Cliffs & Spruce/Pine Forests (medium horizontal parallax)
        float midScroll = positiveMod(t * (6.4 * scaleUnit), mTileWidth);
        drawTiledPath(canvas, w, midScroll, mMidMountainPath, mMidMountainPaint, mTileWidth);
        drawTiledPath(canvas, w, midScroll, mMidCliffShadePath, mMidShadowPaint, mTileWidth);
        drawTiledPath(canvas, w, midScroll, mMidRimPath, mMidRimPaint, mTileWidth);
        drawTiledPath(canvas, w, midScroll, mMidPinePath, mMidPinePaint, mTileWidth);

        // Warm glowing pixel lantern window in the distant mountain ridge outpost
        if (mLanternOffsetX >= 0f) {
            float lanternFlicker = 0.84f + 0.16f * (float) Math.sin(t * 3.1);
            int glowAlpha = Math.round(90f * lanternFlicker);
            mSunBlockPaint.setColor((glowAlpha << 24) | 0xFFF59E0B);
            for (float base = -midScroll; base < w; base += mTileWidth) {
                float lx = base + mLanternOffsetX;
                if (lx > -mBlockUnit * 4f && lx < w + mBlockUnit * 4f) {
                    canvas.drawCircle(lx + mBlockUnit * 0.4f, mLanternY + mBlockUnit * 0.4f,
                            mBlockUnit * 2.2f, mSunBlockPaint);
                    int winAlpha = Math.round(240f * lanternFlicker);
                    int prev = mSunBlockPaint.getColor();
                    mSunBlockPaint.setColor((winAlpha << 24) | 0xFFFDE68A);
                    canvas.drawRect(lx, mLanternY, lx + mBlockUnit * 0.8f, mLanternY + mBlockUnit * 0.8f,
                            mSunBlockPaint);
                    mSunBlockPaint.setColor(prev);
                }
            }
        }

        // Low golden valley fog drifting gently above the foreground ridge
        float midMistBreath = 0.80f + 0.20f * (float) Math.cos(t * 0.52);
        mHazePaint.setShader(mMidHazeShader);
        mHazePaint.setAlpha(Math.round(230f * midMistBreath));
        canvas.drawRect(0f, mMidFogTop, w, mMidFogBottom, mHazePaint);

        // 7. Layer 3 — Foreground Stepped Blocky Terrain & Framing Pines (near parallax)
        float foreScroll = positiveMod(t * (12.2 * scaleUnit), mTileWidth);
        drawTiledPath(canvas, w, foreScroll, mForeTerrainPath, mForeTerrainPaint, mTileWidth);
        drawTiledPath(canvas, w, foreScroll, mForeRimPath, mForeRimPaint, mTileWidth);
        drawTiledPath(canvas, w, foreScroll, mForePinePath, mForePinePaint, mTileWidth);

        // 8. Layer 5 — Subtle Floating Golden Sunlight Motes / Firefly Embers
        float verticalSpan = h * 0.82f;
        float topBound = h * 0.12f;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float sway = (float) Math.sin(t * 0.9 + mParticlePhase[i]) * mBlockUnit * 1.4f;
            float px = positiveMod(mParticleX[i] + t * mParticleSpeedX[i] + sway, w);
            float py = topBound + positiveMod((mParticleY[i] - topBound) + t * mParticleSpeedY[i], verticalSpan);
            float pulse = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 1.65 + mParticlePhase[i]));
            float r = mParticleRadius[i];

            // Soft outer halo
            int haloAlpha = Math.round(46f * pulse);
            int rgb = mParticleWarmGold[i] ? 0xFFFDE68A : 0xFFF59E0B;
            mParticlePaint.setColor((haloAlpha << 24) | (rgb & 0x00FFFFFF));
            canvas.drawCircle(px, py, r * 2.4f, mParticlePaint);

            // Crisp warm pixel mote core
            int coreAlpha = Math.round(168f * pulse);
            mParticlePaint.setColor((coreAlpha << 24) | 0xFFFFF7D6);
            canvas.drawRect(px - r * 0.65f, py - r * 0.65f, px + r * 0.65f, py + r * 0.65f, mParticlePaint);
        }

        // 9. Gentle atmospheric edge framing & central readability halo
        mVignettePaint.setAlpha(255);
        mVignettePaint.setShader(mTopScrimShader);
        canvas.drawRect(0f, 0f, w, h * 0.22f, mVignettePaint);
        mVignettePaint.setShader(mBottomScrimShader);
        canvas.drawRect(0f, h * 0.80f, w, h, mVignettePaint);
        mVignettePaint.setShader(mCenterReadabilityShader);
        canvas.drawRect(0f, 0f, w, h, mVignettePaint);
    }

    private void drawCloudTier(@NonNull Canvas canvas, int w, double t, boolean nearTier) {
        float margin = mBlockUnit * 26f;
        float span = w + margin * 2f;
        for (int i = 0; i < CLOUD_COUNT; i++) {
            if (mCloudNearTier[i] != nearTier) continue;
            float cw = mCloudWidth[i];
            float ch = mCloudHeight[i];
            float cx = positiveMod(mCloudBaseX[i] + t * mCloudSpeed[i], span) - margin;
            if (cx + cw < -mBlockUnit * 2f || cx > w + mBlockUnit * 2f) continue;
            float cy = mCloudY[i];

            // Classic stepped 3D Minecraft shader cloud slab: upper deck, sunlit body, shaded base
            int topColor = nearTier ? 0xB8FFF3D6 : 0x82FFE8BF;
            int sideColor = nearTier ? 0x9EFDBA74 : 0x6EF59E0B;
            int underColor = nearTier ? 0x7A7C3F3E : 0x5469343A;

            // Upper blocky crown slab
            mCloudPaint.setColor(topColor);
            canvas.drawRect(cx + cw * 0.16f, cy - ch * 0.58f, cx + cw * 0.78f, cy, mCloudPaint);
            // Main sunlit face
            canvas.drawRect(cx, cy, cx + cw, cy + ch * 0.55f, mCloudPaint);
            // Warm sunset amber side face
            mCloudPaint.setColor(sideColor);
            canvas.drawRect(cx + cw * 0.45f, cy + ch * 0.15f, cx + cw, cy + ch * 0.78f, mCloudPaint);
            // Soft atmospheric shaded cloud underside
            mCloudPaint.setColor(underColor);
            canvas.drawRect(cx + mBlockUnit * 0.5f, cy + ch * 0.55f,
                    cx + cw - mBlockUnit * 0.4f, cy + ch, mCloudPaint);
        }
    }

    private static void drawTiledPath(@NonNull Canvas canvas, int viewportWidth, float scrollOffset,
                                      @Nullable Path path, @NonNull Paint paint, float tileWidth) {
        if (path == null || tileWidth <= 0f) return;
        for (float x = -scrollOffset; x < viewportWidth; x += tileWidth) {
            canvas.save();
            canvas.translate(x, 0f);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    private void drawStaticFallback(@NonNull Canvas canvas, int w, int h) {
        if (mFallbackPaint.getShader() != null) {
            canvas.drawRect(0f, 0f, w, h, mFallbackPaint);
        } else {
            canvas.drawColor(Color.parseColor("#1A1118"));
        }
        // Minimal zero-allocation static blocky horizon silhouette
        mSunBlockPaint.setColor(0xFF120C10);
        canvas.drawRect(0f, h * 0.78f, w, h, mSunBlockPaint);
    }

    /** Builds a closed stepped voxel terrain polygon spanning {@code [0, cols * step]}. */
    @NonNull
    private static Path buildSteppedTerrainPath(@NonNull float[] heights, float step, int viewportHeight) {
        Path path = new Path();
        float bottom = viewportHeight + 4f;
        path.moveTo(0f, bottom);
        path.lineTo(0f, heights[0]);
        float x = 0f;
        for (int i = 0; i < heights.length; i++) {
            float y = heights[i];
            if (i > 0) {
                path.lineTo(x, y);
            }
            x += step;
            path.lineTo(x, y);
        }
        // Close periodically to heights[0] so adjacent tiles meet without any vertical gap.
        path.lineTo(x, heights[0]);
        path.lineTo(x, bottom);
        path.close();
        return path;
    }

    /** Builds crisp horizontal sunlit top-face rim segments along voxel block ledges. */
    @NonNull
    private static Path buildSteppedRimPath(@NonNull float[] heights, float step) {
        Path path = new Path();
        float x = 0f;
        for (int i = 0; i < heights.length; i++) {
            float y = heights[i];
            int prev = (i == 0) ? heights.length - 1 : i - 1;
            // Highlight flat summits and sunward-facing ledges
            if (y <= heights[prev]) {
                path.moveTo(x, y);
                path.lineTo(x + step, y);
            }
            x += step;
        }
        return path;
    }

    /** Builds subtle shaded vertical voxel cliff faces on slopes turned away from the sun. */
    @NonNull
    private static Path buildVoxelCliffShadePath(@NonNull float[] heights, float step, float depth) {
        Path path = new Path();
        for (int i = 1; i < heights.length; i++) {
            if (heights[i] < heights[i - 1]) {
                float x = i * step;
                float top = heights[i];
                float bottom = Math.min(heights[i - 1] + depth, top + depth * 1.4f);
                path.addRect(x - step * 0.85f, top + step * 0.25f, x, bottom, Path.Direction.CW);
            }
        }
        return path;
    }

    /** Adds an authentic tiered blocky Minecraft spruce/pine tree silhouette centered at {@code cx}. */
    private static void addBlockySpruceTree(@NonNull Path out, float cx, float baseY, float unit, int tiers) {
        // Trunk block
        float trunkHalf = unit * 0.42f;
        float trunkH = unit * 1.45f;
        out.addRect(cx - trunkHalf, baseY - trunkH, cx + trunkHalf, baseY + unit * 0.35f, Path.Direction.CW);

        // Stepped rectangular foliage tiers narrowing toward the pixel crown
        float y = baseY - trunkH;
        for (int t = 0; t < tiers; t++) {
            float tierHalfW = unit * (2.15f - t * 0.44f);
            float tierH = unit * 1.18f;
            out.addRect(cx - tierHalfW, y - tierH, cx + tierHalfW, y, Path.Direction.CW);
            float subHalfW = tierHalfW * 0.66f;
            float subH = unit * 0.82f;
            out.addRect(cx - subHalfW, y - tierH - subH, cx + subHalfW, y - tierH, Path.Direction.CW);
            y -= (tierH + subH * 0.72f);
        }
        // Iconic single-block spruce tip
        float tipHalf = unit * 0.45f;
        out.addRect(cx - tipHalf, y - unit * 1.05f, cx + tipHalf, y + unit * 0.1f, Path.Direction.CW);
    }

    private static float quantizeBlock(float value, float blockSize) {
        return Math.round(value / blockSize) * blockSize;
    }

    private static float positiveMod(double value, float modulus) {
        if (modulus <= 0f) return 0f;
        double rem = value % modulus;
        if (rem < 0.0) rem += modulus;
        return (float) rem;
    }

    private static long nextSeed(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    private static float unitFloat(long seed) {
        return ((seed >>> 40) & 0xFFFFFF) / (float) 0x1000000;
    }

    private static boolean areAnimatorsEnabledSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return true;
    }
}
