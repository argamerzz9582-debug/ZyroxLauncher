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

        Button btnPingAll = ZyroxStudioUi.createSecondaryButton(ctx, "Ping All", true);
        btnPingAll.setOnClickListener(v -> pingAllServers());
        topBar.addView(btnPingAll, ZyroxStudioUi.spacedParam(
                ViewGroup.LayoutParams.WRAP_CONTENT, ZyroxStudioUi.dp(ctx, 34), ZyroxStudioUi.dp(ctx, 5)));

        Button btnImport = ZyroxStudioUi.createSecondaryButton(ctx, "Import", true);
        btnImport.setOnClickListener(v -> mImportLauncher.launch(new String[]{"application/json", "*/*"}));
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
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
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
            TextView empty = ZyroxStudioUi.createSubtitle(ctx,
                    "No servers saved yet for this instance.\nTap '+ Add Server' to add a server synced directly with Minecraft's Multiplayer menu, or 'Import' a server list.");
            empty.setPadding(ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24),
                    ZyroxStudioUi.dp(ctx, 12), ZyroxStudioUi.dp(ctx, 24));
            mListContainer.addView(empty);
            return;
        }

        for (ServerListManager.ServerEntry s : mServers) {
            LinearLayout card = ZyroxStudioUi.createItemCard(ctx);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

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

            String title = (s.favorite ? "★ " : "") + s.name
                    + (s.group.isEmpty() ? "" : "  [" + s.group + "]");
            info.addView(ZyroxStudioUi.createTitle(ctx, title));

            android.text.SpannableStringBuilder richSub = new android.text.SpannableStringBuilder(s.address);
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

            Button btnJoin = ZyroxStudioUi.createPrimaryButton(ctx, "Join");
            btnJoin.setOnClickListener(v -> quickJoin(s));
            row.addView(btnJoin, ZyroxStudioUi.spacedParam(
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
                        mServers.add(entry);
                    } else {
                        existing.name = rawName.isEmpty() ? rawAddr : rawName;
                        existing.address = rawAddr;
                        existing.group = rawGroup;
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
                    server.lastMotd = !res.formattedMotd.isEmpty() ? res.formattedMotd : res.motd;
                    if (res.faviconBase64 != null) server.iconBase64 = res.faviconBase64;
                } else {
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

    private void quickJoin(@NonNull ServerListManager.ServerEntry server) {
        ServerListManager.setPendingQuickConnect(server.address);
        Toast.makeText(requireContext(), "Launching into " + server.name + " (" + server.address + ")...",
                Toast.LENGTH_SHORT).show();
        Tools.backToMainMenu(requireActivity());
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
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
