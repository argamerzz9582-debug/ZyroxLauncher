package git.artdeell.mojo.authenticator.accounts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.ZyroxHttpClient;

/**
 * Dual-mode Minecraft character renderer:
 * <ol>
 *   <li>Crisp pixel-exact 2D isometric head + hat avatar renderer ({@link #render(int, Bitmap)})</li>
 *   <li>Interactive hardware-accelerated OpenGL ES 2.0 3D rotatable full-body player &amp; 3D cape
 *       preview ({@link PlayerModelGlView}) with Classic (4px) / Slim (3px) arms, 64×64 dual outer
 *       layers, touch orbit + pinch-zoom, walk/idle animation, and verified HTTPS Ely.by /
 *       OptiFine cape resolver ({@link CapeSource}).</li>
 * </ol>
 */
public class SkinHeadRenderer {
    private static final String TAG = "SkinHeadRenderer";

    /** Official HTTPS endpoints for Ely.by &amp; OptiFine cape lookup (zero cleartext HTTP). */
    public static final String ELY_BY_TEXTURES_API = "https://skinsystem.ely.by/textures/";
    public static final String OPTIFINE_CAPE_HTTPS_BASE = "https://s.optifine.net/capes/";
    private static final int MAX_CAPE_BYTES = 2 * 1024 * 1024;

    public enum CapeSource {
        NONE("none", "No Cape"),
        ELY_BY("elyby", "Ely.by Cape"),
        OPTIFINE("optifine", "OptiFine Cape"),
        CUSTOM_LOCAL("local", "Custom PNG Cape");

        @NonNull public final String id;
        @NonNull public final String displayLabel;

        CapeSource(@NonNull String id, @NonNull String displayLabel) {
            this.id = id;
            this.displayLabel = displayLabel;
        }

        @NonNull
        public static CapeSource fromId(@Nullable String id) {
            if (id != null) {
                for (CapeSource s : values()) {
                    if (s.id.equalsIgnoreCase(id)) return s;
                }
            }
            return NONE;
        }
    }

    private static final Matrix MIRROR_MATRIX = new Matrix();
    static {
        MIRROR_MATRIX.setScale(-1, 1);
    }

    private static final float[][] ISO_POINTS = {
            {0.5f, 1.0f},         // 0 Bottom
            {0.9330127f, 0.75f},  // 1 Bottom-right
            {0.066987306f, 0.75f},// 2 Bottom-left
            {0.5f, 0.0f},         // 3 Top
            {0.9330127f, 0.25f},  // 4 Top-right
            {0.066987306f, 0.25f},// 5 Top-left
            {0.5f, 0.5f}          // 6 Center
    };

    private static final int FACE_LEFT = 0;
    private static final int FACE_RIGHT = 1;
    private static final int FACE_TOP = 2;
    private static final int FACE_REAR_RIGHT = 3;
    private static final int FACE_REAR_LEFT = 4;
    private static final int FACE_BOTTOM = 5;

    private int mCoordScale = 1;
    private final float[] mMeshBuffer = new float[8];
    private final Paint mNearestPaint = new Paint();
    private final Set<Bitmap> mTempBitmaps = new HashSet<>();

    public SkinHeadRenderer() {
        mNearestPaint.setFilterBitmap(false);
        mNearestPaint.setDither(false);
    }

    // =========================================================================
    // 2D Isometric Head + Outer Hat Avatar Renderer
    // =========================================================================

    private Bitmap getSubregion(Bitmap src, int left, int top, int right, int bottom) {
        return getSubregion(src, left, top, right, bottom, false);
    }

