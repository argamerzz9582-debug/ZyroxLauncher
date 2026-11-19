package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.ModloaderDownloadListener;
import git.artdeell.mojo.modding.ModloaderListenerProxy;

/**
 * Base version-picker fragment that fetches downloadable loader/mod releases on Zyrox's bounded
 * background executor, auto-locks/expands the group matching the active {@link Instance}'s clean
 * Minecraft release, and safely detaches across Android configuration and lifecycle transitions.
 */
public abstract class ModVersionListFragment<T> extends Fragment
        implements Runnable, View.OnClickListener,
        ExpandableListView.OnChildClickListener, ModloaderDownloadListener {

    private final String mExtraTag;
    private ExpandableListView mExpandableListView;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private LayoutInflater mInflater;
    private View mRetryView;
    @Nullable private Future<?> mLoadFuture;
    @Nullable private String mActiveCleanMcVersion;
    private volatile boolean mForceNextRefresh = false;

    public ModVersionListFragment(String mFragmentTag) {
        super(R.layout.fragment_mod_version_list);
        this.mExtraTag = mFragmentTag + "_proxy";
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.mInflater = LayoutInflater.from(context);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTitleView = view.findViewById(R.id.title_textview);
        if (mTitleView != null) {
            mTitleView.setText(getTitleText());
        }
        mProgressBar = view.findViewById(R.id.mod_dl_list_progress);
        mExpandableListView = view.findViewById(R.id.mod_dl_expandable_version_list);
        mExpandableListView.setOnChildClickListener(this);
        mRetryView = view.findViewById(R.id.mod_dl_retry_layout);
        view.findViewById(R.id.forge_installer_retry_button).setOnClickListener(this);

        ModloaderListenerProxy taskProxy = getTaskProxy();
        if (taskProxy != null) {
            mExpandableListView.setEnabled(false);
            taskProxy.attachListener(this);
        }
        startAsyncVersionLoad();
    }

    private void startAsyncVersionLoad() {
        if (mLoadFuture != null) {
            mLoadFuture.cancel(true);
        }
        mLoadFuture = MojoApplication.sExecutorService.submit(this);
    }

    @Override
    public void onStop() {
        ModloaderListenerProxy taskProxy = getTaskProxy();
        if (taskProxy != null) taskProxy.detachListener();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (mLoadFuture != null) {
            mLoadFuture.cancel(true);
            mLoadFuture = null;
        }
        super.onDestroyView();
    }

    @Override
    public void run() {
        boolean force = mForceNextRefresh;
        mForceNextRefresh = false;
        // Resolve active instance clean MC version off the main thread
        String detectedMc = null;
        try {
            Instance selected = Instances.loadSelectedInstance();
            if (selected != null) {
                detectedMc = selected.getCleanMinecraftVersion();
            }
        } catch (Throwable ignored) {
        }
        final String cleanMc = detectedMc;

        try {
            T versions = loadVersionList(force);
            if (Thread.currentThread().isInterrupted()) return;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mExpandableListView == null) return;
                mActiveCleanMcVersion = cleanMc;
                if (versions != null) {
                    ExpandableListAdapter adapter = createAdapter(versions, mInflater);
                    mExpandableListView.setAdapter(adapter);
                    autoExpandRecommendedGroup(adapter, cleanMc);
                } else if (mRetryView != null) {
                    mRetryView.setVisibility(View.VISIBLE);
                }
                if (mProgressBar != null) {
                    mProgressBar.setVisibility(View.GONE);
                }
            });
        } catch (IOException e) {
            Tools.runOnUiThread(() -> {
                if (isAdded() && getContext() != null) {
                    Tools.showError(getContext(), e);
                    if (mRetryView != null) mRetryView.setVisibility(View.VISIBLE);
                    if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                }
            });
        }
    }

    /**
     * Automatically locates and expands the version group matching the active instance's clean
     * Minecraft release (falling back to the top stable group) and updates the header label.
     */
    private void autoExpandRecommendedGroup(@Nullable ExpandableListAdapter adapter,
                                            @Nullable String cleanMcVersion) {
        if (adapter == null || adapter.getGroupCount() <= 0 || mExpandableListView == null) {
            return;
        }
        int matchedGroup = -1;
        if (cleanMcVersion != null && !cleanMcVersion.isEmpty()) {
            String target = cleanMcVersion.trim();
            String mcPrefixed = "Minecraft " + target;
            for (int g = 0; g < adapter.getGroupCount(); g++) {
                Object grp = adapter.getGroup(g);
                if (grp == null) continue;
                String label = String.valueOf(grp).trim();
                if (label.equalsIgnoreCase(target)
                        || label.equalsIgnoreCase(mcPrefixed)
                        || label.endsWith(" " + target)) {
                    matchedGroup = g;
                    break;
                }
            }
        }
        if (matchedGroup >= 0) {
            mExpandableListView.expandGroup(matchedGroup);
            mExpandableListView.setSelectedGroup(matchedGroup);
            if (mTitleView != null && isAdded()) {
                String base = getString(getTitleText());
                mTitleView.setText(base + " • MC " + cleanMcVersion + " (Recommended)");
            }
        } else if (adapter.getGroupCount() == 1) {
            mExpandableListView.expandGroup(0);
        }
    }

    /** Returns the clean base Minecraft version detected for the currently selected instance. */
    @Nullable
    public String getActiveInstanceMinecraftVersion() {
        return mActiveCleanMcVersion;
    }

    @Override
    public void onClick(View view) {
        mForceNextRefresh = true;
        if (mRetryView != null) mRetryView.setVisibility(View.GONE);
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        startAsyncVersionLoad();
    }

    @Override
    public boolean onChildClick(ExpandableListView expandableListView,
                                View view,
                                int groupPosition,
                                int childPosition,
                                long id) {
        Context ctx = getContext();
        if (ctx == null || !ProgressLayout.ensureNoOngoingTasks(ctx)) return true;
        ExpandableListAdapter adapter = expandableListView.getExpandableListAdapter();
        if (adapter == null) return true;
        Object selectedVersion = adapter.getChild(groupPosition, childPosition);
        if (selectedVersion == null) return true;

        ModloaderListenerProxy taskProxy = new ModloaderListenerProxy();
        Runnable downloadTask = createDownloadTask(selectedVersion, taskProxy);
        setTaskProxy(taskProxy);
        taskProxy.attachListener(this);
        mExpandableListView.setEnabled(false);
        MojoApplication.sExecutorService.execute(downloadTask);
        return true;
    }

    @Override
    public void onDownloadFinished(File downloadedFile) {
        Tools.runOnUiThread(() -> {
            ModloaderListenerProxy proxy = getTaskProxy();
            if (proxy != null) proxy.detachListener();
            setTaskProxy(null);
            if (!isAdded()) return;
            Context context = requireContext();
            if (mExpandableListView != null) mExpandableListView.setEnabled(true);
            if (!getParentFragmentManager().isStateSaved()) {
                getParentFragmentManager().popBackStackImmediate();
            }
            onDownloadFinished(context, downloadedFile);
        });
    }

    @Override
    public void onDataNotAvailable() {
        Tools.runOnUiThread(() -> {
            ModloaderListenerProxy proxy = getTaskProxy();
            if (proxy != null) proxy.detachListener();
            setTaskProxy(null);
            if (!isAdded()) return;
            Context context = requireContext();
            if (mExpandableListView != null) mExpandableListView.setEnabled(true);
            Tools.dialog(context,
                    context.getString(R.string.global_error),
                    context.getString(getNoDataMsg()));
        });
    }

    @Override
    public void onDownloadError(Exception e) {
        Tools.runOnUiThread(() -> {
            ModloaderListenerProxy proxy = getTaskProxy();
            if (proxy != null) proxy.detachListener();
            setTaskProxy(null);
            if (!isAdded()) return;
            Context context = requireContext();
            if (mExpandableListView != null) mExpandableListView.setEnabled(true);
            Tools.showError(context, e);
        });
    }

    private void setTaskProxy(@Nullable ModloaderListenerProxy proxy) {
        ExtraCore.setValue(mExtraTag, proxy);
    }

    @Nullable
    private ModloaderListenerProxy getTaskProxy() {
        return (ModloaderListenerProxy) ExtraCore.getValue(mExtraTag);
    }

    public abstract int getTitleText();
    public abstract int getNoDataMsg();

    public abstract T loadVersionList() throws IOException;

    public T loadVersionList(boolean forceRefresh) throws IOException {
        return loadVersionList();
    }

    public abstract ExpandableListAdapter createAdapter(T versionList, LayoutInflater layoutInflater);
    public abstract Runnable createDownloadTask(Object selectedVersion, ModloaderListenerProxy listenerProxy);
    public abstract void onDownloadFinished(Context context, File downloadedFile);
}
