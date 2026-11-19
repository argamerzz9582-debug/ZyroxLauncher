package git.artdeell.mojo.modding.modpacks;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Future;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.imagecache.ImageReceiver;
import git.artdeell.mojo.modding.modpacks.imagecache.ModIconCache;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;
import git.artdeell.mojo.progresskeeper.TaskCountListener;

public class ModItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements TaskCountListener {
    private static final ModItem[] MOD_ITEMS_EMPTY = new ModItem[0];
    public static final int VIEW_TYPE_MOD_ITEM = 0;
    public static final int VIEW_TYPE_LOADING = 1;

    private final Set<ViewHolder> mViewHolderSet = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<String> mQueuedModIds = new HashSet<>();
    private final ModIconCache mIconCache = new ModIconCache();
    private final SearchResultCallback mSearchResultCallback;
    private final float mIconCornerPx;
    private ModItem[] mModItems;
    private final ModpackApi mModpackApi;
    private OnModClickListener mOnModClickListener;

    private Future<?> mTaskInProgress;
    private SearchFilters mSearchFilters;
    private SearchResult mCurrentResult;
    private boolean mLastPage;
    private boolean mTasksRunning;

    public interface OnModClickListener {
        void onModSelected(@NonNull ModItem item);
    }

    public ModItemAdapter(Resources resources, ModpackApi api, SearchResultCallback callback) {
        mIconCornerPx = 6f * resources.getDisplayMetrics().density;
        mModpackApi = api;
        mModItems = new ModItem[]{};
        mSearchResultCallback = callback;
    }

