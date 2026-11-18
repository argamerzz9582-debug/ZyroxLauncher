package git.artdeell.mojo.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;

/**
 * Full-bleed edge-to-edge Full HD 1080p Minecraft loading screen view playing
 * {@code R.raw.zyrox_loading_scene} ({@code Loading screen.mp4}) with an instant frame-0 poster
 * ({@code R.drawable.bg_zyrox_loading_poster}) and zero audio focus theft.
 *
 * <p>Loops smoothly on hardware {@code MediaCodec} from launch until Mojang's official splash
 * screen renders its first frame, then releases 100% of decoder, surface, and poster memory
 * asynchronously via {@link #release()}.
 */
public class MinecraftLoadingLandscapeView extends ZyroxLoopingVideoView {

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
        setVideoResource(R.raw.zyrox_loading_scene, R.drawable.bg_zyrox_loading_poster);
    }
}
