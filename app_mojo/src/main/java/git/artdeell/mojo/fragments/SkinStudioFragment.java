package git.artdeell.mojo.fragments;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.SkinHeadRenderer;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.skins.SkinStudioManager;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Zyrox 2.3 Skin Studio — Interactive OpenGL ES 2.0 3D rotatable full-body player &amp; swaying cape
 * preview, Ely.by + OptiFine + local PNG cape switcher, 64×64 &amp; 64×32 PNG validation,
 * Classic (4px) / Slim (3px) arm toggle, and official Microsoft profile skin upload.
 */
public class SkinStudioFragment extends Fragment {
    public static final String TAG = "SKIN_STUDIO_FRAGMENT";
    private static final String PREF_CAPE_SOURCE = "zyrox_skin_cape_source";
    private static final String PREF_CAPE_USER = "zyrox_skin_cape_username";

    private File mLibraryDir;
    private SkinHeadRenderer.PlayerModelGlView mPreviewView;
    private TextView mPreviewCaption;
    private Button mBtnModelToggle;
    private Button mBtnCape;
    private Button mBtnUploadOnline;
    private LinearLayout mListContainer;
    @Nullable private SkinStudioManager.SkinEntry mSelectedSkin;
    @Nullable private Bitmap mPreviewBitmap;
    @Nullable private Bitmap mCapeBitmap;
    @NonNull private SkinHeadRenderer.CapeSource mActiveCapeSource = SkinHeadRenderer.CapeSource.NONE;

