package git.artdeell.mojo.authenticator.accounts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Keep;

import com.google.gson.JsonParseException;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

@Keep
public class Account {
    protected transient File mSaveLocation;
    public String accessToken = "0";
    public String profileId = "00000000-0000-0000-0000-000000000000";
    public String username = "Steve";
    public AuthType authType = AuthType.LOCAL;
    public boolean isMicrosoft = false;
    public String refreshToken = "0";
    public String xuid;
    public long expiresAt;
    private transient Bitmap mFaceCache;

    protected Account() {}

    public void updateSkinFace() {
        String skinFaceUrlTemplate = authType.skinUrl;
        if (skinFaceUrlTemplate == null) return;
        String skinFaceUrl = String.format(skinFaceUrlTemplate, username);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(skinFaceUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                Log.w("SkinLoader", "Blocked non-HTTPS skin endpoint");
                return;
            }
            Log.i("SkinLoader", "Updating skin face...");
            File skinFile = getSkinFaceFile();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            byte[] skinBytes = IOUtils.toByteArray(conn.getInputStream());
            Bitmap skinBitmap = BitmapFactory.decodeByteArray(skinBytes, 0, skinBytes.length);
            if (skinBitmap == null) return;
            Bitmap skinFace = new SkinHeadRenderer().render(100, skinBitmap);
            skinBitmap.recycle();
            if (skinFace == null) return;
            try (FileOutputStream fileOutputStream = new FileOutputStream(skinFile)) {
                skinFace.compress(Bitmap.CompressFormat.WEBP, 90, fileOutputStream);
            }
            Log.i("SkinLoader", "Update skin face success");
        } catch (IOException e) {
            Log.w("SkinLoader", "Could not update skin face");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean isLocal() {
        return authType == AuthType.LOCAL || "0".equals(accessToken);
    }

    public Account toLocal() {
        Account localAccount = new Account();
        localAccount.username = this.username;
        localAccount.authType = AuthType.LOCAL;
        return localAccount;
    }

    void decryptStoredTokens() {
        this.accessToken = AccountTokenCipher.decrypt(this.accessToken);
        this.refreshToken = AccountTokenCipher.decrypt(this.refreshToken);
    }

    private Account toEncryptedSnapshot() {
        Account snapshot = new Account();
        snapshot.accessToken = AccountTokenCipher.encrypt(this.accessToken);
        snapshot.refreshToken = AccountTokenCipher.encrypt(this.refreshToken);
        snapshot.profileId = this.profileId;
        snapshot.username = this.username;
        snapshot.authType = this.authType;
        snapshot.isMicrosoft = this.isMicrosoft;
        snapshot.xuid = this.xuid;
        snapshot.expiresAt = this.expiresAt;
        return snapshot;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void save() throws IOException {
        if (mSaveLocation == null) throw new RuntimeException("Not supposed to save ephemeral account");
        FileUtils.ensureParentDirectory(mSaveLocation);
        JSONUtils.writeToFile(mSaveLocation, toEncryptedSnapshot());
        mSaveLocation.setReadable(false, false);
        mSaveLocation.setReadable(true, true);
        mSaveLocation.setWritable(false, false);
        mSaveLocation.setWritable(true, true);
    }

    public Account reload() {
        try {
            Account account = JSONUtils.readFromFile(mSaveLocation, Account.class);
            if (account == null) return null;
            account.mSaveLocation = mSaveLocation;
            account.decryptStoredTokens();
            return account;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    public Bitmap getSkinFace() {
        if (isLocal()) return null;
        File skinFaceFile = getSkinFaceFile();
        if (!skinFaceFile.exists()) return null;
        if (mFaceCache == null) {
            mFaceCache = BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
        }
        return mFaceCache;
    }

    private File getSkinFaceFile() {
        String safeProfileId = FileUtils.escapeFileName(profileId != null ? profileId : "default");
        return new File(Tools.DIR_CACHE, "skin-face-" + safeProfileId + "-" + authType.name() + ".webp");
    }
}
