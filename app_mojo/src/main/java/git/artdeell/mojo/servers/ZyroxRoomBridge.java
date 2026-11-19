package git.artdeell.mojo.servers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight, zero-root, zero-VPN, pure-Java user-space 1-Tap Room Code ({@code ZYX-XXXX})
 * Multiplayer LAN &amp; Direct P2P Tunnel Bridge.
 *
 * <ul>
 *   <li><b>Host Mode</b>: Wraps a local Minecraft "Open to LAN" world port in an authenticated,
 *       multi-client {@code TCP_NODELAY} relay accompanied by a UDP room-discovery &amp; NAT
 *       keepalive beacon. Produces both a short 4-character {@code ZYX-XXXX} LAN/Hotspot code
 *       and a compact CRC-verified {@code ZYX-XXXX-XXXXXXXXXX} direct peer token.</li>
 *   <li><b>Join Mode</b>: Resolves either a short {@code ZYX-XXXX} code (via instant subnet/hotspot
 *       UDP probe) or a full direct peer token, spins up a local loopback endpoint
 *       ({@code 127.0.0.1:<port>}), authenticates the room key, and tunnels Minecraft Java protocol
 *       traffic seamlessly so friends can join in 1 tap without typing IP addresses or ports.</li>
 * </ul>
 */
public final class ZyroxRoomBridge {

    public static final String ROOM_PREFIX = "ZYX-";
    public static final int DISCOVERY_UDP_PORT = 44456;
    private static final int MAX_CONCURRENT_PEERS = 8;
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int DISCOVERY_TIMEOUT_MS = 1400;
    private static final int HANDSHAKE_TIMEOUT_MS = 4000;
    private static final int IDLE_SOCKET_TIMEOUT_MS = 120_000;

    private static final String DISCOVER_MAGIC = "ZYROX_DISC_V1:";
    private static final String OFFER_MAGIC = "ZYROX_OFFER_V1:";
    private static final String PUNCH_MAGIC = "ZYROX_PUNCH_V1:";
    private static final String TUNNEL_MAGIC = "ZYROX_TUN_V1:";
    private static final String TUNNEL_OK = "ZYROX_OK\n";

    /** Crockford-inspired 32-char unambiguous alphabet (omits 0, O, 1, I to avoid typos). */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Nullable private static volatile HostSession sActiveHost;
    @Nullable private static volatile ClientTunnel sActiveClient;

    /** Parsed representation of a {@code ZYX-XXXX} or {@code ZYX-XXXX-<endpoint>} room code. */
    public static final class ParsedRoomCode {
        @NonNull public final String shortCode;
        @Nullable public final String directHost;
        public final int directPort;

        public ParsedRoomCode(@NonNull String shortCode,
                              @Nullable String directHost,
                              int directPort) {
            this.shortCode = shortCode;
            this.directHost = directHost;
            this.directPort = directPort;
        }

        public boolean hasDirectEndpoint() {
            return directHost != null && !directHost.isEmpty()
                    && directPort > 0 && directPort <= 65535;
        }
    }

    /** Active host session exposing a local Minecraft LAN world under a {@code ZYX-XXXX} code. */
    public static final class HostSession implements Closeable {
        @NonNull public final String shortCode;
        @NonNull public final String fullShareCode;
        @NonNull public final String worldTitle;
        @NonNull public final String bindAddress;
        public final int localMcPort;
        public final int bridgeTcpPort;

        private final AtomicBoolean mRunning = new AtomicBoolean(true);
        private final AtomicInteger mActivePeers = new AtomicInteger(0);
        private final java.util.Set<Socket> mOpenSockets =
                new java.util.concurrent.CopyOnWriteArraySet<>();
        @NonNull private final ServerSocket mServerSocket;
        @Nullable private final DatagramSocket mBeaconSocket;
        public final boolean carrierGradeNat;
        @NonNull public final String networkDiagnostic;

        HostSession(@NonNull String shortCode,
                    @NonNull String fullShareCode,
                    @NonNull String worldTitle,
                    @NonNull String bindAddress,
                    int localMcPort,
                    int bridgeTcpPort,
                    @NonNull ServerSocket serverSocket,
                    @Nullable DatagramSocket beaconSocket,
                    boolean carrierGradeNat,
                    @NonNull String networkDiagnostic) {
            this.shortCode = shortCode;
            this.fullShareCode = fullShareCode;
            this.worldTitle = worldTitle;
            this.bindAddress = bindAddress;
            this.localMcPort = localMcPort;
            this.bridgeTcpPort = bridgeTcpPort;
            this.mServerSocket = serverSocket;
            this.mBeaconSocket = beaconSocket;
            this.carrierGradeNat = carrierGradeNat;
            this.networkDiagnostic = networkDiagnostic;
        }

        public boolean isRunning() {
            return mRunning.get() && !mServerSocket.isClosed();
        }

        public int getConnectedPeerCount() {
            return mActivePeers.get();
        }

        void trackSocket(@Nullable Socket s) {
            if (s == null) return;
            if (!mRunning.get()) {
                closeQuietly(s);
                return;
            }
            mOpenSockets.add(s);
        }

