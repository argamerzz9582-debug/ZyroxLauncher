package git.artdeell.mojo.multirt;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.activity.result.ActivityResultLauncher;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;
import git.artdeell.mojo.dialogs.PackageSelector;
import git.artdeell.mojo.dialogs.PackageAdapter;

public class MultiRTConfigDialog extends PackageSelector {
    public void show(){
        refresh();
        mDialog.show();
    }
    @SuppressLint("NotifyDataSetChanged") //only used to completely refresh the list, it is necessary
    public void refresh() {
        RecyclerView.Adapter<?> adapter = mDialogView.getAdapter();
        if(adapter != null) adapter.notifyDataSetChanged();
    }
    protected int getTitleRes() { return R.string.multirt_config_title; }
    protected int getPositiveButtonText() { return R.string.multirt_config_add; }
    protected int getNeutralButtonText() { return R.string.multirt_delete_runtime; }
    protected int getEditingButtonLabel() { return R.string.multirt_config_setdefault; }
    protected PackageAdapter<?> createAdapter(Context context, PackageSelector dialog) { return new RTRecyclerViewAdapter(dialog); }
    protected void onPositive(Context context, ActivityResultLauncher<Object> launcher) {
        CharSequence[] opts = {
                context.getString(R.string.zyrox_jre_download_online),
                context.getString(R.string.zyrox_pkg_import_local)
        };
        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.multirt_config_title)
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        git.artdeell.mojo.downloader.RuntimeDriverStoreDialog.showJreStore(context, this::refresh);
                    } else {
                        launcher.launch(null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
