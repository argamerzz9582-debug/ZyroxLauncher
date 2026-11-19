package git.artdeell.mojo.updater;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.R;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * High-Contrast Liquid Glass "What's New" modal dialog powering Zyrox Launcher's GitHub Releases
 * in-app auto-updater, complete with formatted Markdown changelog, live download progress &amp;
 * transfer speed, optional SHA-256 checksum verification, Android 8+ unknown-sources permission
 * flow, and 1-tap {@link androidx.core.content.FileProvider} installation.
 */
public class AppUpdateDialogFragment extends DialogFragment {

    public static final String TAG = "AppUpdateDialogFragment";

    private static final String ARG_TAG = "tag_name";
    private static final String ARG_VERSION = "version_clean";
    private static final String ARG_TITLE = "title";
    private static final String ARG_BODY = "body";
    private static final String ARG_HTML_URL = "html_url";
    private static final String ARG_APK_URL = "apk_url";
    private static final String ARG_APK_NAME = "apk_name";
    private static final String ARG_ABI = "matched_abi";
    private static final String ARG_SIZE = "apk_size";
    private static final String ARG_SHA256 = "expected_sha256";
    private static final String ARG_SUM_URL = "checksum_url";

    private AppUpdateChecker.ReleaseInfo mRelease;
    private View mProgressContainer;
    private TextView mProgressStatus;
    private TextView mSpeedText;
    private TextView mPercentText;
    private ProgressBar mProgressBar;
    private Button mSkipBtn;
    private Button mLaterBtn;
    private Button mUpdateNowBtn;

    @Nullable
    private AtomicBoolean mActiveCancel;
    @Nullable
    private File mVerifiedApkFile;
    private boolean mAwaitingInstallPermission = false;