        void untrackSocket(@Nullable Socket s) {
            if (s != null) {
                mOpenSockets.remove(s);
            }
        }

        @Override
        public void close() {
            if (!mRunning.compareAndSet(true, false)) return;
            closeQuietly(mBeaconSocket);
            closeQuietly(mServerSocket);
            for (Socket s : mOpenSockets) {
                closeQuietly(s);
            }
            mOpenSockets.clear();
            mActivePeers.set(0);
            if (sActiveHost == this) {
                sActiveHost = null;
            }
        }
    }

    /** Active client tunnel binding a remote {@code ZYX-XXXX} room strictly to {@code 127.0.0.1:<port>}. */
    public static final class ClientTunnel implements Closeable {
        @NonNull public final String shortCode;
        @NonNull public final String remoteHost;
        public final int remotePort;
        public final int localPort;
        @NonNull public final String worldTitle;

        private final AtomicBoolean mRunning = new AtomicBoolean(true);
        private final java.util.Set<Socket> mOpenSockets =
                new java.util.concurrent.CopyOnWriteArraySet<>();
        @NonNull private final ServerSocket mLocalLoopback;

        ClientTunnel(@NonNull String shortCode,
                     @NonNull String remoteHost,
                     int remotePort,
                     int localPort,
                     @NonNull String worldTitle,
                     @NonNull ServerSocket localLoopback) {
            this.shortCode = shortCode;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.localPort = localPort;
            this.worldTitle = worldTitle;
            this.mLocalLoopback = localLoopback;
        }

        @NonNull
        public String getLoopbackAddress() {
            return "127.0.0.1:" + localPort;
        }

        public boolean isRunning() {
            return mRunning.get() && !mLocalLoopback.isClosed();
        }

        void trackSocket(@Nullable Socket s) {
            if (s == null) return;
            if (!mRunning.get()) {
                closeQuietly(s);
                return;
            }
            mOpenSockets.add(s);
        }

        void untrackSocket(@Nullable Socket s) {
            if (s != null) {
                mOpenSockets.remove(s);
            }
        }

        @Override
        public void close() {
            if (!mRunning.compareAndSet(true, false)) return;
            closeQuietly(mLocalLoopback);
            for (Socket s : mOpenSockets) {
                closeQuietly(s);
            }
            mOpenSockets.clear();
            if (sActiveClient == this) {
                sActiveClient = null;
            }
        }
    }

    private ZyroxRoomBridge() {}

