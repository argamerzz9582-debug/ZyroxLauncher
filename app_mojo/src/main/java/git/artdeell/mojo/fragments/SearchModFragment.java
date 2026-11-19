package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.runOnUiThread;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.ModItemAdapter;
import git.artdeell.mojo.modding.modpacks.SelfReferencingFuture;
import git.artdeell.mojo.modding.modpacks.api.CommonApi;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.TaskCountListener;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;

public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private TextView mProviderBannerView;
    private ColorStateList mDefaultTextColor;
    private ModpackApi modpackApi;
    private final SearchFilters mSearchFilters;
    private Button mImportButton;
    private TaskCountListener mTaskCountListener;

    // Content type, Platform & Category filter chips
    private String mActiveContentType = git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MODPACK;
    private TextView[] mContentTypeTabs;
    private TextView[] mPlatformChips;
    private TextView[] mCategoryChips;

    // Slide-over Right Glass Version Drawer
    private View mVersionDrawer;
    private View mVersionScrim;
    private TextView mDrawerTitle;
    private TextView mDrawerSummary;
    private TextView mDrawerError;
    private ProgressBar mDrawerProgress;
    private RecyclerView mDrawerVersionList;
    private Future<?> mDetailFuture;

    ActivityResultLauncher<Object> mImportLauncher = registerForActivityResult(
            new git.artdeell.mojo.contracts.OpenDocumentWithExtension("mrpack", "zip"),
            uri -> {
                if (uri == null || getContext() == null) return;
                Context context = getContext();
                ContentResolver contentResolver = context.getContentResolver();
                MojoApplication.sExecutorService.execute(() -> performLocalInstall(uri, context, contentResolver));
            });

    public void triggerLocalImport() {
        if (!ProgressLayout.ensureNoOngoingTasks(requireContext())) return;
        mImportLauncher.launch(null);
    }

    public boolean handleBackPressed() {
        if (mVersionDrawer != null && mVersionDrawer.getVisibility() == View.VISIBLE) {
            closeVersionDrawer();
            return true;
        }
        return false;
    }

    public void performLocalInstall(Uri uri, Context context, ContentResolver contentResolver) {
        String fileName = git.artdeell.mojo.contracts.OpenDocumentWithExtension.resolveFileName(context, uri);
        if (fileName == null || fileName.isEmpty()) return;
        if (!git.artdeell.mojo.contracts.OpenDocumentWithExtension.hasAllowedExtension(
                fileName, "mrpack", "zip")) {
            final String badName = fileName;
            runOnUiThread(() -> Toast.makeText(
                    context.getApplicationContext(),
                    "Unsupported modpack file \"" + badName + "\". Expected .mrpack or .zip",
                    Toast.LENGTH_LONG).show());
            return;
        }
        File outFile = new File(Tools.DIR_CACHE, fileName + ".cf");
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, R.string.multirt_progress_caching);
        try (InputStream inputStream = contentResolver.openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(outFile)) {
            if (inputStream == null) return;
            IOUtils.copy(inputStream, outputStream);
            outputStream.flush();
        } catch (IOException e) {
            Tools.showErrorRemote("Error", e);
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            return;
        }
        try {
            modpackApi.installLocalModpack(fileName, outFile, null);
        } catch (IOException e) {
            Tools.showErrorRemote("Error", e);
        } finally {
            outFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    public SearchModFragment() {
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.isModpack = true;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        modpackApi = new CommonApi(context.getString(R.string.curseforge_api_key));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        mModItemAdapter.setOnModClickListener(this::openVersionDrawer);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);

        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mProviderBannerView = view.findViewById(R.id.tv_store_provider_banner);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        mImportButton = view.findViewById(R.id.mineButton_import_local_modpack);

        // Version slide-over drawer bindings
        mVersionDrawer = view.findViewById(R.id.zyrox_version_drawer);
        mVersionScrim = view.findViewById(R.id.zyrox_version_drawer_scrim);
        mDrawerTitle = view.findViewById(R.id.tv_drawer_mod_title);
        mDrawerSummary = view.findViewById(R.id.tv_drawer_mod_summary);
        mDrawerError = view.findViewById(R.id.tv_drawer_error);
        mDrawerProgress = view.findViewById(R.id.pb_drawer_versions);
        mDrawerVersionList = view.findViewById(R.id.rv_drawer_versions);
        mDrawerVersionList.setLayoutManager(new LinearLayoutManager(getContext()));
        mDrawerVersionList.setHasFixedSize(true);
        mDrawerVersionList.setItemViewCacheSize(12);
        mDrawerVersionList.setItemAnimator(null);
        view.findViewById(R.id.btn_close_version_drawer).setOnClickListener(v -> closeVersionDrawer());
        mVersionScrim.setOnClickListener(v -> closeVersionDrawer());

        mDefaultTextColor = mStatusTextView.getTextColors();

        // 2-Column Horizontal Mod Grid
        GridLayoutManager gridManager = new GridLayoutManager(getContext(), 2);
        gridManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mModItemAdapter.getItemViewType(position) == ModItemAdapter.VIEW_TYPE_LOADING ? 2 : 1;
            }
        });
        mRecyclerview.setLayoutManager(gridManager);
        mRecyclerview.setHasFixedSize(true);
        mRecyclerview.setItemViewCacheSize(16);
        mRecyclerview.setItemAnimator(null);
        mRecyclerview.setAdapter(mModItemAdapter);

        setupFilterChips(view);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mFilterButton.setOnClickListener(v -> displayFilterDialog());
        mImportButton.setOnClickListener(v -> triggerLocalImport());
        mTaskCountListener = taskCount -> {
            runOnUiThread(() -> {
                if (isAdded() && mImportButton != null) mImportButton.setEnabled(taskCount == 0);
            });
            return false;
        };
        ProgressKeeper.addTaskCountListener(mTaskCountListener);

        searchMods(null);
    }

    private void setupFilterChips(@NonNull View view) {
        TextView tModpacks = view.findViewById(R.id.tab_store_modpacks);
        TextView tMods = view.findViewById(R.id.tab_store_mods);
        TextView tShaders = view.findViewById(R.id.tab_store_shaders);
        TextView tResources = view.findViewById(R.id.tab_store_resourcepacks);
        TextView btnFpsBoost = view.findViewById(R.id.btn_store_fps_boost);
        mContentTypeTabs = new TextView[]{tModpacks, tMods, tShaders, tResources};
        selectChip(mContentTypeTabs, tModpacks);

        if (tModpacks != null) {
            tModpacks.setOnClickListener(v -> applyContentType(
                    tModpacks, git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MODPACK));
        }
        if (tMods != null) {
            tMods.setOnClickListener(v -> applyContentType(
                    tMods, git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MOD));
        }
        if (tShaders != null) {
            tShaders.setOnClickListener(v -> applyContentType(
                    tShaders, git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_SHADER));
        }
        if (tResources != null) {
            tResources.setOnClickListener(v -> applyContentType(
                    tResources, git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_RESOURCEPACK));
        }
        if (btnFpsBoost != null) {
            btnFpsBoost.setOnClickListener(v -> promptInstallFpsBoostPack(requireContext(), null));
        }

        TextView pAll = view.findViewById(R.id.btn_platform_all);
        TextView pModrinth = view.findViewById(R.id.btn_platform_modrinth);
        TextView pCurse = view.findViewById(R.id.btn_platform_curseforge);
        mPlatformChips = new TextView[]{pAll, pModrinth, pCurse};
        selectChip(mPlatformChips, pAll);

        pAll.setOnClickListener(v -> applyPlatform(pAll, SearchFilters.PLATFORM_ALL));
        pModrinth.setOnClickListener(v -> applyPlatform(pModrinth, SearchFilters.PLATFORM_MODRINTH));
        pCurse.setOnClickListener(v -> {
            if ("DUMMY".equals(getString(R.string.curseforge_api_key))) {
                Toast.makeText(requireContext(), "CurseForge key not set — showing Modrinth catalog", Toast.LENGTH_SHORT).show();
                applyPlatform(pModrinth, SearchFilters.PLATFORM_MODRINTH);
                return;
            }
            applyPlatform(pCurse, SearchFilters.PLATFORM_CURSEFORGE);
        });

        TextView cAll = view.findViewById(R.id.chip_filter_all);
        TextView cOpt = view.findViewById(R.id.chip_filter_optimization);
        TextView cPacks = view.findViewById(R.id.chip_filter_modpacks);
        TextView cFabric = view.findViewById(R.id.chip_filter_fabric);
        TextView cForge = view.findViewById(R.id.chip_filter_forge);
        TextView cQuilt = view.findViewById(R.id.chip_filter_quilt);
        TextView cNeo = view.findViewById(R.id.chip_filter_neoforge);
        TextView cShaders = view.findViewById(R.id.chip_filter_mods);

        mCategoryChips = new TextView[]{cAll, cOpt, cPacks, cFabric, cForge, cQuilt, cNeo, cShaders};
        selectChip(mCategoryChips, cAll);

        cAll.setOnClickListener(v -> applyCategory(cAll, null, null));
        cOpt.setOnClickListener(v -> applyCategory(cOpt, "optimization", null));
        cPacks.setOnClickListener(v -> applyCategory(cPacks, null, "modpack"));
        cFabric.setOnClickListener(v -> applyCategory(cFabric, "fabric", null));
        cForge.setOnClickListener(v -> applyCategory(cForge, "forge", null));
        cQuilt.setOnClickListener(v -> applyCategory(cQuilt, "quilt", null));
        cNeo.setOnClickListener(v -> applyCategory(cNeo, "neoforge", null));
        cShaders.setOnClickListener(v -> {
            if (mContentTypeTabs != null && mContentTypeTabs.length > 2 && mContentTypeTabs[2] != null) {
                applyContentType(mContentTypeTabs[2],
                        git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_SHADER);
            } else {
                selectChip(mCategoryChips, cShaders);
                mSearchFilters.category = null;
                mSearchFilters.projectTypeOverride = git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_SHADER;
                searchMods(mSearchEditText.getText().toString().trim());
            }
        });
    }

    private void applyContentType(@NonNull TextView tab, @NonNull String contentType) {
        mActiveContentType = contentType;
        if (mContentTypeTabs != null) {
            selectChip(mContentTypeTabs, tab);
        }
        git.artdeell.mojo.instances.Instance active = git.artdeell.mojo.instances.Instances.loadSelectedInstance();
        String instMc = active != null
                ? git.artdeell.mojo.content.ModrinthContentInstaller.extractMinecraftVersion(active.versionId) : null;
        String instLoader = active != null
                ? git.artdeell.mojo.content.ModrinthContentInstaller.extractModLoader(active.versionId) : null;

        if (git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MODPACK.equals(contentType)) {
            mSearchFilters.isModpack = true;
            mSearchFilters.projectTypeOverride = null;
            mSearchFilters.category = null;
            mSearchFilters.mcVersion = null;
            mSearchFilters.platform = SearchFilters.PLATFORM_ALL;
            if (mPlatformChips != null && mPlatformChips.length > 0) {
                selectChip(mPlatformChips, mPlatformChips[0]);
            }
        } else {
            mSearchFilters.isModpack = false;
            mSearchFilters.projectTypeOverride = contentType;
            mSearchFilters.platform = SearchFilters.PLATFORM_MODRINTH;
            if (mPlatformChips != null && mPlatformChips.length > 1) {
                selectChip(mPlatformChips, mPlatformChips[1]);
            }
            mSearchFilters.mcVersion = instMc;
            mSearchFilters.category = git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MOD.equals(contentType)
                    ? instLoader : null;
            if (active != null && isAdded()) {
                String targetName = active.name != null && !active.name.isEmpty() ? active.name : active.versionId;
                Toast.makeText(requireContext(),
                        "Target instance: " + targetName + (instMc != null ? (" (" + instMc + ")") : ""),
                        Toast.LENGTH_SHORT).show();
            }
        }
        if (mCategoryChips != null && mCategoryChips.length > 0) {
            selectChip(mCategoryChips, mCategoryChips[0]);
        }
        searchMods(mSearchEditText.getText().toString());
    }

    /** Shared 1-Click FPS Boost Pack dialog callable from both Store and Content Manager. */
    public static void promptInstallFpsBoostPack(@NonNull Context ctx, @Nullable Runnable onFinishedUi) {
        final git.artdeell.mojo.instances.Instance inst = git.artdeell.mojo.instances.Instances.loadSelectedInstance();
        if (inst == null) {
            Toast.makeText(ctx, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        String mc = git.artdeell.mojo.content.ModrinthContentInstaller.extractMinecraftVersion(inst.versionId);
        String loader = git.artdeell.mojo.content.ModrinthContentInstaller.extractModLoader(inst.versionId);
        if (mc == null || loader == null) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Mod Loader Needed for FPS Boost")
                    .setMessage("Active instance (" + (inst.versionId != null ? inst.versionId : "Vanilla")
                            + ") is plain Vanilla.\n\nInstall Fabric, Quilt, NeoForge, or Forge on this instance first, then tap FPS Boost to auto-install Sodium, Lithium, FerriteCore, Entity Culling, and ImmediatelyFast!")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final boolean[] includeIris = {true};
        new AlertDialog.Builder(ctx)
                .setTitle("1-Click FPS Boost Pack (" + loader.toUpperCase(java.util.Locale.ROOT) + " " + mc + ")")
                .setMultiChoiceItems(
                        new CharSequence[]{"Include Iris / Oculus Shader Support"},
                        new boolean[]{true},
                        (d, which, checked) -> includeIris[0] = checked)
                .setPositiveButton("Install Stack", (d, w) -> {
                    final Context appCtx = ctx.getApplicationContext();
                    Toast.makeText(appCtx, "Downloading verified FPS Boost mods…", Toast.LENGTH_SHORT).show();
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 10, R.string.zyrox_fps_boost_installing);
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            git.artdeell.mojo.content.ModrinthContentInstaller.InstallSummary sum =
                                    git.artdeell.mojo.content.ModrinthContentInstaller.installZeroLagFpsPack(
                                            inst, includeIris[0], null);
                            runOnUiThread(() -> {
                                Toast.makeText(appCtx, sum.message, Toast.LENGTH_LONG).show();
                                if (onFinishedUi != null) onFinishedUi.run();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(appCtx,
                                    "FPS Boost: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        } finally {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void selectChip(TextView[] group, TextView active) {
        for (TextView tv : group) {
            if (tv != null) tv.setSelected(tv == active);
        }
    }

    private void applyPlatform(TextView chip, int platform) {
        selectChip(mPlatformChips, chip);
        mSearchFilters.platform = platform;
        searchMods(mSearchEditText.getText().toString());
    }

    private void applyCategory(TextView chip, @Nullable String category, @Nullable String projectType) {
        selectChip(mCategoryChips, chip);
        mSearchFilters.category = category;
        mSearchFilters.projectTypeOverride = projectType;
        searchMods(mSearchEditText.getText().toString());
    }

    private void openVersionDrawer(@NonNull ModItem item) {
        if (mDetailFuture != null) {
            mDetailFuture.cancel(true);
            mDetailFuture = null;
        }
        mDrawerTitle.setText(item.title);
        mDrawerSummary.setText(item.description);
        mDrawerError.setVisibility(View.GONE);
        mDrawerProgress.setVisibility(View.VISIBLE);
        mDrawerVersionList.setAdapter(null);

        mVersionScrim.setVisibility(View.VISIBLE);
        mVersionDrawer.setVisibility(View.VISIBLE);
        if (git.artdeell.mojo.prefs.LauncherPreferences.isReducedMotionEnabled(requireContext())) {
            mVersionDrawer.setTranslationX(0f);
        } else {
            mVersionDrawer.setTranslationX(mVersionDrawer.getWidth() > 0 ? mVersionDrawer.getWidth() : 400f);
            mVersionDrawer.animate().translationX(0f).setDuration(220L).start();
        }

        mDetailFuture = new SelfReferencingFuture(myFuture -> {
            ModDetail detail = modpackApi.getModDetails(item);
            runOnUiThread(() -> {
                if (myFuture.isCancelled() || !isAdded()) return;
                mDetailFuture = null;
                mDrawerProgress.setVisibility(View.GONE);
                if (detail == null || detail.versionNames == null || detail.versionNames.length == 0) {
                    mDrawerError.setVisibility(View.VISIBLE);
                    return;
                }
                mDrawerVersionList.setAdapter(new VersionDrawerAdapter(item, detail));
            });
        }).startOnExecutor(MojoApplication.sExecutorService);
    }

    private void closeVersionDrawer() {
        if (mDetailFuture != null) {
            mDetailFuture.cancel(true);
            mDetailFuture = null;
        }
        if (mVersionScrim != null) mVersionScrim.setVisibility(View.GONE);
        if (mVersionDrawer != null) mVersionDrawer.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        if (mDetailFuture != null) {
            mDetailFuture.cancel(true);
            mDetailFuture = null;
        }
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        if (mTaskCountListener != null) {
            ProgressKeeper.removeTaskCountListener(mTaskCountListener);
        }
    }

    private void syncProviderFallbackBanner() {
        if (mProviderBannerView == null || mModItemAdapter == null) return;
        String notice = mModItemAdapter.getActiveFallbackNotice();
        if (notice != null && !notice.trim().isEmpty()) {
            mProviderBannerView.setText(notice);
            mProviderBannerView.setVisibility(View.VISIBLE);
        } else {
            mProviderBannerView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onSearchFinished() {
        if (!isAdded()) return;
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
        syncProviderFallbackBanner();
    }

    @Override
    public void onSearchError(int error) {
        if (!isAdded()) return;
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        syncProviderFallbackBanner();
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.zyrox_danger));
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mStatusTextView.setVisibility(View.GONE);
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);

            if (mSelectVersionButton == null || mSelectedVersion == null || mApplyButton == null) {
                return;
            }

            mSelectVersionButton.setOnClickListener(v ->
                    VersionSelectorDialog.open(v.getContext(), true, (id, snapshot) -> mSelectedVersion.setText(id)));
            mSelectedVersion.setText(mSearchFilters.mcVersion);

            mApplyButton.setOnClickListener(v -> {
                mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }

    private class VersionDrawerAdapter extends RecyclerView.Adapter<VersionDrawerAdapter.VH> {
        private final ModItem mItem;
        private final ModDetail mDetail;
        private final List<String> mNames;

        VersionDrawerAdapter(ModItem item, ModDetail detail) {
            mItem = item;
            mDetail = detail;
            mNames = detail.versionNames != null ? Arrays.asList(detail.versionNames) : Collections.emptyList();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_zyrox_mod_version, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.versionName.setText(mNames.get(position));
            holder.installBtn.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                if (!ProgressLayout.ensureNoOngoingTasks(v.getContext())) return;
                final String targetType = mActiveContentType;
                if (!git.artdeell.mojo.content.ModrinthContentInstaller.TYPE_MODPACK.equals(targetType)) {
                    final git.artdeell.mojo.instances.Instance active =
                            git.artdeell.mojo.instances.Instances.loadSelectedInstance();
                    if (active == null) {
                        Toast.makeText(v.getContext(), R.string.no_instance, Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String dlUrl = (mDetail.versionUrls != null && pos < mDetail.versionUrls.length)
                            ? mDetail.versionUrls[pos] : null;
                    final String sha1 = (mDetail.versionHashes != null && pos < mDetail.versionHashes.length)
                            ? mDetail.versionHashes[pos] : null;
                    if (dlUrl == null || dlUrl.isEmpty()) {
                        Toast.makeText(v.getContext(), "Download URL unavailable for this version", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final Context appCtx = v.getContext().getApplicationContext();
                    mModItemAdapter.markModQueued(mItem);
                    closeVersionDrawer();
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 25, R.string.zyrox_direct_installing);
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            git.artdeell.mojo.content.ModrinthContentInstaller.InstallSummary summary =
                                    git.artdeell.mojo.content.ModrinthContentInstaller.installDirectArtifact(
                                            active, targetType, mItem.id, mItem.title, dlUrl, sha1, null);
                            runOnUiThread(() -> Toast.makeText(appCtx, summary.message, Toast.LENGTH_LONG).show());
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(appCtx,
                                    "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        } finally {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        }
                    });
                    return;
                }
                mModItemAdapter.markModQueued(mItem);
                modpackApi.handleModpackInstallation(
                        v.getContext().getApplicationContext(),
                        mDetail,
                        pos
                );
                closeVersionDrawer();
            });
        }

        @Override
        public int getItemCount() {
            return mNames.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView versionName;
            final Button installBtn;

            VH(@NonNull View itemView) {
                super(itemView);
                versionName = itemView.findViewById(R.id.tv_mod_version_name);
                installBtn = itemView.findViewById(R.id.btn_mod_version_install);
            }
        }
    }
}
