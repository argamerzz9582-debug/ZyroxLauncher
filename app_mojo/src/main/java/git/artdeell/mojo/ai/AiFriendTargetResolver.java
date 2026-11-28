package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.BotAuthMode;
import git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.servers.ServerListManager;
import git.artdeell.mojo.servers.ServerPinger;
import git.artdeell.mojo.servers.ZyroxRoomBridge;

/**
 * Resolves Minecraft world/server connection endpoints and authentication advisories across
 * <b>LAN Worlds</b>, <b>Zyrox Self-Hosted Rooms ({@code ZYX-XXXX})</b>, and <b>Saved Servers</b>.
 */
public final class AiFriendTargetResolver {

    /** Matches vanilla/modded singleplayer Open-to-LAN log lines (`Local game hosted on port 54321` or `Started serving on 54321`). */
    private static final Pattern LAN_PORT_LOG_PATTERN = Pattern.compile(
            "(?:Local game hosted on port|Started serving on)\\s+(\\d{2,5})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final String[] PUBLIC_COMPETITIVE_DOMAINS = {
            "hypixel.net",
            "minemen.club",
            "cubecraft.net",
            "wynncraft.com",
            "mineplex.com",
            "gommehd.net",
            "lunar.gg",
            "2b2t.org"
    };

    private AiFriendTargetResolver() {
    }

    /** Resolved endpoint instructions passed to {@link CompanionBotBridgeClient}. */
    public static final class ResolvedTarget {
        @NonNull public final TargetConnectionMode mode;
        @NonNull public final String host;
        public final int port;
        @Nullable public final String zyroxRoomCode;
        @NonNull public final BotAuthMode authMode;
        @NonNull public final String displaySummary;
        @Nullable public final String safetyAdvisory;

        public ResolvedTarget(@NonNull TargetConnectionMode mode,
                              @NonNull String host,
                              int port,
                              @Nullable String zyroxRoomCode,
                              @NonNull BotAuthMode authMode,
                              @NonNull String displaySummary,
                              @Nullable String safetyAdvisory) {
            this.mode = mode;
            this.host = host;
            this.port = port;
            this.zyroxRoomCode = zyroxRoomCode;
            this.authMode = authMode;
            this.displaySummary = displaySummary;
            this.safetyAdvisory = safetyAdvisory;
        }
    }

    /** Parsed {@code host:port} pair. */
    public static final class HostPort {
        @NonNull public final String host;
        public final int port;

