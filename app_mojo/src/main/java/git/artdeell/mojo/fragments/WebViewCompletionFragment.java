package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
            mUrlLabel.setText(mAuthUrl);
        }

        mWebview.setBackgroundColor(Color.parseColor("#0D0B0A"));
        mWebview.requestFocus();
        mWebview.setOnFocusChangeListener((v, f) -> {
            if (!f) mWebview.requestFocus();
        });
        setWebViewSettings();
        if (savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return root;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mWebview.setWebViewClient(new WebViewTrackClient());
        mBlankClient = false;
    }

    private void startNewSession() {
        CookieManager.getInstance().removeAllCookies((b) -> {
            mWebview.clearHistory();
            mWebview.clearCache(true);
            mWebview.clearFormData();
            mWebview.clearHistory();
            WebStorage.getInstance().deleteAllData();
            mWebview.loadUrl(mAuthUrl);
        });
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        Log.i("MSAuthFragment", "Restoring state...");
        if (mWebview.restoreState(savedInstanceState) == null) {
            Log.w("MSAuthFragment", "Failed to restore state, starting afresh");
            startNewSession();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mBlankClient) mWebview.setWebViewClient(new WebViewTrackClient());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        mWebview.setWebViewClient(new WebViewClient());
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
    }

    /* Expose webview actions to others */
    public boolean canGoBack() {
        return mWebview != null && mWebview.canGoBack();
    }

    public void goBack() {
        if (mWebview != null) mWebview.goBack();
    }

    /** Client to track when to send the data to the launcher */
    class WebViewTrackClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
                return true;
            }
            return super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (mLoadingBar != null) mLoadingBar.setVisibility(View.VISIBLE);
            if (mUrlLabel != null && url != null) mUrlLabel.setText(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (mLoadingBar != null) mLoadingBar.setVisibility(View.GONE);
            if (mUrlLabel != null && url != null) mUrlLabel.setText(url);
            if (url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
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