    private final ActivityResultLauncher<Intent> mInstallPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                mAwaitingInstallPermission = false;
                Context ctx = getContext();
                if (ctx == null || mVerifiedApkFile == null) return;
                if (AppUpdateChecker.canInstallPackages(ctx)) {
                    triggerSystemInstall(ctx, mVerifiedApkFile);
                } else {
                    Toast.makeText(ctx,
                            "Enable \"Install unknown apps\" for Zyrox Launcher, then tap Install Now.",
                            Toast.LENGTH_LONG).show();
                }
            });

    @NonNull
    public static AppUpdateDialogFragment newInstance(@NonNull AppUpdateChecker.ReleaseInfo info) {
        AppUpdateDialogFragment frag = new AppUpdateDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TAG, info.tagName);
        args.putString(ARG_VERSION, info.versionClean);
        args.putString(ARG_TITLE, info.title);
        args.putString(ARG_BODY, info.body);
        args.putString(ARG_HTML_URL, info.htmlUrl);
        args.putString(ARG_APK_URL, info.apkDownloadUrl);
        args.putString(ARG_APK_NAME, info.apkAssetName);
        args.putString(ARG_ABI, info.matchedAbi);
        args.putLong(ARG_SIZE, info.apkSizeBytes);
        args.putString(ARG_SHA256, info.expectedSha256);
        args.putString(ARG_SUM_URL, info.checksumAssetUrl);
        frag.setArguments(args);
        return frag;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_app_update, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = getArguments();
        if (a == null) {
            dismissAllowingStateLoss();
            return;
        }
        mRelease = new AppUpdateChecker.ReleaseInfo(
                a.getString(ARG_TAG, ""),
                a.getString(ARG_VERSION, ""),
                a.getString(ARG_TITLE, "Zyrox Launcher Update"),
                a.getString(ARG_BODY, ""),
                a.getString(ARG_HTML_URL, AppUpdateChecker.RELEASES_PAGE),
                a.getString(ARG_APK_URL, null),
                a.getString(ARG_APK_NAME, "ZyroxLauncher.apk"),
                a.getString(ARG_ABI, "universal"),
                a.getLong(ARG_SIZE, 0L),
                a.getString(ARG_SHA256, null),
                a.getString(ARG_SUM_URL, null),
                true
        );

        TextView titleView = view.findViewById(R.id.tv_update_title);
        TextView metaView = view.findViewById(R.id.tv_update_meta);
        TextView badgeView = view.findViewById(R.id.tv_update_version_badge);
        TextView changelogView = view.findViewById(R.id.tv_update_changelog);

        mProgressContainer = view.findViewById(R.id.layout_update_progress);
        mProgressStatus = view.findViewById(R.id.tv_update_progress_status);
        mSpeedText = view.findViewById(R.id.tv_update_speed);
        mPercentText = view.findViewById(R.id.tv_update_percent);
        mProgressBar = view.findViewById(R.id.pb_update_download);
        mSkipBtn = view.findViewById(R.id.btn_update_skip);
        mLaterBtn = view.findViewById(R.id.btn_update_later);
        mUpdateNowBtn = view.findViewById(R.id.btn_update_now);

        titleView.setText(mRelease.title);
        String newTag = mRelease.tagName.startsWith("v") || mRelease.tagName.startsWith("V")
                ? mRelease.tagName : ("v" + mRelease.versionClean);
        badgeView.setText("v" + BuildConfig.VERSION_NAME + "  →  " + newTag);

        StringBuilder meta = new StringBuilder();
        if (mRelease.apkSizeBytes > 0L) {
            meta.append(ZyroxStudioUi.formatBytes(mRelease.apkSizeBytes));
        } else {
            meta.append("GitHub Release APK");
        }
        meta.append("  ·  ").append(mRelease.matchedAbi);
        if (mRelease.expectedSha256 != null || mRelease.checksumAssetUrl != null) {
            meta.append("  ·  SHA-256 Verified");
        }
        metaView.setText(meta.toString());

        changelogView.setText(AppUpdateChecker.formatMarkdownChangelog(mRelease.body));

        mSkipBtn.setOnClickListener(v -> {
            cancelActiveDownload();
            AppUpdateChecker.skipVersion(requireContext(), mRelease.tagName);
            Toast.makeText(requireContext(),
                    "Skipped " + newTag + " — check manually anytime in Security & About.",
                    Toast.LENGTH_SHORT).show();
            dismissAllowingStateLoss();
        });

        mLaterBtn.setOnClickListener(v -> {
            if (mActiveCancel != null && !mActiveCancel.get()) {
                cancelActiveDownload();
                return;
            }
            AppUpdateChecker.snoozeUpdatePrompt(requireContext());
            dismissAllowingStateLoss();
        });

        mUpdateNowBtn.setOnClickListener(v -> onUpdateNowClicked());
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        DisplayMetrics dm = requireContext().getResources().getDisplayMetrics();
        int targetWidth = Math.min(
                Math.round(560f * dm.density),
                Math.round(dm.widthPixels * 0.86f));
        window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAwaitingInstallPermission && mVerifiedApkFile != null && getContext() != null
                && AppUpdateChecker.canInstallPackages(getContext())) {
            mAwaitingInstallPermission = false;
            triggerSystemInstall(getContext(), mVerifiedApkFile);
        }
    }

    @Override
    public void onDestroyView() {
        cancelActiveDownload();
        super.onDestroyView();
    }

    private void onUpdateNowClicked() {
        Context ctx = getContext();
        if (ctx == null) return;

        // If the APK is already downloaded & verified, jump straight to installation
        if (mVerifiedApkFile != null && mVerifiedApkFile.isFile()) {
            proceedToInstall(ctx, mVerifiedApkFile);
            return;
        }

        if (!AppUpdateChecker.isDeviceOnline(ctx)) {
            Toast.makeText(ctx,
                    "You appear to be offline — connect to Wi-Fi or mobile data to download this update.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // If the release has no downloadable .apk asset, fall back to opening the HTTPS release page
        if (!AppUpdateChecker.isTrustedGitHubDownloadUrl(mRelease.apkDownloadUrl)) {
            AppUpdateChecker.openHttpsUrl(ctx, mRelease.htmlUrl);
            dismissAllowingStateLoss();
            return;
        }

        setCancelable(false);
        mUpdateNowBtn.setEnabled(false);
        mUpdateNowBtn.setText("Downloading…");
        mSkipBtn.setVisibility(View.INVISIBLE);
        mLaterBtn.setText("Cancel");
        mProgressContainer.setVisibility(View.VISIBLE);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setProgress(0);
        mProgressStatus.setText("Connecting to GitHub Releases…");
        mSpeedText.setText("");
        mPercentText.setText("0%");

        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        mActiveCancel = cancelFlag;

        AppUpdateChecker.downloadUpdateApkAsync(ctx, mRelease, cancelFlag,
                new AppUpdateChecker.ApkDownloadListener() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes,
                                           double bytesPerSec, @NonNull String phaseLabel) {
                        if (!isAdded()) return;
                        mProgressStatus.setText(phaseLabel);
                        mSpeedText.setText(AppUpdateChecker.formatSpeed(bytesPerSec));
                        if (totalBytes > 0L) {
                            int permille = (int) Math.max(0L,
                                    Math.min(1000L, (downloadedBytes * 1000L) / totalBytes));
                            mProgressBar.setIndeterminate(false);
                            mProgressBar.setProgress(permille);
                            mPercentText.setText(String.format(Locale.ROOT, "%d%%", permille / 10));
                        } else {
                            mProgressBar.setIndeterminate(true);
                            mPercentText.setText("");
                        }
                    }

                    @Override
                    public void onCompleted(@NonNull File verifiedApkFile, boolean sha256Verified) {
                        mActiveCancel = null;
                        mVerifiedApkFile = verifiedApkFile;
                        if (!isAdded()) return;
                        setCancelable(true);
                        mProgressBar.setIndeterminate(false);
                        mProgressBar.setProgress(1000);
                        mPercentText.setText("100%");
                        mSpeedText.setText(sha256Verified ? "SHA-256 OK" : "Verified");
                        mProgressStatus.setText("Download ready — launching installer…");
                        mLaterBtn.setText("Close");
                        mUpdateNowBtn.setEnabled(true);
                        mUpdateNowBtn.setText("Install Now");
                        proceedToInstall(requireContext(), verifiedApkFile);
                    }

                    @Override
                    public void onCancelled() {
                        mActiveCancel = null;
                        if (!isAdded()) return;
                        resetToIdleState("Update download cancelled.");
                    }

                    @Override
                    public void onFailed(@NonNull String errorMessage) {
                        mActiveCancel = null;
                        if (!isAdded()) return;
                        resetToIdleState("Error: " + errorMessage);
                        mUpdateNowBtn.setText("Retry Update");
                    }
                });
    }

    private void proceedToInstall(@NonNull Context ctx, @NonNull File apkFile) {
        if (!AppUpdateChecker.canInstallPackages(ctx)) {
            mAwaitingInstallPermission = true;
            mProgressStatus.setText("Grant install permission to complete update");
            Toast.makeText(ctx,
                    "Allow Zyrox Launcher to install this verified GitHub update.",
                    Toast.LENGTH_LONG).show();
            try {
                mInstallPermissionLauncher.launch(
                        AppUpdateChecker.createInstallPermissionSettingsIntent(ctx));
            } catch (Throwable primaryErr) {
                try {
                    mInstallPermissionLauncher.launch(
                            new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
                } catch (Throwable fallbackErr) {
                    mAwaitingInstallPermission = false;
                    Toast.makeText(ctx,
                            "Enable \"Install unknown apps\" in Android Settings → Apps → Zyrox Launcher.",
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        triggerSystemInstall(ctx, apkFile);
    }

    private void triggerSystemInstall(@NonNull Context ctx, @NonNull File apkFile) {
        try {
            AppUpdateChecker.launchApkInstaller(ctx, apkFile);
        } catch (Exception e) {
            Toast.makeText(ctx,
                    "Failed to open installer: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void cancelActiveDownload() {
        if (mActiveCancel != null) {
            mActiveCancel.set(true);
            mActiveCancel = null;
        }
    }

    private void resetToIdleState(@NonNull String statusText) {
        setCancelable(true);
        mProgressBar.setIndeterminate(false);
        mProgressStatus.setText(statusText);
        mSpeedText.setText("");
        mSkipBtn.setVisibility(View.VISIBLE);
        mLaterBtn.setText("Later");
        mUpdateNowBtn.setEnabled(true);
        mUpdateNowBtn.setText("Update Now");
    }
}
