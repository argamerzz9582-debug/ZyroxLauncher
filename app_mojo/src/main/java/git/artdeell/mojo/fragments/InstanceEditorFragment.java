package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Architecture.is32BitsDevice;
import static git.artdeell.mojo.Tools.getTotalDeviceMemory;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.instances.DisplayInstance;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.InstanceManager;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.metadata.JavaRuntimeAdvisor;
import git.artdeell.mojo.metadata.MojangVersionProvider;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.multirt.RTSpinnerAdapter;
import git.artdeell.mojo.multirt.Runtime;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.ArchiveSecurity;
import git.artdeell.mojo.utils.CropperUtils;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

/**
 * Zyrox 2.0 Instance Studio — Per-instance configuration, isolated RAM allocation, JRE & renderer
 * compatibility advice, isolated cloning, health check/repair, verified SHA-256 backup/restore,
 * config export/import, and impact-verified deletion.
 */
public class InstanceEditorFragment extends Fragment implements CropperUtils.CropperReceiver {
    public static final String TAG = "InstanceEditorFragment";
    private static final int RAM_MIN_MB = 256;
    private static final int RAM_STEP_MB = 64;

    public static final int TAB_OVERVIEW = 0;
    public static final int TAB_CONTENT = 1;
    public static final int TAB_ENGINE = 2;
    public static final int TAB_CONTROLS = 3;

    private Instance mInstance;
    private String mSelectedControlLayout;
    private Button mSaveButton, mDeleteButton, mStudioToolsButton, mControlSelectButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private EditText mDefaultName, mDefaultJvmArgument, mEnvVarsEdit;
    private TextView mDefaultVersion, mDefaultControl, mRendererHint, mRamValueView;
    private SeekBar mRamSeekBar;
    private ImageView mInstanceIcon;
    private CheckBox mSharedDataCheckbox, mLockRuntimeCheckbox, mLockRendererCheckbox, mContextControlsCheckbox;
    private RecyclerView mInstanceListRecycler;
    private InstanceRailAdapter mRailAdapter;

    // 4-Tab Simple / Pro Mode UI bindings
    private TextView mTabOverview, mTabContent, mTabEngine, mTabControls;
    private View mPanelOverview, mPanelContent, mPanelEngine, mPanelControls;
    private TextView mChipPresetBattery, mChipPresetBalanced, mChipPresetMaxFps;
    private TextView mChipScale75, mChipScale100, mOverviewControlQuick;
    private TextView mContentSummaryView, mEngineSimpleSummaryView, mToggleProModeBtn;
    private View mProModeContainer;
    private int mActiveTab = TAB_OVERVIEW;
    private boolean mProModeExpanded = false;
    private String mSelectedPerfPreset = Instance.PRESET_BALANCED;
    private int mSelectedResScale = 100;

    private int mRecommendedIconSize;
    private int mMaxSafeRamMb = 2048;
    private int mSelectedRamMb;
    private List<String> mRenderNames;

