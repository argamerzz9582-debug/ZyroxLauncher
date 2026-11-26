package git.artdeell.mojo.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** Pure-JVM tests for the backdrop catalog and its playback policy. */
public class ZyroxBackdropsTest {

    @Test
    public void defaultsAreSnowForHomeAndDarkDiamondForLoading() {
        assertSame(ZyroxBackdrops.Backdrop.SNOWY_PEAKS, ZyroxBackdrops.DEFAULT_HOME);
        assertSame(ZyroxBackdrops.Backdrop.DIAMOND_FORGE, ZyroxBackdrops.DEFAULT_LOADING);
    }

    @Test
    public void idsAreUniqueStableAndLowerCase() {
        Set<String> ids = new HashSet<>();
        for (ZyroxBackdrops.Backdrop b : ZyroxBackdrops.Backdrop.values()) {
            assertTrue("duplicate id " + b.id, ids.add(b.id));
            assertEquals(b.id, b.id.trim().toLowerCase(java.util.Locale.ROOT));
            assertNotEquals(0, b.videoRes);
            assertNotEquals(0, b.posterRes);
            assertNotEquals(0, b.titleRes);
        }
        // Persisted in user preferences: renaming would silently reset everyone's choice.
        assertEquals("snow", ZyroxBackdrops.Backdrop.SNOWY_PEAKS.id);
        assertEquals("diamond", ZyroxBackdrops.Backdrop.DIAMOND_FORGE.id);
    }

    @Test
    public void fromIdIsTolerantAndFallsBackForUnknownOrRemovedIds() {
        ZyroxBackdrops.Backdrop fb = ZyroxBackdrops.DEFAULT_HOME;
        assertSame(ZyroxBackdrops.Backdrop.DIAMOND_FORGE, ZyroxBackdrops.fromId("diamond", fb));
        assertSame(ZyroxBackdrops.Backdrop.DIAMOND_FORGE, ZyroxBackdrops.fromId("  DIAMOND ", fb));
        assertSame(fb, ZyroxBackdrops.fromId(null, fb));
        assertSame(fb, ZyroxBackdrops.fromId("", fb));
        assertSame(fb, ZyroxBackdrops.fromId("starlight", fb)); // pre-2.9 clip id
        assertSame(ZyroxBackdrops.DEFAULT_LOADING,
                ZyroxBackdrops.fromId("../../etc", ZyroxBackdrops.DEFAULT_LOADING));
    }

    @Test
    public void userAndAccessibilitySwitchesAlwaysWin() {
        assertFalse(ZyroxBackdrops.shouldAnimate(false, false, false, false, 0));
        assertFalse(ZyroxBackdrops.shouldAnimate(true, true, false, false, 0));
        assertTrue(ZyroxBackdrops.shouldAnimate(true, false, false, false, 0));
    }

    @Test
    public void powerAwareModeHoldsPosterOnBatterySaverAndSevereThermal() {
        assertFalse(ZyroxBackdrops.shouldAnimate(true, false, true, true, 0));
        assertTrue("light/moderate heat keeps animating",
                ZyroxBackdrops.shouldAnimate(true, false, true, false, 2));
        for (int status = 3; status <= 6; status++) { // SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
            assertFalse("thermal " + status, ZyroxBackdrops.shouldAnimate(true, false, true, false, status));
        }
        // Power-aware off: user explicitly wants motion regardless of battery/thermal state
        assertTrue(ZyroxBackdrops.shouldAnimate(true, false, false, true, 6));
    }

    @Test
    public void dimIsClampedAndMapsToAlpha() {
        assertEquals(0, ZyroxBackdrops.dimPercentToAlpha(-20));
        assertEquals(0, ZyroxBackdrops.dimPercentToAlpha(0));
        assertEquals(64, ZyroxBackdrops.dimPercentToAlpha(25));
        assertEquals(179, ZyroxBackdrops.dimPercentToAlpha(ZyroxBackdrops.MAX_DIM_PERCENT));
        assertEquals("never fully black", 179, ZyroxBackdrops.dimPercentToAlpha(100));
        assertEquals(0x40000000, ZyroxBackdrops.dimPercentToScrimColor(25));
        assertTrue(ZyroxBackdrops.DEFAULT_DIM_PERCENT > 0
                && ZyroxBackdrops.DEFAULT_DIM_PERCENT <= ZyroxBackdrops.MAX_DIM_PERCENT);
    }

    @Test
    public void liveGlassBlurDropsToGradientWhilePowerConstrained() {
        git.artdeell.mojo.utils.GpuUtils.GpuArchitecture adreno =
                git.artdeell.mojo.utils.GpuUtils.GpuArchitecture.ADRENO_7XX;
        assertSame(ZyroxLiquidGlassDrawable.GlassBlurTier.HARDWARE_RENDER_EFFECT,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(34, 8192, adreno, false, false));
        assertSame(ZyroxLiquidGlassDrawable.GlassBlurTier.FROSTED_GRADIENT_FALLBACK,
                ZyroxLiquidGlassDrawable.resolveGlassBlurTier(34, 8192, adreno, false, true));
        // Same rule as the backdrop: power-constrained == !shouldAnimate(...)
        assertFalse(ZyroxBackdrops.shouldAnimate(true, false, true, false, 4));
    }

    @Test
    public void recyclerDetectionIsByNameAndIgnoresUnrelatedClasses() {
        assertFalse(ZyroxLiquidGlassDrawable.isRecyclerViewClass(null));
        assertFalse(ZyroxLiquidGlassDrawable.isRecyclerViewClass(Object.class));
        assertFalse(ZyroxLiquidGlassDrawable.isRecyclerViewClass(java.util.ArrayList.class));
    }
}
