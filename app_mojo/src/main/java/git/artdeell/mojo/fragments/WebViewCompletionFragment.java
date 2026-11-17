package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;

public abstract class WebViewCompletionFragment extends Fragment {
    private final String mTrackedUrl;
    private final String mAuthUrl;
    private WebView mWebview;
    private ProgressBar mLoadingBar;
    private TextView mUrlLabel;
    private boolean mBlankClient = true;
    private boolean mIsCompleted = false;

    protected WebViewCompletionFragment(String mTrackedUrl, String mAuthUrl) {
        this.mTrackedUrl = mTrackedUrl;
        this.mAuthUrl = mAuthUrl;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_microsoft_login, container, false);
        mWebview = root instanceof WebView ? (WebView) root : root.findViewById(R.id.oauth_webview);
        mLoadingBar = root.findViewById(R.id.oauth_progress_bar);
        mUrlLabel = root.findViewById(R.id.oauth_url_label);
        if (mUrlLabel != null) {
            mUrlLabel.setText(formatSafeDisplayUrl(mAuthUrl));
        }

        mWebview.setBackgroundColor(Color.parseColor("#0A101E"));
        mWebview.setFilterTouchesWhenObscured(true);
        mWebview.requestFocus();
        mWebview.setOnFocusChangeListener((v, f) -> {
            if (!f && mWebview != null) mWebview.requestFocus();
        });
        setWebViewSettings();
        if (savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return root;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void setWebViewSettings() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Hardened MASVS-PLATFORM-2 WebView sandbox controls for OAuth sign-in
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSavePassword(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(mWebview, false);
        mWebview.setWebViewClient(new WebViewTrackClient());
        mBlankClient = false;
    }

    private void startNewSession() {
        CookieManager.getInstance().removeAllCookies((b) -> {
            if (mWebview == null) return;
            mWebview.clearHistory();
            mWebview.clearCache(true);
            mWebview.clearFormData();
            WebStorage.getInstance().deleteAllData();
            mWebview.loadUrl(mAuthUrl);
        });
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        if (mWebview.restoreState(savedInstanceState) == null) {
            Log.w("OAuthWebView", "Could not restore OAuth state; starting fresh session");
            startNewSession();
        }
    }

    /**
     * Strips query parameters and fragments before displaying the URL so OAuth codes
     * or state parameters are never exposed on screen.
     */
    private static String formatSafeDisplayUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if ("https".equalsIgnoreCase(scheme) && host != null) {
                return scheme + "://" + host + (path != null ? path : "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mBlankClient && mWebview != null) {
            mWebview.setWebViewClient(new WebViewTrackClient());
            mBlankClient = false;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if (mWebview != null) {
            mWebview.setWebViewClient(new WebViewClient());
            mBlankClient = true;
            super.onSaveInstanceState(outState);
            mWebview.saveState(outState);
        } else {
            super.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onDestroyView() {
        if (mWebview != null) {
            mWebview.stopLoading();
            if (mIsCompleted) {
                mWebview.clearCache(true);
                mWebview.clearHistory();
                mWebview.clearFormData();
                CookieManager.getInstance().removeAllCookies(null);
            }
            mWebview.destroy();
            mWebview = null;
        }
        super.onDestroyView();
    }

    public boolean canGoBack() {
        return mWebview != null && mWebview.canGoBack();
    }

    public void goBack() {
        if (mWebview != null) mWebview.goBack();
    }

    class WebViewTrackClient extends WebViewClient {

        private boolean handleNavigation(@Nullable String url) {
            if (url == null) return true;
            if (url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
                return true;
            }
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            // Permit only HTTPS pages during OAuth authentication; block file/content/intent/http
            if (!"https".equalsIgnoreCase(scheme) && !"about".equalsIgnoreCase(scheme)) {
                Log.w("OAuthWebView", "Blocked non-HTTPS navigation during sign-in");
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request != null ? request.getUrl() : null;
            return handleNavigation(uri != null ? uri.toString() : null);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (mLoadingBar != null) mLoadingBar.setVisibility(View.VISIBLE);
            if (mUrlLabel != null && url != null && !url.startsWith(mTrackedUrl)) {
                mUrlLabel.setText(formatSafeDisplayUrl(url));
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (mLoadingBar != null) mLoadingBar.setVisibility(View.GONE);
            if (url != null && url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
            } else if (mUrlLabel != null && url != null) {
                mUrlLabel.setText(formatSafeDisplayUrl(url));
            }
        }
    }

    private void internalSignalCompletion(String fullUrl) {
        if (mIsCompleted) return;
        mIsCompleted = true;
        signalCompletion(fullUrl);
    }

    protected abstract void signalCompletion(String fullUrl);
}
