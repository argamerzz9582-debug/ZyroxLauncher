package git.artdeell.mojo.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;

/**
 * Full-bleed edge-to-edge seamless cherry-blossom Minecraft loading screen view.
 *
 * <p>Backed by {@link ZyroxSeamlessGifView} and {@link ZyroxGifDecoder} so
 * {@code R.raw.zyrox_loading_scene} loops with zero end-of-clip buffer hitch and zero visual seam
 * from launch until Mojang's official loading screen draws its first rendered frame, then frees all
 * frame bitmaps immediately via {@link #release()}.
 */
public class MinecraftLoadingLandscapeView extends ZyroxSeamlessGifView {

    public MinecraftLoadingLandscapeView(@NonNull Context context) {
        this(context, null, 0);
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MinecraftLoadingLandscapeView(@NonNull Context context,
                                         @Nullable AttributeSet attrs,
                                         int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setGifResource(R.raw.zyrox_loading_scene);
    }
}
