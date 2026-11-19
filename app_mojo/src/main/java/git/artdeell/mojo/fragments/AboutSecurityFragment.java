package git.artdeell.mojo.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.diagnostics.GraphicsAdvisor;
import git.artdeell.mojo.input.ControlProfileManager;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.security.AppTrustVerifier;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Zyrox 2.0 Security & Verification Center + Graphics Advisor + Control Profile Studio.
 *
 * <p>Surfaces real runtime APK signing SHA-256 verification, honest Google Play Protect and
 * sideloading transparency, zero-telemetry privacy documentation, open-source credits, user-
 * confirmed Graphics Center profiles with hardware diagnostics, and Control Profile management.
 */
public class AboutSecurityFragment extends Fragment {
    public static final String TAG = "ABOUT_SECURITY_FRAGMENT";

    private int mActiveTab = 0;
    private Button mTabSecurity, mTabGraphics, mTabControls;
    private LinearLayout mBodyContainer;

    private final ActivityResultLauncher<String[]> mImportControlLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onControlImported);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);

        mTabSecurity = ZyroxStudioUi.createTabButton(ctx, "Security & Trust");
        mTabSecurity.setOnClickListener(v -> switchTab(0));
        tabRow.addView(mTabSecurity, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), 0));

        mTabGraphics = ZyroxStudioUi.createTabButton(ctx, "Graphics Center");
        mTabGraphics.setOnClickListener(v -> switchTab(1));
        tabRow.addView(mTabGraphics, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        mTabControls = ZyroxStudioUi.createTabButton(ctx, "Control Profiles");
        mTabControls.setOnClickListener(v -> switchTab(2));
        tabRow.addView(mTabControls, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        panel.addView(tabRow);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        mBodyContainer = new LinearLayout(ctx);
        mBodyContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mBodyContainer);
        panel.addView(scroll, sLp);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        int startTab = getArguments() != null ? getArguments().getInt("tab", 0) : 0;
        switchTab(startTab);
    }

    private void switchTab(int idx) {
        mActiveTab = idx;
        mTabSecurity.setSelected(idx == 0);
        mTabGraphics.setSelected(idx == 1);
        mTabControls.setSelected(idx == 2);
        if (idx == 0) renderSecurityTab();
        else if (idx == 1) renderGraphicsTab();
        else renderControlProfilesTab();
    }

    private void renderSecurityTab() {
        Context ctx = requireContext();
        mBodyContainer.removeAllViews();
        AppTrustVerifier.TrustReport report = AppTrustVerifier.inspect(ctx);

        // Card 1: Official Zyrox Brand Crest + Live APK Signing Fingerprint & Build Authenticity
        LinearLayout signCard = ZyroxStudioUi.createItemCard(ctx);
        LinearLayout brandRow = new LinearLayout(ctx);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logoIv = new android.widget.ImageView(ctx);
        logoIv.setImageResource(git.artdeell.mojo.R.drawable.ic_zyrox_logo);
        logoIv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int logoSz = ZyroxStudioUi.dp(ctx, 40);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSz, logoSz);
        logoLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 10));
        brandRow.addView(logoIv, logoLp);
        brandRow.addView(ZyroxStudioUi.createTitle(ctx, "Zyrox Launcher • APK Integrity & Signing Certificate"));
        signCard.addView(brandRow);
        signCard.addView(ZyroxStudioUi.createSubtitle(ctx, report.buildCopyableSummary()));

        git.artdeell.mojo.updater.AppUpdateChecker.ReleaseInfo cachedUpd =
                git.artdeell.mojo.updater.AppUpdateChecker.getAvailableUpdate(ctx);
        if (cachedUpd != null && cachedUpd.updateAvailable) {
            signCard.addView(ZyroxStudioUi.createBadge(
                    ctx, "⬆ Update Available: " + cachedUpd.tagName + " — ready for 1-tap install", false));
        }

        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);

        String updBtnLabel = (cachedUpd != null && cachedUpd.updateAvailable)
                ? ("⬆ Update to " + cachedUpd.tagName)
                : "Check Updates";
        Button btnCheckUpd = ZyroxStudioUi.createPrimaryButton(ctx, updBtnLabel);
        btnCheckUpd.setOnClickListener(v -> {
            if (cachedUpd != null && cachedUpd.updateAvailable && getActivity() != null) {
                git.artdeell.mojo.updater.AppUpdateChecker.markAutoPromptShown(cachedUpd.tagName);
                git.artdeell.mojo.updater.AppUpdateChecker.showUpdateDialog(requireActivity(), cachedUpd);
            } else {
                git.artdeell.mojo.updater.AppUpdateChecker.checkNowInteractive(requireContext());
            }
        });
        actRow.addView(btnCheckUpd, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), 0));

        Button btnCopySha = ZyroxStudioUi.createSecondaryButton(ctx, "Copy Cert SHA-256", true);
        btnCopySha.setOnClickListener(v -> copyText("zyrox_cert_sha256", report.certSha256Colon,
                "Copied signing certificate SHA-256 to clipboard"));
        actRow.addView(btnCopySha, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnReleases = ZyroxStudioUi.createSecondaryButton(ctx, "Official Releases", true);
        btnReleases.setOnClickListener(v -> Tools.openURL(requireActivity(), AppTrustVerifier.OFFICIAL_RELEASES_URL));
        actRow.addView(btnReleases, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnSecurity = ZyroxStudioUi.createSecondaryButton(ctx, "Security Docs", true);
        btnSecurity.setOnClickListener(v -> Tools.openURL(requireActivity(), AppTrustVerifier.OFFICIAL_SECURITY_URL));
        actRow.addView(btnSecurity, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        android.widget.HorizontalScrollView actScroll = new android.widget.HorizontalScrollView(ctx);
        actScroll.setHorizontalScrollBarEnabled(false);
        actScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        actScroll.addView(actRow);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        signCard.addView(actScroll, aLp);
        mBodyContainer.addView(signCard);

        // Card 2: Honest Android & Google Play Protect Guidance
        LinearLayout ppCard = ZyroxStudioUi.createItemCard(ctx);
        ppCard.addView(ZyroxStudioUi.createTitle(ctx, "Google Play Protect & Safe Installation Guide"));
        ppCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "• Download Zyrox Launcher ONLY from the official GitHub Releases page and compare the APK SHA-256 checksum plus signing certificate SHA-256 above.\n"
                        + "• Keep Google Play Protect enabled on your device. Never install modified/re-signed APKs from third-party repost sites.\n"
                        + "• Note: Sideloaded open-source Java/JVM launchers load OpenJDK runtimes, native OpenGL/Vulkan renderers, and user-selected Minecraft/mod JARs, which Android may occasionally prompt to scan as a newly published app — Zyrox never bypasses or disables Android security checks."));
        mBodyContainer.addView(ppCard);

        // Card 3: Privacy, Zero Telemetry & Open-Source Credits
        LinearLayout privCard = ZyroxStudioUi.createItemCard(ctx);
        privCard.addView(ZyroxStudioUi.createTitle(ctx, "Privacy, Zero Telemetry & Open-Source Attribution"));
        privCard.addView(ZyroxStudioUi.createSubtitle(ctx,
                "• Zero hidden telemetry, ad SDKs, analytics trackers, or silent background log uploads.\n"
                        + "• Exported crash reports and logs automatically redact access tokens, refresh tokens, bearer headers, emails, and UUIDs.\n"
                        + "• Network requests contact only official Minecraft/Microsoft login & asset endpoints, Modrinth/CurseForge content APIs, and user-entered multiplayer servers over verified HTTPS/SLP.\n"
                        + "• Open-Source License: LGPL-3.0 • Built upon the open-source MojoLauncher / PojavLauncher ecosystem with original Zyrox Launcher 2.0 engineering. Minecraft is a trademark of Mojang AB / Microsoft (not affiliated)."));
        mBodyContainer.addView(privCard);
    }

    private void renderGraphicsTab() {
        Context ctx = requireContext();
        mBodyContainer.removeAllViews();

        // Card 1: Hardware & Renderer Capability Report + Perf HUD Toggle
        LinearLayout diagCard = ZyroxStudioUi.createItemCard(ctx);
        diagCard.addView(ZyroxStudioUi.createTitle(ctx, "Device GPU, Vulkan & Renderer Intelligence"));
        final String summary = GraphicsAdvisor.buildDiagnosticsSummary(ctx);
        diagCard.addView(ZyroxStudioUi.createSubtitle(ctx, summary));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = ZyroxStudioUi.dp(ctx, 8);

        Button btnCopyDiag = ZyroxStudioUi.createSecondaryButton(ctx, "Copy Diagnostics", true);
        btnCopyDiag.setOnClickListener(v -> copyText("zyrox_gpu_diag", summary, "Copied redacted graphics diagnostics"));
        row.addView(btnCopyDiag, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), 0));

        Button btnHud = ZyroxStudioUi.createSecondaryButton(ctx,
                LauncherPreferences.PREF_ZYROX_PERF_HUD ? "In-Game HUD: ON" : "In-Game HUD: OFF", true);
        btnHud.setOnClickListener(v -> {
            boolean next = !LauncherPreferences.PREF_ZYROX_PERF_HUD;
            LauncherPreferences.PREF_ZYROX_PERF_HUD = next;
            if (LauncherPreferences.DEFAULT_PREF != null) {
                LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxPerfHud", next).apply();
            }
            renderGraphicsTab();
        });
        row.addView(btnHud, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnPkgStore = ZyroxStudioUi.createPrimaryButton(ctx, "JRE / Turnip");
        btnPkgStore.setOnClickListener(v ->
                git.artdeell.mojo.downloader.RuntimeDriverStoreDialog.showHub(requireContext(), this::renderGraphicsTab));
        row.addView(btnPkgStore, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        diagCard.addView(row, rLp);
        mBodyContainer.addView(diagCard);

        // Card 2..6: User-confirmed Graphics Profiles
        for (GraphicsAdvisor.Profile p : GraphicsAdvisor.Profile.values()) {
            boolean active = p.id.equalsIgnoreCase(LauncherPreferences.PREF_ZYROX_GRAPHICS_PROFILE);
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout hRow = new LinearLayout(ctx);
            hRow.setOrientation(LinearLayout.HORIZONTAL);
            hRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ZyroxStudioUi.createTitle(ctx, p.title + (active ? "  [ACTIVE]" : "")));
            info.addView(ZyroxStudioUi.createSubtitle(ctx, p.description));
            hRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button apply = active
                    ? ZyroxStudioUi.createSecondaryButton(ctx, "Active", true)
                    : ZyroxStudioUi.createPrimaryButton(ctx, "Apply");
            apply.setOnClickListener(v -> confirmApplyProfile(p));
            hRow.addView(apply, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 8)));

            card.addView(hRow);
            mBodyContainer.addView(card);
        }
    }

    private void confirmApplyProfile(@NonNull GraphicsAdvisor.Profile profile) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Apply " + profile.title + " Profile?")
                .setMessage(profile.description + "\n\nUpdates resolution scale (" + profile.resolutionPercent
                        + "%), VSync (" + (profile.forceVsync ? "ON" : "OFF")
                        + "), and launcher visual overhead. Your worlds, mods, and instances stay untouched.")
                .setPositiveButton("Apply Profile", (d, w) -> {
                    GraphicsAdvisor.applyProfile(profile);
                    Toast.makeText(requireContext(), "Applied " + profile.title + " profile", Toast.LENGTH_SHORT).show();
                    renderGraphicsTab();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderControlProfilesTab() {
        Context ctx = requireContext();
        mBodyContainer.removeAllViews();

        LinearLayout headerCard = ZyroxStudioUi.createItemCard(ctx);
        LinearLayout hRow = new LinearLayout(ctx);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        hRow.setGravity(Gravity.CENTER_VERTICAL);
        hRow.addView(ZyroxStudioUi.createSubtitle(ctx,
                        "Liquid Glass Touch & Gamepad Control Profiles (per-instance binding supported in Instance Studio)"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import JSON", true);
        btnImport.setOnClickListener(v -> mImportControlLauncher.launch(new String[]{"application/json", "*/*"}));
        hRow.addView(btnImport, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnGamepad = ZyroxStudioUi.createSecondaryButton(ctx, "Gamepad", true);
        btnGamepad.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null));
        hRow.addView(btnGamepad, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnEditor = ZyroxStudioUi.createPrimaryButton(ctx, "HUD Editor");
        btnEditor.setOnClickListener(v -> startActivity(new Intent(ctx, CustomControlsActivity.class)));
        hRow.addView(btnEditor, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        headerCard.addView(hRow);
        mBodyContainer.addView(headerCard);

        File ctrlDir = new File(Tools.CTRLMAP_PATH);
        List<ControlProfileManager.ProfileSummary> profiles =
                ControlProfileManager.listProfiles(ctrlDir, LauncherPreferences.PREF_DEFAULTCTRL_PATH);
        for (ControlProfileManager.ProfileSummary p : profiles) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ZyroxStudioUi.createTitle(ctx, p.displayName + (p.isDefault ? "  [DEFAULT]" : "")));
            info.addView(ZyroxStudioUi.createSubtitle(ctx, p.fileName + " • " + p.buttonCount + " Liquid Glass controls"));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnAudit = ZyroxStudioUi.createSecondaryButton(ctx, "Audit", true);
            btnAudit.setOnClickListener(v -> auditControlProfile(p));
            row.addView(btnAudit, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnClone = ZyroxStudioUi.createSecondaryButton(ctx, "Clone", true);
            btnClone.setOnClickListener(v -> cloneControlProfile(p, ctrlDir));
            row.addView(btnClone, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            if (!p.isDefault) {
                Button btnDef = ZyroxStudioUi.createSecondaryButton(ctx, "Set Default", true);
                btnDef.setOnClickListener(v -> {
                    LauncherPreferences.PREF_DEFAULTCTRL_PATH = p.file.getAbsolutePath();
                    if (LauncherPreferences.DEFAULT_PREF != null) {
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putString("defaultCtrl", p.file.getAbsolutePath()).apply();
                    }
                    renderControlProfilesTab();
                });
                row.addView(btnDef, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));
            }

            card.addView(row);
            mBodyContainer.addView(card);
        }
    }

    private void auditControlProfile(@NonNull ControlProfileManager.ProfileSummary profile) {
        MojoApplication.sExecutorService.execute(() -> {
            try (FileInputStream in = new FileInputStream(profile.file)) {
                String json = new String(ArchiveSecurity.readBytesBounded(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
                ControlProfileManager.LayoutAuditReport report = ControlProfileManager.validateAndAuditJson(json);
                StringBuilder sb = new StringBuilder();
                sb.append("Controls: ").append(report.totalButtons)
                        .append(" • Schema v").append(report.version).append('\n');
                if (report.isClean()) {
                    sb.append("\n[OK] Zero keybind conflicts, healthy touch target dimensions, and valid dynamic landscape coordinates.");
                } else {
                    for (String c : report.keybindConflicts) sb.append("\n• Conflict: ").append(c);
                    for (String s : report.smallTargetWarnings) sb.append("\n• Touch size: ").append(s);
                    for (String f : report.formulaWarnings) sb.append("\n• Position: ").append(f);
                }
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Control Audit — " + profile.displayName)
                            .setMessage(sb.toString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void cloneControlProfile(@NonNull ControlProfileManager.ProfileSummary profile,
                                     @NonNull File ctrlDir) {
        EditText input = ZyroxStudioUi.createGlassSearchInput(requireContext(), "New profile name");
        input.setText(profile.displayName + "_Copy");
        new AlertDialog.Builder(requireContext())
                .setTitle("Clone Control Profile")
                .setView(input)
                .setPositiveButton("Clone", (d, w) -> {
                    String name = input.getText().toString().trim();
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            File created = ControlProfileManager.duplicateProfile(profile.file, ctrlDir, name);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), "Created " + created.getName(), Toast.LENGTH_SHORT).show();
                                renderControlProfilesTab();
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onControlImported(@Nullable Uri uri) {
        if (uri == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final File ctrlDir = new File(Tools.CTRLMAP_PATH);
        MojoApplication.sExecutorService.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot open file");
                File out = ControlProfileManager.importProfile(in, ctrlDir, "ImportedLayout");
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Imported profile " + out.getName(), Toast.LENGTH_SHORT).show();
                    if (mActiveTab == 2) renderControlProfilesTab();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void copyText(@NonNull String label, @NonNull String text, @NonNull String toastMsg) {
        if (git.artdeell.mojo.security.LogRedactor.copyRedactedToClipboard(
                requireContext(), label, text)) {
            Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show();
        }
    }
}
