package git.artdeell.mojo.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.content.InstanceContentManager;
import git.artdeell.mojo.content.ModMetadataParser;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.worlds.WorldManager;

/**
 * Zyrox 2.0 Mod Manager 2.0 + Resource Packs + Shader Packs + Datapacks + Safe Config Editor.
 *
 * <p>Performs zero-execution static archive inspection, flags duplicate mods and loader/version
 * warnings, toggles items cleanly via {@code .disabled}, saves/restores mod toggle snapshots,
 * checks Modrinth SHA-1 updates, and edits plain-text configs atomically with {@code .bak} backup.
 */
public class ContentManagerFragment extends Fragment {
    public static final String TAG = "CONTENT_MANAGER_FRAGMENT";

    private static final long MAX_CONFIG_EDIT_BYTES = 256L * 1024L;
    private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;

    private enum TabKind {
        MODS,
        RESOURCE_PACKS,
        SHADER_PACKS,
        DATAPACKS,
        CONFIGS
    }

    private Instance mInstance;
    private TabKind mActiveKind = TabKind.MODS;
    private final List<Button> mTabButtons = new ArrayList<>();
    private final TabKind[] mTabOrder = TabKind.values();
    private final String[] mTabLabels = {"Mods", "Resources", "Shaders", "Datapacks", "Configs"};

    private TextView mStatusLine;
    private EditText mSearchBox;
    private Button mBtnUpdates;
    private Button mBtnSnapshot;
    private LinearLayout mListContainer;
    private List<ModMetadataParser.ModInfo> mLoadedMods = new ArrayList<>();
    private List<InstanceContentManager.ContentItem> mLoadedItems = new ArrayList<>();
    private boolean mHasShaderMod = false;
    @Nullable private File mSelectedDatapackWorld;

    private final ActivityResultLauncher<Object> mImportJarLauncher =
            registerForActivityResult(new git.artdeell.mojo.contracts.OpenDocumentWithExtension("jar"), this::onFileImported);
    private final ActivityResultLauncher<Object> mImportZipLauncher =
            registerForActivityResult(new git.artdeell.mojo.contracts.OpenDocumentWithExtension("zip"), this::onFileImported);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        mTabButtons.clear();

