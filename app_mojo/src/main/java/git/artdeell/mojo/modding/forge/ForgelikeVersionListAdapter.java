package git.artdeell.mojo.modding.forge;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListAdapter;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import git.artdeell.mojo.metadata.ForgeMetadataProvider;

public class ForgelikeVersionListAdapter extends BaseExpandableListAdapter implements ExpandableListAdapter {
    private final List<String> mGameVersions;
    private final List<List<String>> mLoaderVersions;
    private final Map<String, String> mRecommendedFullByMc;
    private final LayoutInflater mLayoutInflater;

    public ForgelikeVersionListAdapter(List<String> forgeVersions, LayoutInflater layoutInflater,
                                       ForgelikeUtils utils) {
        this(forgeVersions, layoutInflater, utils, Collections.emptyMap());
    }

    public ForgelikeVersionListAdapter(List<String> forgeVersions, LayoutInflater layoutInflater,
                                       ForgelikeUtils utils,
                                       @Nullable Map<String, String> recommendedLoaderByMc) {
        this.mLayoutInflater = layoutInflater;
        mGameVersions = new ArrayList<>();
        mLoaderVersions = new ArrayList<>();
        mRecommendedFullByMc = new HashMap<>();

        if (forgeVersions != null) {
            for (String version : forgeVersions) {
                if (version == null || utils.shouldSkipVersion(version)) continue;
                String gameVersion = utils.processVersionString(version);
                if (gameVersion == null || gameVersion.isEmpty()) continue;

                List<String> versionList;
                int gameVersionIndex = mGameVersions.indexOf(gameVersion);
                if (gameVersionIndex != -1) {
                    versionList = mLoaderVersions.get(gameVersionIndex);
                } else {
                    versionList = new ArrayList<>();
                    mGameVersions.add(gameVersion);
                    mLoaderVersions.add(versionList);
                }
                if (!versionList.contains(version)) {
                    versionList.add(version);
                }
            }
        }

        if (utils.isVersionOrderInversed()) {
            for (List<String> versionList : mLoaderVersions) {
                reverseList(versionList);
            }
            reverseList(mLoaderVersions);
            reverseList(mGameVersions);
        }

        if (recommendedLoaderByMc != null && !recommendedLoaderByMc.isEmpty()) {
            for (int i = 0; i < mGameVersions.size(); i++) {
                String mc = mGameVersions.get(i);
                String recLoader = recommendedLoaderByMc.get(mc);
                if (recLoader == null) continue;
                String full = ForgeMetadataProvider.findCompatibleFullVersion(
                        mLoaderVersions.get(i), mc, recLoader);
                if (full != null) {
                    mRecommendedFullByMc.put(mc, full);
                }
            }
        }
    }

    @Override
    public int getGroupCount() {
        return mGameVersions.size();
    }

    @Override
    public int getChildrenCount(int i) {
        return mLoaderVersions.get(i).size();
    }

    @Override
    public Object getGroup(int i) {
        return getGameVersion(i);
    }

    @Override
    public Object getChild(int i, int i1) {
        // Always return the raw verified Maven artifact version for installer execution
        return getForgeVersion(i, i1);
    }

    @Override
    public long getGroupId(int i) {
        return i;
    }

    @Override
    public long getChildId(int i, int i1) {
        return i1;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getGroupView(int i, boolean b, View convertView, ViewGroup viewGroup) {
        if (convertView == null)
            convertView = mLayoutInflater.inflate(android.R.layout.simple_expandable_list_item_1, viewGroup, false);

        ((TextView) convertView).setText(getGameVersion(i));

        return convertView;
    }

    @Override
    public View getChildView(int i, int i1, boolean b, View convertView, ViewGroup viewGroup) {
        if (convertView == null)
            convertView = mLayoutInflater.inflate(android.R.layout.simple_expandable_list_item_1, viewGroup, false);
        String mc = getGameVersion(i);
        String version = getForgeVersion(i, i1);
        String rec = mRecommendedFullByMc.get(mc);
        String label = version.equals(rec) ? (version + " ★") : version;
        ((TextView) convertView).setText(label);
        return convertView;
    }

    private String getGameVersion(int i) {
        return mGameVersions.get(i);
    }

    private String getForgeVersion(int i, int i1) {
        return mLoaderVersions.get(i).get(i1);
    }

    @Override
    public boolean isChildSelectable(int i, int i1) {
        return true;
    }

    private static <T> void reverseList(List<T> list) {
        for (int i = 0, j = list.size() - 1; i < j; i++, j--) {
            T temp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, temp);
        }
    }
}
