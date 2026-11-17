package git.artdeell.mojo.utils.profiles;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListAdapter;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.metadata.MojangVersionProvider;
import git.artdeell.mojo.utils.FilteredSubList;

public class VersionListAdapter extends BaseExpandableListAdapter implements ExpandableListAdapter {

    private final LayoutInflater mLayoutInflater;

    private final String[] mGroups;
    private final String[] mInstalledVersions;
    private final List<?>[] mData;
    private final boolean mHideCustomVersions;
    private final int mSnapshotListPosition;

    public VersionListAdapter(JVersionList.Version[] versionList, boolean hideCustomVersions, Context ctx) {
        mHideCustomVersions = hideCustomVersions;
        mLayoutInflater = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        JVersionList.Version[] cleanVersions = deduplicateVersions(versionList);

        List<JVersionList.Version> releaseList = new FilteredSubList<>(
                cleanVersions, item -> item != null && MojangVersionProvider.TYPE_RELEASE.equals(item.type));
        List<JVersionList.Version> snapshotList = new FilteredSubList<>(
                cleanVersions, item -> item != null && MojangVersionProvider.TYPE_SNAPSHOT.equals(item.type));
        List<JVersionList.Version> betaList = new FilteredSubList<>(
                cleanVersions, item -> item != null && MojangVersionProvider.TYPE_OLD_BETA.equals(item.type));
        List<JVersionList.Version> alphaList = new FilteredSubList<>(
                cleanVersions, item -> item != null && MojangVersionProvider.TYPE_OLD_ALPHA.equals(item.type));

        // Query existing installed versions while filtering stray empty folders/files
        mInstalledVersions = queryInstalledVersions(new File(Tools.DIR_GAME_NEW, "versions"));

        if (!areInstalledVersionsAvailable()) {
            mGroups = new String[]{
                    ctx.getString(R.string.mcl_setting_veroption_release),
                    ctx.getString(R.string.mcl_setting_veroption_snapshot),
                    ctx.getString(R.string.mcl_setting_veroption_oldbeta),
                    ctx.getString(R.string.mcl_setting_veroption_oldalpha)
            };
            mData = new List[]{releaseList, snapshotList, betaList, alphaList};
            mSnapshotListPosition = 1;
        } else {
            mGroups = new String[]{
                    ctx.getString(R.string.mcl_setting_veroption_installed),
                    ctx.getString(R.string.mcl_setting_veroption_release),
                    ctx.getString(R.string.mcl_setting_veroption_snapshot),
                    ctx.getString(R.string.mcl_setting_veroption_oldbeta),
                    ctx.getString(R.string.mcl_setting_veroption_oldalpha)
            };
            mData = new List[]{Arrays.asList(mInstalledVersions), releaseList, snapshotList, betaList, alphaList};
            mSnapshotListPosition = 2;
        }
    }

    private static JVersionList.Version[] deduplicateVersions(JVersionList.Version[] raw) {
        if (raw == null || raw.length == 0) return new JVersionList.Version[0];
        LinkedHashMap<String, JVersionList.Version> unique = new LinkedHashMap<>(raw.length);
        for (JVersionList.Version v : raw) {
            if (v == null || v.id == null || v.id.isEmpty() || v.type == null) continue;
            if (!unique.containsKey(v.id)) {
                unique.put(v.id, v);
            }
        }
        return unique.values().toArray(new JVersionList.Version[0]);
    }

    private static String[] queryInstalledVersions(File versionsRoot) {
        File[] entries = versionsRoot.listFiles();
        if (entries == null || entries.length == 0) return null;
        ArrayList<String> valid = new ArrayList<>(entries.length);
        for (File dir : entries) {
            if (dir == null || !dir.isDirectory()) continue;
            String name = dir.getName();
            File versionJson = new File(dir, name + ".json");
            if (versionJson.isFile() && versionJson.length() > 0) {
                valid.add(name);
            }
        }
        if (valid.isEmpty()) return null;
        String[] arr = valid.toArray(new String[0]);
        Arrays.sort(arr);
        return arr;
    }

    @Override
    public int getGroupCount() {
        return mGroups.length;
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return mData[groupPosition].size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return mData[groupPosition];
    }

    @Override
    public String getChild(int groupPosition, int childPosition) {
        if (isInstalledVersionSelected(groupPosition)) {
            return mInstalledVersions[childPosition];
        }
        return ((JVersionList.Version) mData[groupPosition].get(childPosition)).id;
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        if (convertView == null)
            convertView = mLayoutInflater.inflate(android.R.layout.simple_expandable_list_item_1, parent, false);

        ((TextView) convertView).setText(mGroups[groupPosition]);

        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        if (convertView == null)
            convertView = mLayoutInflater.inflate(android.R.layout.simple_expandable_list_item_1, parent, false);
        ((TextView) convertView).setText(getChild(groupPosition, childPosition));
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public boolean isSnapshotSelected(int groupPosition) {
        return groupPosition == mSnapshotListPosition;
    }

    private boolean areInstalledVersionsAvailable() {
        if (mHideCustomVersions) return false;
        return !(mInstalledVersions == null || mInstalledVersions.length == 0);
    }

    private boolean isInstalledVersionSelected(int groupPosition) {
        return groupPosition == 0 && areInstalledVersionsAvailable();
    }
}