    private Bitmap internalGetSubregion(Bitmap src, int left, int top, int right, int bottom, boolean mirror) {
        Bitmap bitmap = Bitmap.createBitmap(src, left, top, right - left, bottom - top);
        if (!mirror) return bitmap;
        Bitmap mirroredBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), MIRROR_MATRIX, false);
        bitmap.recycle();
        return mirroredBitmap;
    }

    private Bitmap getSubregion(Bitmap src, int left, int top, int right, int bottom, boolean mirror) {
        left *= mCoordScale;
        top *= mCoordScale;
        right *= mCoordScale;
        bottom *= mCoordScale;
        Bitmap subregion = internalGetSubregion(src, left, top, right, bottom, mirror);
        mTempBitmaps.add(subregion);
        return subregion;
    }

    private void applyFace(float[] dst, int face, float mul, float off) {
        switch (face) {
            case FACE_LEFT:
                dst[0] = ISO_POINTS[5][0] * mul + off; dst[1] = ISO_POINTS[5][1] * mul + off;
                dst[2] = ISO_POINTS[6][0] * mul + off; dst[3] = ISO_POINTS[6][1] * mul + off;
                dst[4] = ISO_POINTS[2][0] * mul + off; dst[5] = ISO_POINTS[2][1] * mul + off;
                dst[6] = ISO_POINTS[0][0] * mul + off; dst[7] = ISO_POINTS[0][1] * mul + off;
                return;
            case FACE_RIGHT:
                dst[0] = ISO_POINTS[6][0] * mul + off; dst[1] = ISO_POINTS[6][1] * mul + off;
                dst[2] = ISO_POINTS[4][0] * mul + off; dst[3] = ISO_POINTS[4][1] * mul + off;
                dst[4] = ISO_POINTS[0][0] * mul + off; dst[5] = ISO_POINTS[0][1] * mul + off;
                dst[6] = ISO_POINTS[1][0] * mul + off; dst[7] = ISO_POINTS[1][1] * mul + off;
                return;
            case FACE_TOP:
                dst[0] = ISO_POINTS[5][0] * mul + off; dst[1] = ISO_POINTS[5][1] * mul + off;
                dst[2] = ISO_POINTS[3][0] * mul + off; dst[3] = ISO_POINTS[3][1] * mul + off;
                dst[4] = ISO_POINTS[6][0] * mul + off; dst[5] = ISO_POINTS[6][1] * mul + off;
                dst[6] = ISO_POINTS[4][0] * mul + off; dst[7] = ISO_POINTS[4][1] * mul + off;
                return;
            case FACE_REAR_RIGHT:
                dst[0] = ISO_POINTS[3][0] * mul + off; dst[1] = ISO_POINTS[3][1] * mul + off;
                dst[2] = ISO_POINTS[4][0] * mul + off; dst[3] = ISO_POINTS[4][1] * mul + off;
                dst[4] = ISO_POINTS[6][0] * mul + off; dst[5] = ISO_POINTS[6][1] * mul + off;
                dst[6] = ISO_POINTS[1][0] * mul + off; dst[7] = ISO_POINTS[1][1] * mul + off;
                return;
            case FACE_REAR_LEFT:
                dst[0] = ISO_POINTS[5][0] * mul + off; dst[1] = ISO_POINTS[5][1] * mul + off;
                dst[2] = ISO_POINTS[3][0] * mul + off; dst[3] = ISO_POINTS[3][1] * mul + off;
                dst[4] = ISO_POINTS[2][0] * mul + off; dst[5] = ISO_POINTS[2][1] * mul + off;
                dst[6] = ISO_POINTS[6][0] * mul + off; dst[7] = ISO_POINTS[6][1] * mul + off;
                return;
            case FACE_BOTTOM:
                dst[0] = ISO_POINTS[2][0] * mul + off; dst[1] = ISO_POINTS[2][1] * mul + off;
                dst[2] = ISO_POINTS[6][0] * mul + off; dst[3] = ISO_POINTS[6][1] * mul + off;
                dst[4] = ISO_POINTS[0][0] * mul + off; dst[5] = ISO_POINTS[0][1] * mul + off;
                dst[6] = ISO_POINTS[1][0] * mul + off; dst[7] = ISO_POINTS[1][1] * mul + off;
        }
    }

    private void drawMesh(Canvas canvas, Bitmap bitmap, int face, float multiplier, float offset) {
        applyFace(mMeshBuffer, face, multiplier, offset);
        canvas.drawBitmapMesh(bitmap, 1, 1, mMeshBuffer, 0, null, 0, mNearestPaint);
    }

    private boolean prepareCoordScale(int sourceDimension) {
        if (sourceDimension <= 0 || sourceDimension % 64 != 0) {
            Log.e(TAG, "Invalid skin dimension: " + sourceDimension);
            return false;
        }
        mCoordScale = sourceDimension / 64;
        return true;
    }

    @Nullable
    public Bitmap render(int side, @Nullable Bitmap sourceSkin) {
        if (sourceSkin == null || sourceSkin.isRecycled()) return null;
        if (!prepareCoordScale(sourceSkin.getWidth())) return null;

        Bitmap overlayTopFace = getSubregion(sourceSkin, 40, 0, 48, 8);
        Bitmap overlayLeftFace = getSubregion(sourceSkin, 32, 8, 40, 16);
        Bitmap overlayRightFace = getSubregion(sourceSkin, 40, 8, 48, 16);
        Bitmap overlayBottomFace = getSubregion(sourceSkin, 48, 0, 56, 8);
        Bitmap overlayRearLeftFace = getSubregion(sourceSkin, 56, 8, 64, 16, true);
        Bitmap overlayRearRightFace = getSubregion(sourceSkin, 48, 8, 56, 16, true);

        Bitmap topFace = getSubregion(sourceSkin, 8, 0, 16, 8);
        Bitmap leftFace = getSubregion(sourceSkin, 0, 8, 8, 16);
        Bitmap rightFace = getSubregion(sourceSkin, 8, 8, 16, 16);

        Bitmap renderTarget = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(renderTarget);

        float headOffset = side / 16f;
        float headMultiplier = side * 14f / 16f;

        drawMesh(canvas, overlayRearLeftFace, FACE_REAR_LEFT, side, 0);
        drawMesh(canvas, overlayRearRightFace, FACE_REAR_RIGHT, side, 0);
        drawMesh(canvas, overlayBottomFace, FACE_BOTTOM, side, 0);

        drawMesh(canvas, leftFace, FACE_LEFT, headMultiplier, headOffset);
        drawMesh(canvas, rightFace, FACE_RIGHT, headMultiplier, headOffset);
        drawMesh(canvas, topFace, FACE_TOP, headMultiplier, headOffset);

        drawMesh(canvas, overlayLeftFace, FACE_LEFT, side, 0);
        drawMesh(canvas, overlayRightFace, FACE_RIGHT, side, 0);
        drawMesh(canvas, overlayTopFace, FACE_TOP, side, 0);

        for (Bitmap region : mTempBitmaps) region.recycle();
        mTempBitmaps.clear();
        return renderTarget;
    }

    // =========================================================================
    // Verified HTTPS Ely.by & OptiFine Cape Resolver
    // =========================================================================

    /** Builds the verified HTTPS OptiFine cape URL for a valid Minecraft username. */
    @Nullable
    public static String buildOptiFineCapeUrl(@Nullable String username) {
        String clean = sanitizeMinecraftUsername(username);
        if (clean == null) return null;
        return OPTIFINE_CAPE_HTTPS_BASE + urlEncode(clean) + ".png";
    }

    /** Extracts and upgrades an Ely.by {@code CAPE.url} from {@code skinsystem.ely.by/textures/<user>}. */
    @Nullable
    public static String parseElyByCapeUrl(@Nullable String texturesJson) {
        if (texturesJson == null || texturesJson.trim().isEmpty()) return null;
        try {
            JsonObject root = JsonParser.parseString(texturesJson).getAsJsonObject();
            if (!root.has("CAPE") || !root.get("CAPE").isJsonObject()) return null;
            JsonObject cape = root.getAsJsonObject("CAPE");
            if (!cape.has("url") || cape.get("url").isJsonNull()) return null;
            String url = cape.get("url").getAsString().trim();
            if (url.startsWith("http://ely.by/") || url.startsWith("http://skinsystem.ely.by/")) {
                url = "https://" + url.substring("http://".length());
            }
            return ZyroxHttpClient.isStrictHttpsUrl(url) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static String sanitizeMinecraftUsername(@Nullable String username) {
        if (username == null) return null;
        String trimmed = username.trim();
        if (trimmed.length() < 2 || trimmed.length() > 32) return null;
        if (!trimmed.matches("^[A-Za-z0-9_.-]+$")) return null;
        return trimmed;
    }

    /**
     * Fetches a decoded {@code ARGB_8888} cape texture from {@link CapeSource#ELY_BY} or
     * {@link CapeSource#OPTIFINE} over pooled HTTPS, normalized onto a standard 2:1 cape atlas.
     */
    @NonNull
    public static Bitmap fetchRemoteCapeBitmap(@NonNull CapeSource source,
                                               @NonNull String username) throws IOException {
        String cleanUser = sanitizeMinecraftUsername(username);
        if (cleanUser == null) {
            throw new IOException("Enter a valid Minecraft / Ely.by username (2–32 chars).");
        }
        final String capeUrl;
        if (source == CapeSource.ELY_BY) {
            String json = ZyroxHttpClient.getString(
                    ELY_BY_TEXTURES_API + urlEncode(cleanUser), null);
            capeUrl = parseElyByCapeUrl(json);
            if (capeUrl == null) {
                throw new IOException("No Ely.by cape found for player \"" + cleanUser + "\".");
            }
        } else if (source == CapeSource.OPTIFINE) {
            capeUrl = buildOptiFineCapeUrl(cleanUser);
            if (capeUrl == null) {
                throw new IOException("Invalid username for OptiFine cape.");
            }
        } else {
            throw new IOException("Unsupported remote cape provider: " + source);
        }

        byte[] bytes;
        try (okhttp3.Response resp = ZyroxHttpClient.openDownloadResponse(capeUrl, 0L)) {
            if (resp.code() == 404) {
                throw new IOException("No " + source.displayLabel + " active for \"" + cleanUser + "\".");
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException(source.displayLabel + " server returned HTTP " + resp.code());
            }
            try (InputStream in = resp.body().byteStream()) {
                bytes = ArchiveSecurity.readBytesBounded(in, MAX_CAPE_BYTES);
            }
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, opts);
        if (decoded == null || decoded.getWidth() < 22 || decoded.getHeight() < 17) {
            if (decoded != null) decoded.recycle();
            throw new IOException("Invalid cape PNG returned by " + source.displayLabel);
        }
        return normalizeCapeAtlas(decoded);
    }

    /**
     * Pads non-power-of-two OptiFine/Ely.by capes (e.g. {@code 46×22}, {@code 92×44}) onto an
     * exact {@code 64×32}-multiple atlas so standard Minecraft cape UV coordinates map 1:1.
     */
    @NonNull
    public static Bitmap normalizeCapeAtlas(@NonNull Bitmap raw) {
        int w = raw.getWidth();
        int h = raw.getHeight();
        int targetW = 64;
        int targetH = 32;
        while (targetW < w || targetH < h) {
            targetW <<= 1;
            targetH <<= 1;
            if (targetW >= 2048) break;
        }
        if (w == targetW && h == targetH && raw.getConfig() == Bitmap.Config.ARGB_8888) {
            return raw;
        }
        Bitmap padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        Paint p = new Paint();
        p.setFilterBitmap(false);
        canvas.drawBitmap(raw, 0f, 0f, p);
        raw.recycle();
        return padded;
    }

    @NonNull
    private static String urlEncode(@NonNull String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // =========================================================================
    // Pure 3D Minecraft Box Mesh + UV Generator (8 floats/vertex: xyz, nxyz, uv)
    // =========================================================================

    public static final int FLOATS_PER_VERTEX = 8;
    public static final int VERTICES_PER_BOX = 36;

    /**
     * Builds the 36-vertex (6 faces × 2 triangles) Minecraft cuboid mesh with exact official
     * skin/cape UV layout (Right, Front, Left, Back, Top, Bottom), optional inflate shell offset
     * for 3D Hat/Jacket/Sleeve/Pants layers, and optional horizontal UV mirror for 64×32 limbs.
     */
    @NonNull
    public static float[] buildMinecraftBoxMesh(float minX, float minY, float minZ,
                                                float sizeX, float sizeY, float sizeZ,
                                                float inflate,
                                                int texU, int texV,
                                                int boxPixelW, int boxPixelH, int boxPixelD,
                                                int atlasW, int atlasH,
                                                boolean mirrorU) {
        float x0 = minX - inflate;
        float y0 = minY - inflate;
        float z0 = minZ - inflate;
        float x1 = minX + sizeX + inflate;
        float y1 = minY + sizeY + inflate;
        float z1 = minZ + sizeZ + inflate;

        // Slight inward texel epsilon eliminates edge bleeding under GL_NEAREST at grazing angles
        final float epsU = 0.02f / atlasW;
        final float epsV = 0.02f / atlasH;

        float[] out = new float[VERTICES_PER_BOX * FLOATS_PER_VERTEX];
        int cursor = 0;

        // Standard Minecraft box net rooted at (texU, texV):
        //   Top:    [u+D .. u+D+W,       v   .. v+D]
        //   Bottom: [u+D+W .. u+D+2W,    v   .. v+D]
        //   Right:  [u .. u+D,           v+D .. v+D+H]  (-X side of player)
        //   Front:  [u+D .. u+D+W,       v+D .. v+D+H]  (+Z facing viewer)
        //   Left:   [u+D+W .. u+2D+W,    v+D .. v+D+H]  (+X side of player)
        //   Back:   [u+2D+W .. u+2D+2W,  v+D .. v+D+H]  (-Z rear of player)
        int d = boxPixelD;
        int w = boxPixelW;
        int h = boxPixelH;

        // Front (+Z)
        cursor = emitQuad(out, cursor,
                x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1,
                0f, 0f, 1f,
                uv(texU + d, atlasW, epsU), uv(texV + d, atlasH, epsV),
                uv(texU + d + w, atlasW, -epsU), uv(texV + d + h, atlasH, -epsV), mirrorU);
        // Back (-Z)
        cursor = emitQuad(out, cursor,
                x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0,
                0f, 0f, -1f,
                uv(texU + 2 * d + w, atlasW, epsU), uv(texV + d, atlasH, epsV),
                uv(texU + 2 * d + 2 * w, atlasW, -epsU), uv(texV + d + h, atlasH, -epsV), mirrorU);
        // Right side of model (-X in viewer space)
        cursor = emitQuad(out, cursor,
                x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0,
                -1f, 0f, 0f,
                uv(texU, atlasW, epsU), uv(texV + d, atlasH, epsV),
                uv(texU + d, atlasW, -epsU), uv(texV + d + h, atlasH, -epsV), mirrorU);
        // Left side of model (+X in viewer space)
        cursor = emitQuad(out, cursor,
                x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1,
                1f, 0f, 0f,
                uv(texU + d + w, atlasW, epsU), uv(texV + d, atlasH, epsV),
                uv(texU + 2 * d + w, atlasW, -epsU), uv(texV + d + h, atlasH, -epsV), mirrorU);
        // Top (+Y)
        cursor = emitQuad(out, cursor,
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                0f, 1f, 0f,
                uv(texU + d, atlasW, epsU), uv(texV, atlasH, epsV),
                uv(texU + d + w, atlasW, -epsU), uv(texV + d, atlasH, -epsV), mirrorU);
        // Bottom (-Y)
        emitQuad(out, cursor,
                x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                0f, -1f, 0f,
                uv(texU + d + w, atlasW, epsU), uv(texV + d, atlasH, -epsV),
                uv(texU + d + 2 * w, atlasW, -epsU), uv(texV, atlasH, epsV), mirrorU);

        return out;
    }

    private static float uv(int px, int total, float eps) {
        return ((float) px / (float) total) + eps;
    }

    private static int emitQuad(float[] dst, int idx,
                                float ax, float ay, float az,
                                float bx, float by, float bz,
                                float cx, float cy, float cz,
                                float dx, float dy, float dz,
                                float nx, float ny, float nz,
                                float u0, float v0, float u1, float v1,
                                boolean mirrorU) {
        float lu = mirrorU ? u1 : u0;
        float ru = mirrorU ? u0 : u1;
        // Triangle 1: A, D, C; Triangle 2: A, C, B
        idx = putVertex(dst, idx, ax, ay, az, nx, ny, nz, lu, v0);
        idx = putVertex(dst, idx, dx, dy, dz, nx, ny, nz, lu, v1);
        idx = putVertex(dst, idx, cx, cy, cz, nx, ny, nz, ru, v1);
        idx = putVertex(dst, idx, ax, ay, az, nx, ny, nz, lu, v0);
        idx = putVertex(dst, idx, cx, cy, cz, nx, ny, nz, ru, v1);
        idx = putVertex(dst, idx, bx, by, bz, nx, ny, nz, ru, v0);
        return idx;
    }

    private static int putVertex(float[] dst, int idx,
                                 float x, float y, float z,
                                 float nx, float ny, float nz,
                                 float u, float v) {
        dst[idx++] = x;
        dst[idx++] = y;
        dst[idx++] = z;
        dst[idx++] = nx;
        dst[idx++] = ny;
        dst[idx++] = nz;
        dst[idx++] = u;
        dst[idx++] = v;
        return idx;
    }

    // =========================================================================
    // Interactive OpenGL ES 2.0 3D Rotatable Player & Cape Preview View
    // =========================================================================

    public static final class PlayerModelGlView extends GLSurfaceView {
        private final PlayerModelRenderer mRenderer;
        private final ScaleGestureDetector mScaleDetector;
        private final GestureDetector mGestureDetector;
        private float mLastTouchX;
        private float mLastTouchY;
        private boolean mIsScaling;

        public PlayerModelGlView(@NonNull Context context) {
            super(context);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            getHolder().setFormat(PixelFormat.TRANSLUCENT);
            setZOrderMediaOverlay(true);

            mRenderer = new PlayerModelRenderer();
            setRenderer(mRenderer);
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

            mScaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                            mIsScaling = true;
                            return true;
                        }

                        @Override
                        public boolean onScale(@NonNull ScaleGestureDetector detector) {
                            mRenderer.adjustZoom(detector.getScaleFactor());
                            return true;
                        }

                        @Override
                        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                            mIsScaling = false;
                        }
                    });

            mGestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(@NonNull MotionEvent e) {
                            mRenderer.toggleFrontBackView();
                            return true;
                        }
                    });
        }

        public void setSkin(@Nullable Bitmap skinBitmap, boolean slimArms) {
            Bitmap copy = (skinBitmap != null && !skinBitmap.isRecycled())
                    ? skinBitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
            queueEvent(() -> mRenderer.updateSkin(copy, slimArms));
        }

        public void setCape(@Nullable Bitmap capeBitmap) {
            Bitmap copy = (capeBitmap != null && !capeBitmap.isRecycled())
                    ? capeBitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
            queueEvent(() -> mRenderer.updateCape(copy));
        }

        public void setWalkAnimationEnabled(boolean walk) {
            mRenderer.setWalkAnimationEnabled(walk);
        }

        public boolean isWalkAnimationEnabled() {
            return mRenderer.isWalkAnimationEnabled();
        }

        public void setShowOuterLayers(boolean show) {
            mRenderer.setShowOuterLayers(show);
        }

        public boolean isShowOuterLayers() {
            return mRenderer.isShowOuterLayers();
        }

        /** Smoothly snaps the 3D player around to 180° rear view when equipping a cape. */
        public void focusOnCape() {
            mRenderer.setYawPitch(165f, 8f);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            mScaleDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mLastTouchX = event.getX();
                    mLastTouchY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!mIsScaling && event.getPointerCount() == 1) {
                        float dx = event.getX() - mLastTouchX;
                        float dy = event.getY() - mLastTouchY;
                        mLastTouchX = event.getX();
                        mLastTouchY = event.getY();
                        mRenderer.orbitBy(dx * 0.55f, dy * 0.38f);
                    }
                    return true;
            }
            return true;
        }
    }

    /** Hardware OpenGL ES 2.0 renderer drawing the full 12-part Minecraft character + 3D cape. */
    static final class PlayerModelRenderer implements GLSurfaceView.Renderer {
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVP;\n"
                + "uniform mat4 uModel;\n"
                + "attribute vec3 aPos;\n"
                + "attribute vec3 aNormal;\n"
                + "attribute vec2 aUV;\n"
                + "varying vec2 vUV;\n"
                + "varying float vShade;\n"
                + "void main() {\n"
                + "  gl_Position = uMVP * vec4(aPos, 1.0);\n"
                + "  vec3 worldN = normalize((uModel * vec4(aNormal, 0.0)).xyz);\n"
                + "  vec3 keyLight = normalize(vec3(0.42, 0.78, 0.65));\n"
                + "  vec3 fillLight = normalize(vec3(-0.55, 0.25, -0.50));\n"
                + "  float diff = max(dot(worldN, keyLight), 0.0) * 0.52\n"
                + "             + max(dot(worldN, fillLight), 0.0) * 0.18;\n"
                + "  vShade = clamp(0.46 + diff, 0.44, 1.04);\n"
                + "  vUV = aUV;\n"
                + "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                + "uniform sampler2D uTex;\n"
                + "uniform float uOpaquePass;\n"
                + "varying vec2 vUV;\n"
                + "varying float vShade;\n"
                + "void main() {\n"
                + "  vec4 c = texture2D(uTex, vUV);\n"
                + "  if (uOpaquePass > 0.5) {\n"
                + "    if (c.a < 0.05) discard;\n"
                + "    gl_FragColor = vec4(c.rgb * vShade, 1.0);\n"
                + "  } else {\n"
                + "    if (c.a < 0.08) discard;\n"
                + "    gl_FragColor = vec4(c.rgb * vShade, c.a);\n"
                + "  }\n"
                + "}\n";

        private int mProgram;
        private int mLocMvp, mLocModel, mLocTex, mLocOpaque;
        private int mLocPos, mLocNormal, mLocUv;
        private int mSkinTexId = 0;
        private int mCapeTexId = 0;
        private boolean mHasSkin = false;
        private boolean mHasCape = false;
        private boolean mSlimArms = false;
        private boolean mLegacy64x32 = false;
        private volatile boolean mWalkAnim = true;
        private volatile boolean mShowOuter = true;

        private volatile float mYawDeg = -22f;
        private volatile float mPitchDeg = 10f;
        private volatile float mZoom = 1.0f;

        private final float[] mProj = new float[16];
        private final float[] mView = new float[16];
        private final float[] mVp = new float[16];
        private final float[] mRoot = new float[16];
        private final float[] mPart = new float[16];
        private final float[] mMvp = new float[16];

        // Limb vertex buffers (rebuilt whenever slimArms or legacy64x32 changes)
        private FloatBuffer mHeadBuf, mHatBuf;
        private FloatBuffer mBodyBuf, mJacketBuf;
        private FloatBuffer mRightArmBuf, mRightSleeveBuf;
        private FloatBuffer mLeftArmBuf, mLeftSleeveBuf;
        private FloatBuffer mRightLegBuf, mRightPantsBuf;
        private FloatBuffer mLeftLegBuf, mLeftPantsBuf;
        private FloatBuffer mCapeBuf;

        private final long mStartMs = SystemClock.uptimeMillis();

        void orbitBy(float dYaw, float dPitch) {
            mYawDeg = (mYawDeg + dYaw) % 360f;
            mPitchDeg = Math.max(-65f, Math.min(65f, mPitchDeg + dPitch));
        }

        void adjustZoom(float factor) {
            mZoom = Math.max(0.65f, Math.min(1.85f, mZoom * factor));
        }

        void setYawPitch(float yaw, float pitch) {
            mYawDeg = yaw;
            mPitchDeg = Math.max(-65f, Math.min(65f, pitch));
        }

        void toggleFrontBackView() {
            float norm = ((mYawDeg % 360f) + 360f) % 360f;
            if (norm > 90f && norm < 270f) {
                mYawDeg = -22f;
                mPitchDeg = 10f;
            } else {
                mYawDeg = 162f;
                mPitchDeg = 8f;
            }
            mZoom = 1.0f;
        }

        void setWalkAnimationEnabled(boolean enabled) {
            mWalkAnim = enabled;
        }

        boolean isWalkAnimationEnabled() {
            return mWalkAnim;
        }

        void setShowOuterLayers(boolean show) {
            mShowOuter = show;
        }

        boolean isShowOuterLayers() {
            return mShowOuter;
        }

        void updateSkin(@Nullable Bitmap bmp, boolean slim) {
            mSlimArms = slim;
            if (bmp == null) {
                mHasSkin = false;
                return;
            }
            mLegacy64x32 = (bmp.getHeight() * 2 == bmp.getWidth());
            mSkinTexId = uploadNearestTexture(mSkinTexId, bmp);
            bmp.recycle();
            mHasSkin = (mSkinTexId != 0);
            rebuildPlayerGeometry();
        }

        void updateCape(@Nullable Bitmap bmp) {
            if (bmp == null) {
                mHasCape = false;
                return;
            }
            Bitmap norm = normalizeCapeAtlas(bmp);
            mCapeTexId = uploadNearestTexture(mCapeTexId, norm);
            norm.recycle();
            mHasCape = (mCapeTexId != 0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            mProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            mLocMvp = GLES20.glGetUniformLocation(mProgram, "uMVP");
            mLocModel = GLES20.glGetUniformLocation(mProgram, "uModel");
            mLocTex = GLES20.glGetUniformLocation(mProgram, "uTex");
            mLocOpaque = GLES20.glGetUniformLocation(mProgram, "uOpaquePass");
            mLocPos = GLES20.glGetAttribLocation(mProgram, "aPos");
            mLocNormal = GLES20.glGetAttribLocation(mProgram, "aNormal");
            mLocUv = GLES20.glGetAttribLocation(mProgram, "aUV");

            // Reset texture IDs after EGL context recreation
            mSkinTexId = 0;
            mCapeTexId = 0;
            rebuildPlayerGeometry();
            mCapeBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -5f, -16f, -1f, 10f, 16f, 1f, 0f,
                    0, 0, 10, 16, 1, 64, 32, false));
            // Create default Steve fallback skin so 3D viewport is alive before first selection
            if (!mHasSkin) {
                Bitmap placeholder = createDefaultPlaceholderSkin();
                mSkinTexId = uploadNearestTexture(0, placeholder);
                placeholder.recycle();
                mHasSkin = (mSkinTexId != 0);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
            float aspect = (float) Math.max(1, width) / (float) Math.max(1, height);
            android.opengl.Matrix.perspectiveM(mProj, 0, 38f, aspect, 4f, 180f);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (!mHasSkin || mProgram == 0 || mHeadBuf == null) return;

            float t = (SystemClock.uptimeMillis() - mStartMs) * 0.001f;
            float armSwing = mWalkAnim
                    ? (float) Math.sin(t * 3.1f) * 26f
                    : (float) Math.sin(t * 1.4f) * 3.2f;
            float legSwing = mWalkAnim ? -armSwing * 0.92f : 0f;
            float headNod = (float) Math.sin(t * 1.1f) * 2.2f;
            float capeSwing = 10f + (mWalkAnim
                    ? (14f + (float) Math.abs(Math.sin(t * 3.1f)) * 12f)
                    : ((float) Math.sin(t * 1.7f) * 3.5f));

            float camDist = 54f / Math.max(0.65f, mZoom);
            android.opengl.Matrix.setLookAtM(mView, 0,
                    0f, 0f, camDist,
                    0f, -0.5f, 0f,
                    0f, 1f, 0f);
            android.opengl.Matrix.multiplyMM(mVp, 0, mProj, 0, mView, 0);

            android.opengl.Matrix.setIdentityM(mRoot, 0);
            android.opengl.Matrix.rotateM(mRoot, 0, mPitchDeg, 1f, 0f, 0f);
            android.opengl.Matrix.rotateM(mRoot, 0, mYawDeg, 0f, 1f, 0f);

            GLES20.glUseProgram(mProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexId);
            GLES20.glUniform1i(mLocTex, 0);

            int armW = mSlimArms ? 3 : 4;
            float rArmPivotX = -(4f + armW * 0.5f);
            float lArmPivotX = (4f + armW * 0.5f);

            // Pass 1: Opaque core body parts
            GLES20.glUniform1f(mLocOpaque, 1f);
            GLES20.glEnable(GLES20.GL_CULL_FACE);
            drawPart(mHeadBuf, 0f, 4f, 0f, headNod, 0f);
            drawPart(mBodyBuf, 0f, 4f, 0f, 0f, 0f);
            drawPart(mRightArmBuf, rArmPivotX, 4f, 0f, armSwing, -2.5f);
            drawPart(mLeftArmBuf, lArmPivotX, 4f, 0f, -armSwing, 2.5f);
            drawPart(mRightLegBuf, -2f, -8f, 0f, legSwing, 0f);
            drawPart(mLeftLegBuf, 2f, -8f, 0f, -legSwing, 0f);

            // Optional 3D Cape hinged at rear top shoulder (y=+4, z=-2.2)
            if (mHasCape && mCapeTexId != 0 && mCapeBuf != null) {
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mCapeTexId);
                drawPart(mCapeBuf, 0f, 4f, -2.15f, capeSwing, 0f);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkinTexId);
            }

            // Pass 2: Dual-layer 3D translucent outer armor/hat/jacket/sleeves/pants
            if (mShowOuter) {
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                GLES20.glUniform1f(mLocOpaque, 0f);
                drawPart(mHatBuf, 0f, 4f, 0f, headNod, 0f);
                if (!mLegacy64x32) {
                    drawPart(mJacketBuf, 0f, 4f, 0f, 0f, 0f);
                    drawPart(mRightSleeveBuf, rArmPivotX, 4f, 0f, armSwing, -2.5f);
                    drawPart(mLeftSleeveBuf, lArmPivotX, 4f, 0f, -armSwing, 2.5f);
                    drawPart(mRightPantsBuf, -2f, -8f, 0f, legSwing, 0f);
                    drawPart(mLeftPantsBuf, 2f, -8f, 0f, -legSwing, 0f);
                }
            }
        }

        private void drawPart(@Nullable FloatBuffer buf,
                              float pivotX, float pivotY, float pivotZ,
                              float rotX, float rotZ) {
            if (buf == null) return;
            System.arraycopy(mRoot, 0, mPart, 0, 16);
            android.opengl.Matrix.translateM(mPart, 0, pivotX, pivotY, pivotZ);
            if (rotX != 0f) android.opengl.Matrix.rotateM(mPart, 0, rotX, 1f, 0f, 0f);
            if (rotZ != 0f) android.opengl.Matrix.rotateM(mPart, 0, rotZ, 0f, 0f, 1f);
            android.opengl.Matrix.multiplyMM(mMvp, 0, mVp, 0, mPart, 0);

            GLES20.glUniformMatrix4fv(mLocMvp, 1, false, mMvp, 0);
            GLES20.glUniformMatrix4fv(mLocModel, 1, false, mPart, 0);

            int stride = FLOATS_PER_VERTEX * 4;
            buf.position(0);
            GLES20.glEnableVertexAttribArray(mLocPos);
            GLES20.glVertexAttribPointer(mLocPos, 3, GLES20.GL_FLOAT, false, stride, buf);
            buf.position(3);
            GLES20.glEnableVertexAttribArray(mLocNormal);
            GLES20.glVertexAttribPointer(mLocNormal, 3, GLES20.GL_FLOAT, false, stride, buf);
            buf.position(6);
            GLES20.glEnableVertexAttribArray(mLocUv);
            GLES20.glVertexAttribPointer(mLocUv, 2, GLES20.GL_FLOAT, false, stride, buf);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, VERTICES_PER_BOX);
        }

        private void rebuildPlayerGeometry() {
            int ah = mLegacy64x32 ? 32 : 64;
            int armW = mSlimArms ? 3 : 4;
            float halfArm = armW * 0.5f;

            // Head (pivot at neck y=0..+8) + Hat shell (+0.48)
            mHeadBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -4f, 0f, -4f, 8f, 8f, 8f, 0f, 0, 0, 8, 8, 8, 64, ah, false));
            mHatBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -4f, 0f, -4f, 8f, 8f, 8f, 0.48f, 32, 0, 8, 8, 8, 64, ah, false));

            // Torso (pivot at shoulders y=-12..0) + Jacket (+0.26)
            mBodyBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -4f, -12f, -2f, 8f, 12f, 4f, 0f, 16, 16, 8, 12, 4, 64, ah, false));
            mRightArmBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -halfArm, -12f, -2f, armW, 12f, 4f, 0f, 40, 16, armW, 12, 4, 64, ah, false));
            mRightLegBuf = toFloatBuffer(buildMinecraftBoxMesh(
                    -2f, -12f, -2f, 4f, 12f, 4f, 0f, 0, 16, 4, 12, 4, 64, ah, false));

            if (mLegacy64x32) {
                mLeftArmBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -halfArm, -12f, -2f, armW, 12f, 4f, 0f, 40, 16, armW, 12, 4, 64, ah, true));
                mLeftLegBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -2f, -12f, -2f, 4f, 12f, 4f, 0f, 0, 16, 4, 12, 4, 64, ah, true));
                mJacketBuf = mRightSleeveBuf = mLeftSleeveBuf = mRightPantsBuf = mLeftPantsBuf = null;
            } else {
                mJacketBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -4f, -12f, -2f, 8f, 12f, 4f, 0.26f, 16, 32, 8, 12, 4, 64, 64, false));
                mRightSleeveBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -halfArm, -12f, -2f, armW, 12f, 4f, 0.26f, 40, 32, armW, 12, 4, 64, 64, false));
                mLeftArmBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -halfArm, -12f, -2f, armW, 12f, 4f, 0f, 32, 48, armW, 12, 4, 64, 64, false));
                mLeftSleeveBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -halfArm, -12f, -2f, armW, 12f, 4f, 0.26f, 48, 48, armW, 12, 4, 64, 64, false));
                mRightPantsBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -2f, -12f, -2f, 4f, 12f, 4f, 0.26f, 0, 32, 4, 12, 4, 64, 64, false));
                mLeftLegBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -2f, -12f, -2f, 4f, 12f, 4f, 0f, 16, 48, 4, 12, 4, 64, 64, false));
                mLeftPantsBuf = toFloatBuffer(buildMinecraftBoxMesh(
                        -2f, -12f, -2f, 4f, 12f, 4f, 0.26f, 0, 48, 4, 12, 4, 64, 64, false));
            }
        }

        private static int uploadNearestTexture(int existingId, @NonNull Bitmap bmp) {
            int texId = existingId;
            if (texId == 0) {
                int[] ids = new int[1];
                GLES20.glGenTextures(1, ids, 0);
                texId = ids[0];
            }
            if (texId == 0) return 0;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            return texId;
        }

        private static int buildProgram(@NonNull String vsSrc, @NonNull String fsSrc) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
            if (vs == 0 || fs == 0) return 0;
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] status = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                GLES20.glDeleteProgram(prog);
                return 0;
            }
            return prog;
        }

        private static int compileShader(int type, @NonNull String src) {
            int sh = GLES20.glCreateShader(type);
            GLES20.glShaderSource(sh, src);
            GLES20.glCompileShader(sh);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteShader(sh);
                return 0;
            }
            return sh;
        }

        @NonNull
        private static FloatBuffer toFloatBuffer(@NonNull float[] data) {
            ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(data).position(0);
            return fb;
        }

        @NonNull
        private static Bitmap createDefaultPlaceholderSkin() {
            Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint();
            // Head (warm skin + dark hair)
            p.setColor(0xFFC68E68); c.drawRect(0, 0, 32, 16, p);
            p.setColor(0xFF3B2718); c.drawRect(8, 0, 16, 8, p);
            p.setColor(0xFF3B2718); c.drawRect(8, 8, 16, 10, p);
            p.setColor(0xFFFFFFFF); c.drawRect(9, 12, 11, 13, p); c.drawRect(13, 12, 15, 13, p);
            p.setColor(0xFF4F46E5); c.drawRect(10, 12, 11, 13, p); c.drawRect(13, 12, 14, 13, p);
            // Torso (Zyrox indigo/teal tunic)
            p.setColor(0xFF0EA5A4); c.drawRect(16, 16, 40, 32, p);
            // Arms
            p.setColor(0xFFC68E68); c.drawRect(40, 16, 56, 32, p);
            p.setColor(0xFFC68E68); c.drawRect(32, 48, 48, 64, p);
            p.setColor(0xFF0EA5A4); c.drawRect(40, 20, 56, 24, p);
            p.setColor(0xFF0EA5A4); c.drawRect(32, 52, 48, 56, p);
            // Legs (deep slate trousers)
            p.setColor(0xFF334155); c.drawRect(0, 16, 16, 32, p);
            p.setColor(0xFF334155); c.drawRect(16, 48, 32, 64, p);
            return bmp;
        }
    }
}
