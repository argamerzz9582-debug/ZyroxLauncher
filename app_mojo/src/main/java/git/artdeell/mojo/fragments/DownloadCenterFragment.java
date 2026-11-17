package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.DownloadQueueManager;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.NetworkPolicyHelper;

/**
 * Zyrox 2.0 Unified Download Manager, Cache Cleaner & Storage/Network Status Center.
 */
public class DownloadCenterFragment extends Fragment implements DownloadQueueManager.QueueListener {
    public static final String TAG = "DOWNLOAD_CENTER_FRAGMENT";

    private TextView mSystemHeader;
    private Button mBtnPauseResume;
    private LinearLayout mListContainer;
    private final android.os.Handler mUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean mUpdateScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long mLastUiUpdateMs = 0L;
    private final Runnable mCoalescedRender = () -> {
        mUpdateScheduled.set(false);
        mLastUiUpdateMs = android.os.SystemClock.uptimeMillis();
        if (isAdded()) renderQueue();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        mSystemHeader = ZyroxStudioUi.createSubtitle(ctx, "Checking download queue & free storage...");
        top.addView(mSystemHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnPkgStore = ZyroxStudioUi.createPrimaryButton(ctx, "JRE / Turnip");
        btnPkgStore.setOnClickListener(v ->
                git.artdeell.mojo.downloader.RuntimeDriverStoreDialog.showHub(requireContext(), null));
        top.addView(btnPkgStore, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnFpsPack = ZyroxStudioUi.createSecondaryButton(ctx, "FPS Boost", true);
        btnFpsPack.setOnClickListener(v ->
                SearchModFragment.promptInstallFpsBoostPack(requireContext(), null));
        top.addView(btnFpsPack, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        mBtnPauseResume = ZyroxStudioUi.createSecondaryButton(ctx, "Pause Queue", true);
        mBtnPauseResume.setOnClickListener(v -> {
            DownloadQueueManager mgr = DownloadQueueManager.getInstance();
            if (mgr.isPaused()) {
                mgr.resumeAll();
            } else {
                mgr.pauseAll();
            }
            renderQueue();
        });
        top.addView(mBtnPauseResume, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnCleanParts = ZyroxStudioUi.createSecondaryButton(ctx, "Clean Temp", true);
        btnCleanParts.setOnClickListener(v -> cleanPartialFiles());
        top.addView(btnCleanParts, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnClearHistory = ZyroxStudioUi.createDangerButton(ctx, "Clear Done", true);
        btnClearHistory.setOnClickListener(v -> {
            DownloadQueueManager.getInstance().clearHistory();
            renderQueue();
        });
        top.addView(btnClearHistory, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        panel.addView(top);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        mListContainer = new LinearLayout(ctx);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mListContainer);
        panel.addView(scroll, sLp);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        DownloadQueueManager.getInstance().addListener(this);
        renderQueue();
    }

    @Override
    public void onStop() {
        DownloadQueueManager.getInstance().removeListener(this);
        mUiHandler.removeCallbacks(mCoalescedRender);
        mUpdateScheduled.set(false);
        super.onStop();
    }

    @Override
    public void onQueueUpdated() {
        if (!mUpdateScheduled.compareAndSet(false, true)) return;
        long elapsed = android.os.SystemClock.uptimeMillis() - mLastUiUpdateMs;
        long delayMs = elapsed >= 200L ? 0L : (200L - elapsed);
        mUiHandler.postDelayed(mCoalescedRender, delayMs);
    }

    private void renderQueue() {
        Context ctx = getContext();
        if (ctx == null) return;
        DownloadQueueManager mgr = DownloadQueueManager.getInstance();
        mBtnPauseResume.setText(mgr.isPaused() ? "Resume Queue" : "Pause Queue");
        mBtnPauseResume.setEnabled(mgr.isPaused() || mgr.getActiveCount() > 0);

        NetworkPolicyHelper.ConnectionType net = NetworkPolicyHelper.detectConnection(ctx);
        long freeBytes = NetworkPolicyHelper.queryFreeDiskBytes(new File(Tools.DIR_GAME_HOME));
        String netLabel;
        switch (net) {
            case WIFI_OR_UNMETERED: netLabel = "Unmetered Wi-Fi"; break;
            case METERED_MOBILE:    netLabel = "Mobile / Metered Data"; break;
            case OFFLINE:           netLabel = "Offline"; break;
            default:                netLabel = "Network Ready"; break;
        }
        String verifyLabel = LauncherPreferences.PREF_VERIFY_FILES ? "SHA-1 Verify ON" : "SHA-1 Verify OFF";
        mSystemHeader.setText(netLabel + " • Free storage: "
                + (freeBytes >= 0 ? NetworkPolicyHelper.formatBytes(freeBytes) : "Unknown")
                + " • Threads: " + mgr.getConcurrencyLimit()
                + " • " + verifyLabel);

        List<DownloadQueueManager.DownloadRecord> items = new ArrayList<>();
        items.addAll(mgr.getActiveDownloads());
        items.addAll(mgr.getHistory());

        mListContainer.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No active or recent downloads.\nGame versions, assets, libraries, runtimes, mods, and modpacks automatically report SHA-1 verified progress, speed, ETA, pause/resume, and temp-file cleanup here.");
            empty.setPadding(ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24),
                    ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24));
            mListContainer.addView(empty);
            return;
        }

        for (DownloadQueueManager.DownloadRecord item : items) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            String badge = "[" + item.status.name() + "]  " + item.title;
            info.addView(ZyroxStudioUi.createTitle(ctx, badge));

            StringBuilder detail = new StringBuilder();
            detail.append(item.category.name()).append(" • ").append(item.getSummaryLine());
            if (item.redactedError != null && !item.redactedError.isEmpty()) {
                detail.append("\nReason: ").append(item.redactedError);
            }
            info.addView(ZyroxStudioUi.createSubtitle(ctx, detail.toString()));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (item.canPause()) {
                Button btnPause = ZyroxStudioUi.createSecondaryButton(ctx, "Pause", true);
                btnPause.setOnClickListener(v -> mgr.requestPause(item.id));
                row.addView(btnPause, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
            } else if (item.canResume()) {
                Button btnResume = ZyroxStudioUi.createSecondaryButton(ctx, "Resume", true);
                btnResume.setOnClickListener(v -> mgr.requestResume(item.id));
                row.addView(btnResume, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
            } else if (item.canRetry()) {
                Button btnRetry = ZyroxStudioUi.createPrimaryButton(ctx, "Retry");
                btnRetry.setOnClickListener(v -> mgr.retryFromHistory(item.id));
                row.addView(btnRetry, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
            }

            if (item.canCancel()) {
                Button btnCancel = ZyroxStudioUi.createDangerButton(ctx, "Cancel", true);
                btnCancel.setOnClickListener(v -> mgr.requestCancel(item.id));
                row.addView(btnCancel, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));
            }

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void cleanPartialFiles() {
        MojoApplication.sExecutorService.execute(() -> {
            int deleted = DownloadQueueManager.cleanupPartialFiles(Tools.DIR_CACHE);
            deleted += DownloadQueueManager.cleanupPartialFiles(new File(Tools.DIR_GAME_NEW));
            final int total = deleted;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        total > 0 ? ("Cleaned " + total + " leftover temporary .part/.tmp file(s).")
                                : "Storage is already clean — no orphaned partial files found.",
                        Toast.LENGTH_LONG).show();
                renderQueue();
            });
        });
    }
}
