package git.artdeell.mojo.game.render;

import android.content.Context;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;

public interface SurfaceProvider{
    View create(Context context, SurfaceCallback callback);
    void updateSize();

    interface SurfaceCallback {
        void onSurfaceAvailable(Surface surface);
        void onSurfaceResized();
        void onSurfaceDestroyed();
        default void onSurfaceFrameUpdated(@Nullable TextureView textureView) {}
    }
}
