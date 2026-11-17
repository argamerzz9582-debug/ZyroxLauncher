package git.artdeell.mojo.prefs.screens;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.R;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.prefs.BackButtonPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;

/**
 * Base class for Zyrox Control Center preference panes.
 */
public abstract class LauncherPreferenceBaseFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    protected Runnable mVisibilityUpdater = () -> {};

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.setBackgroundColor(Color.TRANSPARENT);
        super.onViewCreated(view, savedInstanceState);
        setDivider(new ColorDrawable(getResources().getColor(R.color.zyrox_divider)));
        setDividerHeight(1);

        // Hide redundant inline BackButtonPreference when hosted inside 2-pane Zyrox Control Center
        if (getParentFragment() instanceof LauncherPreferenceFragment && getPreferenceScreen() != null) {
            int count = getPreferenceScreen().getPreferenceCount();
            for (int i = 0; i < count; i++) {
                Preference p = getPreferenceScreen().getPreference(i);
                if (p instanceof BackButtonPreference) {
                    p.setVisible(false);
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        mVisibilityUpdater.run();
    }

    @Override
    public void onPause() {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        LauncherPreferences.loadPreferences(getContext());
        if ("zyroxAnimatedBg".equals(s)) {
            ExtraCore.setValue(ExtraConstants.REFRESH_BG_VIDEO, true);
        }
    }

    protected Preference requirePreference(CharSequence key) {
        Preference preference = findPreference(key);
        if (preference != null) return preference;
        throw new IllegalStateException("Preference " + key + " is null");
    }

    @SuppressWarnings("unchecked")
    protected <T extends Preference> T requirePreference(CharSequence key, Class<T> preferenceClass) {
        Preference preference = requirePreference(key);
        if (preferenceClass.isInstance(preference)) return (T) preference;
        throw new IllegalStateException("Preference " + key + " is not an instance of " + preferenceClass.getSimpleName());
    }

    protected LauncherActivity getLauncherActivity() {
        return (LauncherActivity) getActivity();
    }
}
