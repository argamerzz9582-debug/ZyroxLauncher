package git.artdeell.mojo.prefs.screens;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.R;

import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.downloader.RuntimeDriverStoreDialog;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.game.renderer.extra.GLESProvider;
import git.artdeell.mojo.game.renderer.impl.MesaRenderSpec;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.NetworkPolicyHelper;
import git.artdeell.mojo.vulkan.DriverInstaller;
import git.artdeell.mojo.vulkan.DriverManager;
import git.artdeell.mojo.vulkan.driver.BuiltinDriver;
import git.artdeell.mojo.vulkan.driver.Driver;
import git.artdeell.mojo.vulkan.ui.DriverConfigDialog;

import android.widget.Toast;

/**
 * Fragment for any settings video related
 */
public class LauncherPreferenceVideoFragment extends LauncherPreferenceBaseFragment {
    private Boolean hasAngle = null;

    private DriverConfigDialog mDialogScreen;
    // Use "adpkg" so OpenDocumentWithExtension resolves to "*/*", allowing both .adpkg and .zip Turnip drivers
    private final ActivityResultLauncher<Object> mInstallDriver =
            registerForActivityResult(new OpenDocumentWithExtension("adpkg"), data -> {
                if(data != null) DriverInstaller.installDriverFromUri(getContext(), data, this::openDriverDialog);
            });
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_video);
        int resolution = (int) (LauncherPreferences.PREF_SCALE_FACTOR * 100);

        CustomSeekBarPreference resolutionSeekbar = requirePreference("resolutionRatio",
                CustomSeekBarPreference.class);
        resolutionSeekbar.setSuffix(" %");

        // #724 bug fix
        if (resolution < 25) {
            resolutionSeekbar.setValue(100);
        } else {
            resolutionSeekbar.setValue(resolution);
        }

        // Sustained performance is only available since Nougat
        SwitchPreferenceCompat sustainedPerfSwitch = requirePreference("sustainedPerformance",
                SwitchPreferenceCompat.class);
        sustainedPerfSwitch.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        sustainedPerfSwitch.setChecked(LauncherPreferences.PREF_SUSTAINED_PERFORMANCE);

        requirePreference("alternate_surface", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        requirePreference("force_vsync", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_FORCE_VSYNC);

        // Show ANGLE switch only if AnglePlugin is available
        if(hasAngle == null) {
            GLESProvider provider = GLESProvider.getGlesProvider(getContext(), true);
            hasAngle = provider instanceof GLESProvider.ExternalAngleProvider || provider instanceof GLESProvider.SystemAngleProvider;
        }

        SwitchPreferenceCompat angleSwitch = requirePreference("use_angle", SwitchPreferenceCompat.class);
        angleSwitch.setVisible(hasAngle);
        angleSwitch.setChecked(LauncherPreferences.PREF_USE_ANGLE);

        ListPreference rendererListPreference = requirePreference("renderer",
                ListPreference.class);
        RendererCache list = RendererCache.getCompatibleRenderers(getContext());
        rendererListPreference.setEntries(list.rendererDisplayNames);
        rendererListPreference.setEntryValues(list.rendererIds.toArray(new String[0]));


        Preference drivers = requirePreference("manageDrivers");
        drivers.setOnPreferenceClickListener(pref -> {
            openDriverDialog();
            return true;
        });

        Preference turnipStore = requirePreference("zyroxTurnipStore");
        turnipStore.setOnPreferenceClickListener(pref -> {
            RuntimeDriverStoreDialog.showHub(requireContext(), this::updateDriversPref);
            return true;
        });

        Preference gpuAuto = requirePreference("zyroxGpuAutoPreset");
        GpuUtils.GLInfo glInfo = GpuUtils.getGlInfo();
        gpuAuto.setSummary(glInfo.architecture.displayName + " • Tap to auto-tune renderer, scale & RAM");
        gpuAuto.setOnPreferenceClickListener(pref -> {
            Instance inst = Instances.loadSelectedInstance();
            GraphicsAdvisor.RendererPreset preset = GraphicsAdvisor.applyOptimalPreset(
                    requireContext(), inst, inst != null ? inst.versionId : null);
            rendererListPreference.setValue(LauncherPreferences.PREF_RENDERER);
            resolutionSeekbar.setValue((int) Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f));
            updateDriversPref();
            Toast.makeText(requireContext(),
                    "Applied preset: " + preset.summary, Toast.LENGTH_LONG).show();
            return true;
        });

        Preference purgeCache = requirePreference("zyroxPurgeShaderCache");
        purgeCache.setOnPreferenceClickListener(pref -> {
            long freed = MesaRenderSpec.purgeShaderCaches();
            Toast.makeText(requireContext(),
                    freed > 0L
                            ? ("Cleared " + NetworkPolicyHelper.formatBytes(freed) + " of Mesa/Zink shader cache")
                            : "Mesa/Zink shader cache is already clean",
                    Toast.LENGTH_SHORT).show();
            return true;
        });
        SwitchPreferenceCompat customVk = requirePreference("zinkPreferSystemDriver", SwitchPreferenceCompat.class);
        boolean gplay = BuildConfig.BUILD_TYPE.equals("gplay");
        boolean isAdreno = GpuUtils.getGlInfo().isAdreno();
        boolean hasSupport = DriverManager.isSupportedByDevice();
        customVk.setVisible(hasSupport && (!gplay || isAdreno));
        customVk.setChecked(LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER);

        computeVisibility();
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getActivity();
        if(activity != null) {
            requirePreference("ignoreNotch").setVisible(LauncherPreferences.hasNotch(activity));
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        computeVisibility();
    }

    private void computeVisibility(){
        requirePreference("force_vsync", SwitchPreferenceCompat.class)
                .setVisible(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        updateDriversPref();
    }

    private void openDriverDialog(){
        if(mDialogScreen == null) {
            mDialogScreen = new DriverConfigDialog();
            mDialogScreen.prepare(getContext(), mInstallDriver);
            mDialogScreen.onDismiss(this::updateDriversPref);
        }
        mDialogScreen.show();
    }
    private void updateDriversPref(){

        Preference preference = requirePreference("manageDrivers");
        boolean hasSupport = DriverManager.isSupportedByDevice();
        boolean gplay = BuildConfig.BUILD_TYPE.equals("gplay");
        if(LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER || gplay || !hasSupport){
            preference.setVisible(false);
            return;
        }
        Driver driver = DriverManager.getPreferredDriver();
        if(driver instanceof BuiltinDriver)
            preference.setSummary(BuiltinDriver.deviceHasDriver() ? R.string.driver_default_name : R.string.driver_config_pref_subtitle);
        else
            preference.setSummary(driver.getName());
        preference.setVisible(true);
    }
}