    private final ActivityResultLauncher<String[]> mSkinPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSkinPicked);
    private final ActivityResultLauncher<String[]> mCapePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onCapePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        // Two-column landscape-optimized studio split: Left = OpenGL 3D Character Viewport, Right = Library
        LinearLayout split = new LinearLayout(ctx);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left Column: OpenGL ES 2.0 3D Rotatable Player + Pose/Layer toggles + Cape Switcher + Upload
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f);
        lLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 10));

        FrameLayout viewportFrame = new FrameLayout(ctx);
        mPreviewView = new SkinHeadRenderer.PlayerModelGlView(ctx);
        viewportFrame.addView(mPreviewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating quick pose + 3D outer-layer pill chips in top-end corner of 3D viewport
        LinearLayout poseBar = new LinearLayout(ctx);
        poseBar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnPose = ZyroxStudioUi.createSecondaryButton(ctx, "Walk", true);
        btnPose.setOnClickListener(v -> {
            boolean next = !mPreviewView.isWalkAnimationEnabled();
            mPreviewView.setWalkAnimationEnabled(next);
            btnPose.setText(next ? "Walk" : "Idle");
        });
        poseBar.addView(btnPose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 28)));

        Button btnLayers = ZyroxStudioUi.createSecondaryButton(ctx, "3D Hat", true);
        btnLayers.setOnClickListener(v -> {
            boolean next = !mPreviewView.isShowOuterLayers();
            mPreviewView.setShowOuterLayers(next);
            btnLayers.setText(next ? "3D Hat" : "Base");
        });
        poseBar.addView(btnLayers, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 28), ZyroxStudioUi.dp(ctx, 4)));

        FrameLayout.LayoutParams poseLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        poseLp.setMargins(0, ZyroxStudioUi.dp(ctx, 4), ZyroxStudioUi.dp(ctx, 4), 0);
        viewportFrame.addView(poseBar, poseLp);

        left.addView(viewportFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mPreviewCaption = ZyroxStudioUi.createSubtitle(ctx, "Drag to orbit 360° • Pinch to zoom • Double-tap rear");
        mPreviewCaption.setPadding(0, ZyroxStudioUi.dp(ctx, 5), 0, ZyroxStudioUi.dp(ctx, 5));
        left.addView(mPreviewCaption);

        LinearLayout leftBtns = new LinearLayout(ctx);
        leftBtns.setOrientation(LinearLayout.HORIZONTAL);

        mBtnModelToggle = ZyroxStudioUi.createSecondaryButton(ctx, "Classic 4px", true);
        mBtnModelToggle.setOnClickListener(v -> toggleSelectedModel());
        leftBtns.addView(mBtnModelToggle, new LinearLayout.LayoutParams(0, ZyroxStudioUi.dp(ctx, 35), 1f));

        mBtnCape = ZyroxStudioUi.createSecondaryButton(ctx, "Cape ▾", true);
        mBtnCape.setOnClickListener(v -> showCapeSwitcherDialog());
        mBtnCape.setOnLongClickListener(v -> {
            clearCapePreview();
            return true;
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 35));
        cLp.setMarginStart(ZyroxStudioUi.dp(ctx, 5));
        leftBtns.addView(mBtnCape, cLp);

        mBtnUploadOnline = ZyroxStudioUi.createPrimaryButton(ctx, "Apply Online");
        mBtnUploadOnline.setOnClickListener(v -> confirmUploadOnline());
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(0, ZyroxStudioUi.dp(ctx, 35), 1f);
        uLp.setMarginStart(ZyroxStudioUi.dp(ctx, 5));
        leftBtns.addView(mBtnUploadOnline, uLp);

        left.addView(leftBtns);
        split.addView(left, lLp);

        // Right Column: Local Skin Library + Import PNG button + Account notice
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f);

        LinearLayout topRight = new LinearLayout(ctx);
        topRight.setOrientation(LinearLayout.HORIZONTAL);
        topRight.setGravity(Gravity.CENTER_VERTICAL);

        Account acct = Accounts.getCurrent();
        boolean isOnlineMs = acct != null && acct.isMicrosoft && !acct.isLocal();
        String acctNote = isOnlineMs
                ? ("Signed in: " + acct.username + " (Official online upload ready)")
                : "Local Skin Library (sign in with Microsoft for online profile sync)";
        topRight.addView(ZyroxStudioUi.createSubtitle(ctx, acctNote),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnImport = ZyroxStudioUi.createPrimaryButton(ctx, "+ Import PNG");
        btnImport.setOnClickListener(v -> mSkinPicker.launch(new String[]{"image/png"}));
        topRight.addView(btnImport, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 35), ZyroxStudioUi.dp(ctx, 6)));
        right.addView(topRight);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        mListContainer = new LinearLayout(ctx);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mListContainer);
        right.addView(scroll, scLp);

        split.addView(right, rLp);
        panel.addView(split);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mLibraryDir = SkinStudioManager.getLibraryDir(new File(Tools.DIR_GAME_HOME));
        mActiveCapeSource = SkinHeadRenderer.CapeSource.fromId(
                LauncherPreferences.DEFAULT_PREF.getString(PREF_CAPE_SOURCE, "none"));
        updateCapeButtonLabel();
        loadSavedCapePreview();
        refreshLibrary();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPreviewView != null) mPreviewView.onResume();
    }

    @Override
    public void onPause() {
        if (mPreviewView != null) mPreviewView.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (mPreviewBitmap != null && !mPreviewBitmap.isRecycled()) {
            mPreviewBitmap.recycle();
            mPreviewBitmap = null;
        }
        if (mCapeBitmap != null && !mCapeBitmap.isRecycled()) {
            mCapeBitmap.recycle();
            mCapeBitmap = null;
        }
        super.onDestroyView();
    }

    private void updateCapeButtonLabel() {
        if (mBtnCape == null) return;
        switch (mActiveCapeSource) {
            case ELY_BY:
                mBtnCape.setText("Ely.by ▾");
                break;
            case OPTIFINE:
                mBtnCape.setText("OptiFine ▾");
                break;
            case CUSTOM_LOCAL:
                mBtnCape.setText("PNG Cape ▾");
                break;
            default:
                mBtnCape.setText("Cape ▾");
                break;
        }
    }

    private void showCapeSwitcherDialog() {
        Context ctx = requireContext();
        String[] options = new String[]{
                "Ely.by Cape (Fetch via HTTPS)",
                "OptiFine Cape (Fetch via HTTPS)",
                "Import Custom Cape PNG",
                "Clear Cape (None)"
        };
        new AlertDialog.Builder(ctx)
                .setTitle("Cape Switcher • 3D Preview")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        promptRemoteCapeFetch(SkinHeadRenderer.CapeSource.ELY_BY);
                    } else if (which == 1) {
                        promptRemoteCapeFetch(SkinHeadRenderer.CapeSource.OPTIFINE);
                    } else if (which == 2) {
                        mCapePicker.launch(new String[]{"image/png"});
                    } else {
                        clearCapePreview();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptRemoteCapeFetch(@NonNull SkinHeadRenderer.CapeSource source) {
        Context ctx = requireContext();
        Account acct = Accounts.getCurrent();
        String savedUser = LauncherPreferences.DEFAULT_PREF.getString(
                PREF_CAPE_USER, acct != null && acct.username != null ? acct.username : "");

        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("Minecraft / " + source.displayLabel + " username");
        input.setText(savedUser);
        int pad = ZyroxStudioUi.dp(ctx, 18);
        LinearLayout box = new LinearLayout(ctx);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(ctx)
                .setTitle("Switch to " + source.displayLabel)
                .setMessage("Fetches the player's live cape over verified HTTPS and applies it to your 3D model.")
                .setView(box)
                .setPositiveButton("Apply Cape", (d, w) -> {
                    String username = input.getText() != null ? input.getText().toString().trim() : "";
                    fetchAndApplyRemoteCape(source, username);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchAndApplyRemoteCape(@NonNull SkinHeadRenderer.CapeSource source,
                                         @NonNull String username) {
        if (mLibraryDir == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final File capeOut = new File(mLibraryDir, "zyrox_cape_preview.png");
        Toast.makeText(appCtx, "Fetching " + source.displayLabel + "…", Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                Bitmap bmp = SkinHeadRenderer.fetchRemoteCapeBitmap(source, username);
                if (!mLibraryDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    mLibraryDir.mkdirs();
                }
                try (FileOutputStream out = new FileOutputStream(capeOut)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(PREF_CAPE_SOURCE, source.id)
                        .putString(PREF_CAPE_USER, username)
                        .apply();
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) {
                        bmp.recycle();
                        return;
                    }
                    applyCapeBitmapUi(bmp, source, true);
                    Toast.makeText(appCtx,
                            source.displayLabel + " applied for " + username + "!",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        e.getMessage() != null ? e.getMessage() : "Cape lookup failed",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void applyCapeBitmapUi(@Nullable Bitmap bmp,
                                   @NonNull SkinHeadRenderer.CapeSource source,
                                   boolean rotateToRear) {
        if (mCapeBitmap != null && !mCapeBitmap.isRecycled()) mCapeBitmap.recycle();
        mCapeBitmap = bmp;
        mActiveCapeSource = source;
        updateCapeButtonLabel();
        if (mPreviewView != null) {
            mPreviewView.setCape(bmp);
            if (rotateToRear && bmp != null) {
                mPreviewView.focusOnCape();
            }
        }
    }

    private void loadSavedCapePreview() {
        if (mLibraryDir == null) return;
        final File capeFile = new File(mLibraryDir, "zyrox_cape_preview.png");
        if (!capeFile.isFile()) return;
        MojoApplication.sExecutorService.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(capeFile.getAbsolutePath(), opts);
            if (bmp == null) return;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) {
                    bmp.recycle();
                    return;
                }
                SkinHeadRenderer.CapeSource src = mActiveCapeSource == SkinHeadRenderer.CapeSource.NONE
                        ? SkinHeadRenderer.CapeSource.CUSTOM_LOCAL : mActiveCapeSource;
                applyCapeBitmapUi(bmp, src, false);
            });
        });
    }

    private void onCapePicked(@Nullable Uri uri) {
        if (uri == null || mLibraryDir == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final File capeOut = new File(mLibraryDir, "zyrox_cape_preview.png");
        MojoApplication.sExecutorService.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot open PNG");
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inScaled = false;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap raw = BitmapFactory.decodeStream(in, null, opts);
                if (raw == null || raw.getWidth() < 22 || raw.getHeight() < 17 || raw.getWidth() > 2048) {
                    if (raw != null) raw.recycle();
                    throw new IllegalArgumentException("Expected a Minecraft cape PNG (e.g. 64×32)");
                }
                Bitmap bmp = SkinHeadRenderer.normalizeCapeAtlas(raw);
                if (!mLibraryDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    mLibraryDir.mkdirs();
                }
                try (FileOutputStream out = new FileOutputStream(capeOut)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(PREF_CAPE_SOURCE, SkinHeadRenderer.CapeSource.CUSTOM_LOCAL.id)
                        .apply();
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) {
                        bmp.recycle();
                        return;
                    }
                    applyCapeBitmapUi(bmp, SkinHeadRenderer.CapeSource.CUSTOM_LOCAL, true);
                    Toast.makeText(appCtx,
                            "Custom PNG cape applied to 3D model! Drag to orbit.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Cape import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void clearCapePreview() {
        if (mLibraryDir != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(mLibraryDir, "zyrox_cape_preview.png").delete();
        }
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(PREF_CAPE_SOURCE, SkinHeadRenderer.CapeSource.NONE.id)
                .apply();
        applyCapeBitmapUi(null, SkinHeadRenderer.CapeSource.NONE, false);
        Toast.makeText(requireContext(), "Cleared cape preview", Toast.LENGTH_SHORT).show();
    }

    private void refreshLibrary() {
        MojoApplication.sExecutorService.execute(() -> {
            List<SkinStudioManager.SkinEntry> skins = SkinStudioManager.listSkins(mLibraryDir);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                renderLibrary(skins);
            });
        });
    }

    private void renderLibrary(@NonNull List<SkinStudioManager.SkinEntry> skins) {
        Context ctx = requireContext();
        mListContainer.removeAllViews();

        if (skins.isEmpty()) {
            mSelectedSkin = null;
            updatePreview(null);
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "Your Skin Library is empty.\nTap '+ Import PNG' to validate and preview any 64×64 (Steve/Alex) or 64×32 (Classic) Minecraft skin PNG.");
            empty.setPadding(ZyroxStudioUi.dp(ctx, 10), ZyroxStudioUi.dp(ctx, 20),
                    ZyroxStudioUi.dp(ctx, 10), ZyroxStudioUi.dp(ctx, 20));
            mListContainer.addView(empty);
            return;
        }

        SkinStudioManager.SkinEntry nextSel = null;
        for (SkinStudioManager.SkinEntry s : skins) {
            if (mSelectedSkin != null && s.id.equals(mSelectedSkin.id)) {
                nextSel = s;
                break;
            }
            if (s.activeLocal && nextSel == null) nextSel = s;
        }
        mSelectedSkin = nextSel != null ? nextSel : skins.get(0);
        updatePreview(mSelectedSkin);

        for (SkinStudioManager.SkinEntry s : skins) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            String title = s.name + (s.activeLocal ? "  [ACTIVE]" : "");
            info.addView(ZyroxStudioUi.createTitle(ctx, title));
            info.addView(ZyroxStudioUi.createSubtitle(ctx,
                    s.width + "×" + s.height + " • " + s.getSkinModel().displayLabel));
            info.setOnClickListener(v -> {
                mSelectedSkin = s;
                updatePreview(s);
            });
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnUse = ZyroxStudioUi.createSecondaryButton(ctx, s.activeLocal ? "Selected" : "Select", true);
            btnUse.setOnClickListener(v -> activateSkin(s));
            row.addView(btnUse, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            Button btnDel = ZyroxStudioUi.createDangerButton(ctx, "Delete", true);
            btnDel.setOnClickListener(v -> deleteSkin(s));
            row.addView(btnDel, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void updatePreview(@Nullable SkinStudioManager.SkinEntry entry) {
        if (mPreviewBitmap != null && !mPreviewBitmap.isRecycled()) {
            mPreviewBitmap.recycle();
            mPreviewBitmap = null;
        }
        if (entry == null) {
            mPreviewView.setSkin(null, false);
            mPreviewCaption.setText("No skin selected • Drag 3D preview to orbit");
            mBtnModelToggle.setEnabled(false);
            mBtnUploadOnline.setEnabled(false);
            return;
        }
        File f = new File(mLibraryDir, entry.fileName);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        mPreviewBitmap = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        SkinStudioManager.SkinModel model = entry.getSkinModel();
        mPreviewView.setSkin(mPreviewBitmap, model == SkinStudioManager.SkinModel.SLIM);
        mPreviewCaption.setText(entry.name + " • " + entry.width + "×" + entry.height + " • " + model.displayLabel);
        mBtnModelToggle.setEnabled(entry.height == 64);
        mBtnModelToggle.setText(model == SkinStudioManager.SkinModel.SLIM ? "Slim 3px" : "Classic 4px");
        mBtnUploadOnline.setEnabled(true);
    }

    private void toggleSelectedModel() {
        SkinStudioManager.SkinEntry sel = mSelectedSkin;
        if (sel == null) return;
        SkinStudioManager.SkinModel next = sel.getSkinModel() == SkinStudioManager.SkinModel.CLASSIC
                ? SkinStudioManager.SkinModel.SLIM : SkinStudioManager.SkinModel.CLASSIC;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                SkinStudioManager.updateSkinModel(mLibraryDir, sel.id, next);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) refreshLibrary();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void activateSkin(@NonNull SkinStudioManager.SkinEntry entry) {
        mSelectedSkin = entry;
        final Account acct = Accounts.getCurrent();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                SkinStudioManager.setActiveLocalSkin(mLibraryDir, entry.id, Tools.DIR_CACHE, acct);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Selected \"" + entry.name + "\" in local library", Toast.LENGTH_SHORT).show();
                    refreshLibrary();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void deleteSkin(@NonNull SkinStudioManager.SkinEntry entry) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Skin \"" + entry.name + "\"?")
                .setMessage("Removes this PNG from your local Zyrox Skin Library. Online account skins are unaffected.")
                .setPositiveButton("Delete", (d, w) -> MojoApplication.sExecutorService.execute(() -> {
                    try {
                        SkinStudioManager.deleteSkin(mLibraryDir, entry.id);
                        Tools.runOnUiThread(() -> {
                            if (isAdded()) refreshLibrary();
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

    private void confirmUploadOnline() {
        SkinStudioManager.SkinEntry sel = mSelectedSkin;
        if (sel == null) return;
        Account acct = Accounts.getCurrent();
        if (acct == null || acct.isLocal() || !acct.isMicrosoft) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Offline / Local Account Notice")
                    .setMessage("Online skin upload uses Mojang's official api.minecraftservices.com profile API and requires a signed-in Microsoft Minecraft account.\n\n"
                            + "For offline/local play, your skin is saved in the local Skin Library; to display custom offline skins in-game, install a client skin mod such as OfflineSkins or CustomSkinLoader via the Mod Store.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Apply Skin to " + acct.username + "?")
                .setMessage("Upload \"" + sel.name + "\" (" + sel.getSkinModel().displayLabel
                        + ") to your official Minecraft Java Edition profile over HTTPS?")
                .setPositiveButton("Upload Skin", (d, w) -> MojoApplication.sExecutorService.execute(() -> {
                    try {
                        SkinStudioManager.uploadToOfficialMinecraftProfile(mLibraryDir, sel, acct);
                        Tools.runOnUiThread(() -> {
                            if (isAdded()) {
                                Toast.makeText(requireContext(),
                                        "Skin uploaded to " + acct.username + "'s official Minecraft profile!",
                                        Toast.LENGTH_LONG).show();
                            }
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

    private void onSkinPicked(@Nullable Uri uri) {
        if (uri == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        final String rawName = readDisplayName(appCtx, uri);
        MojoApplication.sExecutorService.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot open PNG file");
                SkinStudioManager.SkinEntry imported =
                        SkinStudioManager.importSkin(mLibraryDir, in, rawName, null);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mSelectedSkin = imported;
                    Toast.makeText(requireContext(),
                            "Imported " + imported.width + "×" + imported.height + " skin (" + imported.getSkinModel().displayLabel + ")",
                            Toast.LENGTH_SHORT).show();
                    refreshLibrary();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @NonNull
    private static String readDisplayName(@NonNull Context ctx, @NonNull Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return "Custom Skin";
    }
}
