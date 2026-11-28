package git.artdeell.mojo.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.servers.ServerListManager;
import git.artdeell.mojo.servers.ServerPinger;
import git.artdeell.mojo.ui.ZyroxStudioUi;
import git.artdeell.mojo.utils.ArchiveSecurity;

/**
 * Zyrox 2.0 Multiplayer Server Manager synced natively with Minecraft's {@code servers.dat}.
 */
public class ServerManagerFragment extends Fragment {
    public static final String TAG = "SERVER_MANAGER_FRAGMENT";

    private Instance mInstance;
    private TextView mHeaderLine;
    private LinearLayout mListContainer;
    private final List<ServerListManager.ServerEntry> mServers = new ArrayList<>();
    private final List<Instance> mCachedInstances = new ArrayList<>();

    private final ActivityResultLauncher<String> mExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onExportSelected);
    private final ActivityResultLauncher<String[]> mImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportSelected);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = ZyroxStudioUi.createGlassScreenRoot(ctx);
        LinearLayout panel = root.findViewById(android.R.id.content);

        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        mHeaderLine = ZyroxStudioUi.createSubtitle(ctx, "Loading saved servers...");
        topBar.addView(mHeaderLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnLan = ZyroxStudioUi.createSecondaryButton(ctx, "Scan LAN", true);
        btnLan.setOnClickListener(v -> runLanWorldScan());
        topBar.addView(btnLan, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnHostRoom = ZyroxStudioUi.createSecondaryButton(ctx, "Host ZYX", true);
        btnHostRoom.setOnClickListener(v -> showHostRoomBridgeDialog());
        topBar.addView(btnHostRoom, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnJoinRoom = ZyroxStudioUi.createSecondaryButton(ctx, "Join ZYX", true);
        btnJoinRoom.setOnClickListener(v -> showJoinRoomBridgeDialog());
        topBar.addView(btnJoinRoom, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnAiFriend = ZyroxStudioUi.createSecondaryButton(ctx, "AI Friend", true);
        btnAiFriend.setOnClickListener(v -> {
            ZyroxRoomBridge.HostSession activeRoom = ZyroxRoomBridge.getActiveHost();
            Bundle args = activeRoom != null
                    ? AiFriendFragment.createPresetArgs(
                            git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode.ZYROX_SELF_HOSTED,
                            null,
                            activeRoom.fullShareCode,
                            AiFriendFragment.TAB_WORLD_BRIDGE)
                    : AiFriendFragment.createPresetArgs(
                            git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode.SAVED_SERVER,
                            null,
                            null,
                            AiFriendFragment.TAB_WORLD_BRIDGE);
            Tools.swapFragment(requireActivity(), AiFriendFragment.class, AiFriendFragment.TAG, args);
        });
        topBar.addView(btnAiFriend, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnPingAll = ZyroxStudioUi.createSecondaryButton(ctx, "Ping All", true);
        btnPingAll.setOnClickListener(v -> pingAllServers());
        topBar.addView(btnPingAll, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import", true);
        btnImport.setOnClickListener(v -> mImportLauncher.launch(new String[]{"*/*"}));
        topBar.addView(btnImport, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnExport = ZyroxStudioUi.createSecondaryButton(ctx, "Export", true);
        btnExport.setOnClickListener(v -> mExportLauncher.launch("zyrox-servers.json"));
        topBar.addView(btnExport, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnAdd = ZyroxStudioUi.createPrimaryButton(ctx, "+ Add Server");
        btnAdd.setOnClickListener(v -> showEditDialog(null));
        topBar.addView(btnAdd, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 6)));

        panel.addView(topBar);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        mListContainer = new LinearLayout(ctx);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mListContainer);
        panel.addView(scroll, sLp);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mInstance = Instances.loadSelectedInstance();
        loadServerList();
    }

    private void loadServerList() {
        if (mInstance == null) {
            mHeaderLine.setText(R.string.no_instance);
            return;
        }
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<ServerListManager.ServerEntry> list =
                        ServerListManager.loadServers(mInstance.getGameDirectory());
                List<Instance> allInstances = Collections.emptyList();
                try {
                    allInstances = Instances.loadAllInstances();
                } catch (Exception ignored) {
                }
                final List<Instance> finalInst = allInstances;
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mCachedInstances.clear();
                    if (finalInst != null) mCachedInstances.addAll(finalInst);
                    mServers.clear();
                    mServers.addAll(list);
                    sortFavoritesFirst();
                    renderList();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) mHeaderLine.setText("Error reading servers.dat: " + e.getMessage());
                });
            }
        });
    }

    private void saveAndRender() {
        sortFavoritesFirst();
        renderList();
        if (mInstance == null) return;
        final List<ServerListManager.ServerEntry> copy = new ArrayList<>(mServers);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ServerListManager.saveServers(mInstance.getGameDirectory(), copy);
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void sortFavoritesFirst() {
        Collections.sort(mServers, (a, b) -> Boolean.compare(b.favorite, a.favorite));
    }

    private void renderList() {
        Context ctx = requireContext();
        mListContainer.removeAllViews();
        mHeaderLine.setText(mInstance.name + " • " + mServers.size() + " saved server(s) in servers.dat");

        if (mServers.isEmpty()) {
            LinearLayout guideCard = ZyroxStudioUi.createItemCard(ctx);
            int pad = ZyroxStudioUi.dp(ctx, 14);
            guideCard.setPadding(pad, pad, pad, pad);
            guideCard.addView(ZyroxStudioUi.createTitle(ctx, "Ready for Multiplayer"));
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "Add popular verified Java servers in 1 tap, scan your local Wi-Fi for active LAN worlds, "
                            + "or enter any server IP — Zyrox syncs directly with servers.dat and auto-matches the right Minecraft version.");
            empty.setPadding(0, ZyroxStudioUi.dp(ctx, 4), 0, ZyroxStudioUi.dp(ctx, 12));
            guideCard.addView(empty);

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);

            Button btnPopular = ZyroxStudioUi.createPrimaryButton(ctx, "Add Popular Servers");
            btnPopular.setOnClickListener(v -> addPopularServersStarterPack());
            actions.addView(btnPopular, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36)));

            Button btnScanLan = ZyroxStudioUi.createSecondaryButton(ctx, "Scan Local LAN Worlds", false);
            btnScanLan.setOnClickListener(v -> runLanWorldScan());
            actions.addView(btnScanLan, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36), ZyroxStudioUi.dp(ctx, 8)));

            Button btnCustom = ZyroxStudioUi.createSecondaryButton(ctx, "+ Custom IP", false);
            btnCustom.setOnClickListener(v -> showEditDialog(null));
            actions.addView(btnCustom, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 36), ZyroxStudioUi.dp(ctx, 8)));

            guideCard.addView(actions);
            mListContainer.addView(guideCard);
            return;
        }

        for (ServerListManager.ServerEntry s : mServers) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            android.widget.ImageView iconView = new android.widget.ImageView(ctx);
            int iconSz = ZyroxStudioUi.dp(ctx, 40);
            iconView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iconView.setBackgroundResource(R.drawable.bg_zyrox_glass_card_soft);
            android.graphics.Bitmap favBmp = decodeServerFavicon(s.iconBase64);
            if (favBmp != null) {
                iconView.setImageBitmap(favBmp);
            } else {
                iconView.setImageResource(R.drawable.ic_zyrox_grass_block);
                int ip = ZyroxStudioUi.dp(ctx, 7);
                iconView.setPadding(ip, ip, ip, ip);
            }
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(iconSz, iconSz);
            icLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 9));
            row.addView(iconView, icLp);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            iLp.setMarginEnd(ZyroxStudioUi.dp(ctx, 8));

            String title = (s.favorite ? "[PIN] " : "") + s.name
                    + (s.group.isEmpty() ? "" : "  [" + s.group + "]");
            info.addView(ZyroxStudioUi.createTitle(ctx, title));

            android.text.SpannableStringBuilder richSub = new android.text.SpannableStringBuilder(s.address);
            if (s.controlProfile != null && !s.controlProfile.isEmpty()) {
                richSub.append(" • Controls: ")
                        .append(git.artdeell.mojo.input.ControlProfileManager
                                .formatProfileBadge(s.controlProfile));
            }
            if (s.lastPingMs >= 0 && s.lastOnline) {
                richSub.append(" • ONLINE ").append(String.valueOf(s.lastPingMs)).append("ms");
                if (s.lastPlayers != null) richSub.append(" • ").append(s.lastPlayers).append(" players");
                if (s.lastVersion != null && !s.lastVersion.isEmpty()) richSub.append(" • ").append(s.lastVersion);
                if (s.lastMotd != null && !s.lastMotd.isEmpty()) {
                    richSub.append("\n");
                    appendMinecraftFormatted(richSub, s.lastMotd);
                }
            } else if (s.lastMotd != null) {
                richSub.append(" • ");
                appendMinecraftFormatted(richSub, s.lastMotd);
            }
            TextView sub = ZyroxStudioUi.createSubtitle(ctx, "");
            sub.setText(richSub);
            info.addView(sub);
            info.setOnClickListener(v -> copyAddress(s.address));
            row.addView(info, iLp);

            Button btnFav = ZyroxStudioUi.createSecondaryButton(ctx, s.favorite ? "Unstar" : "Star", true);
            btnFav.setOnClickListener(v -> {
                s.favorite = !s.favorite;
                saveAndRender();
            });
            row.addView(btnFav, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnPing = ZyroxStudioUi.createSecondaryButton(ctx, "Ping", true);
            btnPing.setOnClickListener(v -> pingSingle(s));
            row.addView(btnPing, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            Button btnEdit = ZyroxStudioUi.createSecondaryButton(ctx, "Edit", true);
            btnEdit.setOnClickListener(v -> showEditDialog(s));
            row.addView(btnEdit, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 4)));

            final String reqMc = s.lastOnline ? s.lastResolvedMcVersion : null;
            final Instance matchingOther = findMatchingInstance(reqMc);
            final boolean currentMatches = reqMc == null
                    || ServerPinger.doesInstanceMatchVersion(mInstance.versionId, reqMc)
                    || Instance.isLatestReleaseId(mInstance.versionId)
                    || Instance.isLatestFabricId(mInstance.versionId);

            if (reqMc != null && !currentMatches && matchingOther == null) {
                Button btnCreateJoin = ZyroxStudioUi.createPrimaryButton(
                        ctx, "Create " + reqMc + " Instance & Join");
                btnCreateJoin.setOnClickListener(v -> createMatchedInstanceAndJoin(s, reqMc));
                row.addView(btnCreateJoin, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));
            } else if (reqMc != null && !currentMatches) {
                Button btnSwitchJoin = ZyroxStudioUi.createPrimaryButton(
                        ctx, "Switch " + reqMc + " & Join");
                btnSwitchJoin.setOnClickListener(v -> switchInstanceAndJoin(matchingOther, s));
                row.addView(btnSwitchJoin, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));
            } else {
                Button btnJoin = ZyroxStudioUi.createPrimaryButton(ctx, "Join");
                btnJoin.setOnClickListener(v -> quickJoin(s));
                row.addView(btnJoin, ZyroxStudioUi.spacedParam(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));
            }

            Button btnAiBot = ZyroxStudioUi.createSecondaryButton(ctx, "AI Bot", true);
            btnAiBot.setOnClickListener(v -> Tools.swapFragment(
                    requireActivity(),
                    AiFriendFragment.class,
                    AiFriendFragment.TAG,
                    AiFriendFragment.createPresetArgs(
                            git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode.SAVED_SERVER,
                            s.address,
                            null,
                            AiFriendFragment.TAB_WORLD_BRIDGE)));
            row.addView(btnAiBot, ZyroxStudioUi.spacedParam(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

            card.addView(row);
            mListContainer.addView(card);
        }
    }

    private void showEditDialog(@Nullable ServerListManager.ServerEntry existing) {
        Context ctx = requireContext();
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = ZyroxStudioUi.dp(ctx, 14);
        form.setPadding(p, p, p, ZyroxStudioUi.dp(ctx, 4));

        EditText etName = ZyroxStudioUi.createGlassSearchInput(ctx, "Server Name (e.g. Hypixel)");
        EditText etAddr = ZyroxStudioUi.createGlassSearchInput(ctx, "Address[:Port] (e.g. mc.example.net:25565)");
        EditText etGroup = ZyroxStudioUi.createGlassSearchInput(ctx, "Optional Group Tag (e.g. Survival, PvP)");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ZyroxStudioUi.dp(ctx, 38));
        gap.topMargin = ZyroxStudioUi.dp(ctx, 8);

        if (existing != null) {
            etName.setText(existing.name);
            etAddr.setText(existing.address);
            etGroup.setText(existing.group);
        }
        form.addView(etName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ZyroxStudioUi.dp(ctx, 38)));
        form.addView(etAddr, gap);
        form.addView(etGroup, gap);

        // Per-Server Control Profile Auto-Switch selector
        final String[] selectedControlProfile = new String[]{
                existing != null ? existing.controlProfile : null
        };
        final Button btnCtrlProfile = ZyroxStudioUi.createSecondaryButton(
                ctx, "Control Profile: " + git.artdeell.mojo.input.ControlProfileManager
                        .formatProfileBadge(selectedControlProfile[0]), false);
        LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ZyroxStudioUi.dp(ctx, 38));
        ctrlLp.topMargin = ZyroxStudioUi.dp(ctx, 8);
        btnCtrlProfile.setOnClickListener(v ->
                git.artdeell.mojo.input.ControlProfileManager.showServerControlProfilePicker(
                        ctx, selectedControlProfile[0], chosen -> {
                            selectedControlProfile[0] = chosen;
                            btnCtrlProfile.setText("Control Profile: "
                                    + git.artdeell.mojo.input.ControlProfileManager
                                    .formatProfileBadge(chosen));
                        }));
        form.addView(btnCtrlProfile, ctrlLp);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(existing == null ? "Add Minecraft Server" : "Edit Server")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String rawAddr = etAddr.getText().toString().trim();
                    String rawName = etName.getText().toString().trim();
                    String rawGroup = etGroup.getText().toString().trim();
                    try {
                        ServerListManager.parseAndValidateAddress(rawAddr);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(ctx, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (existing == null) {
                        ServerListManager.ServerEntry entry = new ServerListManager.ServerEntry(
                                rawName.isEmpty() ? rawAddr : rawName, rawAddr);
                        entry.group = rawGroup;
                        entry.controlProfile = ServerListManager
                                .sanitizeControlProfileName(selectedControlProfile[0]);
                        mServers.add(entry);
                    } else {
                        existing.name = rawName.isEmpty() ? rawAddr : rawName;
                        existing.address = rawAddr;
                        existing.group = rawGroup;
                        existing.controlProfile = ServerListManager
                                .sanitizeControlProfileName(selectedControlProfile[0]);
                    }
                    saveAndRender();
                })
                .setNegativeButton(android.R.string.cancel, null);

        if (existing != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                mServers.remove(existing);
                saveAndRender();
            });
        }
        builder.show();
    }

    private void pingSingle(@NonNull ServerListManager.ServerEntry server) {
        server.lastMotd = "Checking status...";
        renderList();
        MojoApplication.sExecutorService.execute(() -> {
            ServerPinger.PingResult res = ServerPinger.ping(server.address);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                server.lastOnline = res.online;
                server.lastPingMs = res.latencyMs;
                if (res.online) {
                    server.lastPlayers = (res.onlinePlayers >= 0 && res.maxPlayers >= 0)
                            ? (res.onlinePlayers + "/" + res.maxPlayers) : null;
                    server.lastVersion = res.versionName;
                    server.lastProtocol = res.protocolVersion;
                    server.lastResolvedMcVersion = res.resolvedMcVersion;
                    server.lastMotd = !res.formattedMotd.isEmpty() ? res.formattedMotd : res.motd;
                    if (res.faviconBase64 != null) server.iconBase64 = res.faviconBase64;
                } else {
                    server.lastProtocol = -1;
                    server.lastResolvedMcVersion = null;
                    server.lastMotd = "Offline (" + (res.errorMessage != null ? res.errorMessage : "unreachable") + ")";
                }
                renderList();
            });
        });
    }

    private void pingAllServers() {
        for (ServerListManager.ServerEntry s : new ArrayList<>(mServers)) {
            pingSingle(s);
        }
    }

    private void addPopularServersStarterPack() {
        String[][] curated = new String[][]{
                {"Hypixel Network", "mc.hypixel.net", "Minigames"},
                {"Wynncraft MMORPG", "play.wynncraft.com", "RPG"},
                {"CubeCraft Games", "play.cubecraft.net", "Minigames"},
                {"Minemen Club", "minemen.club", "PvP"}
        };
        for (String[] row : curated) {
            ServerListManager.ServerEntry entry = new ServerListManager.ServerEntry(row[0], row[1]);
            entry.group = row[2];
            mServers.add(entry);
        }
        saveAndRender();
        pingAllServers();
    }

    /**
     * Hosts a local "Open to LAN" Minecraft world via {@link git.artdeell.mojo.servers.ZyroxRoomBridge},
     * generating a 1-tap shareable {@code ZYX-XXXX} room code with zero port-forwarding hassle on
     * Wi-Fi/Hotspot and a full direct P2P token for cross-subnet friends.
     */
    private void showHostRoomBridgeDialog() {
        final Context ctx = requireContext();
        final git.artdeell.mojo.servers.ZyroxRoomBridge.HostSession active =
                git.artdeell.mojo.servers.ZyroxRoomBridge.getActiveHost();
        if (active != null) {
            String status = "Active Room Code:  " + active.shortCode
                    + "\nDirect P2P Code:  " + active.fullShareCode
                    + "\nLocal LAN Port:  " + active.localMcPort
                    + "  ·  Connected Peers:  " + active.getConnectedPeerCount()
                    + "\n\nNetwork Status: " + active.networkDiagnostic;
            new AlertDialog.Builder(ctx)
                    .setTitle("Zyrox Room Active • " + active.shortCode)
                    .setMessage(status)
                    .setPositiveButton("Copy Full Code", (d, w) -> {
                        ClipboardManager cm =
                                (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText(
                                    "Zyrox Room Code", active.fullShareCode));
                        }
                        Toast.makeText(ctx, "Copied " + active.fullShareCode, Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("Stop Hosting", (d, w) -> {
                        active.close();
                        Toast.makeText(ctx, "Room " + active.shortCode + " closed.",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        form.setPadding(pad, ZyroxStudioUi.dp(ctx, 10), pad, ZyroxStudioUi.dp(ctx, 4));

        TextView hint = new TextView(ctx);
        hint.setText("Open your singleplayer world -> Pause -> Open to LAN, then enter the LAN port below (default 25565) to generate an instant ZYX-XXXX room code:");
        hint.setTextColor(0xFFD6E4F8);
        hint.setTextSize(12f);
        form.addView(hint);

        final EditText portInput = new EditText(ctx);
        portInput.setHint("Minecraft LAN Port (e.g. 25565)");
        portInput.setText("25565");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setSingleLine(true);
        form.addView(portInput);

        final EditText titleInput = new EditText(ctx);
        titleInput.setHint("Room Name (optional)");
        titleInput.setText(mInstance != null && Tools.isValidString(mInstance.name)
                ? mInstance.name + " LAN" : "Zyrox Co-Op World");
        titleInput.setSingleLine(true);
        form.addView(titleInput);

        new AlertDialog.Builder(ctx)
                .setTitle("Host 1-Tap ZYX Room Bridge")
                .setView(form)
                .setPositiveButton("Start Room", (d, w) -> {
                    int port;
                    try {
                        port = Integer.parseInt(portInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, "Enter a valid port (1024–65535).",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String roomTitle = titleInput.getText().toString().trim();
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            git.artdeell.mojo.servers.ZyroxRoomBridge.HostSession session =
                                    git.artdeell.mojo.servers.ZyroxRoomBridge.startHosting(
                                            port, roomTitle);
                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                ClipboardManager cm = (ClipboardManager)
                                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText(
                                            "Zyrox Room Code", session.fullShareCode));
                                }
                                Toast.makeText(requireContext(),
                                        "Room live! Copied code: " + session.shortCode,
                                        Toast.LENGTH_LONG).show();
                                showHostRoomBridgeDialog();
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (isAdded()) {
                                    Toast.makeText(requireContext(),
                                            "Host failed: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Connects to a friend's {@code ZYX-XXXX} room code, pings the tunneled world to auto-match
     * the required Minecraft protocol version against local instances, and joins in 1 tap.
     */
    private void showJoinRoomBridgeDialog() {
        final Context ctx = requireContext();
        final EditText codeInput = new EditText(ctx);
        codeInput.setHint("ZYX-7K9M  or  ZYX-7K9M-...");
        codeInput.setSingleLine(true);
        int pad = ZyroxStudioUi.dp(ctx, 16);
        codeInput.setPadding(pad, pad, pad, pad);

        // Auto-prefill if clipboard already holds a ZYX- room code
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && clip.toString().trim().toUpperCase(java.util.Locale.ROOT)
                        .startsWith(git.artdeell.mojo.servers.ZyroxRoomBridge.ROOM_PREFIX)) {
                    codeInput.setText(clip.toString().trim());
                }
            }
        } catch (Throwable ignored) {
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Join Friend's ZYX Room")
                .setMessage("Enter your friend's ZYX-XXXX room code. Zyrox automatically tunnels the session and matches your Minecraft version.")
                .setView(codeInput)
                .setPositiveButton("Connect & Join", (d, w) -> {
                    final String rawCode = codeInput.getText().toString().trim();
                    Toast.makeText(ctx, "Connecting to " + rawCode + "…",
                            Toast.LENGTH_SHORT).show();
                    MojoApplication.sExecutorService.execute(() -> {
                        try {
                            git.artdeell.mojo.servers.ZyroxRoomBridge.ClientTunnel tunnel =
                                    git.artdeell.mojo.servers.ZyroxRoomBridge.connectToRoom(rawCode);
                            // Persist cross-process handoff so :game binds its own 127.0.0.1 tunnel
                            git.artdeell.mojo.servers.ZyroxRoomBridge
                                    .persistPendingTunnelForGameLaunch(tunnel);
                            ServerListManager.ServerEntry entry = new ServerListManager.ServerEntry(
                                    tunnel.worldTitle, tunnel.getLoopbackAddress());
                            entry.group = "ZYX Room";

                            // Probe the live tunneled world to auto-match its MC protocol version
                            ServerPinger.PingResult ping =
                                    ServerPinger.ping(tunnel.getLoopbackAddress());
                            final String reqMc = ping.online ? ping.resolvedMcVersion : null;
                            entry.lastOnline = ping.online;
                            entry.lastPingMs = ping.latencyMs;
                            entry.lastProtocol = ping.protocolVersion;
                            entry.lastVersion = ping.versionName;
                            entry.lastResolvedMcVersion = reqMc;

                            Tools.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                for (int idx = mServers.size() - 1; idx >= 0; idx--) {
                                    ServerListManager.ServerEntry cur = mServers.get(idx);
                                    if ("ZYX Room".equals(cur.group)
                                            && cur.name.equals(entry.name)) {
                                        mServers.remove(idx);
                                    }
                                }
                                mServers.add(0, entry);
                                saveAndRender();

                                boolean curOk = reqMc == null || mInstance == null
                                        || ServerPinger.doesInstanceMatchVersion(
                                            mInstance.versionId, reqMc)
                                        || Instance.isLatestReleaseId(mInstance.versionId)
                                        || Instance.isLatestFabricId(mInstance.versionId);
                                if (!curOk) {
                                    Instance match = findMatchingInstance(reqMc);
                                    if (match != null) {
                                        switchInstanceAndJoin(match, entry);
                                        return;
                                    }
                                    createMatchedInstanceAndJoin(entry, reqMc);
                                    return;
                                }
                                quickJoin(entry);
                            });
                        } catch (Exception e) {
                            Tools.runOnUiThread(() -> {
                                if (isAdded()) {
                                    Toast.makeText(requireContext(),
                                            e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runLanWorldScan() {
        final Context ctx = requireContext();
        Toast.makeText(ctx, "Scanning local Wi-Fi (224.0.2.60:4445) for LAN worlds…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            final List<ServerPinger.LanWorldEntry> lanWorlds =
                    ServerPinger.scanLocalLanWorlds(2200);
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (lanWorlds.isEmpty()) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("No Local LAN Worlds Found")
                            .setMessage("Make sure the host opened their world via Pause → Open to LAN on the same Wi-Fi network or mobile hotspot, then scan again.")
                            .setPositiveButton("Scan Again", (d, w) -> runLanWorldScan())
                            .setNegativeButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                String[] labels = new String[lanWorlds.size()];
                for (int i = 0; i < lanWorlds.size(); i++) {
                    ServerPinger.LanWorldEntry e = lanWorlds.get(i);
                    labels[i] = e.motd + "  (" + e.address + ")";
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle("Discovered LAN Worlds (" + lanWorlds.size() + ")")
                        .setItems(labels, (d, which) -> {
                            ServerPinger.LanWorldEntry picked = lanWorlds.get(which);
                            ServerListManager.ServerEntry entry =
                                    new ServerListManager.ServerEntry(picked.motd, picked.address);
                            entry.group = "LAN";
                            mServers.add(entry);
                            saveAndRender();
                            quickJoin(entry);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    @Nullable
    private Instance findMatchingInstance(@Nullable String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return null;
        for (Instance inst : mCachedInstances) {
            if (inst != null && ServerPinger.doesInstanceMatchVersion(inst.versionId, mcVersion)) {
                return inst;
            }
        }
        return null;
    }

    private void switchInstanceAndJoin(@NonNull Instance target,
                                       @NonNull ServerListManager.ServerEntry server) {
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<ServerListManager.ServerEntry> targetList =
                        ServerListManager.loadServers(target.getGameDirectory());
                boolean exists = false;
                for (ServerListManager.ServerEntry e : targetList) {
                    if (e.address.equalsIgnoreCase(server.address)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    targetList.add(server);
                    ServerListManager.saveServers(target.getGameDirectory(), targetList);
                }
                Instances.setSelectedInstance(target);
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
                Tools.runOnUiThread(() -> {
                    ServerListManager.setPendingQuickConnect(server.address, server.controlProfile);
                    Toast.makeText(appCtx,
                            "Switched to \"" + target.name + "\" & joining " + server.name + "…",
                            Toast.LENGTH_SHORT).show();
                    if (isAdded()) {
                        Tools.backToMainMenu(requireActivity());
                        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(
                        appCtx, "Switch failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void createMatchedInstanceAndJoin(@NonNull ServerListManager.ServerEntry server,
                                              @NonNull String mcVersion) {
        final Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx,
                "Creating calibrated Minecraft " + mcVersion + " instance for " + server.name + "…",
                Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                final String title = server.name + " (" + mcVersion + ")";
                Instance created = Instances.createInstance(inst -> {
                    inst.name = title;
                    inst.versionId = mcVersion;
                    inst.performancePreset = Instance.PRESET_MAX_FPS;
                    inst.sharedData = false;
                }, "Server_" + mcVersion.replace('.', '_'));
                git.artdeell.mojo.diagnostics.GraphicsAdvisor.ensureOptimalInstanceRenderer(
                        appCtx, created, mcVersion);
                ServerListManager.saveServers(
                        created.getGameDirectory(), Collections.singletonList(server));
                git.artdeell.mojo.utils.GameOptionsUtils.applyZeroLagFpsOptions(
                        created.getGameDirectory(), false);
                Instances.setSelectedInstance(created);
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);

                Tools.runOnUiThread(() -> {
                    ServerListManager.setPendingQuickConnect(server.address, server.controlProfile);
                    Toast.makeText(appCtx,
                            "Created \"" + created.name + "\" — launching into " + server.name + "!",
                            Toast.LENGTH_LONG).show();
                    if (isAdded()) {
                        Tools.backToMainMenu(requireActivity());
                        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> Toast.makeText(
                        appCtx, "Auto-setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void quickJoin(@NonNull ServerListManager.ServerEntry server) {
        ServerListManager.setPendingQuickConnect(server.address, server.controlProfile);
        Toast.makeText(requireContext(), "Launching into " + server.name + " (" + server.address + ")...",
                Toast.LENGTH_SHORT).show();
        Tools.backToMainMenu(requireActivity());
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    @Override
    public void onDestroyView() {
        // Release any unlaunched :launcher loopback tunnel if the user backed out without launching
        if (!ServerListManager.hasPendingQuickConnect()) {
            git.artdeell.mojo.servers.ZyroxRoomBridge.stopClientTunnel();
        }
        super.onDestroyView();
    }

    private void copyAddress(@NonNull String address) {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("server_address", address));
            Toast.makeText(requireContext(), "Copied server IP: " + address, Toast.LENGTH_SHORT).show();
        }
    }

    private void onExportSelected(@Nullable Uri uri) {
        if (uri == null) return;
        final String payload = ServerListManager.exportServersJson(mServers);
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try (OutputStream out = appCtx.getContentResolver().openOutputStream(uri)) {
                if (out != null) out.write(payload.getBytes(StandardCharsets.UTF_8));
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Server list exported!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onImportSelected(@Nullable Uri uri) {
        if (uri == null) return;
        final Context appCtx = requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot read file");
                String json = new String(ArchiveSecurity.readBytesBounded(in, 1024 * 1024), StandardCharsets.UTF_8);
                List<ServerListManager.ServerEntry> imported = ServerListManager.importServersJson(json);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mServers.addAll(imported);
                    saveAndRender();
                    Toast.makeText(requireContext(), "Imported " + imported.size() + " server(s)!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Nullable
    private static android.graphics.Bitmap decodeServerFavicon(@Nullable String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > 192 * 1024) return null;
        try {
            byte[] raw = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Converts Minecraft {@code §0..§f / §l / §r} formatting codes into crisp colored spans. */
    private static void appendMinecraftFormatted(@NonNull android.text.SpannableStringBuilder out,
                                                 @NonNull String motd) {
        Integer currentColor = null;
        boolean bold = false;
        int spanStart = out.length();
        for (int i = 0; i < motd.length(); i++) {
            char c = motd.charAt(i);
            if (c == '§' && i + 1 < motd.length()) {
                int spanEnd = out.length();
                applyMcSpan(out, spanStart, spanEnd, currentColor, bold);
                char code = Character.toLowerCase(motd.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r') {
                    currentColor = null;
                    bold = false;
                } else {
                    Integer mapped = mcCodeToColor(code);
                    if (mapped != null) {
                        currentColor = mapped;
                        bold = false;
                    }
                }
                spanStart = out.length();
            } else {
                out.append(c);
            }
        }
        applyMcSpan(out, spanStart, out.length(), currentColor, bold);
    }

    private static void applyMcSpan(@NonNull android.text.SpannableStringBuilder out,
                                    int start, int end,
                                    @Nullable Integer color, boolean bold) {
        if (end <= start) return;
        if (color != null) {
            out.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @Nullable
    private static Integer mcCodeToColor(char code) {
        switch (code) {
            case '0': return 0xFF7A869A;
            case '1': return 0xFF4C7BFF;
            case '2': return 0xFF42C87A;
            case '3': return 0xFF4AD0D8;
            case '4': return 0xFFE55353;
            case '5': return 0xFFC278FF;
            case '6': return 0xFFFFB84D;
            case '7': return 0xFFC2D2EC;
            case '8': return 0xFF8DA2BE;
            case '9': return 0xFF82B4FF;
            case 'a': return 0xFF75F0A8;
            case 'b': return 0xFF7EE8FF;
            case 'c': return 0xFFFF7B7B;
            case 'd': return 0xFFFF9EE5;
            case 'e': return 0xFFFFE57F;
            case 'f': return 0xFFF8FBFF;
            default: return null;
        }
    }
}
