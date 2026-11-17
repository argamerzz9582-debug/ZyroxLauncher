package git.artdeell.mojo.prefs.screens;

import static git.artdeell.mojo.Architecture.is32BitsDevice;
import static git.artdeell.mojo.Tools.getTotalDeviceMemory;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.EditTextPreference;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.multirt.MultiRTConfigDialog;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;

public class LauncherPreferenceJavaFragment extends LauncherPreferenceBaseFragment {
    private MultiRTConfigDialog mDialogScreen;
    private CustomSeekBarPreference mMemorySeekbar;
    private int mTotalDeviceRamMb = 0;

    private final ActivityResultLauncher<Object> mVmInstallLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("xz"), (data) -> {
                if (data != null) Tools.installRuntimeFromUri(getContext(), data);
            });

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        int ramAllocation = LauncherPreferences.PREF_RAM_ALLOCATION;
        addPreferencesFromResource(R.xml.pref_java);

        mMemorySeekbar = requirePreference("allocation", CustomSeekBarPreference.class);
        Context ctx = mMemorySeekbar.getContext();
        mTotalDeviceRamMb = getTotalDeviceMemory(ctx);

        int maxRAM;
        if (is32BitsDevice() || mTotalDeviceRamMb < 2048) {
            maxRAM = Math.min(1024, mTotalDeviceRamMb);
        } else {
            maxRAM = mTotalDeviceRamMb - (mTotalDeviceRamMb < 3064 ? 800 : 1024);
        }

        mMemorySeekbar.setMaxKeepIncrement(maxRAM);
        mMemorySeekbar.setValue(ramAllocation);
        mMemorySeekbar.setSuffix(" MB");
        updateLiveRamSummary(ctx);

        EditTextPreference editJVMArgs = findPreference("javaArgs");
        if (editJVMArgs != null) {
            editJVMArgs.setOnBindEditTextListener(TextView::setSingleLine);
        }

        requirePreference("install_jre").setOnPreferenceClickListener(preference -> {
            openMultiRTDialog();
            return true;
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        if ("allocation".equals(s) && getContext() != null) {
            updateLiveRamSummary(getContext());
        }
    }

    private void updateLiveRamSummary(Context context) {
        if (mMemorySeekbar == null || context == null) return;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            int freeMb = (int) (mi.availMem / (1024L * 1024L));
            mMemorySeekbar.setSummary(context.getString(
                    R.string.zyrox_ram_status_fmt,
                    LauncherPreferences.PREF_RAM_ALLOCATION,
                    freeMb,
                    mTotalDeviceRamMb
            ));
        } catch (Throwable ignored) {
        }
    }

    private void openMultiRTDialog() {
        if (mDialogScreen == null) {
            mDialogScreen = new MultiRTConfigDialog();
            mDialogScreen.prepare(getContext(), mVmInstallLauncher);
        }
        mDialogScreen.show();
    }
}