    private final ActivityResultLauncher<String> mExportSetupBundleLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/octet-stream"),
                    this::onExportSetupBundleUri);

    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);
    private final ActivityResultLauncher<String> mExportConfigLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onExportConfigUri);
    private final ActivityResultLauncher<Object> mImportConfigLauncher =
            registerForActivityResult(new git.artdeell.mojo.contracts.OpenDocumentWithExtension("json"), this::onImportConfigUri);
    private final ActivityResultLauncher<Object> mOpenControlLayoutLauncher =
            registerForActivityResult(new git.artdeell.mojo.contracts.OpenDocumentWithExtension("json"), uri -> {
                if (uri == null || !isAdded()) return;
                try {
                    File imported = git.artdeell.mojo.input.ControlProfileManager
                            .importControlJsonUri(requireContext(), uri, null);
                    mSelectedControlLayout = imported.getAbsolutePath();
                    if (mDefaultControl != null) {
                        mDefaultControl.setText(imported.getName());
                    }
                    Toast.makeText(requireContext(),
                            "Selected control layout: " + imported.getName(),
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Tools.showError(requireContext(), e);
                }
            });

    public InstanceEditorFragment() {
        super(R.layout.fragment_instance_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);
        Context context = view.getContext();

        mRailAdapter = new InstanceRailAdapter();
        mInstanceListRecycler.setLayoutManager(new LinearLayoutManager(context));
        mInstanceListRecycler.setHasFixedSize(true);
        mInstanceListRecycler.setItemViewCacheSize(12);
        mInstanceListRecycler.setItemAnimator(null);
        mInstanceListRecycler.setAdapter(mRailAdapter);

        View newInstanceBtn = view.findViewById(R.id.btn_rail_new_instance);
        if (newInstanceBtn != null) {
            newInstanceBtn.setOnClickListener(v ->
                    Tools.swapFragment(requireActivity(), ProfileTypeSelectFragment.class, ProfileTypeSelectFragment.TAG, null));
        }

        RendererCache list = RendererCache.getCompatibleRenderers(context);
        mRenderNames = list.rendererIds;
        List<String> renderList = new ArrayList<>(list.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(list.rendererDisplayNames));
        renderList.add(context.getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(context, R.layout.item_simple_list_1, renderList));
        mDefaultRenderer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                updateRendererAndRuntimeHint(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        setupRamSlider(context);

        mSaveButton.setOnClickListener(v -> {
            if (mInstance == null) return;
            InstanceIconProvider.dropIcon(mInstance);
            save();
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> showImpactAwareDeleteDialog());

        if (mStudioToolsButton != null) {
            mStudioToolsButton.setOnClickListener(v -> showStudioToolsMenu());
        }

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

        setupFourTabAndSimpleProUi(view, controlSelectListener);

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
        if (is32BitsDevice() || deviceRam < 2048) {
            mMaxSafeRamMb = Math.min(1024, Math.max(RAM_MIN_MB, deviceRam));
        } else {
            mMaxSafeRamMb = Math.max(RAM_MIN_MB + RAM_STEP_MB, deviceRam - (deviceRam < 3064 ? 800 : 1024));
        }
        mSelectedRamMb = Math.max(RAM_MIN_MB, Math.min(mMaxSafeRamMb, LauncherPreferences.PREF_RAM_ALLOCATION));
        int maxSteps = Math.max(1, (mMaxSafeRamMb - RAM_MIN_MB) / RAM_STEP_MB);
        mRamSeekBar.setMax(maxSteps);
        mRamSeekBar.setProgress((mSelectedRamMb - RAM_MIN_MB) / RAM_STEP_MB);
        mRamValueView.setText(mSelectedRamMb + " MB");

        mRamSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSelectedRamMb = RAM_MIN_MB + progress * RAM_STEP_MB;
                mRamValueView.setText(mSelectedRamMb + " MB");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateRendererAndRuntimeHint(int position) {
        if (mRendererHint == null || mRenderNames == null) return;
        String baseHint;
        if (position < 0 || position >= mRenderNames.size()) {
            baseHint = getString(R.string.zyrox_renderer_hint_default);
        } else {
            String id = mRenderNames.get(position).toLowerCase(Locale.ROOT);
            if (id.contains("zink")) {
                baseHint = getString(R.string.zyrox_renderer_hint_zink);
            } else if (id.contains("ltw")) {
                baseHint = getString(R.string.zyrox_renderer_hint_ltw);
            } else if (id.contains("freedreno") || id.contains("kgsl")) {
                baseHint = getString(R.string.zyrox_renderer_hint_freedreno);
            } else {
                baseHint = getString(R.string.zyrox_renderer_hint_gles);
            }
        }

        // Append non-hardcoded Java runtime compatibility guidance when local version JSON is installed
        if (mInstance != null && Tools.isValidString(mInstance.versionId)) {
            try {
                JVersionList.Version vInfo = Tools.getVersionInfo(mInstance.versionId);
                if (vInfo != null) {
                    int reqJava = JavaRuntimeAdvisor.resolveRequiredJava(vInfo, mInstance.versionId);
                    Runtime selectedRt = mInstance.selectedRuntime != null
                            ? MultiRTUtils.read(mInstance.selectedRuntime) : null;
                    JavaRuntimeAdvisor.Advice jre = JavaRuntimeAdvisor.evaluate(
                            reqJava, mInstance.versionId, selectedRt, MultiRTUtils.getRuntimes(), null);
                    baseHint = baseHint + "  •  " + jre.badgeText;
                }
            } catch (Throwable ignored) {
            }
        }
        if (mInstance != null && mInstance.hasCustomVulkanDriver()) {
            git.artdeell.mojo.vulkan.driver.Driver bound =
                    git.artdeell.mojo.vulkan.DriverManager.resolveDriverForInstance(mInstance);
            baseHint = baseHint + "  •  GPU Driver: "
                    + git.artdeell.mojo.vulkan.DriverManager.formatDriverLabel(bound);
        }
        mRendererHint.setText(baseHint);
    }

    private void showStudioToolsMenu() {
        if (mInstance == null) return;
        String currentDriverBadge = mInstance.hasCustomVulkanDriver()
                ? mInstance.vulkanDriver : "Global Default";
        String[] actions = new String[]{
                "Assign GPU Driver (Turnip / Vulkan) [" + currentDriverBadge + "]",
                "Auto-Detect GPU & Apply Optimal Renderer Preset",
                "Open Turnip / Runtime Driver Store",
                "Clone Instance (Isolated Copy)",
                "Health Check & Safe Repair",
                "Create Verified Instance Backup (.zip + SHA-256)",
                "Restore Instance from Backup",
                "Export Instance Profile Config (.json)",
                "Import Instance Profile Config (.json)",
                "Open Instance Worlds & Backups",
                "Open Instance Mods, Packs, Shaders & Configs"
        };
        new AlertDialog.Builder(requireContext())
                .setTitle("Instance Studio — " + (Tools.isValidString(mInstance.name) ? mInstance.name : mInstance.versionId))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0: promptAssignInstanceGpuDriver(); break;
                        case 1: applyAutoGpuPresetToInstance(); break;
                        case 2: git.artdeell.mojo.downloader.RuntimeDriverStoreDialog.show(requireContext()); break;
                        case 3: promptCloneInstance(); break;
                        case 4: runHealthCheck(); break;
                        case 5: createInstanceBackup(); break;
                        case 6: promptRestoreInstanceBackup(); break;
                        case 7:
                            save();
                            String safe = (Tools.isValidString(mInstance.name) ? mInstance.name : "instance")
                                    .replaceAll("[^A-Za-z0-9._-]", "_");
                            mExportConfigLauncher.launch(safe + "-zyrox-instance.json");
                            break;
                        case 8:
                            mImportConfigLauncher.launch(null);
                            break;
                        case 9:
                            Tools.swapFragment(requireActivity(), WorldManagerFragment.class, WorldManagerFragment.TAG, null);
                            break;
                        case 10:
                            Tools.swapFragment(requireActivity(), ContentManagerFragment.class, ContentManagerFragment.TAG, null);
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptAssignInstanceGpuDriver() {
        if (mInstance == null || !isAdded()) return;
        Context ctx = requireContext();
        List<git.artdeell.mojo.vulkan.driver.Driver> drivers =
                git.artdeell.mojo.vulkan.DriverManager.getDrivers();
        git.artdeell.mojo.vulkan.driver.Driver globalDriver =
                git.artdeell.mojo.vulkan.DriverManager.getPreferredDriver();

        String[] labels = new String[drivers.size() + 1];
        String[] tokens = new String[drivers.size() + 1];
        labels[0] = ctx.getString(R.string.global_default)
                + " (" + git.artdeell.mojo.vulkan.DriverManager.formatDriverLabel(globalDriver) + ")";
        tokens[0] = null;

        int checked = 0;
        for (int i = 0; i < drivers.size(); i++) {
            git.artdeell.mojo.vulkan.driver.Driver d = drivers.get(i);
            boolean builtin = d instanceof git.artdeell.mojo.vulkan.driver.BuiltinDriver;
            String tok = builtin
                    ? git.artdeell.mojo.vulkan.DriverManager.SYSTEM_DRIVER_TOKEN
                    : d.getHash();
            tokens[i + 1] = tok;
            labels[i + 1] = git.artdeell.mojo.vulkan.DriverManager.formatDriverLabel(d);
            if (mInstance.hasCustomVulkanDriver() && tok != null
                    && tok.equalsIgnoreCase(mInstance.vulkanDriver)) {
                checked = i + 1;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Per-Instance Vulkan / Turnip Driver")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    mInstance.vulkanDriver = tokens[which];
                    mInstance.maybeWrite();
                    updateRendererAndRuntimeHint(mDefaultRenderer.getSelectedItemPosition());
                    Toast.makeText(ctx,
                            "Assigned GPU driver: " + labels[which],
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNeutralButton("Turnip Store", (d, w) ->
                        git.artdeell.mojo.downloader.RuntimeDriverStoreDialog.showForInstance(
                                ctx, mInstance, () -> {
                                    if (isAdded()) {
                                        updateRendererAndRuntimeHint(mDefaultRenderer.getSelectedItemPosition());
                                    }
                                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyAutoGpuPresetToInstance() {
        if (mInstance == null || !isAdded()) return;
        Context ctx = requireContext();
        git.artdeell.mojo.diagnostics.GraphicsAdvisor.RendererPreset preset =
                git.artdeell.mojo.diagnostics.GraphicsAdvisor.applyOptimalPreset(
                        ctx, mInstance, mInstance.versionId);
        if (preset.rendererId != null && mRenderNames != null) {
            int idx = mRenderNames.indexOf(preset.rendererId);
            if (idx >= 0) {
                mDefaultRenderer.setSelection(idx);
            }
        }
        updateRendererAndRuntimeHint(mDefaultRenderer.getSelectedItemPosition());
        new AlertDialog.Builder(ctx)
                .setTitle("GPU Preset Applied — " + preset.presetName)
                .setMessage(preset.summary)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void promptCloneInstance() {
        Context ctx = requireContext();
        EditText nameInput = ZyroxStudioUi.createGlassSearchInput(ctx, "Cloned instance name");
        String base = Tools.isValidString(mInstance.name) ? mInstance.name : "Instance";
        nameInput.setText(base + " (Copy)");

        new AlertDialog.Builder(ctx)
                .setTitle("Clone Instance")
                .setMessage("Creates an isolated new instance UUID & directory without touching your original worlds or mods.")
                .setView(nameInput)
                .setPositiveButton("Clone + Mods/Configs", (d, w) ->
                        executeClone(nameInput.getText().toString(), true, false))
                .setNeutralButton("Clone + Worlds Too", (d, w) ->
                        executeClone(nameInput.getText().toString(), true, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void executeClone(@NonNull String newName, boolean copyContent, boolean copyWorlds) {
        save();
        final Instance src = mInstance;
        Toast.makeText(requireContext(), "Cloning instance safely...", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Instance cloned = InstanceManager.cloneInstance(
                        src, Instances.getInstancesDirectory(), newName, copyContent, copyWorlds);
                Instances.setSelectedInstance(cloned);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    loadValues(cloned, requireContext());
                    reloadInstanceRail();
                    ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                    Toast.makeText(requireContext(), "Created cloned instance \"" + cloned.name + "\"",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Clone failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void runHealthCheck() {
        save();
        final Instance target = mInstance;
        MojoApplication.sExecutorService.execute(() -> {
            int reqJava = 0;
            int selJava = 0;
            try {
                if (Tools.isValidString(target.versionId)) {
                    JVersionList.Version vInfo = Tools.getVersionInfo(target.versionId);
                    reqJava = JavaRuntimeAdvisor.resolveRequiredJava(vInfo, target.versionId);
                }
                if (Tools.isValidString(target.selectedRuntime)) {
                    Runtime rt = MultiRTUtils.read(target.selectedRuntime);
                    if (rt != null) selJava = rt.javaVersion;
                }
            } catch (Throwable ignored) {
            }
            InstanceManager.HealthReport report = InstanceManager.checkHealth(
                    target, new File(Tools.DIR_HOME_VERSION), new File(Tools.CTRLMAP_PATH), reqJava, selJava);
            StringBuilder sb = new StringBuilder();
            sb.append("Status: ").append(report.status.name()).append('\n');
            sb.append("Disk Usage: ").append(ZyroxStudioUi.formatBytes(report.directoryBytes))
                    .append(" • Worlds: ").append(report.worldCount)
                    .append(" • Mods: ").append(report.enabledModCount);
            if (report.disabledModCount > 0) {
                sb.append(" (+").append(report.disabledModCount).append(" disabled)");
            }
            sb.append('\n');
            for (String finding : report.findings) sb.append("\n• ").append(finding);
            if (report.findings.isEmpty()) {
                sb.append("\nAll required directories, version metadata, runtime, and control bindings look valid.");
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle("Instance Health Check")
                        .setMessage(sb.toString())
                        .setPositiveButton("Safe Repair", (d, w) -> {
                            try {
                                List<String> actions = InstanceManager.repairInstance(
                                        target, new File(Tools.CTRLMAP_PATH));
                                int count = actions.size();
                                if (target.consecutiveStartupFailures >= 2 && target.lastKnownGoodRenderer != null) {
                                    if (InstanceManager.restoreLastKnownGoodRenderer(target)) count++;
                                }
                                loadValues(target, requireContext());
                                Toast.makeText(requireContext(),
                                        "Safe repair completed (" + count + " item(s) checked; worlds/mods untouched).",
                                        Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                Toast.makeText(requireContext(),
                                        "Repair failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    @NonNull
    private static File getInstanceBackupDirectory() {
        return new File(new File(Tools.DIR_GAME_HOME, "backups"), "instances");
    }

    private void createInstanceBackup() {
        save();
        final Instance target = mInstance;
        final File backupRoot = getInstanceBackupDirectory();
        final int retention = LauncherPreferences.PREF_ZYROX_BACKUP_RETENTION;
        Toast.makeText(requireContext(), "Creating verified SHA-256 instance backup...", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                File zip = InstanceManager.createInstanceBackup(target, backupRoot, true, retention);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                "Instance backup verified: " + zip.getName() + " (" + ZyroxStudioUi.formatBytes(zip.length()) + ")",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void promptRestoreInstanceBackup() {
        File backupRoot = getInstanceBackupDirectory();
        File[] zips = backupRoot.listFiles(f -> f.isFile() && f.getName().endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            Toast.makeText(requireContext(), "No instance backup archives found yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(zips, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[zips.length];
        for (int i = 0; i < zips.length; i++) {
            labels[i] = zips[i].getName() + " (" + ZyroxStudioUi.formatBytes(zips[i].length()) + ")";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Restore Instance Backup (Creates New Isolated Copy)")
                .setItems(labels, (d, which) -> {
                    final File chosen = zips[which];
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            Instance restored = InstanceManager.restoreInstanceBackup(
                                    chosen, Instances.getInstancesDirectory());
                            Instances.setSelectedInstance(restored);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                loadValues(restored, requireContext());
                                reloadInstanceRail();
                                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                                Toast.makeText(requireContext(),
                                        "Restored instance \"" + restored.name + "\"!", Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (isAdded()) Toast.makeText(requireContext(),
                                        "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImpactAwareDeleteDialog() {
        if (mInstance == null) return;
        final Instance target = mInstance;
        MojoApplication.sExecutorService.execute(() -> {
            InstanceManager.DeleteImpact impact = InstanceManager.computeDeleteImpact(target);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.instance_delete)
                        .setMessage(impact.buildConfirmationMessage())
                        .setPositiveButton("Delete Instance", (d, w) -> {
                            DeleteConfirmDialogFragment dialogFragment = new DeleteConfirmDialogFragment();
                            dialogFragment.show(getChildFragmentManager(), DeleteConfirmDialogFragment.TAG);
                        })
                        .setNeutralButton("Backup First", (d, w) -> createInstanceBackup())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void onExportConfigUri(@Nullable Uri uri) {
        if (uri == null || mInstance == null) return;
        final String json = InstanceManager.exportConfigJson(mInstance);
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try (OutputStream out = appCtx.getContentResolver().openOutputStream(uri)) {
                if (out != null) out.write(json.getBytes(StandardCharsets.UTF_8));
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Exported instance configuration JSON!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onImportConfigUri(@Nullable Uri uri) {
        if (uri == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot read file");
                String json = new String(ArchiveSecurity.readBytesBounded(in, 512 * 1024), StandardCharsets.UTF_8);
                Instance imported = InstanceManager.importConfigJson(json, Instances.getInstancesDirectory());
                Instances.setSelectedInstance(imported);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    loadValues(imported, requireContext());
                    reloadInstanceRail();
                    ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                    Toast.makeText(requireContext(), "Imported instance \"" + imported.name + "\"!", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
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
            Context ctx = v.getContext();
            List<File> saved = git.artdeell.mojo.input.ControlProfileManager.listControlFiles();
            if (saved.isEmpty()) {
                mOpenControlLayoutLauncher.launch(null);
                return;
            }
            String[] names = new String[saved.size() + 1];
            names[0] = ctx.getString(R.string.global_default);
            for (int i = 0; i < saved.size(); i++) {
                names[i + 1] = saved.get(i).getName();
            }
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.mcl_option_customcontrol)
                    .setItems(names, (dialog, which) -> {
                        if (which == 0) {
                            mSelectedControlLayout = null;
                            if (mInstance != null) mInstance.controlLayout = null;
                            mDefaultControl.setText("");
                            syncOverviewControlQuickLabel("");
                        } else {
                            File chosen = saved.get(which - 1);
                            mSelectedControlLayout = chosen.getAbsolutePath();
                            mDefaultControl.setText(chosen.getName());
                            syncOverviewControlQuickLabel(chosen.getName());
                        }
                    })
                    .setNeutralButton(R.string.zyrox_action_import,
                            (d, w) -> mOpenControlLayoutLauncher.launch(null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot) -> {
            mDefaultVersion.setText(id);
            if (mInstance != null) mInstance.versionId = id;
            updateRendererAndRuntimeHint(mDefaultRenderer.getSelectedItemPosition());
        });
    }

    private void setupFourTabAndSimpleProUi(@NonNull View view,
                                            @NonNull View.OnClickListener controlSelectListener) {
        if (mTabOverview != null) mTabOverview.setOnClickListener(v -> switchEditorTab(TAB_OVERVIEW));
        if (mTabContent != null) mTabContent.setOnClickListener(v -> switchEditorTab(TAB_CONTENT));
        if (mTabEngine != null) mTabEngine.setOnClickListener(v -> switchEditorTab(TAB_ENGINE));
        if (mTabControls != null) mTabControls.setOnClickListener(v -> switchEditorTab(TAB_CONTROLS));

        if (mChipPresetBattery != null) {
            mChipPresetBattery.setOnClickListener(v -> selectPerformancePreset(Instance.PRESET_BATTERY));
        }
        if (mChipPresetBalanced != null) {
            mChipPresetBalanced.setOnClickListener(v -> selectPerformancePreset(Instance.PRESET_BALANCED));
        }
        if (mChipPresetMaxFps != null) {
            mChipPresetMaxFps.setOnClickListener(v -> selectPerformancePreset(Instance.PRESET_MAX_FPS));
        }
        if (mChipScale75 != null) {
            mChipScale75.setOnClickListener(v -> selectResolutionScale(75));
        }
        if (mChipScale100 != null) {
            mChipScale100.setOnClickListener(v -> selectResolutionScale(100));
        }
        if (mOverviewControlQuick != null) {
            mOverviewControlQuick.setOnClickListener(controlSelectListener);
        }

        // Expandable Advanced / Pro Mode (starts collapsed in Simple Mode by default)
        setProModeExpanded(LauncherPreferences.PREF_ZYROX_INSTANCE_PRO_MODE);
        if (mToggleProModeBtn != null) {
            mToggleProModeBtn.setOnClickListener(v -> {
                boolean next = !mProModeExpanded;
                setProModeExpanded(next);
                LauncherPreferences.PREF_ZYROX_INSTANCE_PRO_MODE = next;
                if (LauncherPreferences.DEFAULT_PREF != null) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putBoolean(LauncherPreferences.KEY_ZYROX_INSTANCE_PRO_MODE, next)
                            .apply();
                }
            });
        }

        View autoCalBtn = view.findViewById(R.id.btn_engine_auto_calibrate);
        if (autoCalBtn != null) {
            autoCalBtn.setOnClickListener(v -> {
                if (mInstance == null) return;
                mInstance.runtimeLocked = false;
                mInstance.rendererLocked = false;
                if (mLockRuntimeCheckbox != null) mLockRuntimeCheckbox.setChecked(false);
                if (mLockRendererCheckbox != null) mLockRendererCheckbox.setChecked(false);
                applyAutoGpuPresetToInstance();
            });
        }

        // Content Tab buttons
        View fpsPackBtn = view.findViewById(R.id.btn_inst_install_fps_pack);
        if (fpsPackBtn != null) {
            fpsPackBtn.setOnClickListener(v -> runOneTapZeroLagFpsPack());
        }
        View manageModsBtn = view.findViewById(R.id.btn_inst_manage_mods);
        if (manageModsBtn != null) {
            manageModsBtn.setOnClickListener(v -> Tools.swapFragment(
                    requireActivity(), ContentManagerFragment.class, ContentManagerFragment.TAG, null));
        }
        View browseStoreBtn = view.findViewById(R.id.btn_inst_browse_store);
        if (browseStoreBtn != null) {
            browseStoreBtn.setOnClickListener(v -> Tools.swapFragment(
                    requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        }
        View manageWorldsBtn = view.findViewById(R.id.btn_inst_manage_worlds);
        if (manageWorldsBtn != null) {
            manageWorldsBtn.setOnClickListener(v -> Tools.swapFragment(
                    requireActivity(), WorldManagerFragment.class, WorldManagerFragment.TAG, null));
        }

        // 1-Tap Pre-Mod Snapshot Rollback buttons (Overview & Content tabs)
        View quickRollback = view.findViewById(R.id.btn_inst_quick_rollback);
        if (quickRollback != null) {
            quickRollback.setOnClickListener(v -> showPreModSnapshotRollbackDialog());
        }
        View contentRollback = view.findViewById(R.id.btn_inst_content_rollback);
        if (contentRollback != null) {
            contentRollback.setOnClickListener(v -> showPreModSnapshotRollbackDialog());
        }

        // Controls Tab: Context-aware inventory helpers + HUD Editor + Zyrox Setup Codes
        if (mContextControlsCheckbox != null) {
            mContextControlsCheckbox.setChecked(LauncherPreferences.PREF_ZYROX_CONTEXT_CONTROLS);
            mContextControlsCheckbox.setOnCheckedChangeListener((cb, checked) -> {
                LauncherPreferences.PREF_ZYROX_CONTEXT_CONTROLS = checked;
                if (LauncherPreferences.DEFAULT_PREF != null) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putBoolean(LauncherPreferences.KEY_ZYROX_CONTEXT_CONTROLS, checked)
                            .apply();
                }
            });
        }
        View hudEditorBtn = view.findViewById(R.id.btn_inst_open_hud_editor);
        if (hudEditorBtn != null) {
            hudEditorBtn.setOnClickListener(v -> startActivity(
                    new android.content.Intent(requireContext(), git.artdeell.mojo.CustomControlsActivity.class)));
        }
        View exportSetupBtn = view.findViewById(R.id.btn_inst_export_setup_code);
        if (exportSetupBtn != null) {
            exportSetupBtn.setOnClickListener(v ->
                    git.artdeell.mojo.input.ControlProfileManager.showZyroxSetupExportDialog(
                            requireContext(), mInstance, suggestedName ->
                                    mExportSetupBundleLauncher.launch(suggestedName)));
        }
        View importSetupBtn = view.findViewById(R.id.btn_inst_import_setup_code);
        if (importSetupBtn != null) {
            importSetupBtn.setOnClickListener(v ->
                    git.artdeell.mojo.input.ControlProfileManager.showZyroxSetupImportDialog(
                            requireContext(), mInstance, result -> {
                                if (!isAdded()) return;
                                if (result.controlFile != null) {
                                    mSelectedControlLayout = result.controlFile.getAbsolutePath();
                                    mDefaultControl.setText(result.controlFile.getName());
                                    syncOverviewControlQuickLabel(result.controlFile.getName());
                                }
                                refreshContentAndSnapshotSummary();
                            }));
        }

        switchEditorTab(TAB_OVERVIEW);
    }

    private void switchEditorTab(int tab) {
        mActiveTab = tab;
        if (mPanelOverview != null) mPanelOverview.setVisibility(tab == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
        if (mPanelContent != null) mPanelContent.setVisibility(tab == TAB_CONTENT ? View.VISIBLE : View.GONE);
        if (mPanelEngine != null) mPanelEngine.setVisibility(tab == TAB_ENGINE ? View.VISIBLE : View.GONE);
        if (mPanelControls != null) mPanelControls.setVisibility(tab == TAB_CONTROLS ? View.VISIBLE : View.GONE);

        styleTabChip(mTabOverview, tab == TAB_OVERVIEW);
        styleTabChip(mTabContent, tab == TAB_CONTENT);
        styleTabChip(mTabEngine, tab == TAB_ENGINE);
        styleTabChip(mTabControls, tab == TAB_CONTROLS);

        if (tab == TAB_CONTENT) {
            refreshContentAndSnapshotSummary();
        }
    }

    private static void styleTabChip(@Nullable TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setSelected(selected);
        chip.setAlpha(selected ? 1.0f : 0.68f);
    }

    private void selectPerformancePreset(@NonNull String preset) {
        mSelectedPerfPreset = preset;
        styleTabChip(mChipPresetBattery, Instance.PRESET_BATTERY.equals(preset));
        styleTabChip(mChipPresetBalanced, Instance.PRESET_BALANCED.equals(preset));
        styleTabChip(mChipPresetMaxFps, Instance.PRESET_MAX_FPS.equals(preset));
    }

    private void selectResolutionScale(int percent) {
        mSelectedResScale = percent <= 85 ? 75 : 100;
        styleTabChip(mChipScale75, mSelectedResScale == 75);
        styleTabChip(mChipScale100, mSelectedResScale == 100);
    }

    private void setProModeExpanded(boolean expanded) {
        mProModeExpanded = expanded;
        if (mProModeContainer != null) {
            mProModeContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (mToggleProModeBtn != null) {
            mToggleProModeBtn.setText(expanded
                    ? "▾ Hide Advanced / Pro Mode (Simple Mode Recommended)"
                    : "▸ Advanced / Pro Mode (Renderer, Java, RAM Slider, JVM & Env Flags)");
        }
    }

    private void syncOverviewControlQuickLabel(@Nullable String pathOrName) {
        if (mOverviewControlQuick == null) return;
        if (pathOrName == null || pathOrName.trim().isEmpty()) {
            mOverviewControlQuick.setText("Global Default (Tap to change)");
            return;
        }
        String name = new File(pathOrName).getName();
        mOverviewControlQuick.setText(name);
    }

    private void refreshContentAndSnapshotSummary() {
        final Instance target = mInstance;
        if (target == null) return;
        MojoApplication.sExecutorService.execute(() -> {
            File gameDir = target.getGameDirectory();
            int mods = countFiles(new File(gameDir, "mods"), ".jar");
            int shaders = countFiles(new File(gameDir, "shaderpacks"), ".zip");
            int packs = countFiles(new File(gameDir, "resourcepacks"), ".zip");
            int snapshots = git.artdeell.mojo.instances.InstanceBackupService
                    .listPreModSnapshots(target).size();
            final String summary = mods + " active mods  •  " + shaders + " shader packs  •  "
                    + packs + " resource packs  •  " + snapshots + "/3 pre-mod rollback snapshots";
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (mContentSummaryView != null) {
                    mContentSummaryView.setText(summary);
                }
            });
        });
    }

    private static int countFiles(@NonNull File dir, @NonNull String suffix) {
        File[] list = dir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(suffix));
        return list != null ? list.length : 0;
    }

    private void runOneTapZeroLagFpsPack() {
        if (mInstance == null || !isAdded()) return;
        final Instance target = mInstance;
        final Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx,
                "Installing Zero-Lag FPS Pack (Sodium, Lithium, FerriteCore, ImmediatelyFast) + tuning options.txt…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                git.artdeell.mojo.instances.InstanceBackupService.createPreModSnapshotQuietly(
                        target, "before-fps-pack");
                git.artdeell.mojo.content.ModrinthContentInstaller.InstallSummary res =
                        git.artdeell.mojo.content.ModrinthContentInstaller.installZeroLagFpsPack(
                                target, false, null);
                Tools.runOnUiThread(() -> {
                    Toast.makeText(appCtx, res.message, Toast.LENGTH_LONG).show();
                    if (isAdded()) refreshContentAndSnapshotSummary();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(
                        appCtx, "FPS Pack: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showPreModSnapshotRollbackDialog() {
        if (mInstance == null || !isAdded()) return;
        final Instance target = mInstance;
        final Context ctx = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            final List<git.artdeell.mojo.instances.InstanceBackupService.SnapshotInfo> snaps =
                    git.artdeell.mojo.instances.InstanceBackupService.listPreModSnapshots(target);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (snaps.isEmpty()) {
                    new AlertDialog.Builder(ctx)
                            .setTitle("Pre-Mod Rollback Snapshots")
                            .setMessage("No pre-mod snapshots yet. Zyrox automatically snapshots config/ and saves/ (retaining the last 3) before every mod install or removal.")
                            .setPositiveButton("Create Snapshot Now", (d, w) ->
                                    MojoApplication.sExecutorService.execute(() -> {
                                        try {
                                            git.artdeell.mojo.instances.InstanceBackupService
                                                    .createPreModSnapshot(target, "manual-checkpoint");
                                            Tools.runOnUiThread(() -> {
                                                Toast.makeText(ctx.getApplicationContext(),
                                                        "Saved rollback snapshot of config/ & saves/!",
                                                        Toast.LENGTH_SHORT).show();
                                                if (isAdded()) refreshContentAndSnapshotSummary();
                                            });
                                        } catch (Exception e) {
                                            Tools.runOnUiThread(() -> Toast.makeText(
                                                    ctx.getApplicationContext(),
                                                    "Snapshot failed: " + e.getMessage(),
                                                    Toast.LENGTH_LONG).show());
                                        }
                                    }))
                            .setNegativeButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                String[] labels = new String[snaps.size()];
                for (int i = 0; i < snaps.size(); i++) {
                    labels[i] = snaps.get(i).formatDisplayLabel();
                }
                new AlertDialog.Builder(ctx)
                        .setTitle("1-Tap Rollback (config/ & worlds/)")
                        .setItems(labels, (d, which) -> {
                            final git.artdeell.mojo.instances.InstanceBackupService.SnapshotInfo chosen =
                                    snaps.get(which);
                            MojoApplication.sExecutorService.execute(() -> {
                                try {
                                    int restoredFiles = git.artdeell.mojo.instances.InstanceBackupService
                                            .rollbackPreModSnapshot(target, chosen.archiveFile);
                                    Tools.runOnUiThread(() -> {
                                        Toast.makeText(ctx.getApplicationContext(),
                                                "Rolled back " + restoredFiles
                                                        + " config & world files cleanly!",
                                                Toast.LENGTH_LONG).show();
                                        if (isAdded()) refreshContentAndSnapshotSummary();
                                    });
                                } catch (Exception e) {
                                    Tools.runOnUiThread(() -> Toast.makeText(
                                            ctx.getApplicationContext(),
                                            "Rollback failed: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show());
                                }
                            });
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void onExportSetupBundleUri(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        final Instance inst = mInstance;
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try (OutputStream out = appCtx.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot open output file");
                String code = git.artdeell.mojo.input.ControlProfileManager
                        .exportZyroxSetupCode(inst);
                out.write(code.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Tools.runOnUiThread(() -> Toast.makeText(
                        appCtx, "Exported .zyrox Setup Bundle!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(
                        appCtx, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @NonNull
    private static String formatEnvVars(@Nullable java.util.Map<String, String> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey().trim()).append('=').append(e.getValue() != null ? e.getValue() : "");
        }
        return sb.toString();
    }

    @Nullable
    private static java.util.Map<String, String> parseEnvVars(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        String[] tokens = raw.trim().split("[,\\s]+");
        for (String tok : tokens) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq).trim();
            String v = tok.substring(eq + 1).trim();
            if (k.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                out.put(k, v);
            }
        }
        return out.isEmpty() ? null : out;
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

        // Sync per-instance RAM allocation (falling back gracefully to global allocation)
        int instRam = instance.getEffectiveRamAllocationMb();
        mSelectedRamMb = Math.max(RAM_MIN_MB, Math.min(mMaxSafeRamMb, instRam));
        if (mRamSeekBar != null) {
            mRamSeekBar.setProgress((mSelectedRamMb - RAM_MIN_MB) / RAM_STEP_MB);
        }
        if (mRamValueView != null) {
            mRamValueView.setText(mSelectedRamMb + " MB");
        }

        mDefaultVersion.setText(instance.versionId);
        mDefaultJvmArgument.setText(nullToEmpty(instance.jvmArgs));
        if (mEnvVarsEdit != null) {
            mEnvVarsEdit.setText(formatEnvVars(instance.envVars));
        }
        mDefaultName.setText(nullToEmpty(instance.name));
        String ctrlLabel = mSelectedControlLayout == null
                ? nullToEmpty(instance.controlLayout) : mSelectedControlLayout;
        mDefaultControl.setText(ctrlLabel);
        syncOverviewControlQuickLabel(ctrlLabel);
        selectPerformancePreset(instance.getEffectivePerformancePreset());
        selectResolutionScale(instance.getEffectiveResolutionScalePercent());
        if (mLockRuntimeCheckbox != null) {
            mLockRuntimeCheckbox.setChecked(instance.runtimeLocked);
        }
        if (mLockRendererCheckbox != null) {
            mLockRendererCheckbox.setChecked(instance.rendererLocked);
        }
        mSharedDataCheckbox.setChecked(instance.sharedData);
        mSharedDataCheckbox.setText(instance.sharedData ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
        updateRendererAndRuntimeHint(rendererIndex);
        refreshContentAndSnapshotSummary();
    }

    private void bindViews(@NonNull View view) {
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_instance_renderer);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);
        mDefaultName = view.findViewById(R.id.vprof_editor_instance_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);
        mEnvVarsEdit = view.findViewById(R.id.vprof_editor_env_vars);
        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mStudioToolsButton = view.findViewById(R.id.vprof_editor_studio_tools_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mInstanceIcon = view.findViewById(R.id.vprof_editor_instance_icon);
        mSharedDataCheckbox = view.findViewById(R.id.vprof_editor_data_checkbox_container);
        mLockRuntimeCheckbox = view.findViewById(R.id.cb_lock_runtime);
        mLockRendererCheckbox = view.findViewById(R.id.cb_lock_renderer);
        mContextControlsCheckbox = view.findViewById(R.id.cb_inst_context_controls);
        mRendererHint = view.findViewById(R.id.tv_renderer_hint);
        mRamValueView = view.findViewById(R.id.tv_vprof_ram_value);
        mRamSeekBar = view.findViewById(R.id.vprof_ram_seekbar);
        mInstanceListRecycler = view.findViewById(R.id.zyrox_instance_list);

        mTabOverview = view.findViewById(R.id.tab_inst_overview);
        mTabContent = view.findViewById(R.id.tab_inst_content);
        mTabEngine = view.findViewById(R.id.tab_inst_engine);
        mTabControls = view.findViewById(R.id.tab_inst_controls);
        mPanelOverview = view.findViewById(R.id.panel_inst_overview);
        mPanelContent = view.findViewById(R.id.panel_inst_content);
        mPanelEngine = view.findViewById(R.id.panel_inst_engine);
        mPanelControls = view.findViewById(R.id.panel_inst_controls);
        mChipPresetBattery = view.findViewById(R.id.chip_preset_battery);
        mChipPresetBalanced = view.findViewById(R.id.chip_preset_balanced);
        mChipPresetMaxFps = view.findViewById(R.id.chip_preset_max_fps);
        mChipScale75 = view.findViewById(R.id.chip_scale_75);
        mChipScale100 = view.findViewById(R.id.chip_scale_100);
        mOverviewControlQuick = view.findViewById(R.id.tv_overview_control_quick);
        mContentSummaryView = view.findViewById(R.id.tv_inst_content_summary);
        mEngineSimpleSummaryView = view.findViewById(R.id.tv_engine_simple_summary);
        mToggleProModeBtn = view.findViewById(R.id.btn_toggle_pro_mode);
        mProModeContainer = view.findViewById(R.id.layout_engine_pro_mode);
    }

    private void save() {
        if (mInstance == null) return;
        mInstance.versionId = mDefaultVersion.getText().toString();
        mInstance.controlLayout = mDefaultControl.getText().toString();
        mInstance.jvmArgs = mDefaultJvmArgument.getText().toString();
        mInstance.performancePreset = mSelectedPerfPreset;
        mInstance.resolutionScalePercent = mSelectedResScale;
        mInstance.runtimeLocked = mLockRuntimeCheckbox != null && mLockRuntimeCheckbox.isChecked();
        mInstance.rendererLocked = mLockRendererCheckbox != null && mLockRendererCheckbox.isChecked();
        if (mEnvVarsEdit != null) {
            mInstance.envVars = parseEnvVars(mEnvVarsEdit.getText().toString());
        }

        // Apply Simple Mode Resolution Scale seamlessly to active global surface scaler
        if (LauncherPreferences.DEFAULT_PREF != null) {
            LauncherPreferences.PREF_SCALE_FACTOR = mSelectedResScale / 100f;
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putInt("resolutionRatio", mSelectedResScale)
                    .apply();
        }

        String newName = mDefaultName.getText().toString().trim();
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

        if (mSelectedRamMb >= RAM_MIN_MB) {
            mInstance.ramAllocationMb = (mSelectedRamMb == LauncherPreferences.PREF_RAM_ALLOCATION)
                    ? 0 : mSelectedRamMb;
        }

        try {
            if (!newName.isEmpty() && !newName.equals(mInstance.name)) {
                Instances.renameInstanceDirectory(mInstance, newName);
            }
            if (!newName.isEmpty()) {
                mInstance.name = newName;
            }
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

    /** Left-Rail RecyclerView Adapter showing saved instances with Liquid Glass active selection */
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