        public HostPort(@NonNull String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Resolves the active configuration into concrete bridge summon parameters.
     */
    @NonNull
    public static ResolvedTarget resolve(@NonNull ConfigData cfg) throws IOException {
        TargetConnectionMode mode = TargetConnectionMode.fromWire(cfg.targetMode);
        BotAuthMode authMode = BotAuthMode.fromWire(cfg.botAuthMode);
        boolean bridgeIsLocal = isLoopbackHost(cfg.bridgeHost);

        switch (mode) {
            case ZYROX_SELF_HOSTED: {
                ZyroxRoomBridge.HostSession activeHost = ZyroxRoomBridge.getActiveHost();
                if (activeHost != null && activeHost.isRunning()) {
                    String mcHost = bridgeIsLocal ? "127.0.0.1" : activeHost.bindAddress;
                    int mcPort = activeHost.localMcPort;
                    String summary = "Zyrox Hosted Room " + activeHost.shortCode + " (" + mcHost + ":" + mcPort + ")";
                    return new ResolvedTarget(
                            mode,
                            mcHost,
                            mcPort,
                            activeHost.fullShareCode,
                            authMode,
                            summary,
                            buildAuthGuidance(mcHost, authMode, true)
                    );
                }

                ZyroxRoomBridge.ClientTunnel activeClient = ZyroxRoomBridge.getActiveClient();
                if (activeClient != null && activeClient.isRunning()) {
                    if (bridgeIsLocal) {
                        int localPort = activeClient.localPort;
                        String summary = "Zyrox Joined Room " + activeClient.shortCode + " (127.0.0.1:" + localPort + ")";
                        return new ResolvedTarget(
                                mode,
                                "127.0.0.1",
                                localPort,
                                activeClient.shortCode,
                                authMode,
                                summary,
                                buildAuthGuidance("127.0.0.1", authMode, true)
                        );
                    }
                    String remoteDirectCode = ZyroxRoomBridge.encodeDirectRoomCode(
                            activeClient.shortCode, activeClient.remoteHost, activeClient.remotePort);
                    String summary = "Zyrox Room " + activeClient.shortCode
                            + " (" + activeClient.remoteHost + ":" + activeClient.remotePort + ")";
                    return new ResolvedTarget(
                            mode,
                            activeClient.remoteHost,
                            activeClient.remotePort,
                            remoteDirectCode,
                            authMode,
                            summary,
                            buildAuthGuidance(activeClient.remoteHost, authMode, true)
                    );
                }

                String rawCode = cfg.zyroxRoomCode == null ? "" : cfg.zyroxRoomCode.trim();
                if (rawCode.isEmpty()) {
                    throw new IOException("No active Zyrox Room detected. Start hosting a room in Server/World Manager or enter a ZYX-XXXX share code.");
                }
                ZyroxRoomBridge.ParsedRoomCode parsed = ZyroxRoomBridge.parseRoomCode(rawCode);
                String hostHint = parsed.hasDirectEndpoint() && parsed.directHost != null ? parsed.directHost : "";
                int portHint = parsed.hasDirectEndpoint() ? parsed.directPort : 0;
                String summary = "Zyrox Room " + parsed.shortCode
                        + (portHint > 0 ? " (relay " + hostHint + ":" + portHint + ")" : " (LAN discovery)");
                return new ResolvedTarget(
                        mode,
                        hostHint,
                        portHint,
                        rawCode,
                        authMode,
                        summary,
                        buildAuthGuidance(hostHint, authMode, true)
                );
            }

            case SAVED_SERVER: {
                String rawServer = cfg.savedServerAddress == null ? "" : cfg.savedServerAddress.trim();
                if (rawServer.isEmpty()) {
                    List<ServerListManager.ServerEntry> servers = loadSavedServersSafe();
                    if (!servers.isEmpty()) {
                        rawServer = servers.get(0).address;
                    }
                }
                if (rawServer.isEmpty()) {
                    throw new IOException("No saved server selected. Pick a server from ServerListManager or enter host:port.");
                }
                HostPort hp = parseHostPort(rawServer, 25565);
                String summary = "Server " + hp.host + ":" + hp.port + " (" + authMode.displayName + ")";
                return new ResolvedTarget(
                        mode,
                        hp.host,
                        hp.port,
                        null,
                        authMode,
                        summary,
                        inspectServerSafetyAndAuth(hp.host, authMode)
                );
            }

            case LAN_WORLD:
            default: {
                String rawLan = cfg.lanAddress == null ? "" : cfg.lanAddress.trim();
                if (rawLan.isEmpty()) {
                    throw new IOException("Enter the Open-to-LAN IP:port (or tap Scan LAN Worlds).");
                }
                HostPort hp = parseHostPort(rawLan, 25565);
                // If user typed 127.0.0.1:<port> while running the bridge on an external LAN PC, substitute phone's Wi-Fi IP
                String resolvedHost = hp.host;
                if (!bridgeIsLocal && isLoopbackHost(resolvedHost)) {
                    String wifiIp = detectLocalIpv4Address();
                    if (wifiIp != null) {
                        resolvedHost = wifiIp;
                    }
                }
                String summary = "LAN World " + resolvedHost + ":" + hp.port;
                return new ResolvedTarget(
                        TargetConnectionMode.LAN_WORLD,
                        resolvedHost,
                        hp.port,
                        null,
                        authMode,
                        summary,
                        buildAuthGuidance(resolvedHost, authMode, true)
                );
            }
        }
    }

    /**
     * Parses {@code "host:port"}, {@code "[ipv6]:port"}, or bare port {@code "54321"} -> {@code 127.0.0.1:54321}.
     */
    @NonNull
    public static HostPort parseHostPort(@NonNull String rawInput, int defaultPort) throws IOException {
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("Empty server/LAN address");
        }

        // Bare port convenience (e.g. "54321" from Open to LAN toast)
        if (trimmed.matches("^\\d{2,5}$")) {
            int p = parsePortStrict(trimmed);
            return new HostPort("127.0.0.1", p);
        }

        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close <= 1) {
                throw new IOException("Malformed IPv6 address: " + trimmed);
            }
            String ipv6Host = trimmed.substring(1, close);
            int port = defaultPort;
            if (close + 1 < trimmed.length() && trimmed.charAt(close + 1) == ':') {
                port = parsePortStrict(trimmed.substring(close + 2));
            }
            return new HostPort(ipv6Host, port);
        }

        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon > 0 && trimmed.indexOf(':') == lastColon) {
            String hostPart = trimmed.substring(0, lastColon).trim();
            String portPart = trimmed.substring(lastColon + 1).trim();
            if (!hostPart.isEmpty() && !portPart.isEmpty()) {
                return new HostPort(hostPart, parsePortStrict(portPart));
            }
        }

        return new HostPort(trimmed, defaultPort);
    }

    private static int parsePortStrict(@NonNull String rawPort) throws IOException {
        try {
            int p = Integer.parseInt(rawPort.trim());
            if (p < 1 || p > 65535) {
                throw new IOException("Port out of range (1–65535): " + p);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid port number: " + rawPort);
        }
    }

    /**
     * Extracts the ephemeral LAN port when Minecraft singleplayer logs {@code "Local game hosted on port <port>"}
     * or {@code "Started serving on <port>"}. Returns {@code -1} if the log line is unrelated.
     */
    public static int parseLanPortFromGameLogLine(@Nullable String line) {
        if (line == null || line.isEmpty()) return -1;
        if (!line.contains("hosted on port") && !line.contains("Started serving on")) {
            return -1;
        }
        Matcher m = LAN_PORT_LOG_PATTERN.matcher(line);
        if (m.find()) {
            try {
                int port = Integer.parseInt(m.group(1));
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** Checks public competitive server anti-bot rules and online/offline auth requirements. */
    @Nullable
    public static String inspectServerSafetyAndAuth(@NonNull String host, @NonNull BotAuthMode authMode) {
        String lower = host.toLowerCase(Locale.ROOT).trim();
        for (String pub : PUBLIC_COMPETITIVE_DOMAINS) {
            if (lower.equals(pub) || lower.endsWith("." + pub)) {
                return "Warning: " + host + " is a public competitive server where automated Mineflayer bots "
                        + "typically violate server rules and risk account bans. Use AI Friend bots only on private SMPs, "
                        + "LAN worlds, or servers where bots are permitted.";
            }
        }
        return buildAuthGuidance(host, authMode, false);
    }

    @NonNull
    private static String buildAuthGuidance(@NonNull String host,
                                            @NonNull BotAuthMode authMode,
                                            boolean isLanOrSelfHosted) {
        if (authMode == BotAuthMode.OFFLINE) {
            if (isLanOrSelfHosted) {
                return "Offline Bot Auth: joins directly when the host world uses an Offline/Local account or online-mode=false. "
                        + "If Mojang session verification rejects the bot, switch Bot Auth to Microsoft Account.";
            }
            return "Offline Bot Auth: works on offline/cracked SMPs (online-mode=false). "
                    + "Premium online-mode=true servers require switching Bot Auth to Microsoft Account.";
        }
        return "Microsoft Device-Code Auth: the bridge worker will prompt a one-time microsoft.com/link code "
                + "for the bot's own licensed Minecraft Java account (cached securely by prismarine-auth).";
    }

    /** Loads saved servers from the currently selected Zyrox instance's {@code servers.dat}. */
    @NonNull
    public static List<ServerListManager.ServerEntry> loadSavedServersSafe() {
        try {
            Instance selected = Instances.loadSelectedInstance();
            if (selected != null) {
                File gameDir = selected.getGameDirectory();
                return ServerListManager.loadServers(gameDir);
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    /** Scans local Wi-Fi for Open-to-LAN announcements via {@link ServerPinger}. */
    @NonNull
    public static List<ServerPinger.LanWorldEntry> scanLocalLanWorldsBlocking(int timeoutMs) {
        try {
            return ServerPinger.scanLocalLanWorlds(timeoutMs);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static boolean isLoopbackHost(@Nullable String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.isEmpty() || "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h);
    }

    @Nullable
    public static String detectLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
