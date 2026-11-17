package git.artdeell.mojo.prefs.screens;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

/**
 * Page 4: Zyrox Control Center — Horizontal 2-Pane Settings Shell
 * (220dp Left Category Rail + Right Frosted Glass Preference Pane).
 */
public class LauncherPreferenceFragment extends Fragment {
    private static final String STATE_SELECTED_TAB = "zyrox_selected_pref_tab";

    private static final class CategoryItem {
        final @StringRes int titleRes;
        final @DrawableRes int iconRes;
        final Class<? extends Fragment> fragmentClass;

        CategoryItem(@StringRes int titleRes, @DrawableRes int iconRes, Class<? extends Fragment> fragmentClass) {
            this.titleRes = titleRes;
            this.iconRes = iconRes;
            this.fragmentClass = fragmentClass;
        }
    }

    private static final CategoryItem[] CATEGORIES = new CategoryItem[]{
            new CategoryItem(R.string.zyrox_cat_video, R.drawable.ic_px_image_renderer, LauncherPreferenceVideoFragment.class),
            new CategoryItem(R.string.zyrox_cat_controls, R.drawable.ic_px_gamepad, LauncherPreferenceControlFragment.class),
            new CategoryItem(R.string.zyrox_cat_java, R.drawable.ic_px_ram, LauncherPreferenceJavaFragment.class),
            new CategoryItem(R.string.zyrox_cat_misc, R.drawable.ic_px_alt_sliders, LauncherPreferenceMiscellaneousFragment.class),
            new CategoryItem(R.string.zyrox_cat_experimental, R.drawable.ic_px_experiment, LauncherPreferenceExperimentalFragment.class)
    };

    private int mSelectedTab = 0;
    private CategoryRailAdapter mAdapter;

    public LauncherPreferenceFragment() {
        super(R.layout.fragment_zyrox_split_shell);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            mSelectedTab = Math.max(0, Math.min(CATEGORIES.length - 1,
                    savedInstanceState.getInt(STATE_SELECTED_TAB, 0)));
        }

        RecyclerView leftRail = view.findViewById(R.id.zyrox_left_list);
        leftRail.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mAdapter = new CategoryRailAdapter();
        leftRail.setAdapter(mAdapter);

        if (savedInstanceState == null || getChildFragmentManager().findFragmentById(R.id.zyrox_right_container) == null) {
            showCategory(mSelectedTab, true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_TAB, mSelectedTab);
    }

    private void showCategory(int index, boolean force) {
        if (!force && index == mSelectedTab) return;
        int prev = mSelectedTab;
        mSelectedTab = index;
        if (mAdapter != null) {
            mAdapter.notifyItemChanged(prev);
            mAdapter.notifyItemChanged(mSelectedTab);
        }
        CategoryItem item = CATEGORIES[index];
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.zyrox_right_container, item.fragmentClass, null, item.fragmentClass.getSimpleName())
                .commit();
    }

    private class CategoryRailAdapter extends RecyclerView.Adapter<CategoryRailAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_zyrox_settings_rail, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CategoryItem cat = CATEGORIES[position];
            holder.icon.setImageResource(cat.iconRes);
            holder.title.setText(cat.titleRes);
            holder.itemView.setSelected(position == mSelectedTab);
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    showCategory(pos, false);
                }
            });
        }

        @Override
        public int getItemCount() {
            return CATEGORIES.length;
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.iv_settings_cat_icon);
                title = itemView.findViewById(R.id.tv_settings_cat_title);
            }
        }
    }
}