    public void setOnModClickListener(@Nullable OnModClickListener listener) {
        mOnModClickListener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void markModQueued(@NonNull ModItem item) {
        mQueuedModIds.add(item.getIconCacheTag());
        notifyDataSetChanged();
    }

    public void performSearchQuery(SearchFilters searchFilters) {
        if (mTaskInProgress != null) {
            mTaskInProgress.cancel(true);
            mTaskInProgress = null;
        }
        this.mSearchFilters = searchFilters;
        this.mLastPage = false;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, null))
                .startOnExecutor(MojoApplication.sExecutorService);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
        View view;
        switch (viewType) {
            case VIEW_TYPE_MOD_ITEM:
                view = layoutInflater.inflate(R.layout.view_mod, viewGroup, false);
                return new ViewHolder(view);
            case VIEW_TYPE_LOADING:
                view = layoutInflater.inflate(R.layout.view_loading, viewGroup, false);
                return new LoadingViewHolder(view);
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_MOD_ITEM:
                ((ModItemAdapter.ViewHolder) holder).setStateLimited(mModItems[position]);
                break;
            case VIEW_TYPE_LOADING:
                loadMoreResults();
                break;
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public int getItemCount() {
        if (mLastPage || mModItems.length == 0) return mModItems.length;
        return mModItems.length + 1;
    }

    private void loadMoreResults() {
        if (mTaskInProgress != null) return;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, mCurrentResult))
                .startOnExecutor(MojoApplication.sExecutorService);
    }

    @Override
    public int getItemViewType(int position) {
        if (position < mModItems.length) return VIEW_TYPE_MOD_ITEM;
        return VIEW_TYPE_LOADING;
    }

    @Override
    public boolean onUpdateTaskCount(int taskCount) {
        Tools.runOnUiThread(() -> {
            mTasksRunning = taskCount != 0;
            for (ViewHolder viewHolder : mViewHolderSet) {
                viewHolder.updateInstallButtonState();
            }
        });
        return false;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private ModItem mModItem = null;
        private final TextView mTitle, mDescription, mDownloadsBadge, mAuthorView;
        private final ImageView mIconView, mSourceView;
        private final Button mInstallButton;
        private Bitmap mThumbnailBitmap;
        private ImageReceiver mImageReceiver;

        public ViewHolder(View view) {
            super(view);
            mViewHolderSet.add(this);

            mTitle = view.findViewById(R.id.mod_title_textview);
            mDescription = view.findViewById(R.id.mod_body_textview);
            mIconView = view.findViewById(R.id.mod_thumbnail_imageview);
            mSourceView = view.findViewById(R.id.mod_source_imageview);
            mDownloadsBadge = view.findViewById(R.id.mod_downloads_badge);
            mAuthorView = view.findViewById(R.id.mod_author_textview);
            mInstallButton = view.findViewById(R.id.mod_install_button);

            View.OnClickListener openDrawer = v -> {
                if (mModItem != null && mOnModClickListener != null) {
                    mOnModClickListener.onModSelected(mModItem);
                }
            };
            view.setOnClickListener(openDrawer);
            if (mInstallButton != null) {
                mInstallButton.setOnClickListener(openDrawer);
            }
        }

        public void setStateLimited(ModItem item) {
            if (mThumbnailBitmap != null) {
                mIconView.setImageResource(R.drawable.ic_mojo_full);
                mThumbnailBitmap.recycle();
                mThumbnailBitmap = null;
            } else {
                mIconView.setImageResource(R.drawable.ic_mojo_full);
            }
            if (mImageReceiver != null) {
                mIconCache.cancelImage(mImageReceiver);
            }

            mModItem = item;
            if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                mImageReceiver = bm -> {
                    mImageReceiver = null;
                    mThumbnailBitmap = bm;
                    RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(mIconView.getResources(), bm);
                    drawable.setCornerRadius(mIconCornerPx);
                    mIconView.setImageDrawable(drawable);
                };
                mIconCache.getImage(mImageReceiver, mModItem.getIconCacheTag(), mModItem.imageUrl);
            }

            mSourceView.setImageResource(getSourceDrawable(item.apiSource));
            mTitle.setText(item.title);
            mDescription.setText(item.description);

            if (mDownloadsBadge != null) {
                mDownloadsBadge.setText(item.getFormattedDownloads());
            }
            if (mAuthorView != null) {
                if (item.author != null && !item.author.isEmpty()) {
                    mAuthorView.setVisibility(View.VISIBLE);
                    mAuthorView.setText("by " + item.author);
                } else {
                    mAuthorView.setVisibility(View.GONE);
                }
            }
            updateInstallButtonState();
        }

        private int getSourceDrawable(int apiSource) {
            switch (apiSource) {
                case Constants.SOURCE_CURSEFORGE:
                    return R.drawable.ic_curseforge;
                case Constants.SOURCE_MODRINTH:
                default:
                    return R.drawable.ic_modrinth;
            }
        }

        private void updateInstallButtonState() {
            if (mInstallButton == null || mModItem == null) return;
            boolean queued = mQueuedModIds.contains(mModItem.getIconCacheTag());
            if (queued) {
                mInstallButton.setBackgroundResource(R.drawable.btn_zyrox_installed);
                mInstallButton.setTextColor(androidx.core.content.ContextCompat.getColor(
                        mInstallButton.getContext(), R.color.zyrox_success));
                mInstallButton.setText(R.string.zyrox_installed);
                mInstallButton.setEnabled(true);
            } else {
                mInstallButton.setBackgroundResource(R.drawable.btn_zyrox_play);
                mInstallButton.setTextColor(androidx.core.content.ContextCompat.getColor(
                        mInstallButton.getContext(), R.color.zyrox_lg_text));
                mInstallButton.setText(R.string.zyrox_install);
                mInstallButton.setEnabled(!mTasksRunning);
            }
        }
    }

    private static class LoadingViewHolder extends RecyclerView.ViewHolder {
        public LoadingViewHolder(View view) {
            super(view);
        }
    }

    private class SearchApiTask implements SelfReferencingFuture.FutureInterface {
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousResult;

        private SearchApiTask(SearchFilters searchFilters, SearchResult previousResult) {
            this.mSearchFilters = searchFilters;
            this.mPreviousResult = previousResult;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void run(Future<?> myFuture) {
            SearchResult result = mModpackApi.searchMod(mSearchFilters, mPreviousResult);
            ModItem[] resultModItems = result != null ? result.results : null;
            if (resultModItems != null && resultModItems.length != 0 && mPreviousResult != null) {
                ModItem[] newModItems = new ModItem[resultModItems.length + mModItems.length];
                System.arraycopy(mModItems, 0, newModItems, 0, mModItems.length);
                System.arraycopy(resultModItems, 0, newModItems, mModItems.length, resultModItems.length);
                resultModItems = newModItems;
            }
            ModItem[] finalModItems = resultModItems;
            Tools.runOnUiThread(() -> {
                if (myFuture.isCancelled()) return;
                mTaskInProgress = null;
                mCurrentResult = result;
                if (finalModItems == null) {
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                } else if (finalModItems.length == 0) {
                    if (mPreviousResult != null) {
                        mLastPage = true;
                        notifyItemChanged(mModItems.length);
                        mSearchResultCallback.onSearchFinished();
                        return;
                    }
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS);
                } else {
                    mSearchResultCallback.onSearchFinished();
                }
                if (finalModItems == null) {
                    mModItems = MOD_ITEMS_EMPTY;
                    notifyDataSetChanged();
                    return;
                }
                if (mPreviousResult != null) {
                    int prevLength = mModItems.length;
                    mModItems = finalModItems;
                    notifyItemChanged(prevLength);
                    notifyItemRangeInserted(prevLength + 1, mModItems.length);
                } else {
                    mModItems = finalModItems;
                    notifyDataSetChanged();
                }
            });
        }
    }

    @androidx.annotation.Nullable
    public String getActiveFallbackNotice() {
        return mCurrentResult != null ? mCurrentResult.fallbackNotice : null;
    }

    public interface SearchResultCallback {
        int ERROR_INTERNAL = 0;
        int ERROR_NO_RESULTS = 1;
        void onSearchFinished();
        void onSearchError(int error);
    }
}
