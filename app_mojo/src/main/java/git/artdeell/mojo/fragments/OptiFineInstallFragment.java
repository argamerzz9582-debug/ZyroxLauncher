package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.content.ModrinthContentInstaller;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceInstaller;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.ModloaderListenerProxy;
import git.artdeell.mojo.modding.optifine.OptiFineDownloadTask;
import git.artdeell.mojo.modding.optifine.OptiFineScraper;
import git.artdeell.mojo.modding.optifine.OptiFineUtils;
import git.artdeell.mojo.modding.optifine.OptiFineVersionListAdapter;

/**
 * Resilient OptiFine installer screen with an automatic 1-Tap
 * <b>"Install Sodium + Iris (Recommended)"</b> upgrade path on Minecraft 1.16+ and whenever
 * OptiFine's HTML download portal is unreachable or breaks scraping.
 */
public class OptiFineInstallFragment extends ModVersionListFragment<OptiFineUtils.OptiFineVersions> {
    public static final String TAG = "OptiFineInstallFragment";

    private View mSodiumIrisCard;
    private TextView mSodiumIrisSubtitle;
    private Button mSodiumIrisButton;

    public OptiFineInstallFragment() {
        super(TAG);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSodiumIrisCard = view.findViewById(R.id.mod_dl_sodium_iris_card);
        mSodiumIrisSubtitle = view.findViewById(R.id.tv_sodium_iris_subtitle);
        mSodiumIrisButton = view.findViewById(R.id.btn_install_sodium_iris);

        Instance current = Instances.loadSelectedInstance();
        String activeVer = current != null ? current.versionId : null;
        if (mSodiumIrisCard != null && OptiFineScraper.recommendsSodiumIris(activeVer)) {
            mSodiumIrisCard.setVisibility(View.VISIBLE);
        }
        if (mSodiumIrisButton != null) {
            mSodiumIrisButton.setOnClickListener(v -> installSodiumIrisRecommended());
        }
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public int getTitleText() {
        return R.string.of_dl_select_version;
    }

    @Override
    public int getNoDataMsg() {
        return R.string.of_dl_failed_to_scrape;
    }

    @Override
    public OptiFineUtils.OptiFineVersions loadVersionList() throws IOException {
        return loadVersionList(false);
    }

    @Override
    public OptiFineUtils.OptiFineVersions loadVersionList(boolean forceRefresh) throws IOException {
        OptiFineUtils.OptiFineVersions versions = OptiFineUtils.downloadOptiFineVersions(forceRefresh);
        if (versions == null) {
            Tools.runOnUiThread(() -> promoteSodiumIrisFallback(
                    "OptiFine servers could not be scraped right now. Tap \"Install Sodium + Iris "
                            + "(Recommended)\" for instant Modrinth setup with higher mobile FPS."));
        }
        return versions;
    }

    @Override
    public void onDownloadError(Exception e) {
        super.onDownloadError(e);
        Tools.runOnUiThread(() -> promoteSodiumIrisFallback(
                "OptiFine mirror failed. Install Sodium + Iris from Modrinth in 1 tap instead."));
    }

    private void promoteSodiumIrisFallback(@NonNull String reason) {
        if (!isAdded()) return;
        if (mSodiumIrisCard != null) {
            mSodiumIrisCard.setVisibility(View.VISIBLE);
        }
        if (mSodiumIrisSubtitle != null) {
            mSodiumIrisSubtitle.setText(reason);
        }
    }

    private void installSodiumIrisRecommended() {
        Context ctx = getContext();
        if (ctx == null || !ProgressLayout.ensureNoOngoingTasks(ctx)) return;
        final Context appCtx = ctx.getApplicationContext();
        if (mSodiumIrisButton != null) {
            mSodiumIrisButton.setEnabled(false);
        }
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 15, R.string.zyrox_direct_installing);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Instance target = Instances.loadSelectedInstance();
                String resolvedLoader = ModrinthContentInstaller.detectInstanceLoader(target);
                if (target != null && ("fabric".equals(resolvedLoader)
                        || "quilt".equals(resolvedLoader)
                        || "neoforge".equals(resolvedLoader))) {
                    ModrinthContentInstaller.InstallSummary summary =
                            ModrinthContentInstaller.installZeroLagFpsPack(target, true, null);
                    Tools.runOnUiThread(() -> {
                        if (mSodiumIrisButton != null) mSodiumIrisButton.setEnabled(true);
                        Toast.makeText(appCtx, summary.message, Toast.LENGTH_LONG).show();
                        if (isAdded()) {
                            getParentFragmentManager().popBackStack();
                        }
                    });
                    return;
                }

                // Active instance is Vanilla or missing: auto-provision Fabric + Sodium + Iris!
                String mcVer = ModrinthContentInstaller.resolveModernGameVersion(
                        target != null ? target.versionId : null);
                String fabricVersionId = FabriclikeUtils.FABRIC_UTILS
                        .installLatestForGameVersion(mcVer);
                Instance created = Instances.createInstance(inst -> {
                    inst.name = "Sodium + Iris " + mcVer;
                    inst.versionId = fabricVersionId;
                    inst.performancePreset = Instance.PRESET_MAX_FPS;
                    inst.sharedData = false;
                }, "SodiumIris");
                Instances.setSelectedInstance(created);
                ModrinthContentInstaller.InstallSummary summary =
                        ModrinthContentInstaller.installZeroLagFpsPack(created, true, null);
                Tools.runOnUiThread(() -> {
                    if (mSodiumIrisButton != null) mSodiumIrisButton.setEnabled(true);
                    Toast.makeText(appCtx,
                            "Created \"" + created.name + "\" — " + summary.message,
                            Toast.LENGTH_LONG).show();
                    if (isAdded()) {
                        getParentFragmentManager().popBackStack();
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (mSodiumIrisButton != null) mSodiumIrisButton.setEnabled(true);
                    Toast.makeText(appCtx,
                            "Sodium + Iris setup failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        });
    }

    @Override
    public ExpandableListAdapter createAdapter(OptiFineUtils.OptiFineVersions versionList,
                                               LayoutInflater layoutInflater) {
        return new OptiFineVersionListAdapter(versionList, layoutInflater);
    }

    private void createInstance(OptiFineUtils.OptiFineVersion version,
                                ModloaderListenerProxy listenerProxy) {
        try {
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0);
            new OptiFineDownloadTask(version).prepareForInstall();
            InstanceInstaller instanceInstaller = OptiFineUtils.createInstaller(version);
            Instances.createInstance(instance -> {
                instance.name = "OptiFine " + (version.gameVersion != null
                        ? version.gameVersion.replace("Minecraft ", "") : "");
                instance.installer = instanceInstaller;
                instance.sharedData = true;
            }, "OptiFine");
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            instanceInstaller.start();
            listenerProxy.onDownloadFinished(null);
        } catch (Exception e) {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            listenerProxy.onDownloadError(e);
        }
    }

    @Override
    public Runnable createDownloadTask(Object selectedVersion, ModloaderListenerProxy listenerProxy) {
        return () -> createInstance((OptiFineUtils.OptiFineVersion) selectedVersion, listenerProxy);
    }

    @Override
    public void onDownloadFinished(Context context, File downloadedFile) {
    }
}
