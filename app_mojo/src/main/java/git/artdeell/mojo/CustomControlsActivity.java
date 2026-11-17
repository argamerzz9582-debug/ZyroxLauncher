package git.artdeell.mojo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.gson.JsonSyntaxException;

import java.io.IOException;

import git.artdeell.mojo.input.ControlData;
import git.artdeell.mojo.input.ControlDrawerData;
import git.artdeell.mojo.input.ControlJoystickData;
import git.artdeell.mojo.input.ControlLayout;
import git.artdeell.mojo.input.EditorExitable;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.CropperUtils;

/**
 * Page 6: Zyrox Custom Controls Touch HUD Editor —
 * Full-screen alignment grid canvas + collapsible top-center floating obsidian pill toolbar
 * + frosted glass button property drawer.
 */
public class CustomControlsActivity extends BaseActivity implements EditorExitable, CropperUtils.CropperReceiver {
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerNavigationView;
    private ControlLayout mControlLayout;
    private CropperUtils.CropperReceiver mCropperReceiver;
    private ActivityResultLauncher<?> mCropperLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCropperLauncher = CropperUtils.registerCropper(this, this);

        setContentView(R.layout.activity_custom_controls);

        mControlLayout = findViewById(R.id.customctrl_controllayout);
        mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
        mDrawerNavigationView = findViewById(R.id.customctrl_navigation_view);
        View pullDrawerButton = findViewById(R.id.drawer_button);

        pullDrawerButton.setOnClickListener(v -> mDrawerLayout.openDrawer(mDrawerNavigationView));
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        // Collapsible Zyrox floating pill toolbar wiring
        View hudPill = findViewById(R.id.zyrox_hud_pill);
        View collapseBtn = findViewById(R.id.btn_hud_collapse);
        View expandBtn = findViewById(R.id.btn_hud_expand);
        View hintLabel = findViewById(R.id.tv_zyrox_hud_hint);

        if (collapseBtn != null && hudPill != null && expandBtn != null) {
            collapseBtn.setOnClickListener(v -> {
                hudPill.setVisibility(View.GONE);
                expandBtn.setVisibility(View.VISIBLE);
                if (hintLabel != null) hintLabel.setVisibility(View.GONE);
            });
            expandBtn.setOnClickListener(v -> {
                expandBtn.setVisibility(View.GONE);
                hudPill.setVisibility(View.VISIBLE);
                if (hintLabel != null) hintLabel.setVisibility(View.VISIBLE);
            });
        }

        View addBtn = findViewById(R.id.btn_hud_add_button);
        if (addBtn != null) addBtn.setOnClickListener(v -> mControlLayout.addControlButton(new ControlData("New")));

        View addDrawer = findViewById(R.id.btn_hud_add_drawer);
        if (addDrawer != null) addDrawer.setOnClickListener(v -> mControlLayout.addDrawer(new ControlDrawerData()));

        View addJoystick = findViewById(R.id.btn_hud_add_joystick);
        if (addJoystick != null) addJoystick.setOnClickListener(v -> mControlLayout.addJoystickButton(new ControlJoystickData()));

