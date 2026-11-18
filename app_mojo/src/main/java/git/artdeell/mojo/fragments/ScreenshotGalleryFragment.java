package git.artdeell.mojo.fragments;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.ui.ZyroxStudioUi;

/**
 * Zyrox Screenshots Gallery Studio — browse, preview in full resolution, save to Android Photos
 * ({@link MediaStore}), share via {@link FileProvider}, and manage in-game {@code F2} screenshots.
 */
public class ScreenshotGalleryFragment extends Fragment {
    public static final String TAG = "SCREENSHOT_GALLERY_FRAGMENT";

    private TextView mStatusLine;
    private LinearLayout mListContainer;
    private boolean mShowAllInstances = false;
    private Button mBtnScope;
    private int mLoadGeneration = 0;
    private final List<Bitmap> mActiveThumbnails = new ArrayList<>();

    private static final class ShotEntry {
        @NonNull final File file;
        @NonNull final String instanceName;
        final long modifiedMs;
        final long sizeBytes;

        ShotEntry(@NonNull File file, @NonNull String instanceName) {
            this.file = file;
            this.instanceName = instanceName;
            this.modifiedMs = file.lastModified();
            this.sizeBytes = file.length();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = ZyroxStudioUi.createTitle(ctx, "Screenshots Studio");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mBtnScope = ZyroxStudioUi.createSecondaryButton(ctx, "All Instances", true);
        mBtnScope.setOnClickListener(v -> {
            mShowAllInstances = !mShowAllInstances;
            mBtnScope.setText(mShowAllInstances ? "Active Only" : "All Instances");
            reloadGallery();
        });
        header.addView(mBtnScope, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        Button btnRefresh = ZyroxStudioUi.createPrimaryButton(ctx, "Refresh");
        btnRefresh.setOnClickListener(v -> reloadGallery());
        header.addView(btnRefresh, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        panel.addView(header);

        mStatusLine = ZyroxStudioUi.createSubtitle(ctx, "Scanning screenshots…");
        mStatusLine.setPadding(ZyroxStudioUi.dp(ctx, 2), ZyroxStudioUi.dp(ctx, 6), 0, ZyroxStudioUi.dp(ctx, 6));
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
        reloadGallery();
    }

    @Override
    public void onDestroyView() {
        mLoadGeneration++;
        recycleThumbnails();
        super.onDestroyView();
    }

    private void recycleThumbnails() {
        for (Bitmap bmp : mActiveThumbnails) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
        mActiveThumbnails.clear();
    }

    private void reloadGallery() {
        if (!isAdded()) return;
        final int gen = ++mLoadGeneration;
        recycleThumbnails();
        mListContainer.removeAllViews();
        final boolean allScope = mShowAllInstances;

        MojoApplication.sExecutorService.execute(() -> {
            List<ShotEntry> entries = collectScreenshots(allScope);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || gen != mLoadGeneration) return;
                renderEntries(entries, gen);
            });
        });
    }