    /** Generates a cryptographically random short room code (e.g. {@code ZYX-7K9M}). */
    @NonNull
    public static String generateShortRoomCode() {
        char[] chars = new char[4];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length()));
        }
        return ROOM_PREFIX + new String(chars);
    }

    /**
     * Encodes a verified direct P2P share token ({@code ZYX-XXXX-XXXXXXXXXXXX}) packing the 4-char
     * room authentication key, IPv4 address (4 bytes), bridge port (2 bytes), and CRC-8 (1 byte)
     * into 12 unambiguous Base32 characters.
     */
    @NonNull
    public static String encodeDirectRoomCode(@NonNull String shortCode,
                                              @NonNull String ipv4Address,
                                              int port) {
        String normalized = normalizeShortCode(shortCode);
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of valid range: " + port);
        }
        String[] octets = ipv4Address.trim().split("\\.");
        if (octets.length != 4) {
            return normalized;
        }
        byte[] payload = new byte[7];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(octets[i]);
            if (v < 0 || v > 255) return normalized;
            payload[i] = (byte) (v & 0xFF);
        }
        payload[4] = (byte) ((port >>> 8) & 0xFF);
        payload[5] = (byte) (port & 0xFF);
        payload[6] = computeCrc8(normalized, payload, 6);
        return normalized + "-" + base32Encode(payload);
    }

    /**
     * Parses and verifies either a short room code ({@code ZYX-7K9M}) or a full direct peer token
     * ({@code ZYX-7K9M-XXXXXXXXXXXX}).
     */
    @NonNull
    public static ParsedRoomCode parseRoomCode(@Nullable String rawInput) throws IOException {
        if (rawInput == null) {
            throw new IOException("Please enter a ZYX-XXXX room code.");
        }
        String cleaned = rawInput.trim().toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace('0', 'O')
                .replace('1', 'I');
        // Reject ambiguous characters replaced above unless part of prefix
        if (!cleaned.startsWith(ROOM_PREFIX)) {
            if (cleaned.length() == 4) {
                cleaned = ROOM_PREFIX + cleaned;
            } else {
                throw new IOException("Room codes must start with ZYX- (example: ZYX-7K9M).");
            }
        }
        String body = cleaned.substring(ROOM_PREFIX.length());
        int dash = body.indexOf('-');
        String keyPart = dash >= 0 ? body.substring(0, dash) : body;
        if (keyPart.length() != 4 || !isValidAlphabet(keyPart)) {
            throw new IOException("Invalid room code format (expected ZYX-XXXX).");
        }
        String shortCode = ROOM_PREFIX + keyPart;
        if (dash < 0) {
            return new ParsedRoomCode(shortCode, null, 0);
        }

        String endpointToken = body.substring(dash + 1);
        if (endpointToken.length() != 12 || !isValidAlphabet(endpointToken)) {
            throw new IOException("Malformed extended room token checksum.");
        }
        byte[] decoded = base32Decode(endpointToken, 7);
        byte expectedCrc = computeCrc8(shortCode, decoded, 6);
        if (decoded[6] != expectedCrc) {
            throw new IOException("Room code checksum mismatch — double-check for typos.");
        }
        String ip = (decoded[0] & 0xFF) + "." + (decoded[1] & 0xFF) + "."
                + (decoded[2] & 0xFF) + "." + (decoded[3] & 0xFF);
        int port = ((decoded[4] & 0xFF) << 8) | (decoded[5] & 0xFF);
        if (port <= 0 || port > 65535) {
            throw new IOException("Room code contains an invalid bridge port.");
        }
        return new ParsedRoomCode(shortCode, ip, port);
    }

    @Nullable
    public static HostSession getActiveHost() {
        HostSession h = sActiveHost;
        return (h != null && h.isRunning()) ? h : null;
    }

    @Nullable
    public static ClientTunnel getActiveClient() {
        ClientTunnel c = sActiveClient;
        return (c != null && c.isRunning()) ? c : null;
    }

    /** Stops any active client loopback tunnel socket cleanly without disturbing an active host. */
    public static synchronized void stopClientTunnel() {
        if (sActiveClient != null) {
            sActiveClient.close();
            sActiveClient = null;
        }
    }

    /** Stops any active P2P host beacon and client loopback bridge cleanly. */
    public static synchronized void stopAll() {
        if (sActiveHost != null) {
            sActiveHost.close();
            sActiveHost = null;
        }
        if (sActiveClient != null) {
            sActiveClient.close();
            sActiveClient = null;
        }
    }

    /**
     * Starts hosting a local Minecraft "Open to LAN" port ({@code 1..65535}) and returns a
     * ready-to-share {@link HostSession} containing both short and direct {@code ZYX-} codes.
     */
    @NonNull
    public static synchronized HostSession startHosting(int localMcLanPort,
                                                        @Nullable String worldTitle)
            throws IOException {
        if (localMcLanPort <= 0 || localMcLanPort > 65535) {
            throw new IOException("Enter a valid Open-to-LAN port between 1024 and 65535.");
        }
        if (sActiveHost != null) {
            sActiveHost.close();
            sActiveHost = null;
        }

        String shortCode = generateShortRoomCode();
        String safeTitle = sanitizeTitle(worldTitle);
        ServerSocket tcpBridge = new ServerSocket();
        tcpBridge.setReuseAddress(true);
        tcpBridge.bind(new InetSocketAddress(0));
        int bridgePort = tcpBridge.getLocalPort();

        NetworkEndpointInfo netInfo = detectPrimaryNetworkEndpoint();
        String lanIp = netInfo.ipv4;
        String fullCode = encodeDirectRoomCode(shortCode, lanIp, bridgePort);

        DatagramSocket udpBeacon = null;
        try {
            udpBeacon = new DatagramSocket(null);
            udpBeacon.setReuseAddress(true);
            udpBeacon.setBroadcast(true);
            udpBeacon.bind(new InetSocketAddress(DISCOVERY_UDP_PORT));
        } catch (IOException wildcardErr) {
            closeQuietly(udpBeacon);
            udpBeacon = null;
            // Fallback to strict 127.0.0.1 loopback bind if OEM firewall blocks wildcard UDP
            try {
                DatagramSocket loopbackBeacon = new DatagramSocket(null);
                loopbackBeacon.setReuseAddress(true);
                loopbackBeacon.bind(new InetSocketAddress(loopbackIpv4(), DISCOVERY_UDP_PORT));
                udpBeacon = loopbackBeacon;
            } catch (IOException ignored) {
                // Full ZYX direct token still works seamlessly without UDP discovery
            }
        }

        final HostSession session = new HostSession(
                shortCode, fullCode, safeTitle, lanIp,
                localMcLanPort, bridgePort, tcpBridge, udpBeacon,
                netInfo.carrierGradeNat, netInfo.diagnostic);
        sActiveHost = session;

        // 1. Authenticated TCP accept loop -> forwards to 127.0.0.1:<localMcLanPort>
        Thread acceptThread = new Thread(() -> runHostAcceptLoop(session),
                "ZyroxRoomHost-TCP");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // 2. UDP discovery & hole-punch responder loop
        if (udpBeacon != null) {
            final DatagramSocket sock = udpBeacon;
            Thread udpThread = new Thread(() -> runHostUdpBeacon(session, sock),
                    "ZyroxRoomHost-UDP");
            udpThread.setDaemon(true);
            udpThread.start();
        }
        return session;
    }

    /**
     * Joins a friend's room code ({@code ZYX-XXXX} or full direct code), opens a verified local
     * loopback tunnel on {@code 127.0.0.1:<ephemeralPort>}, and validates reachability.
     */
    @NonNull
    public static synchronized ClientTunnel connectToRoom(@NonNull String rawRoomCode)
            throws IOException {
        ParsedRoomCode parsed = parseRoomCode(rawRoomCode);
        String remoteHost = parsed.directHost;
        int remotePort = parsed.directPort;
        String worldTitle = "Zyrox Room " + parsed.shortCode;

        if (!parsed.hasDirectEndpoint()) {
            DiscoveredOffer offer = discoverHostViaUdp(parsed.shortCode);
            if (offer == null) {
                NetworkEndpointInfo localNet = detectPrimaryNetworkEndpoint();
                String hint = localNet.carrierGradeNat
                        ? " Mobile carrier CGNAT blocks LAN broadcasts — join the host's Wi-Fi/Hotspot or connect via a shared VPN."
                        : " Ensure both devices are on the same Wi-Fi/Hotspot, or paste the host's full ZYX share code.";
                throw new IOException("No room found for " + parsed.shortCode + "." + hint);
            }
            remoteHost = offer.host;
            remotePort = offer.port;
            worldTitle = offer.title;
        } else {
            // Send lightweight UDP NAT traversal probe before opening TCP session
            sendUdpHolePunchProbe(remoteHost, parsed.shortCode);
        }

        // Verify live reachability & room-code authentication with CGNAT-aware diagnostics
        try {
            verifyRemoteRoomHandshake(remoteHost, remotePort, parsed.shortCode);
        } catch (IOException handshakeErr) {
            throw new IOException(diagnoseConnectionFailure(remoteHost, remotePort, handshakeErr),
                    handshakeErr);
        }

        return openLoopbackClientTunnel(parsed.shortCode, remoteHost, remotePort, 0, worldTitle);
    }

    @NonNull
    private static synchronized ClientTunnel openLoopbackClientTunnel(@NonNull String shortCode,
                                                                      @NonNull String remoteHost,
                                                                      int remotePort,
                                                                      int preferredLocalPort,
                                                                      @NonNull String worldTitle)
            throws IOException {
        if (sActiveClient != null) {
            sActiveClient.close();
            sActiveClient = null;
        }

        InetAddress loopbackAddr = loopbackIpv4();
        ServerSocket loopback = new ServerSocket();
        loopback.setReuseAddress(true);
        if (preferredLocalPort > 0 && preferredLocalPort <= 65535) {
            try {
                loopback.bind(new InetSocketAddress(loopbackAddr, preferredLocalPort));
            } catch (IOException busy) {
                closeQuietly(loopback);
                loopback = new ServerSocket();
                loopback.setReuseAddress(true);
                loopback.bind(new InetSocketAddress(loopbackAddr, 0));
            }
        } else {
            loopback.bind(new InetSocketAddress(loopbackAddr, 0));
        }

        final ClientTunnel tunnel = new ClientTunnel(
                shortCode, remoteHost, remotePort,
                loopback.getLocalPort(), worldTitle, loopback);
        sActiveClient = tunnel;

        Thread clientAccept = new Thread(() -> runClientLoopbackProxy(tunnel),
                "ZyroxRoomClient-TCP");
        clientAccept.setDaemon(true);
        clientAccept.start();
        return tunnel;
    }

    private static final String PENDING_TUNNEL_FILE = ".zyrox_pending_room_tunnel.json";
    private static final long PENDING_TUNNEL_TTL_MS = 10L * 60L * 1000L;

    /**
     * Persists a short-lived cross-process handoff file so {@code :game} seamlessly re-binds the
     * {@code 127.0.0.1} tunnel after {@code :launcher} hands off to {@code GameActivity}.
     */
    public static void persistPendingTunnelForGameLaunch(@NonNull ClientTunnel tunnel) {
        if (git.artdeell.mojo.Tools.DIR_CACHE == null) return;
        try {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("shortCode", tunnel.shortCode);
            obj.addProperty("remoteHost", tunnel.remoteHost);
            obj.addProperty("remotePort", tunnel.remotePort);
            obj.addProperty("localPort", tunnel.localPort);
            obj.addProperty("worldTitle", tunnel.worldTitle);
            obj.addProperty("savedAtMs", System.currentTimeMillis());
            java.io.File out = new java.io.File(
                    git.artdeell.mojo.Tools.DIR_CACHE, PENDING_TUNNEL_FILE);
            git.artdeell.mojo.Tools.write(out, obj.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called in {@code :game} before JVM boot: if {@code quickConnectAddress} points to a pending
     * {@code 127.0.0.1} room tunnel, re-establishes it in {@code :game} and returns its live
     * {@code 127.0.0.1:<port>} address.
     */
    @NonNull
    public static String ensurePendingTunnelInGameProcess(@NonNull String quickConnectAddress) {
        if (!quickConnectAddress.startsWith("127.0.0.1:")
                || git.artdeell.mojo.Tools.DIR_CACHE == null) {
            return quickConnectAddress;
        }
        java.io.File handoff = new java.io.File(
                git.artdeell.mojo.Tools.DIR_CACHE, PENDING_TUNNEL_FILE);
        if (!handoff.isFile()) return quickConnectAddress;
        try {
            String raw = git.artdeell.mojo.Tools.read(handoff.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            handoff.delete();
            com.google.gson.JsonObject obj = com.google.gson.JsonParser
                    .parseString(raw).getAsJsonObject();
            long savedAt = obj.has("savedAtMs") ? obj.get("savedAtMs").getAsLong() : 0L;
            if (System.currentTimeMillis() - savedAt > PENDING_TUNNEL_TTL_MS) {
                return quickConnectAddress;
            }
            ClientTunnel existing = sActiveClient;
            if (existing != null && existing.isRunning()
                    && quickConnectAddress.equals(existing.getLoopbackAddress())) {
                return quickConnectAddress;
            }
            String shortCode = obj.get("shortCode").getAsString();
            String remoteHost = obj.get("remoteHost").getAsString();
            int remotePort = obj.get("remotePort").getAsInt();
            int localPort = obj.get("localPort").getAsInt();
            String title = obj.has("worldTitle") ? obj.get("worldTitle").getAsString()
                    : ("Zyrox Room " + shortCode);
            ClientTunnel reopened = openLoopbackClientTunnel(
                    shortCode, remoteHost, remotePort, localPort, title);
            return reopened.getLoopbackAddress();
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            handoff.delete();
            return quickConnectAddress;
        }
    }

    private static void runHostAcceptLoop(@NonNull HostSession session) {
        while (session.isRunning()) {
            Socket peer = null;
            try {
                peer = session.mServerSocket.accept();
                if (session.mActivePeers.get() >= MAX_CONCURRENT_PEERS) {
                    closeQuietly(peer);
                    continue;
                }
                final Socket accepted = peer;
                Thread worker = new Thread(() -> handleHostPeer(session, accepted),
                        "ZyroxRoomPeer");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                closeQuietly(peer);
                if (!session.isRunning()) break;
            }
        }
    }

    private static void handleHostPeer(@NonNull HostSession session, @NonNull Socket peer) {
        Socket localMc = null;
        session.mActivePeers.incrementAndGet();
        session.trackSocket(peer);
        try {
            peer.setTcpNoDelay(true);
            peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream peerIn = new BufferedInputStream(peer.getInputStream(), 16384);
            OutputStream peerOut = peer.getOutputStream();

            String line = readAsciiLineBounded(peerIn, 64);
            String expected = TUNNEL_MAGIC + session.shortCode;
            if ((expected + ":PING").equals(line)) {
                peerOut.write(TUNNEL_OK.getBytes(StandardCharsets.US_ASCII));
                peerOut.flush();
                return;
            }
            if (!expected.equals(line)) {
                return;
            }
            peerOut.write(TUNNEL_OK.getBytes(StandardCharsets.US_ASCII));
            peerOut.flush();
            peer.setSoTimeout(IDLE_SOCKET_TIMEOUT_MS);

            localMc = new Socket();
            session.trackSocket(localMc);
            localMc.setTcpNoDelay(true);
            localMc.connect(new InetSocketAddress(loopbackIpv4(), session.localMcPort),
                    CONNECT_TIMEOUT_MS);
            localMc.setSoTimeout(IDLE_SOCKET_TIMEOUT_MS);

            bridgeBidirectional(peer, peerIn, peerOut,
                    localMc, localMc.getInputStream(), localMc.getOutputStream());
        } catch (Throwable ignored) {
        } finally {
            session.untrackSocket(localMc);
            session.untrackSocket(peer);
            closeQuietly(localMc);
            closeQuietly(peer);
            session.mActivePeers.decrementAndGet();
        }
    }

    private static void runClientLoopbackProxy(@NonNull ClientTunnel tunnel) {
        while (tunnel.isRunning()) {
            Socket localGame = null;
            try {
                localGame = tunnel.mLocalLoopback.accept();
                if (!tunnel.isRunning()) {
                    closeQuietly(localGame);
                    break;
                }
                final Socket gameSock = localGame;
                Thread worker = new Thread(() -> handleClientConnection(tunnel, gameSock),
                        "ZyroxRoomForward");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                closeQuietly(localGame);
                if (!tunnel.isRunning()) break;
            }
        }
    }

    private static void handleClientConnection(@NonNull ClientTunnel tunnel,
                                               @NonNull Socket localGame) {
        Socket remote = null;
        tunnel.trackSocket(localGame);
        try {
            localGame.setTcpNoDelay(true);
            localGame.setSoTimeout(IDLE_SOCKET_TIMEOUT_MS);

            remote = new Socket();
            tunnel.trackSocket(remote);
            remote.setTcpNoDelay(true);
            remote.connect(new InetSocketAddress(tunnel.remoteHost, tunnel.remotePort),
                    CONNECT_TIMEOUT_MS);
            remote.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            OutputStream remOut = remote.getOutputStream();
            InputStream remIn = new BufferedInputStream(remote.getInputStream(), 16384);
            String hello = TUNNEL_MAGIC + tunnel.shortCode + "\n";
            remOut.write(hello.getBytes(StandardCharsets.US_ASCII));
            remOut.flush();

            String ack = readAsciiLineBounded(remIn, 32);
            if (!"ZYROX_OK".equals(ack)) {
                return;
            }
            remote.setSoTimeout(IDLE_SOCKET_TIMEOUT_MS);

            bridgeBidirectional(localGame, localGame.getInputStream(), localGame.getOutputStream(),
                    remote, remIn, remOut);
        } catch (Throwable ignored) {
        } finally {
            tunnel.untrackSocket(remote);
            tunnel.untrackSocket(localGame);
            closeQuietly(remote);
            closeQuietly(localGame);
        }
    }

    private static void verifyRemoteRoomHandshake(@NonNull String host,
                                                  int port,
                                                  @NonNull String shortCode) throws IOException {
        try (Socket probe = new Socket()) {
            probe.setTcpNoDelay(true);
            probe.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            probe.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            OutputStream out = probe.getOutputStream();
            out.write((TUNNEL_MAGIC + shortCode + ":PING\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = readAsciiLineBounded(probe.getInputStream(), 32);
            if (!"ZYROX_OK".equals(reply)) {
                throw new IOException("Room " + shortCode + " rejected handshake or expired.");
            }
        }
    }

    private static void bridgeBidirectional(@NonNull Socket sockA,
                                            @NonNull InputStream inA,
                                            @NonNull OutputStream outA,
                                            @NonNull Socket sockB,
                                            @NonNull InputStream inB,
                                            @NonNull OutputStream outB) {
        Thread reverse = new Thread(() -> {
            pumpStream(inB, outA);
            closeQuietly(sockA);
        }, "ZyroxRoomPipe");
        reverse.setDaemon(true);
        reverse.start();
        pumpStream(inA, outB);
        closeQuietly(sockB);
        try {
            reverse.join(1500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pumpStream(@NonNull InputStream in, @NonNull OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (in.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void runHostUdpBeacon(@NonNull HostSession session,
                                         @NonNull DatagramSocket sock) {
        byte[] buf = new byte[512];
        while (session.isRunning() && !sock.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(),
                        pkt.getLength(), StandardCharsets.UTF_8).trim();
                if ((DISCOVER_MAGIC + session.shortCode).equals(msg)
                        || (PUNCH_MAGIC + session.shortCode).equals(msg)) {
                    byte[] resp = (OFFER_MAGIC + session.shortCode + ":"
                            + session.bridgeTcpPort + ":" + session.worldTitle)
                            .getBytes(StandardCharsets.UTF_8);
                    DatagramPacket reply = new DatagramPacket(
                            resp, resp.length, pkt.getAddress(), pkt.getPort());
                    sock.send(reply);
                }
            } catch (IOException e) {
                if (!session.isRunning()) break;
            }
        }
    }

    private static final class DiscoveredOffer {
        @NonNull final String host;
        final int port;
        @NonNull final String title;

        DiscoveredOffer(@NonNull String host, int port, @NonNull String title) {
            this.host = host;
            this.port = port;
            this.title = title;
        }
    }

    @Nullable
    private static DiscoveredOffer discoverHostViaUdp(@NonNull String shortCode) {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(DISCOVERY_TIMEOUT_MS);
            byte[] req = (DISCOVER_MAGIC + shortCode).getBytes(StandardCharsets.UTF_8);

            // 1. Always probe strict 127.0.0.1 loopback first (instant same-device discovery)
            try {
                sock.send(new DatagramPacket(req, req.length, loopbackIpv4(), DISCOVERY_UDP_PORT));
            } catch (IOException ignored) {
            }
            // 2. Probe directed subnet broadcasts on active Wi-Fi/Hotspot interfaces (reliable on
            // Android 10–16 even when mobile data is active alongside Wi-Fi)
            for (InetAddress bcast : listSubnetBroadcastAddresses()) {
                try {
                    sock.send(new DatagramPacket(req, req.length, bcast, DISCOVERY_UDP_PORT));
                } catch (IOException ignored) {
                }
            }
            // 3. Probe standard limited broadcast as fallback
            try {
                sock.send(new DatagramPacket(req, req.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_UDP_PORT));
            } catch (IOException ignored) {
            }

            byte[] respBuf = new byte[512];
            DatagramPacket respPkt = new DatagramPacket(respBuf, respBuf.length);
            sock.receive(respPkt);
            String text = new String(respPkt.getData(), respPkt.getOffset(),
                    respPkt.getLength(), StandardCharsets.UTF_8).trim();
            String prefix = OFFER_MAGIC + shortCode + ":";
            if (!text.startsWith(prefix)) return null;
            String rest = text.substring(prefix.length());
            int colon = rest.indexOf(':');
            String portStr = colon >= 0 ? rest.substring(0, colon) : rest;
            String title = colon >= 0 ? rest.substring(colon + 1) : ("Room " + shortCode);
            int port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) return null;
            return new DiscoveredOffer(respPkt.getAddress().getHostAddress(), port, title);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static java.util.List<InetAddress> listSubnetBroadcastAddresses() {
        java.util.List<InetAddress> out = new java.util.ArrayList<>(4);
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return out;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast instanceof Inet4Address && !out.contains(bcast)) {
                        out.add(bcast);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    @NonNull
    private static InetAddress loopbackIpv4() throws IOException {
        return InetAddress.getByAddress("127.0.0.1", new byte[]{127, 0, 0, 1});
    }

    private static void sendUdpHolePunchProbe(@NonNull String host, @NonNull String shortCode) {
        try (DatagramSocket sock = new DatagramSocket()) {
            byte[] punch = (PUNCH_MAGIC + shortCode).getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(punch, punch.length,
                    InetAddress.getByName(host), DISCOVERY_UDP_PORT));
        } catch (Throwable ignored) {
        }
    }

    /** Returns {@code true} if {@code ipv4} lies inside RFC 6598 Shared CGNAT space (100.64.0.0/10). */
    public static boolean isCarrierGradeNatIpv4(@Nullable String ipv4) {
        if (ipv4 == null || ipv4.trim().isEmpty()) return false;
        String[] parts = ipv4.trim().split("\\.");
        if (parts.length != 4) return false;
        try {
            int o1 = Integer.parseInt(parts[0]);
            int o2 = Integer.parseInt(parts[1]);
            int o3 = Integer.parseInt(parts[2]);
            int o4 = Integer.parseInt(parts[3]);
            if ((o1 | o2 | o3 | o4) < 0 || o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255) {
                return false;
            }
            return o1 == 100 && o2 >= 64 && o2 <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns {@code true} if {@code ifaceName} looks like an Android mobile-carrier radio interface. */
    public static boolean isCellularInterfaceName(@Nullable String ifaceName) {
        if (ifaceName == null) return false;
        String n = ifaceName.toLowerCase(Locale.ROOT);
        return n.startsWith("rmnet") || n.startsWith("ccmni")
                || n.startsWith("pdp") || n.startsWith("clat")
                || n.startsWith("wwan") || n.startsWith("ril");
    }

    /** Produces clear, actionable diagnostics when a P2P room connection fails on mobile networks. */
    @NonNull
    public static String diagnoseConnectionFailure(@NonNull String remoteHost,
                                                   int remotePort,
                                                   @Nullable Throwable cause) {
        if (isCarrierGradeNatIpv4(remoteHost)) {
            return "Host (" + remoteHost + ") is behind Mobile Carrier CGNAT (100.64.0.0/10), "
                    + "which blocks direct inbound connections. Join the host's Wi-Fi / Mobile Hotspot, "
                    + "or connect both devices to the same mesh VPN (e.g. Tailscale/WireGuard).";
        }
        String rawMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        if (rawMsg.toLowerCase(Locale.ROOT).contains("rejected")
                || rawMsg.toLowerCase(Locale.ROOT).contains("auth")) {
            return "Room code rejected — the host may have restarted the room. Ask for the latest ZYX code.";
        }
        if (cause instanceof java.net.ConnectException) {
            return "Room " + remoteHost + ":" + remotePort
                    + " refused connection — verify the host still has the room & Open-to-LAN active.";
        }
        boolean remotePrivate = remoteHost.startsWith("192.168.")
                || remoteHost.startsWith("10.")
                || remoteHost.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
        if (cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.net.NoRouteToHostException) {
            if (remotePrivate) {
                return "Timed out reaching LAN host " + remoteHost + ":" + remotePort
                        + ". Connect both devices to the exact same Wi-Fi or Mobile Hotspot "
                        + "(mobile carrier NAT isolates cross-network private IPs).";
            }
            return "Timed out reaching " + remoteHost + ":" + remotePort
                    + ". Mobile carrier CGNAT or router firewall blocked direct P2P — use same Wi-Fi, "
                    + "Mobile Hotspot, or a shared mesh VPN.";
        }
        return "Could not connect to room (" + remoteHost + ":" + remotePort + "): "
                + (!rawMsg.isEmpty() ? rawMsg : "connection unavailable");
    }

    /** Classified active IPv4 endpoint with carrier-CGNAT diagnosis. */
    public static final class NetworkEndpointInfo {
        @NonNull public final String ipv4;
        public final boolean carrierGradeNat;
        @NonNull public final String diagnostic;

        public NetworkEndpointInfo(@NonNull String ipv4,
                                   boolean carrierGradeNat,
                                   @NonNull String diagnostic) {
            this.ipv4 = ipv4;
            this.carrierGradeNat = carrierGradeNat;
            this.diagnostic = diagnostic;
        }
    }

    /** Discovers the best LAN / Hotspot / Mesh-VPN IPv4 and detects Mobile Carrier CGNAT. */
    @NonNull
    public static NetworkEndpointInfo detectPrimaryNetworkEndpoint() {
        String vpnIp = null;
        String otherSiteLocal = null;
        String cellularIp = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (NetworkInterface nif : Collections.list(ifaces)) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName().toLowerCase(Locale.ROOT);
                    boolean isLan = name.startsWith("wlan") || name.startsWith("ap")
                            || name.startsWith("swlan") || name.startsWith("eth")
                            || name.startsWith("rndis") || name.startsWith("softap");
                    boolean isVpn = name.startsWith("tun") || name.startsWith("wg")
                            || name.startsWith("tailscale") || name.startsWith("zt");
                    boolean isCell = isCellularInterfaceName(name);

                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                                || addr.isLinkLocalAddress()) {
                            continue;
                        }
                        String ip = addr.getHostAddress();
                        if (ip == null) continue;
                        if (isLan && addr.isSiteLocalAddress()) {
                            return new NetworkEndpointInfo(ip, false,
                                    "Wi-Fi / Hotspot ready (" + ip + ") — nearby players join directly.");
                        }
                        if (isVpn && vpnIp == null) {
                            vpnIp = ip;
                        } else if (isCell && cellularIp == null) {
                            cellularIp = ip;
                        } else if (addr.isSiteLocalAddress() && otherSiteLocal == null) {
                            otherSiteLocal = ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (otherSiteLocal != null) {
            return new NetworkEndpointInfo(otherSiteLocal, false,
                    "LAN interface active (" + otherSiteLocal + ") — players on the same network can join.");
        }
        if (vpnIp != null) {
            return new NetworkEndpointInfo(vpnIp, false,
                    "VPN tunnel active (" + vpnIp + ") — friends on the same VPN share the full ZYX code.");
        }
        if (cellularIp != null) {
            boolean cgnat = isCarrierGradeNatIpv4(cellularIp)
                    || cellularIp.startsWith("10.")
                    || cellularIp.startsWith("192.168.")
                    || cellularIp.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
            String diag = cgnat
                    ? ("Mobile Carrier CGNAT detected (" + cellularIp
                        + "): inbound internet joins are blocked by your carrier. Turn on Mobile Hotspot "
                        + "or connect both devices to the same Wi-Fi / mesh VPN.")
                    : ("Mobile data endpoint (" + cellularIp
                        + "): if friends time out due to carrier firewall, use Mobile Hotspot or Wi-Fi.");
            return new NetworkEndpointInfo(cellularIp, true, diag);
        }
        return new NetworkEndpointInfo("127.0.0.1", false,
                "Loopback only (127.0.0.1) — connect to Wi-Fi or turn on Mobile Hotspot to invite other devices.");
    }

    /** Discovers the device's primary Wi-Fi / Mobile Hotspot site-local IPv4 address. */
    @NonNull
    public static String detectPrimarySiteLocalIpv4() {
        return detectPrimaryNetworkEndpoint().ipv4;
    }

    @NonNull
    private static String readAsciiLineBounded(@NonNull InputStream in, int maxLen)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(maxLen);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            if (out.size() >= maxLen) throw new IOException("Handshake line too long");
            out.write(b);
        }
        return new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    @NonNull
    private static String normalizeShortCode(@NonNull String shortCode) {
        String s = shortCode.trim().toUpperCase(Locale.ROOT);
        if (!s.startsWith(ROOM_PREFIX)) s = ROOM_PREFIX + s;
        if (s.length() != 8 || !isValidAlphabet(s.substring(4))) {
            throw new IllegalArgumentException("Invalid ZYX short room code: " + shortCode);
        }
        return s;
    }

    private static boolean isValidAlphabet(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static byte computeCrc8(@NonNull String shortCode, @NonNull byte[] data, int len) {
        int crc = 0x5A;
        for (int i = 0; i < shortCode.length(); i++) {
            crc = stepCrc8(crc, shortCode.charAt(i) & 0xFF);
        }
        for (int i = 0; i < len; i++) {
            crc = stepCrc8(crc, data[i] & 0xFF);
        }
        return (byte) (crc & 0xFF);
    }

    private static int stepCrc8(int crc, int b) {
        crc ^= b;
        for (int bit = 0; bit < 8; bit++) {
            crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) : (crc << 1);
        }
        return crc & 0xFF;
    }

    @NonNull
    private static String base32Encode(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte raw : bytes) {
            buffer = (buffer << 8) | (raw & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(ALPHABET.charAt((buffer >>> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    @NonNull
    private static byte[] base32Decode(@NonNull String encoded, int expectedBytes)
            throws IOException {
        byte[] out = new byte[expectedBytes];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int val = ALPHABET.indexOf(encoded.charAt(i));
            if (val < 0) throw new IOException("Invalid character in room token");
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8 && idx < expectedBytes) {
                bitsLeft -= 8;
                out[idx++] = (byte) ((buffer >>> bitsLeft) & 0xFF);
            }
        }
        if (idx != expectedBytes) {
            throw new IOException("Truncated room endpoint token");
        }
        return out;
    }

    @NonNull
    private static String sanitizeTitle(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Zyrox P2P World";
        String clean = raw.replaceAll("[\\r\\n:]", " ").trim();
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private static void closeQuietly(@Nullable Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
