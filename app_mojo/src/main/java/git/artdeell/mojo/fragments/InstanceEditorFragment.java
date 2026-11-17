package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Architecture.is32BitsDevice;
import static git.artdeell.mojo.Tools.getTotalDeviceMemory;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.instances.DisplayInstance;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.RTSpinnerAdapter;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.CropperUtils;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

public class InstanceEditorFragment extends Fragment implements CropperUtils.CropperReceiver {
    public static final String TAG = "InstanceEditorFragment";
    private static final int RAM_MIN_MB = 256;
    private static final int RAM_STEP_MB = 64;

    private Instance mInstance;
    private String mSelectedControlLayout;
    private Button mSaveButton, mDeleteButton, mControlSelectButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultVersion, mDefaultControl, mRendererHint, mRamValueView;
    private SeekBar mRamSeekBar;
    private ImageView mInstanceIcon;
    private CheckBox mSharedDataCheckbox;
    private RecyclerView mInstanceListRecycler;
    private InstanceRailAdapter mRailAdapter;
    private int mRecommendedIconSize;
    private int mSelectedRamMb;
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    private List<String> mRenderNames;

    public InstanceEditorFragment() {
        super(R.layout.fragment_instance_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if (value != null) {
            mSelectedControlLayout = value;
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);
        Context context = view.getContext();

        // Setup Left Rail (saved instances list)
        mRailAdapter = new InstanceRailAdapter();
        mInstanceListRecycler.setLayoutManager(new LinearLayoutManager(context));
        mInstanceListRecycler.setAdapter(mRailAdapter);

        View newInstanceBtn = view.findViewById(R.id.btn_rail_new_instance);
        if (newInstanceBtn != null) {
            newInstanceBtn.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), ProfileTypeSelectFragment.class, ProfileTypeSelectFragment.TAG, null));
        }

        // Renderer Spinner + dynamic FPS / compatibility hint
        RendererCache list = RendererCache.getCompatibleRenderers(context);
        mRenderNames = list.rendererIds;
        List<String> renderList = new ArrayList<>(list.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(list.rendererDisplayNames));
        renderList.add(context.getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(context, R.layout.item_simple_list_1, renderList));
        mDefaultRenderer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                updateRendererHint(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // JVM RAM allocation slider synced with global Zyrox memory preference
        setupRamSlider(context);

        mSaveButton.setOnClickListener(v -> {
            if (mInstance == null) return;
            InstanceIconProvider.dropIcon(mInstance);
            save();
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> {
            DeleteConfirmDialogFragment dialogFragment = new DeleteConfirmDialogFragment();
            dialogFragment.show(getChildFragmentManager(), DeleteConfirmDialogFragment.TAG);
        });

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        mInstanceIcon.setOnClickListener(v -> {
            mRecommendedIconSize = Math.max(64, Math.max(v.getWidth(), v.getHeight()));
            CropperUtils.startCropper(mCropperLauncher);
        });

        mSharedDataCheckbox.setOnCheckedChangeListener((v, checked) -> {
            if (mInstance != null) mInstance.sharedData = checked;
            mSharedDataCheckbox.setText(checked ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
        });

        Instance selectedInstance = Instances.loadSelectedInstance();
        if (selectedInstance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            getParentFragmentManager().popBackStack();
        } else {
            loadValues(selectedInstance, context);
            reloadInstanceRail();
        }
    }

    private void setupRamSlider(@NonNull Context context) {
        if (mRamSeekBar == null || mRamValueView == null) return;
        int deviceRam = getTotalDeviceMemory(context);
        int maxRam;
        if (is32BitsDevice() || deviceRam < 2048) {
            maxRam = Math.min(1024, Math.max(RAM_MIN_MB, deviceRam));
        } else {
            maxRam = Math.max(RAM_MIN_MB + RAM_STEP_MB, deviceRam - (deviceRam < 3064 ? 800 : 1024));
        }
        mSelectedRamMb = Math.max(RAM_MIN_MB, Math.min(maxRam, LauncherPreferences.PREF_RAM_ALLOCATION));
        int maxSteps = Math.max(1, (maxRam - RAM_MIN_MB) / RAM_STEP_MB);
        mRamSeekBar.setMax(maxSteps);
        mRamSeekBar.setProgress((mSelectedRamMb - RAM_MIN_MB) / RAM_STEP_MB);
        mRamValueView.setText(mSelectedRamMb + " MB");

        mRamSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSelectedRamMb = RAM_MIN_MB + progress * RAM_STEP_MB;
                mRamValueView.setText(mSelectedRamMb + " MB");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateRendererHint(int position) {
        if (mRendererHint == null || mRenderNames == null) return;
        if (position < 0 || position >= mRenderNames.size()) {
            mRendererHint.setText(R.string.zyrox_renderer_hint_default);
            return;
        }
        String id = mRenderNames.get(position).toLowerCase(Locale.ROOT);
        if (id.contains("zink")) {
            mRendererHint.setText(R.string.zyrox_renderer_hint_zink);
        } else if (id.contains("ltw")) {
            mRendererHint.setText(R.string.zyrox_renderer_hint_ltw);
        } else if (id.contains("freedreno") || id.contains("kgsl")) {
            mRendererHint.setText(R.string.zyrox_renderer_hint_freedreno);
        } else {
            mRendererHint.setText(R.string.zyrox_renderer_hint_gles);
        }
    }

    private void reloadInstanceRail() {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                final Instances instances = Instances.loadDisplay();
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || mRailAdapter == null) return;
                    mRailAdapter.setInstances(instances.list, instances.selectedIndex);
                });
            } catch (IOException e) {
                Log.w(TAG, "Unable to load instance rail", e);
            }
        });
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            Tools.swapFragment(requireActivity(), FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot) -> mDefaultVersion.setText(id));
    }

    private static String nullToEmpty(String in) {
        return in == null ? "" : in;
    }

    private void loadValues(@NonNull Instance instance, @NonNull Context context) {
        mInstance = instance;
        mInstanceIcon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), instance));

        List<Runtime> runtimes = MultiRTUtils.getRuntimes();
        int jvmIndex = -1;
        if (instance.selectedRuntime != null) {
            jvmIndex = runtimes.indexOf(new Runtime(instance.selectedRuntime));
        }
        mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
        if (jvmIndex == -1) jvmIndex = Math.max(0, runtimes.size() - 1);
        if (!runtimes.isEmpty()) mDefaultRuntime.setSelection(jvmIndex);

        int rendererIndex = mRenderNames.indexOf(instance.getLaunchRenderer());
        if (rendererIndex == -1) {
            rendererIndex = mDefaultRenderer.getAdapter().getCount() - 1;
        }
        mDefaultRenderer.setSelection(rendererIndex);
        updateRendererHint(rendererIndex);

        mDefaultVersion.setText(instance.versionId);
        mDefaultJvmArgument.setText(nullToEmpty(instance.jvmArgs));
        mDefaultName.setText(nullToEmpty(instance.name));
        mDefaultControl.setText(mSelectedControlLayout == null ? nullToEmpty(instance.controlLayout) : mSelectedControlLayout);
        mSharedDataCheckbox.setChecked(instance.sharedData);
        mSharedDataCheckbox.setText(instance.sharedData ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
    }

    private void bindViews(@NonNull View view) {
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_instance_renderer);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);
        mDefaultName = view.findViewById(R.id.vprof_editor_instance_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);
        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mInstanceIcon = view.findViewById(R.id.vprof_editor_instance_icon);
        mSharedDataCheckbox = view.findViewById(R.id.vprof_editor_data_checkbox_container);
        mRendererHint = view.findViewById(R.id.tv_renderer_hint);
        mRamValueView = view.findViewById(R.id.tv_vprof_ram_value);
        mRamSeekBar = view.findViewById(R.id.vprof_ram_seekbar);
        mInstanceListRecycler = view.findViewById(R.id.zyrox_instance_list);
    }

    private void save() {
        if (mInstance == null) return;
        mInstance.versionId = mDefaultVersion.getText().toString();
        mInstance.controlLayout = mDefaultControl.getText().toString();
        mInstance.jvmArgs = mDefaultJvmArgument.getText().toString();

        String newName = mDefaultName.getText().toString();
        if (mInstance.controlLayout.isEmpty()) mInstance.controlLayout = null;
        if (mInstance.jvmArgs.isEmpty()) mInstance.jvmArgs = null;

        Object rtObj = mDefaultRuntime.getSelectedItem();
        if (rtObj instanceof Runtime) {
            Runtime selectedRuntime = (Runtime) rtObj;
            mInstance.selectedRuntime = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                    ? null : selectedRuntime.name;
        }

        if (mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) {
            mInstance.renderer = null;
        } else if (mDefaultRenderer.getSelectedItemPosition() >= 0) {
            mInstance.renderer = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());
        }

        if (mSelectedRamMb >= RAM_MIN_MB && LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.PREF_RAM_ALLOCATION = mSelectedRamMb;
            LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", mSelectedRamMb).apply();
        }

        try {
            if (!newName.isEmpty() && !newName.equals(mInstance.name)) {
                Instances.renameInstanceDirectory(mInstance, newName);
            }
            mInstance.name = newName;
            mInstance.write();
        } catch (Exception e) {
            Tools.showErrorRemote(e);
        }
    }

    @Override
    public float getAspectRatio() {
        return 1f;
    }

    @Override
    public int getTargetMaxSide() {
        return mRecommendedIconSize;
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mInstanceIcon.setImageBitmap(contentBitmap);
        Log.i("bitmap", "w=" + contentBitmap.getWidth() + " h=" + contentBitmap.getHeight());
        try {
            mInstance.encodeNewIcon(contentBitmap);
            reloadInstanceRail();
        } catch (IOException e) {
            Tools.showErrorRemote(e);
        }
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }

    /** Left-Rail RecyclerView Adapter showing saved instances with amber active bar */
    private class InstanceRailAdapter extends RecyclerView.Adapter<InstanceRailAdapter.RailHolder> {
        private List<DisplayInstance> mList = Collections.emptyList();
        private int mSelected = 0;

        void setInstances(List<DisplayInstance> list, int selectedIndex) {
            mList = list != null ? list : Collections.emptyList();
            mSelected = selectedIndex;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RailHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_zyrox_instance_rail, parent, false);
            return new RailHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RailHolder holder, int position) {
            DisplayInstance item = mList.get(position);
            holder.icon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), item));

            String name = Tools.validOrNullString(item.name);
            String ver = Tools.validOrNullString(item.versionId);
            if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(ver)) {
                ver = holder.itemView.getContext().getString(R.string.profiles_latest_release);
            } else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(ver)) {
                ver = holder.itemView.getContext().getString(R.string.profiles_latest_snapshot);
            }
            holder.title.setText(name != null ? name : (ver != null ? ver : getString(R.string.unnamed)));
            holder.version.setText(ver != null ? ver : "-");
            holder.itemView.setSelected(position == mSelected);

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos == mSelected) return;
                DisplayInstance clicked = mList.get(pos);
                Instances.setSelectedInstance(clicked);
                mSelectedControlLayout = null;
                Instance full = Instances.loadSelectedInstance();
                if (full != null && getContext() != null) {
                    int prev = mSelected;
                    mSelected = pos;
                    notifyItemChanged(prev);
                    notifyItemChanged(mSelected);
                    loadValues(full, getContext());
                    ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }

        class RailHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView version;

            RailHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.iv_rail_instance_icon);
                title = itemView.findViewById(R.id.tv_rail_instance_name);
                version = itemView.findViewById(R.id.tv_rail_instance_version);
            }
        }
    }
}
