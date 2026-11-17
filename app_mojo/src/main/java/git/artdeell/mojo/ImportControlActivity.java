package git.artdeell.mojo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import git.artdeell.mojo.input.LayoutBitmaps;
import git.artdeell.mojo.utils.FileUtils;

/**
 * Activity dedicated to importing control layout files safely with canonical path containment
 * and bounded input stream validation.
 */
public class ImportControlActivity extends Activity {
    private static final long MAX_IMPORT_BYTES = 25L * 1024L * 1024L;
    private static final String TMP_IMPORT_NAME = "TMP_IMPORT_FILE.json";

    private Uri mUriData;
    private boolean mHasIntentChanged = true;
    private volatile boolean mIsFileVerified = false;

    private EditText mEditText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Tools.checkStorageInteractive(this)) {
            Tools.initStorageConstants(getApplicationContext());
        } else {
            return;
        }

        setContentView(R.layout.activity_import_control);
        View rootView = getWindow() != null ? getWindow().getDecorView() : null;
        if (rootView != null) {
            rootView.setFilterTouchesWhenObscured(true);
        }
        mEditText = findViewById(R.id.editText_import_control_file_name);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent != null) setIntent(intent);
        mHasIntentChanged = true;
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (!Tools.checkStorageInteractive(this)) {
            return;
        }
        if (!mHasIntentChanged) return;
        mIsFileVerified = false;
        getUriData();
        if (mUriData == null) {
            finishAndRemoveTask();
            return;
        }
        String detectedName = Tools.getFileName(this, mUriData);
        mEditText.setText(trimFileName(detectedName != null ? detectedName : ""));
        mHasIntentChanged = false;

        new Thread(() -> {
            boolean imported = importControlFile();
            if (imported && verify()) {
                mIsFileVerified = true;
            } else {
                cleanupTempFile();
                runOnUiThread(() -> {
                    Toast.makeText(
                            ImportControlActivity.this,
                            getText(R.string.import_control_invalid_file),
                            Toast.LENGTH_SHORT).show();
                    finishAndRemoveTask();
                });
            }
        }).start();

        Tools.MAIN_HANDLER.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getApplicationContext().getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && mEditText != null) {
                imm.showSoftInput(mEditText, InputMethodManager.SHOW_IMPLICIT);
                mEditText.setSelection(mEditText.getText().length());
            }
        }, 100);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void cleanupTempFile() {
        if (Tools.CTRLMAP_PATH == null) return;
        new File(Tools.CTRLMAP_PATH, TMP_IMPORT_NAME).delete();
    }

    public void startImport(View view) {
        String fileName = trimFileName(mEditText.getText().toString());
        if (!isFileNameValid(fileName)) {
            Toast.makeText(this, getText(R.string.import_control_invalid_name), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mIsFileVerified) {
            Toast.makeText(this, getText(R.string.import_control_verifying_file), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            File ctrlDir = new File(Tools.CTRLMAP_PATH);
            FileUtils.ensureDirectory(ctrlDir);
            File tempFile = FileUtils.resolveInsideDirectory(ctrlDir, TMP_IMPORT_NAME);
            File destFile = FileUtils.resolveInsideDirectory(ctrlDir, fileName + ".json");
            if (!tempFile.renameTo(destFile)) {
                Toast.makeText(this, getText(R.string.import_control_invalid_name), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (IOException e) {
            Toast.makeText(this, getText(R.string.import_control_invalid_name), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getApplicationContext(), getText(R.string.import_control_done), Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }

    private boolean importControlFile() {
        if (mUriData == null || !"content".equalsIgnoreCase(mUriData.getScheme())) {
            return false;
        }
        File ctrlDir = new File(Tools.CTRLMAP_PATH);
        try {
            FileUtils.ensureDirectory(ctrlDir);
            File tmpFile = FileUtils.resolveInsideDirectory(ctrlDir, TMP_IMPORT_NAME);
            try (InputStream is = getContentResolver().openInputStream(mUriData);
                 OutputStream os = new FileOutputStream(tmpFile)) {
                if (is == null) return false;
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = is.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_IMPORT_BYTES) {
                        throw new IOException("Control file exceeds maximum allowed size");
                    }
                    os.write(buffer, 0, read);
                }
                return total > 0;
            }
        } catch (IOException | SecurityException e) {
            Log.w("ImportControlActivity", "Failed to read control file");
            return false;
        }
    }

    private static boolean isFileNameValid(String fileName) {
        fileName = trimFileName(fileName);
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)) return false;
        File ctrlDir = new File(Tools.CTRLMAP_PATH);
        try {
            File target = FileUtils.resolveInsideDirectory(ctrlDir, fileName + ".json");
            return !target.exists();
        } catch (IOException e) {
            return false;
        }
    }

    private static String trimFileName(String fileName) {
        if (fileName == null) return "";
        return fileName
                .replace(".json", "")
                .replaceAll("%..", "")
                .replace("\0", "")
                .replace("..", "")
                .replace("/", "")
                .replace("\\", "")
                .trim();
    }

    private void getUriData() {
        Intent intent = getIntent();
        if (intent == null) {
            mUriData = null;
            return;
        }
        Uri candidate = intent.getData();
        if (candidate == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            candidate = intent.getClipData().getItemAt(0).getUri();
        }
        mUriData = (candidate != null && "content".equalsIgnoreCase(candidate.getScheme())) ? candidate : null;
    }

    private static boolean verify() {
        try {
            LayoutBitmaps.ControlsContainer layout = LayoutBitmaps.load(new File(Tools.CTRLMAP_PATH, TMP_IMPORT_NAME));
            JSONObject layoutJobj = new JSONObject(layout.mControlsJson);
            return layoutJobj.has("version") && layoutJobj.has("mControlDataList");
        } catch (IOException | JSONException e) {
            Log.w("ImportControlActivity", "Failed to validate layout");
            return false;
        }
    }
}