        for (int i = 0; i < mTabOrder.length; i++) {
            final int idx = i;
            Button tab = ZyroxStudioUi.createTabButton(ctx, mTabLabels[i]);
            tab.setOnClickListener(v -> selectTab(mTabOrder[idx]));
            tabRow.addView(tab, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), i == 0 ? 0 : ZyroxStudioUi.dp(ctx, 5)));
            mTabButtons.add(tab);
        }

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "+ Import", true);
        btnImport.setOnClickListener(v -> triggerImport());
        tabRow.addView(btnImport, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 10)));

        Button btnFpsBoost = ZyroxStudioUi.createSecondaryButton(ctx, "FPS Boost", true);
        btnFpsBoost.setOnClickListener(v -> SearchModFragment.promptInstallFpsBoostPack(
                requireContext(), () -> {
                    if (isAdded()) reloadActiveTab();
                }));
        tabRow.addView(btnFpsBoost, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnOptCfg = ZyroxStudioUi.createSecondaryButton(ctx, "Optimize FPS", true);
        btnOptCfg.setOnClickListener(v -> showGameOptimizerDialog());
        tabRow.addView(btnOptCfg, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnStore = ZyroxStudioUi.createPrimaryButton(ctx, "Store");
        btnStore.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        tabRow.addView(btnStore, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        tabScroll.addView(tabRow);
        panel.addView(tabScroll);

        LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = ZyroxStudioUi.dp(ctx, 7);

        mSearchBox = ZyroxStudioUi.createGlassSearchInput(ctx, "Filter installed items...");
        mSearchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderFiltered(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        filterRow.addView(mSearchBox, new LinearLayout.LayoutParams(0, ZyroxStudioUi.dp(ctx, 34), 1f));

        mBtnUpdates = ZyroxStudioUi.createSecondaryButton(ctx, "Check Updates", true);
        mBtnUpdates.setOnClickListener(v -> checkModUpdates());
        filterRow.addView(mBtnUpdates, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        mBtnSnapshot = ZyroxStudioUi.createSecondaryButton(ctx, "Snapshot", true);
        mBtnSnapshot.setOnClickListener(v -> showSnapshotMenu());
        filterRow.addView(mBtnSnapshot, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        panel.addView(filterRow, fLp);

        mStatusLine = ZyroxStudioUi.createSubtitle(ctx, "");
        mStatusLine.setPadding(ZyroxStudioUi.dp(ctx, 2), ZyroxStudioUi.dp(ctx, 5), 0, ZyroxStudioUi.dp(ctx, 4));
        panel.addView(mStatusLine);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        mListContainer = new LinearLayout(ctx);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mListContainer);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mInstance = Instances.loadSelectedInstance();
        selectTab(TabKind.MODS);
    }

    private void selectTab(@NonNull TabKind kind) {
        mActiveKind = kind;
        for (int i = 0; i < mTabOrder.length; i++) {
            mTabButtons.get(i).setSelected(mTabOrder[i] == kind);
        }
        boolean isMods = (kind == TabKind.MODS);
        mBtnUpdates.setVisibility(isMods ? View.VISIBLE : View.GONE);
        mBtnSnapshot.setVisibility(isMods ? View.VISIBLE : View.GONE);

        if (kind == TabKind.DATAPACKS && mInstance != null) {
            List<WorldManager.WorldEntry> worlds = WorldManager.listWorlds(mInstance.getGameDirectory());
            if (!worlds.isEmpty() && (mSelectedDatapackWorld == null || !mSelectedDatapackWorld.isDirectory())) {
                mSelectedDatapackWorld = worlds.get(0).directory;
            }
        }
        reloadActiveTab();
    }

    private void reloadActiveTab() {
        if (mInstance == null) {
            mStatusLine.setText(R.string.no_instance);
            return;
        }
        mStatusLine.setText("Inspecting " + mActiveKind.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "...");
        final Instance inst = mInstance;
        final TabKind kind = mActiveKind;
        final File worldDir = mSelectedDatapackWorld;

        MojoApplication.sExecutorService.execute(() -> {
            if (kind == TabKind.MODS) {
                List<ModMetadataParser.ModInfo> mods =
                        InstanceContentManager.listMods(inst.getGameDirectory(), inst.versionId);
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || mActiveKind != kind) return;
                    mLoadedMods = mods;
                    renderFiltered();
                });
                return;
            }
            final List<InstanceContentManager.ContentItem> items;
            final boolean shaderMod;
            switch (kind) {
                case RESOURCE_PACKS:
                    items = InstanceContentManager.listResourcePacks(inst.getGameDirectory());
                    shaderMod = false;
                    break;
                case SHADER_PACKS:
                    items = InstanceContentManager.listShaderPacks(inst.getGameDirectory());
                    shaderMod = InstanceContentManager.hasShaderModInstalled(inst.getGameDirectory());
                    break;
                case DATAPACKS:
                    items = worldDir != null
                            ? InstanceContentManager.listWorldDatapacks(worldDir)
                            : new ArrayList<>();
                    shaderMod = false;
                    break;
                case CONFIGS:
                default:
                    items = InstanceContentManager.listConfigFiles(inst.getGameDirectory());
                    shaderMod = false;
                    break;
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mActiveKind != kind) return;
                mLoadedItems = items;
                mHasShaderMod = shaderMod;
                renderFiltered();
            });
        });
    }

    private void renderFiltered() {
        Context ctx = requireContext();
        mListContainer.removeAllViews();
        String query = mSearchBox.getText() != null
                ? mSearchBox.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        String instLabel = Tools.isValidString(mInstance.name) ? mInstance.name : "Instance";
        String verLabel = Tools.isValidString(mInstance.versionId) ? mInstance.versionId : "unset";

        if (mActiveKind == TabKind.MODS) {
            renderModsList(ctx, query, instLabel, verLabel);
        } else {
            renderContentItemsList(ctx, query, instLabel, verLabel);
        }
    }

    private void renderModsList(@NonNull Context ctx, @NonNull String query,
                                @NonNull String instLabel, @NonNull String verLabel) {
        int warningsCount = 0;
        List<ModMetadataParser.ModInfo> visible = new ArrayList<>();
        for (ModMetadataParser.ModInfo mod : mLoadedMods) {
            if (!mod.warnings.isEmpty() || mod.corruptJar) warningsCount++;
            if (query.isEmpty()
                    || mod.name.toLowerCase(Locale.ROOT).contains(query)
                    || mod.modId.toLowerCase(Locale.ROOT).contains(query)
                    || mod.file.getName().toLowerCase(Locale.ROOT).contains(query)
                    || mod.loader.name().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(mod);
            }
        }

        String header = instLabel + " (" + verLabel + ") • "
                + visible.size() + "/" + mLoadedMods.size() + " mod(s)";
        if (warningsCount > 0) header += " • " + warningsCount + " compatibility alert(s)";
        mStatusLine.setText(header);

        if (visible.isEmpty()) {
            addEmptyHint(ctx);
            return;
        }

        for (ModMetadataParser.ModInfo mod : visible) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            iLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 8));

            String title = mod.name
                    + (mod.version.isEmpty() ? "" : "  v" + mod.version)
                    + (mod.enabled ? "" : "  [DISABLED]");
            info.addView(ZyroxStudioUi.createTitle(ctx, title));

            String sub = mod.file.getName() + " • " + ZyroxStudioUi.formatBytes(mod.sizeBytes)
                    + " • " + mod.loader.name();
            if (!mod.warnings.isEmpty()) {
                sub += "\n[!] " + mod.warnings.get(0);
            } else if (!mod.description.isEmpty()) {
                String shortDesc = mod.description.length() > 90
                        ? mod.description.substring(0, 87) + "..." : mod.description;
                sub += " — " + shortDesc;
            }
            info.addView(ZyroxStudioUi.createSubtitle(ctx, sub));
            row.addView(info, iLp);

            Button btnToggle = ZyroxStudioUi.createSecondaryButton(ctx, mod.enabled ? "Disable" : "Enable", true);
            btnToggle.setOnClickListener(v -> toggleFile(mod.file, !mod.enabled));
            row.addView(btnToggle, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnDel = ZyroxStudioUi.createDangerButton(ctx, "Remove", true);
            btnDel.setOnClickListener(v -> confirmDeleteFile(mod.name, mod.file, mod.sizeBytes));
            row.addView(btnDel, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void renderContentItemsList(@NonNull Context ctx, @NonNull String query,
                                        @NonNull String instLabel, @NonNull String verLabel) {
        int warningsCount = 0;
        List<InstanceContentManager.ContentItem> visible = new ArrayList<>();
        for (InstanceContentManager.ContentItem item : mLoadedItems) {
            if (!item.warning.isEmpty()) warningsCount++;
            if (query.isEmpty()
                    || item.name.toLowerCase(Locale.ROOT).contains(query)
                    || item.file.getName().toLowerCase(Locale.ROOT).contains(query)
                    || item.subtitle.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(item);
            }
        }

        String header = instLabel + " (" + verLabel + ") • "
                + visible.size() + "/" + mLoadedItems.size() + " item(s)";
        if (warningsCount > 0) header += " • " + warningsCount + " note(s)";
        if (mActiveKind == TabKind.SHADER_PACKS) {
            header += mHasShaderMod
                    ? " • Iris/Oculus detected (use LTW/Zink renderer)"
                    : " • Install Iris/Oculus + use LTW/Zink renderer";
        } else if (mActiveKind == TabKind.DATAPACKS && mSelectedDatapackWorld != null) {
            header += " • World: " + mSelectedDatapackWorld.getName();
        }
        mStatusLine.setText(header);

        if (visible.isEmpty()) {
            addEmptyHint(ctx);
            return;
        }

        for (InstanceContentManager.ContentItem item : visible) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            iLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 8));

            String title = item.name + (item.enabled ? "" : "  [DISABLED]");
            info.addView(ZyroxStudioUi.createTitle(ctx, title));

            String sub = item.file.getName() + " • " + ZyroxStudioUi.formatBytes(item.sizeBytes)
                    + (item.subtitle.isEmpty() ? "" : " • " + item.subtitle);
            if (!item.warning.isEmpty()) {
                sub += "\n[!] " + item.warning;
            }
            info.addView(ZyroxStudioUi.createSubtitle(ctx, sub));
            row.addView(info, iLp);

            if (mActiveKind == TabKind.CONFIGS) {
                if (item.sizeBytes <= MAX_CONFIG_EDIT_BYTES) {
                    Button btnEdit = ZyroxStudioUi.createSecondaryButton(ctx, "Edit", true);
                    btnEdit.setOnClickListener(v -> openConfigEditor(item));
                    row.addView(btnEdit, ZyroxStudioUi.spacedParam(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
                }
            } else {
                Button btnToggle = ZyroxStudioUi.createSecondaryButton(ctx, item.enabled ? "Disable" : "Enable", true);
                btnToggle.setOnClickListener(v -> toggleFile(item.file, !item.enabled));
                row.addView(btnToggle, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
            }

            Button btnDel = ZyroxStudioUi.createDangerButton(ctx, "Remove", true);
            btnDel.setOnClickListener(v -> confirmDeleteFile(item.name, item.file, item.sizeBytes));
            row.addView(btnDel, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void addEmptyHint(@NonNull Context ctx) {
        LinearLayout guideCard = ZyroxStudioUi.createItemCard(ctx);
        int pad = ZyroxStudioUi.dp(ctx, 14);
        guideCard.setPadding(pad, pad, pad, pad);
        guideCard.addView(ZyroxStudioUi.createTitle(ctx,
                mActiveKind == TabKind.MODS
                        ? "Supercharge Your FPS in One Tap"
                        : "No Items Installed Yet"));
        TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                mActiveKind == TabKind.MODS
                        ? "Install our curated Zero-Lag FPS Pack (Sodium, Lithium, FerriteCore & ImmediatelyFast) "
                        + "matched to your Minecraft version, browse Modrinth, or import a local .jar mod."
                        : "Browse verified packs & shaders in the Modrinth Store, install essential FPS mods, or import a local .zip archive.");
        empty.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, ZyroxStudioUi.dp(ctx, 12));
        guideCard.addView(empty);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button btnFps = ZyroxStudioUi.createPrimaryButton(ctx, "Install Essential FPS Mods");
        btnFps.setOnClickListener(v -> {
            if (mInstance == null) return;
            final Instance target = mInstance;
            final Context appCtx = ctx.getApplicationContext();
            btnFps.setEnabled(false);
            Toast.makeText(appCtx,
                    "Downloading Sodium, Lithium, FerriteCore & ImmediatelyFast + tuning options.txt…",
                    Toast.LENGTH_SHORT).show();
            MojoApplication.sExecutorService.execute(() -> {
                try {
                    git.artdeell.mojo.instances.InstanceBackupService
                            .createPreModSnapshotQuietly(target, "before-essential-fps");
                    boolean wantIris = (mActiveKind == TabKind.SHADER_PACKS);
                    git.artdeell.mojo.content.ModrinthContentInstaller.InstallSummary summary =
                            git.artdeell.mojo.content.ModrinthContentInstaller
                                    .installZeroLagFpsPack(target, wantIris, null);
                    Tools.runOnUiThread(() -> {
                        Toast.makeText(appCtx, summary.message, Toast.LENGTH_LONG).show();
                        if (isAdded()) reloadActiveTab();
                    });
                } catch (Exception e) {
                    Tools.runOnUiThread(() -> {
                        btnFps.setEnabled(true);
                        Toast.makeText(appCtx, "FPS Pack: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        actions.addView(btnFps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36)));

        Button btnStore = ZyroxStudioUi.createSecondaryButton(ctx, "Browse Modrinth", false);
        btnStore.setOnClickListener(v -> Tools.swapFragment(
                requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        actions.addView(btnStore, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36), ZyroxStudioUi.dp(ctx, 8)));

        guideCard.addView(actions);
        mListContainer.addView(guideCard);
    }

    private void toggleFile(@NonNull File file, boolean enable) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                InstanceContentManager.setModEnabled(file, enable);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) reloadActiveTab();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmDeleteFile(@NonNull String displayName, @NonNull File file, long sizeBytes) {
        final boolean isConfig = (mActiveKind == TabKind.CONFIGS);
        new AlertDialog.Builder(requireContext())
                .setTitle((isConfig ? "Reset \"" : "Remove \"") + displayName + "\"?")
                .setMessage("File: " + file.getName() + " (" + ZyroxStudioUi.formatBytes(sizeBytes) + ")"
                        + (isConfig ? "\nA .reset.bak backup will be preserved automatically." : ""))
                .setPositiveButton(isConfig ? "Reset" : "Remove", (d, w) -> MojoApplication.sExecutorService.execute(() -> {
                    try {
                        if (mActiveKind == TabKind.MODS && mInstance != null) {
                            git.artdeell.mojo.instances.InstanceBackupService
                                    .createPreModSnapshotQuietly(mInstance, "before-mod-remove");
                        }
                        File safe = FileUtils.ensureInsideDirectory(mInstance.getGameDirectory(), file);
                        if (isConfig) {
                            InstanceContentManager.resetConfigToDefaultWithBackup(safe);
                        } else if (safe.isDirectory()) {
                            org.apache.commons.io.FileUtils.deleteDirectory(safe);
                        } else if (!safe.delete()) {
                            throw new IOException("Could not delete " + safe.getName());
                        }
                        Tools.runOnUiThread(() -> {
                            if (isAdded()) reloadActiveTab();
                        });
                    } catch (Exception e) {
                        Tools.runOnUiThread(() -> {
                            if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final git.artdeell.mojo.content.ContentStudioActions.UiHost mActionsHost =
            new git.artdeell.mojo.content.ContentStudioActions.UiHost() {
                @Override
                public boolean isHostActive() {
                    return isAdded();
                }

                @Override
                @Nullable
                public Context getHostContext() {
                    return isAdded() ? getContext() : null;
                }

                @Override
                public void onContentMutated() {
                    if (isAdded()) reloadActiveTab();
                }
            };

    private void openConfigEditor(@NonNull InstanceContentManager.ContentItem item) {
        if (mInstance != null) {
            git.artdeell.mojo.content.ContentStudioActions.openConfigEditor(mActionsHost, mInstance, item);
        }
    }

    private void checkModUpdates() {
        if (mInstance != null) {
            git.artdeell.mojo.content.ContentStudioActions.checkModUpdates(mActionsHost, mInstance, mLoadedMods);
        }
    }

    private void showSnapshotMenu() {
        if (mInstance != null) {
            git.artdeell.mojo.content.ContentStudioActions.showSnapshotMenu(mActionsHost, mInstance);
        }
    }

    private void runBatchModUpdate() {
        if (mInstance != null) {
            git.artdeell.mojo.content.ContentStudioActions.runBatchModUpdate(mActionsHost, mInstance);
        }
    }

    private void showGameOptimizerDialog() {
        if (mInstance != null && isAdded()) {
            git.artdeell.mojo.content.ContentStudioActions.showGameOptimizerDialog(requireContext(), mInstance);
        }
    }

    private void triggerImport() {
        if (mInstance == null) return;
        if (mActiveKind == TabKind.DATAPACKS && mSelectedDatapackWorld == null) {
            Toast.makeText(requireContext(), "Create or import a world first to add datapacks.", Toast.LENGTH_LONG).show();
            return;
        }
        if (mActiveKind == TabKind.MODS) {
            mImportJarLauncher.launch(null);
        } else {
            mImportZipLauncher.launch(null);
        }
    }

    private void onFileImported(@Nullable Uri uri) {
        if (uri == null || mInstance == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final String fileName = queryDisplayName(appCtx, uri);
        final File targetDir;
        switch (mActiveKind) {
            case MODS:
                targetDir = new File(mInstance.getGameDirectory(), "mods");
                break;
            case RESOURCE_PACKS:
                targetDir = new File(mInstance.getGameDirectory(), "resourcepacks");
                break;
            case SHADER_PACKS:
                targetDir = new File(mInstance.getGameDirectory(), "shaderpacks");
                break;
            case DATAPACKS:
                if (mSelectedDatapackWorld == null) return;
                targetDir = new File(mSelectedDatapackWorld, "datapacks");
                break;
            case CONFIGS:
            default:
                targetDir = new File(mInstance.getGameDirectory(), "config");
                break;
        }
        final boolean snapshotBefore = (mActiveKind == TabKind.MODS || mActiveKind == TabKind.CONFIGS);
        final Instance instSnap = mInstance;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                if (snapshotBefore) {
                    git.artdeell.mojo.instances.InstanceBackupService.createPreModSnapshotQuietly(
                            instSnap, "local-mod-import");
                }
                try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("Cannot open selected file");
                    File imported = InstanceContentManager.importFileFromStream(
                            in, targetDir, fileName, MAX_IMPORT_BYTES);
                    Tools.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), "Imported " + imported.getName(), Toast.LENGTH_SHORT).show();
                        reloadActiveTab();
                    });
                }
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Import blocked: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @NonNull
    private static String queryDisplayName(@NonNull Context ctx, @NonNull Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "imported.jar";
    }
}