        View presetsBtn = findViewById(R.id.btn_hud_presets);
        if (presetsBtn != null) {
            presetsBtn.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.zyrox_hud_presets_title)
                    .setItems(git.artdeell.mojo.input.ControlPresetFactory.PRESET_NAMES, (d, which) -> {
                        mControlLayout.applyPreset(which);
                        android.widget.Toast.makeText(this,
                                git.artdeell.mojo.input.ControlPresetFactory.PRESET_NAMES[which],
                                android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }

        View undoBtn = findViewById(R.id.btn_hud_undo);
        if (undoBtn != null) {
            undoBtn.setOnClickListener(v -> {
                if (!mControlLayout.undoLayout()) {
                    android.widget.Toast.makeText(this, "Nothing to undo", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        View redoBtn = findViewById(R.id.btn_hud_redo);
        if (redoBtn != null) {
            redoBtn.setOnClickListener(v -> {
                if (!mControlLayout.redoLayout()) {
                    android.widget.Toast.makeText(this, "Nothing to redo", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        android.widget.TextView snapBtn = findViewById(R.id.btn_hud_snap);
        View gridView = findViewById(R.id.zyrox_hud_grid);
        if (snapBtn != null) {
            snapBtn.setText(LauncherPreferences.PREF_ZYROX_HUD_SNAP_GRID
                    ? R.string.zyrox_hud_snap_on : R.string.zyrox_hud_snap_off);
            if (gridView != null) {
                gridView.setAlpha(LauncherPreferences.PREF_ZYROX_HUD_SNAP_GRID ? 1f : 0.35f);
            }
            snapBtn.setOnClickListener(v -> {
                boolean next = !LauncherPreferences.PREF_ZYROX_HUD_SNAP_GRID;
                LauncherPreferences.PREF_ZYROX_HUD_SNAP_GRID = next;
                if (LauncherPreferences.DEFAULT_PREF != null) {
                    LauncherPreferences.DEFAULT_PREF.edit().putBoolean("zyroxHudSnapGrid", next).apply();
                }
                snapBtn.setText(next ? R.string.zyrox_hud_snap_on : R.string.zyrox_hud_snap_off);
                if (gridView != null) {
                    gridView.setAlpha(next ? 1f : 0.35f);
                }
            });
        }

        View loadBtn = findViewById(R.id.btn_hud_load);
        if (loadBtn != null) loadBtn.setOnClickListener(v -> mControlLayout.openLoadDialog());

        View saveBtn = findViewById(R.id.btn_hud_save);
        if (saveBtn != null) saveBtn.setOnClickListener(v -> mControlLayout.openSaveDialog(this));

        View exitBtn = findViewById(R.id.btn_hud_exit);
        if (exitBtn != null) exitBtn.setOnClickListener(v -> mControlLayout.askToExit(this));

        mDrawerNavigationView.setAdapter(new ArrayAdapter<>(
                this,
                R.layout.item_simple_list_1,
                getResources().getStringArray(R.array.menu_customcontrol_customactivity)));
        mDrawerNavigationView.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0:
                    mControlLayout.addControlButton(new ControlData("New"));
                    break;
                case 1:
                    mControlLayout.addDrawer(new ControlDrawerData());
                    break;
                case 2:
                    mControlLayout.addJoystickButton(new ControlJoystickData());
                    break;
                case 3:
                    mControlLayout.openLoadDialog();
                    break;
                case 4:
                    mControlLayout.openSaveDialog(this);
                    break;
                case 5:
                    mControlLayout.openSetDefaultDialog();
                    break;
                case 6:
                    shareCurrentLayout();
                    break;
            }
            mDrawerLayout.closeDrawers();
        });
        mControlLayout.setModifiable(true);
    }

    private void shareCurrentLayout() {
        try {
            Uri contentUri = DocumentsContract.buildDocumentUri(
                    getString(R.string.storageProviderAuthorities),
                    mControlLayout.saveToDirectory(mControlLayout.mLayoutFileName));

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.setType("application/json");

            Intent sendIntent = Intent.createChooser(shareIntent, mControlLayout.mLayoutFileName);
            startActivity(sendIntent);
        } catch (Exception e) {
            Tools.showError(this, e);
        }
    }

    @Override
    public void onAttachedToWindow() {
        mControlLayout.post(() -> {
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            } catch (IOException | JsonSyntaxException e) {
                Tools.showError(this, e);
            }
        });
    }

    public void startCropping(CropperUtils.CropperReceiver cropperReceiver) {
        mCropperReceiver = cropperReceiver;
        CropperUtils.startCropper(mCropperLauncher);
    }

    @Override
    public void onBackPressed() {
        mControlLayout.askToExit(this);
    }

    @Override
    public void exitEditor() {
        super.onBackPressed();
    }

    @Override
    public float getAspectRatio() {
        if (mCropperReceiver != null) return mCropperReceiver.getAspectRatio();
        return 1f;
    }

    @Override
    public int getTargetMaxSide() {
        if (mCropperReceiver != null) return mCropperReceiver.getTargetMaxSide();
        return 128;
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        if (mCropperReceiver != null) mCropperReceiver.onCropped(contentBitmap);
    }

    @Override
    public void onFailed(Exception exception) {
        if (mCropperReceiver != null) mCropperReceiver.onFailed(exception);
    }
}