    @NonNull
    private static List<ShotEntry> collectScreenshots(boolean allInstances) {
        List<ShotEntry> out = new ArrayList<>();
        Instance active = Instances.loadSelectedInstance();
        if (!allInstances && active != null) {
            String label = active.name != null && !active.name.isEmpty() ? active.name : "Active Instance";
            scanDir(new File(active.getGameDirectory(), "screenshots"), label, out);
        } else {
            if (active != null) {
                scanDir(new File(active.getGameDirectory(), "screenshots"),
                        active.name != null ? active.name : "Default", out);
            }
            if (Tools.DIR_GAME_NEW != null) {
                scanDir(new File(Tools.DIR_GAME_NEW, "screenshots"), "Shared", out);
            }
            File profilesRoot = Tools.DIR_GAME_HOME != null ? new File(Tools.DIR_GAME_HOME, "instances") : null;
            File[] dirs = profilesRoot != null ? profilesRoot.listFiles(File::isDirectory) : null;
            if (dirs != null) {
                for (File d : dirs) {
                    scanDir(new File(d, "screenshots"), d.getName(), out);
                }
            }
        }
        // Deduplicate by canonical/absolute path & sort newest first
        List<ShotEntry> unique = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ShotEntry e : out) {
            if (seen.add(e.file.getAbsolutePath())) unique.add(e);
        }
        Collections.sort(unique, (a, b) -> Long.compare(b.modifiedMs, a.modifiedMs));
        return unique.size() > 80 ? new ArrayList<>(unique.subList(0, 80)) : unique;
    }

    private static void scanDir(@Nullable File dir, @NonNull String label, @NonNull List<ShotEntry> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles(f -> {
            if (!f.isFile() || f.length() <= 0) return false;
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp");
        });
        if (files == null) return;
        for (File f : files) {
            out.add(new ShotEntry(f, label));
        }
    }

    private void renderEntries(@NonNull List<ShotEntry> entries, int gen) {
        Context ctx = requireContext();
        long totalBytes = 0L;
        for (ShotEntry e : entries) totalBytes += e.sizeBytes;
        mStatusLine.setText(entries.size() + " screenshot(s) • "
                + ZyroxStudioUi.formatBytes(totalBytes)
                + " • Capture in-game anytime via F2 or Quick Macros");

        if (entries.isEmpty()) {
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No screenshots captured yet.\nIn-game, open Quick Settings or Quick Macros and tap 'Screenshot (F2)' — every capture appears here automatically!");
            int p = ZyroxStudioUi.dp(ctx, 16);
            empty.setPadding(p, p, p, p);
            mListContainer.addView(empty);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault());
        int thumbW = ZyroxStudioUi.dp(ctx, 92);
        int thumbH = ZyroxStudioUi.dp(ctx, 54);

        for (ShotEntry entry : entries) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView thumb = new ImageView(ctx);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.bg_zyrox_glass_card_soft);
            thumb.setOnClickListener(v -> showFullPreview(entry));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(thumbW, thumbH);
            tLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 10));
            row.addView(thumb, tLp);
            loadThumbnailAsync(entry.file, thumb, thumbW, thumbH, gen);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ZyroxStudioUi.createTitle(ctx, entry.file.getName()));
            info.addView(ZyroxStudioUi.createSubtitle(ctx,
                    sdf.format(new Date(entry.modifiedMs)) + " • "
                            + ZyroxStudioUi.formatBytes(entry.sizeBytes) + " • " + entry.instanceName));
            info.setOnClickListener(v -> showFullPreview(entry));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnSave = ZyroxStudioUi.createSecondaryButton(ctx, "Gallery", true);
            btnSave.setOnClickListener(v -> saveToPhoneGallery(entry.file));
            row.addView(btnSave, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            Button btnShare = ZyroxStudioUi.createPrimaryButton(ctx, "Share");
            btnShare.setOnClickListener(v -> shareScreenshot(entry.file));
            row.addView(btnShare, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            Button btnDel = ZyroxStudioUi.createDangerButton(ctx, "Del", true);
            btnDel.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                    .setTitle("Delete Screenshot?")
                    .setMessage(entry.file.getName())
                    .setPositiveButton("Delete", (d, w) -> {
                        if (entry.file.delete()) reloadGallery();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            row.addView(btnDel, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void loadThumbnailAsync(@NonNull File file, @NonNull ImageView target,
                                    int reqW, int reqH, int gen) {
        MojoApplication.sExecutorService.execute(() -> {
            Bitmap bmp = decodeBounded(file, reqW, reqH, Bitmap.Config.RGB_565);
            if (bmp == null) return;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || gen != mLoadGeneration) {
                    bmp.recycle();
                    return;
                }
                mActiveThumbnails.add(bmp);
                target.setImageBitmap(bmp);
            });
        });
    }

    private void showFullPreview(@NonNull ShotEntry entry) {
        final Context ctx = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            Bitmap preview = decodeBounded(entry.file, 1280, 720, Bitmap.Config.ARGB_8888);
            if (preview == null) return;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) {
                    preview.recycle();
                    return;
                }
                ImageView iv = new ImageView(ctx);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = ZyroxStudioUi.dp(ctx, 10);
                iv.setPadding(pad, pad, pad, pad);
                iv.setImageBitmap(preview);
                AlertDialog dlg = new AlertDialog.Builder(ctx)
                        .setTitle(entry.file.getName())
                        .setView(iv)
                        .setPositiveButton("Save to Gallery", (d, w) -> saveToPhoneGallery(entry.file))
                        .setNeutralButton("Share", (d, w) -> shareScreenshot(entry.file))
                        .setNegativeButton(android.R.string.ok, null)
                        .create();
                dlg.setOnDismissListener(d -> {
                    iv.setImageDrawable(null);
                    if (!preview.isRecycled()) preview.recycle();
                });
                dlg.show();
            });
        });
    }

    private void saveToPhoneGallery(@NonNull File file) {
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ContentResolver resolver = appCtx.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/ZyroxLauncher");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore unavailable");
                try (FileInputStream in = new FileInputStream(file);
                     OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Cannot write image");
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                }
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Saved to Pictures/ZyroxLauncher!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(appCtx,
                        "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void shareScreenshot(@NonNull File file) {
        try {
            Context ctx = requireContext();
            String auth = ctx.getPackageName() + ".provider";
            Uri uri = FileProvider.getUriForFile(ctx, auth, file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Minecraft Screenshot"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private static Bitmap decodeBounded(@NonNull File file, int reqW, int reqH,
                                        @NonNull Bitmap.Config config) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while ((bounds.outWidth / (sample * 2)) >= Math.max(32, reqW)
                    && (bounds.outHeight / (sample * 2)) >= Math.max(32, reqH)) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = config;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
