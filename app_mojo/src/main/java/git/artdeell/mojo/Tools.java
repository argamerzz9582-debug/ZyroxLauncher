package git.artdeell.mojo;

import git.artdeell.mojo.utils.SdkLevel;
import static android.os.Build.VERSION.SDK_INT;
import static git.artdeell.mojo.MojoApplication.sExecutorService;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import git.artdeell.mojo.awt.AWTActivity;
import git.artdeell.mojo.game.GameActivity;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;
import git.artdeell.mojo.utils.lifecycle.ContextExecutorTask;
import git.artdeell.mojo.utils.maven.MavenName;
import git.artdeell.mojo.utils.maven.MavenNameAdapter;
import git.artdeell.mojo.utils.memory.MemoryHoleFinder;
import git.artdeell.mojo.utils.memory.SelfMapsParser;
import git.artdeell.mojo.multirt.MultiRTUtils;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.value.DependentLibrary;
import git.artdeell.mojo.value.LibraryArtifact;

import org.apache.commons.io.IOUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public static final String MAVEN_CENTRAL = "https://maven-central-eu.storage-download.googleapis.com/maven2/";
    public  static final float BYTE_TO_MB = 1024 * 1024;
    public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    public static String APP_NAME = "Zyrox Launcher";

    public static final Gson GLOBAL_GSON = new GsonBuilder()
            .registerTypeAdapter(MavenName.class, new MavenNameAdapter())
            .setPrettyPrinting()
            .create();

    public static final String URL_HOME = git.artdeell.mojo.security.AppTrustVerifier.OFFICIAL_REPO_URL;
    public static String NATIVE_LIB_DIR;
    public static String DIR_DATA; //Initialized later to get context
    public static File DIR_CACHE;
    public static String MULTIRT_HOME;
    public static int DEVICE_ARCHITECTURE;

    // New since 3.3.1
    public static String DIR_ACCOUNT_NEW;
    // PojavLauncher is left for compatibility purposes
    public static String DIR_GAME_HOME = resolveInitialGameHome();

    /**
     * Placeholder used only until {@link #initStorageConstants(Context)} assigns the real,
     * context-derived root. Code must not cache paths derived from this value in static finals.
     */
    private static String resolveInitialGameHome() {
        if (!SdkLevel.atLeast(29)) {
            try {
                File ext = legacyExternalStorageDirectory();
                if (ext != null) {
                    return ext.getAbsolutePath() + "/games/PojavLauncher";
                }
            } catch (Throwable ignored) {
            }
        }
        return "/sdcard/games/PojavLauncher";
    }

    /**
     * The single, intentional use of the deprecated shared-storage root. Only valid for the legacy
     * (pre-scoped-storage, API &lt; 29) PojavLauncher layout; API 29+ uses
     * {@link Context#getExternalFilesDir(String)} instead.
     */
    @SuppressWarnings("deprecation")
    @Nullable
    private static File legacyExternalStorageDirectory() {
        return Environment.getExternalStorageDirectory();
    }
    public static String DIR_GAME_NEW;

    // New since 2.4.2
    public static String DIR_HOME_VERSION;
    public static String DIR_HOME_LIBRARY;

    public static String DIR_HOME_CRASH;

    public static String ASSETS_PATH;
    public static String OBSOLETE_RESOURCES_PATH;
    public static String CTRLMAP_PATH;
    public static String CTRLDEF_FILE;


    private static @Nullable File getAppStorageRoot(Context ctx) {
        if(SdkLevel.atLeast(29)) {
            return ctx.getExternalFilesDir(null);
        }
        File externalStorageDirectory = legacyExternalStorageDirectory();
        if(externalStorageDirectory == null) return null;
        // PojavLauncher is left for compatibility purposes
        File launcherRoot = new File(externalStorageDirectory,"games/PojavLauncher");
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(launcherRoot))) return null;
        return launcherRoot;
    }

    /**
     * Checks if the Pojav's storage root is accessible and read-writable
     * @param context context to get the storage root if it's not set yet
     * @return true if storage is fine, false if storage is not accessible
     */
    public static boolean checkStorageRoot(Context context) {
        return getAppStorageRoot(context) != null;
    }

    /**
     * Checks if the Pojav's storage root is accessible and read-writable. If it's not, starts
     * the MissingStorageActivity and finishes the supplied activity.
     * @param context the Activity that checks for storage availability
     * @return whether the storage is available or not.
     */
    public static boolean checkStorageInteractive(Activity context) {
        if(!Tools.checkStorageRoot(context)) {
            context.startActivity(new Intent(context, MissingStorageActivity.class));
            context.finish();
            return false;
        }
        return true;
    }

    /**
     * Initialize context constants most necessary for launcher's early startup phase
     * that are not dependent on user storage.
     * All values that depend on DIR_DATA and are not dependent on DIR_GAME_HOME must
     * be initialized here.
     * @param ctx the context for initialization.
     */
    public static void initEarlyConstants(Context ctx) {
        DIR_CACHE = ctx.getCacheDir();
        DIR_DATA = ctx.getFilesDir().getParent();
        MULTIRT_HOME = DIR_DATA + "/runtimes";
        DIR_ACCOUNT_NEW = DIR_DATA + "/accounts";
        NATIVE_LIB_DIR = ctx.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * Initialize context constants that depend on user storage.
     * Any value (in)directly dependent on DIR_GAME_HOME should be set only here.
     * You ABSOLUTELY MUST check for storage presence using checkStorageRoot() before calling this.
     */
    public static void initStorageConstants(Context ctx){
        initEarlyConstants(ctx);
        File appStorageRoot = getAppStorageRoot(ctx);
        if(appStorageRoot == null) throw new RuntimeException("Whoops! You have to put the SD into your phone.");
        DIR_GAME_HOME = appStorageRoot.getAbsolutePath();
        DIR_GAME_NEW = DIR_GAME_HOME + "/.minecraft";
        DIR_HOME_VERSION = DIR_GAME_NEW + "/versions";
        DIR_HOME_LIBRARY = DIR_GAME_NEW + "/libraries";
        DIR_HOME_CRASH = DIR_GAME_NEW + "/crash-reports";
        ASSETS_PATH = DIR_GAME_NEW + "/assets";
        OBSOLETE_RESOURCES_PATH = DIR_GAME_NEW + "/resources";
        CTRLMAP_PATH = DIR_GAME_HOME + "/controlmap";
        CTRLDEF_FILE = DIR_GAME_HOME + "/controlmap/default.json";
    }

    public static void buildNotificationChannel(Context context){
        if(!SdkLevel.atLeast(Build.VERSION_CODES.O)) return;
        NotificationChannel channel = new NotificationChannel(
                context.getString(R.string.notif_channel_id),
                context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }


    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        return git.artdeell.mojo.utils.SystemWindowHelper.getDisplayMetrics(activity);
    }

    /** Re-applies sticky immersive bar hiding when focus returns after popups, dialogs, or IME. */
    public static void hideSystemBars(Activity activity) {
        git.artdeell.mojo.utils.SystemWindowHelper.hideSystemBars(activity);
    }

    public static void setInsetsMode(Activity activity, boolean noSystemBars, boolean ignoreNotch) {
        git.artdeell.mojo.utils.SystemWindowHelper.setInsetsMode(activity, noSystemBars, ignoreNotch);
    }

    // Note: this should *NOT* be used for positioning and sizing things on the screen
    public static DisplayMetrics currentDisplayMetrics;

    public static float dpToPx(float dp) {
        float density = (currentDisplayMetrics != null && currentDisplayMetrics.density > 0f)
                ? currentDisplayMetrics.density : 1f;
        return dp * density;
    }

    public static float pxToDp(float px){
        float density = (currentDisplayMetrics != null && currentDisplayMetrics.density > 0f)
                ? currentDisplayMetrics.density : 1f;
        return px / density;
    }

    public static void copyAssetFile(Context ctx, String assetPath, String output, boolean overwrite) throws IOException {
        String fileName = FileUtils.getFileName(assetPath);
        if(fileName == null) fileName = assetPath;
        File outputFile = new File(output, fileName);
        copyAssetFile(ctx.getAssets(), assetPath, outputFile, overwrite);
    }

    public static void copyAssetFile(AssetManager assetManager, String fileName, File output, boolean overwrite) throws IOException {
        FileUtils.ensureParentDirectory(output);
        if(output.exists() && !overwrite) return;
        try (
                InputStream inputStream = assetManager.open(fileName);
                FileOutputStream fileOutputStream = new FileOutputStream(output)
        ){
            IOUtils.copy(inputStream, fileOutputStream);
        }
    }

    public static String printToString(Throwable throwable) {
        return git.artdeell.mojo.utils.ErrorDialogDispatcher.printStackTrace(throwable);
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }
    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, R.string.global_error, null, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, R.string.global_error, ctx.getString(rolledMessage), e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, R.string.global_error, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e, boolean exitIfOk) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, R.string.global_error, rolledMessage, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int title, final String rolledMessage, final Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, title, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showError(ctx, titleId, null, e, exitIfOk, false);
    }

    public static void showErrorRemote(Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showErrorRemote(null, e);
    }
    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showErrorRemote(context.getString(rolledMessage), e);
    }
    public static void showErrorRemote(String rolledMessage, Throwable e) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showErrorRemote(rolledMessage, e);
    }

    public static void dialogOnUiThread(final Activity activity, final CharSequence title, final CharSequence message) {
        activity.runOnUiThread(() -> dialog(activity, title, message));
    }
    public static void dialogOnUiThread(final Activity activity, final int title, final int message) {
        activity.runOnUiThread(() -> dialog(activity, title, message));
    }
    public static void dialog(final Context context, final CharSequence title, final CharSequence message) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showDialog(context, title, message);
    }
    public static void dialog(final Context context, final int title, final int message) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.showDialog(context, title, message);
    }

    public static void openURL(Activity act, String url) {
        git.artdeell.mojo.utils.ErrorDialogDispatcher.openUrl(act, url);
    }

    public static void preProcessLibrary(DependentLibrary libItem) {
        git.artdeell.mojo.utils.VersionManifestResolver.preProcessLibrary(libItem);
    }

    public static String read(InputStream is) throws IOException {
        String readResult = IOUtils.toString(is, StandardCharsets.UTF_8);
        is.close();
        return readResult;
    }

    public static String read(String path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static String read(File path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static void write(File path, String content) throws IOException {
        FileUtils.ensureParentDirectory(path);
        try (FileOutputStream fileOutputStream = new FileOutputStream(path)){
            IOUtils.write(content, fileOutputStream);
        }
    }
    public static void write(String path, String content) throws IOException {
        write(new File(path), content);
    }
    public static void write(InputStream source, File dest) throws IOException {
        try(FileOutputStream fos = new FileOutputStream(dest)){
            byte[] buf = new byte[65535];
            int len;
            while((len = source.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
        }
    }

    public static boolean isAndroid8OrHigher() {
        return SdkLevel.atLeast(26);
    }

    public static void fullyExit() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public static void printLauncherInfo(String gameVersion, String javaArguments, RenderSpec renderer, Context ctx) {
        Logger.appendToLog("Info: Launcher version: " + BuildConfig.VERSION_NAME);
        Logger.appendToLog("Info: Build type: " + BuildConfig.BUILD_TYPE);
        Logger.appendToLog("Info: Architecture: " + Architecture.archAsString(DEVICE_ARCHITECTURE));
        Logger.appendToLog("Info: Device model: " + Build.MANUFACTURER + " " +Build.MODEL);
        Logger.appendToLog("Info: API version: " + SDK_INT);
        Logger.appendToLog("Info: Selected game version: " + gameVersion);
        Logger.appendToLog("Info: Custom Java arguments: \"" + javaArguments + "\"");
        GpuUtils.GLInfo info = GpuUtils.getGlInfo();
        Logger.appendToLog("Info: Total RAM on device: " + getTotalDeviceMemory(ctx) + " Mb");
        Logger.appendToLog("Info: RAM allocated: " + LauncherPreferences.PREF_RAM_ALLOCATION + " Mb");
        Logger.appendToLog("Info: Graphics device: "+info.vendor+ " "+info.renderer+" (OpenGL ES "+info.glesMajorVersion+")");
        Logger.appendToLog("Info: Selected renderer: " + renderer.tag() + " (" + renderer.name() + ")");
    }

    public static JVersionList.Version getVersionInfo(String versionName) {
        return git.artdeell.mojo.utils.VersionManifestResolver.resolveVersionInfo(versionName, false);
    }

    public static JVersionList.Version getVersionInfo(String versionName, boolean skipInheriting) {
        return git.artdeell.mojo.utils.VersionManifestResolver.resolveVersionInfo(versionName, skipInheriting);
    }

    public static String getSelectedRuntime(Instance instance) {
        String runtime = LauncherPreferences.PREF_DEFAULT_RUNTIME;
        String profileRuntime = instance.selectedRuntime;
        if (profileRuntime != null) {
            if (MultiRTUtils.forceReread(profileRuntime).versionString != null) {
                runtime = profileRuntime;
            }
        }
        return runtime;
    }

    public static void createLibraryInfo(DependentLibrary library) {
        git.artdeell.mojo.utils.VersionManifestResolver.createLibraryInfo(library);
    }

    public interface DownloaderFeedback {
        void updateProgress(int curr, int max);
    }

    public static int getTotalDeviceMemory(Context ctx) {
        return git.artdeell.mojo.utils.SystemWindowHelper.getTotalDeviceMemoryMb(ctx);
    }

    public static int getFreeDeviceMemory(Context ctx) {
        return git.artdeell.mojo.utils.SystemWindowHelper.getFreeDeviceMemoryMb(ctx);
    }

    public static int getMaxContinuousAddressSpaceSize() {
        return git.artdeell.mojo.utils.SystemWindowHelper.getMaxContinuousAddressSpaceSizeMb();
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        return git.artdeell.mojo.utils.SystemWindowHelper.getDisplayFriendlyRes(displaySideRes, scaling);
    }

    public static String getFileName(Context ctx, Uri uri) {
        try(Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if(c == null) return uri.getLastPathSegment(); // idk myself but it happens on asus file manager
            if(!c.moveToFirst()) return uri.getLastPathSegment();
            int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if(columnIndex == -1) return uri.getLastPathSegment();
            return c.getString(columnIndex);
        } catch (Exception e) {
            // Turns out that the content resolver can throw you literally anything if the underlying provider crashes
            // Fall back in that case
            return uri.getLastPathSegment();
        }
    }

    /** Swap the main fragment with another */
    public static void swapFragment(FragmentActivity fragmentActivity , Class<? extends Fragment> fragmentClass,
                                    @Nullable String fragmentTag, @Nullable Bundle bundle) {
        if (fragmentActivity == null || fragmentActivity.isFinishing()) return;
        FragmentManager fm = fragmentActivity.getSupportFragmentManager();
        if (fm.isStateSaved()) return;
        // Prevent duplicate back-stack pushes on rapid double-tap when no new arguments are passed
        Fragment current = fm.findFragmentById(R.id.container_fragment);
        if (bundle == null && current != null && current.getClass() == fragmentClass) {
            return;
        }
        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction()
                .setReorderingAllowed(true);
        if (!LauncherPreferences.isReducedMotionEnabled(fragmentActivity)) {
            tx.setCustomAnimations(
                    R.anim.zyrox_frag_enter,
                    R.anim.zyrox_frag_exit,
                    R.anim.zyrox_frag_enter,
                    R.anim.zyrox_frag_exit
            );
        }
        tx.addToBackStack(fragmentClass.getName())
                .replace(R.id.container_fragment, fragmentClass, bundle, fragmentTag)
                .commit();
    }

    public static void backToMainMenu(FragmentActivity fragmentActivity) {
        fragmentActivity.getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);

    }

    /** Remove the current fragment */
    public static void removeCurrentFragment(FragmentActivity fragmentActivity){
        fragmentActivity.getSupportFragmentManager().popBackStack();
    }

    /** Launch the mod installer activity. The Uri must be from our own content provider or
     * from ACTION_OPEN_DOCUMENT
     */
    public static void launchModInstaller(Context context, @NonNull Uri uri){
        Intent intent = new Intent(context, AWTActivity.class);
        intent.putExtra("modUri", uri);
        context.startActivity(intent);
    }


    public static void installRuntimeFromUri(Context context, Uri uri){
        sExecutorService.execute(() -> {
            try {
                String name = getFileName(context, uri);
                MultiRTUtils.installRuntimeNamed(
                        NATIVE_LIB_DIR,
                        context.getContentResolver().openInputStream(uri),
                        name);

                MultiRTUtils.postPrepare(name);
            } catch (IOException e) {
                Tools.showError(context, e);
            }
        });
    }

    public static String extractUntilCharacter(String input, String whatFor, char terminator) {
        int whatForStart = input.indexOf(whatFor);
        if(whatForStart == -1) return null;
        whatForStart += whatFor.length();
        int terminatorIndex = input.indexOf(terminator, whatForStart);
        if(terminatorIndex == -1) return null;
        return input.substring(whatForStart, terminatorIndex);
    }

    public static boolean isValidString(String string) {
        return string != null && !string.isEmpty();
    }

    public static String validOrNullString(String string) {
        if(!isValidString(string)) return null;
        return string;
    }

    public static void runOnUiThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    /**
     * Triggers the Android share chooser with a sanitized, token-redacted copy of {@code latestlog.txt}.
     * Performs streaming redaction on the background executor and never exposes raw credentials.
     */
    public static void shareLog(Context context){
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        final File sourceLog = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!sourceLog.isFile() || sourceLog.length() == 0L) {
            runOnUiThread(() -> android.widget.Toast.makeText(
                    appCtx, R.string.zyrox_log_empty, android.widget.Toast.LENGTH_SHORT).show());
            return;
        }
        MojoApplication.sExecutorService.execute(() -> {
            try {
                File exportDir = new File(Tools.DIR_GAME_HOME, "logs");
                File redactedLog = new File(exportDir, "latestlog-redacted.txt");
                git.artdeell.mojo.security.LogRedactor.writeRedactedLogFile(
                        sourceLog, redactedLog, 8L * 1024L * 1024L);
                runOnUiThread(() -> openPath(appCtx, redactedLog, true));
            } catch (IOException e) {
                final String safeErr = git.artdeell.mojo.security.LogRedactor.redact(e.getMessage());
                runOnUiThread(() -> android.widget.Toast.makeText(
                        appCtx, safeErr, android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Determine the MIME type of a File.
     * @param file The file to determine the type of
     * @return the type, or the default value *slash* if cannot be determined
     */
    public static String getMimeType(File file) {
        if(file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String mimeType = null;
        try (FileInputStream fileInputStream = new FileInputStream(file)){
            // Theoretically we don't even need the buffer since we don't care about the
            // contents of the file after the guess, but mark-supported streams
            // are a requirement of URLConnection.guessContentTypeFromStream()
            try(BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {
                mimeType = URLConnection.guessContentTypeFromStream(bufferedInputStream);
            }
        }catch (IOException e) {
            Log.w("FileMimeType", "Failed to determine MIME type by stream", e);
        }
        if(mimeType != null) return mimeType;
        mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if(mimeType != null) return mimeType;
        return "*/*";
    }

    /**
     * Open the path specified by a File in a file explorer or in a relevant application.
     * @param context the current Context
     * @param file the File to open
     * @param share whether to open a "Share" or an "Open" dialog.
     */
    public static void openPath(Context context, File file, boolean share) {
        // Enforce automatic credential redaction whenever sharing any unredacted launcher/MC log file
        if (share && file != null && file.isFile()) {
            String lowerName = file.getName().toLowerCase(java.util.Locale.ROOT);
            if ((lowerName.endsWith(".log") || lowerName.equals("latestlog.txt")
                    || lowerName.startsWith("crash-"))
                    && !lowerName.contains("-redacted")) {
                try {
                    File exportDir = new File(Tools.DIR_GAME_HOME, "logs");
                    File safeCopy = new File(exportDir, file.getName() + "-redacted.txt");
                    file = git.artdeell.mojo.security.LogRedactor.writeRedactedLogFile(
                            file, safeCopy, 8L * 1024L * 1024L);
                } catch (IOException e) {
                    Log.w("Tools", "Blocked share of unredacted log file");
                    return;
                }
            }
        }
        Uri contentUri = DocumentsContract.buildDocumentUri(context.getString(R.string.storageProviderAuthorities), file.getAbsolutePath());
        String mimeType = getMimeType(file);
        Intent intent = new Intent();
        if(share) {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        }else {
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mimeType);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooserIntent = Intent.createChooser(intent, file.getName());
        context.startActivity(chooserIntent);
    }

    /** Mesure the textview height, given its current parameters */
    public static int mesureTextviewHeight(TextView t) {
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(t.getWidth(), View.MeasureSpec.AT_MOST);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        t.measure(widthMeasureSpec, heightMeasureSpec);
        return t.getMeasuredHeight();
    }

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        if(weakReference == null) return null;
        return weakReference.get();
    }

    public static boolean deviceSupportsGyro(@NonNull Context context) {
        return ((SensorManager)context.getSystemService(Context.SENSOR_SERVICE)).getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;

    }

    public static void dialogForceClose(Context ctx) {
        new android.app.AlertDialog.Builder(ctx)
                .setMessage(R.string.mcn_exit_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                    try {
                        Tools.restartLauncherActivity(ctx);
                        Tools.fullyExit();
                    } catch (Throwable th) {
                        Log.w(Tools.APP_NAME, "Could not enable System.exit() method!", th);
                    }
                }).show();
    }

    public static int getTranslationFromCursorY(int cursorY, int viewHeight, int imeHeight, int padding){
        int visibleHeight = viewHeight - imeHeight;
        if(cursorY < visibleHeight)
            return 0;
        return Math.min(imeHeight, cursorY - visibleHeight + padding);
    }

    public static void restartLauncherActivity(Context context){
        Intent intent = new Intent(context, LauncherActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.getApplicationContext().startActivity(intent);
    }

    public static void maybeClearList(List<?> list) {
        try {
            list.clear();
        } catch (UnsupportedOperationException ignored) {}
    }
}
