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
import java.io.InputStream;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.skins.SkinStudioManager;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Zyrox 2.0 Skin Studio — Validates 64×64 & 64×32 PNG skins, renders dual-layer front/back
 * character previews, supports Classic (Steve 4px) and Slim (Alex 3px) arm models, manages a
 * persistent local library, and uploads cleanly to official Microsoft Minecraft profiles.
 */
public class SkinStudioFragment extends Fragment {
    public static final String TAG = "SKIN_STUDIO_FRAGMENT";

    private File mLibraryDir;
    private ZyroxStudioUi.SkinCharacterPreviewView mPreviewView;
    private TextView mPreviewCaption;
    private Button mBtnModelToggle;
    private Button mBtnUploadOnline;
    private LinearLayout mListContainer;
    @Nullable private SkinStudioManager.SkinEntry mSelectedSkin;
    @Nullable private Bitmap mPreviewBitmap;

    private final ActivityResultLauncher<String[]> mSkinPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSkinPicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        // Two-column landscape-optimized studio split: Left = Live Character Preview, Right = Skin Library
        LinearLayout split = new LinearLayout(ctx);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left Column: Dual-layer Front/Back Character Preview + Model & Upload actions
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f);
        lLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 10));

        mPreviewView = new ZyroxStudioUi.SkinCharacterPreviewView(ctx);
        left.addView(mPreviewView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mPreviewCaption = ZyroxStudioUi.createSubtitle(ctx, "No skin selected");
        mPreviewCaption.setPadding(0, ZyroxStudioUi.dp(ctx, 5), 0, ZyroxStudioUi.dp(ctx, 5));
        left.addView(mPreviewCaption);

        LinearLayout leftBtns = new LinearLayout(ctx);
        leftBtns.setOrientation(LinearLayout.HORIZONTAL);

        mBtnModelToggle = ZyroxStudioUi.createSecondaryButton(ctx, "Classic 4px", true);
        mBtnModelToggle.setOnClickListener(v -> toggleSelectedModel());
        leftBtns.addView(mBtnModelToggle, new LinearLayout.LayoutParams(0, ZyroxStudioUi.dp(ctx, 35), 1f));

        mBtnUploadOnline = ZyroxStudioUi.createPrimaryButton(ctx, "Apply Online");
        mBtnUploadOnline.setOnClickListener(v -> confirmUploadOnline());
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(0, ZyroxStudioUi.dp(ctx, 35), 1f);
        uLp.setMarginStart(ZyroxStudioUi.dp(ctx, 6));
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
        refreshLibrary();
    }

    @Override
    public void onDestroyView() {
        if (mPreviewBitmap != null && !mPreviewBitmap.isRecycled()) {
            mPreviewBitmap.recycle();
            mPreviewBitmap = null;
        }
        super.onDestroyView();
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
            mPreviewView.setSkin(null, SkinStudioManager.SkinModel.CLASSIC);
            mPreviewCaption.setText("No skin selected");
            mBtnModelToggle.setEnabled(false);
            mBtnUploadOnline.setEnabled(false);
            return;
        }
        File f = new File(mLibraryDir, entry.fileName);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        mPreviewBitmap = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        SkinStudioManager.SkinModel model = entry.getSkinModel();
        mPreviewView.setSkin(mPreviewBitmap, model);
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
