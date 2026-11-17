package git.artdeell.mojo.utils.profiles;

import static git.artdeell.mojo.extra.ExtraCore.getValue;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.atomic.AtomicReference;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.metadata.LocalMetadataCache;
import git.artdeell.mojo.metadata.VersionMetadataRepository;

public class VersionSelectorDialog {
    public static void open(Context context, boolean hideCustomVersions, VersionSelectorListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        ExpandableListView expandableListView = (ExpandableListView) LayoutInflater.from(context)
                .inflate(R.layout.dialog_expendable_list_view, null);

        JVersionList initialList = (JVersionList) getValue(ExtraConstants.RELEASE_TABLE);
        if (initialList == null || initialList.versions == null) {
            initialList = VersionMetadataRepository.getInstance().getCurrentCatalog();
        }
        JVersionList.Version[] initialArray =
                (initialList != null && initialList.versions != null)
                        ? initialList.versions
                        : new JVersionList.Version[0];

        AtomicReference<VersionListAdapter> adapterRef =
                new AtomicReference<>(new VersionListAdapter(initialArray, hideCustomVersions, context));
        expandableListView.setAdapter(adapterRef.get());

        builder.setView(expandableListView);
        builder.setNeutralButton(R.string.zyrox_refresh_versions, null);
        builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.show();

        // Keep open dialog synchronized if background startup refresh publishes new versions
        ExtraListener<JVersionList> releaseListener = (key, value) -> {
            if (value == null || value.versions == null) return false;
            Tools.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                rebindAdapterPreservingExpansion(expandableListView, adapterRef, value.versions, hideCustomVersions, context);
            });
            return false;
        };
        ExtraCore.addExtraListener(ExtraConstants.RELEASE_TABLE, releaseListener);
        dialog.setOnDismissListener(d ->
                ExtraCore.removeExtraListenerFromValue(ExtraConstants.RELEASE_TABLE, releaseListener));

        // Override neutral button click so tapping Refresh updates catalog live without closing dialog
        Button refreshButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> triggerManualRefresh(
                    context, dialog, refreshButton, expandableListView, adapterRef, hideCustomVersions));
        }

        // If catalog was completely empty on open (e.g., connectivity just came back), auto-sync once
        if (initialArray.length == 0 && refreshButton != null) {
            triggerManualRefresh(context, dialog, refreshButton, expandableListView, adapterRef, hideCustomVersions);
        }

        expandableListView.setOnChildClickListener((parent, v1, groupPosition, childPosition, id) -> {
            VersionListAdapter currentAdapter = adapterRef.get();
            String version = currentAdapter.getChild(groupPosition, childPosition);
            listener.onVersionSelected(version, currentAdapter.isSnapshotSelected(groupPosition));
            dialog.dismiss();
            return true;
        });
    }

    private static void triggerManualRefresh(Context context,
                                             AlertDialog dialog,
                                             Button refreshButton,
                                             ExpandableListView listView,
                                             AtomicReference<VersionListAdapter> adapterRef,
                                             boolean hideCustomVersions) {
        refreshButton.setEnabled(false);
        VersionMetadataRepository.getInstance().refreshMinecraftVersions(
                new VersionMetadataRepository.CatalogCallback() {
                    @Override
                    public void onCatalogAvailable(@NonNull JVersionList catalog,
                                                   @NonNull LocalMetadataCache.SyncSource source,
                                                   boolean changed) {
                        ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, catalog);
                        Tools.runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            refreshButton.setEnabled(true);
                            rebindAdapterPreservingExpansion(
                                    listView, adapterRef, catalog.versions, hideCustomVersions, context);
                            if (source == LocalMetadataCache.SyncSource.OFFLINE_CACHE_FALLBACK) {
                                Toast.makeText(context, R.string.zyrox_versions_offline_cached, Toast.LENGTH_SHORT).show();
                            } else if (changed || source == LocalMetadataCache.SyncSource.UPDATED_FROM_NETWORK) {
                                Toast.makeText(context, R.string.zyrox_versions_updated, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, R.string.zyrox_versions_up_to_date, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onSyncError(@NonNull Exception error, boolean hasCachedCatalog) {
                        if (hasCachedCatalog) return; // Handled cleanly via OFFLINE_CACHE_FALLBACK above
                        Tools.runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            refreshButton.setEnabled(true);
                            Toast.makeText(context, R.string.zyrox_versions_refresh_failed, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        );
    }

    private static void rebindAdapterPreservingExpansion(ExpandableListView listView,
                                                         AtomicReference<VersionListAdapter> adapterRef,
                                                         JVersionList.Version[] versions,
                                                         boolean hideCustomVersions,
                                                         Context context) {
        VersionListAdapter oldAdapter = adapterRef.get();
        int oldGroupCount = oldAdapter != null ? oldAdapter.getGroupCount() : 0;
        boolean[] expanded = new boolean[oldGroupCount];
        for (int i = 0; i < oldGroupCount; i++) {
            expanded[i] = listView.isGroupExpanded(i);
        }

        VersionListAdapter freshAdapter = new VersionListAdapter(versions, hideCustomVersions, context);
        adapterRef.set(freshAdapter);
        listView.setAdapter(freshAdapter);

        int restoreCount = Math.min(expanded.length, freshAdapter.getGroupCount());
        for (int i = 0; i < restoreCount; i++) {
            if (expanded[i]) {
                listView.expandGroup(i);
            }
        }
    }
}
