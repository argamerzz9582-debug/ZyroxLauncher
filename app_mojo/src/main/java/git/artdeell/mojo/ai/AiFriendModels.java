package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Strongly-typed domain models, enums, and Java 8 / API 21 callback contracts for Zyrox AI Friend.
 *
 * <p>Shared across the Launcher AI Brain ({@code :launcher} process), the In-Game AI Friend
 * Quick Prompt Overlay ({@code :game} process), {@link GgufModelManager}, {@link OnlineFreeApiLlmProvider},
 * and {@link CompanionBotBridgeClient}.</p>
 */
public final class AiFriendModels {

    private AiFriendModels() {
    }

    /** Java 8 / API 21 functional result callback (avoids API 24 {@code java.util.function.Consumer}). */
    public interface ResultCallback<T> {
        void onSuccess(@NonNull T result);
        void onError(@NonNull String message);
    }

    /** Lightweight state-change listener notified on the Android main thread. */
    public interface StateListener {
        void onAiFriendStateChanged();
    }

    /** Byte-level progress listener for SAF model imports and built-in GGUF downloads. */
    public interface TransferProgressListener {
        void onProgress(@NonNull String modelId,
                        int percent,
                        long transferredBytes,
                        long totalBytes,
                        long bytesPerSecond);
    }

    /** Selectable AI Friend personas / tactical roles in Minecraft. */
    public enum AiRole {
        COMPANION(
                "Companion",
                "COMPANION",
                "Balanced survival partner — chats warmly, stays close, and helps with everyday tasks.",
                "You are an upbeat, loyal Minecraft survival companion playing directly alongside the player. "
                        + "Keep spoken messages concise, warm, and natural for Minecraft chat (1-2 short sentences). "
                        + "Prefer staying near the player unless asked to gather resources or hold position.",
                BotAction.FOLLOW
        ),
        BODYGUARD(
                "Bodyguard",
                "BODYGUARD",
                "Tactical protector — watches perimeters, engages hostile mobs, and escorts the player.",
                "You are a vigilant Minecraft bodyguard protecting your squadmate from creepers, skeletons, "
                        + "zombies, and players. Speak crisply like a tactical escort. Prioritize GUARD and FOLLOW.",
                BotAction.GUARD
        ),
        MINER(
                "Miner",
                "MINER",
                "Underground specialist — mines coal/iron/diamond veins and lights dark caves with torches.",
                "You are an experienced Minecraft cave miner and geologist. Give sharp underground tips "
                        + "(Y-levels, lava safety, ore veins) and favor MINE_ORES, PLACE_TORCHES, or FOLLOW.",
                BotAction.MINE_ORES
        ),
        BUILDER(
                "Builder",
                "BUILDER",
                "Resource & base assistant — harvests timber, lights build perimeters, and assists construction.",
                "You are a creative, practical Minecraft builder and lumberjack. Help gather logs, light up "
                        + "build sites against mob spawns, and share concise building tips.",
                BotAction.GATHER_WOOD
        ),
        SCOUT(
                "Scout",
                "SCOUT",
                "Fast pathfinder — reconnoiters biomes, rallies to coordinates, and lights safe trails.",
                "You are an agile Minecraft scout and pathfinder. Report terrain observations concisely, "
                        + "rally quickly on COME_HERE or FOLLOW, and mark dark paths with PLACE_TORCHES.",
                BotAction.FOLLOW
        );

        public final String displayName;
        public final String badgeLabel;
        public final String description;
        public final String systemPersonaDirective;
        public final BotAction defaultAction;

        AiRole(@NonNull String displayName,
               @NonNull String badgeLabel,
               @NonNull String description,
               @NonNull String systemPersonaDirective,
               @NonNull BotAction defaultAction) {
            this.displayName = displayName;
            this.badgeLabel = badgeLabel;
            this.description = description;
            this.systemPersonaDirective = systemPersonaDirective;
            this.defaultAction = defaultAction;
        }

        @NonNull
        public static AiRole fromWire(@Nullable String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return COMPANION;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (AiRole role : values()) {
                if (role.name().equals(norm) || role.displayName.equalsIgnoreCase(raw.trim())) {
                    return role;
                }
            }
            return COMPANION;
        }
    }

    /**
     * Structured executable bot actions shared between the LLM JSON schema and the Companion Bot Bridge.
     */
    public enum BotAction {
        FOLLOW("Follow Player", "Follow", "Pathfinds continuously within 3 blocks of the player."),
        COME_HERE("Come Here", "Come", "Sprints directly to the player's current block position once."),
        GUARD("Guard Area", "Guard", "Patrols immediate perimeter and attacks nearby hostile mobs."),
        GATHER_WOOD("Gather Wood", "Wood", "Finds and harvests nearby log blocks and collects drops."),
        MINE_ORES("Mine Ores", "Mine", "Locates and mines exposed coal, copper, iron, gold, redstone, or diamond ores."),
        PLACE_TORCHES("Place Torches", "Torches", "Places torches from inventory when nearby block light is low."),
        IDLE("Stand Idle", "Idle", "Stops active pathfinding/mining tasks and holds current position.");

        public final String displayName;
        public final String shortChipLabel;
        public final String description;

        BotAction(@NonNull String displayName,
                  @NonNull String shortChipLabel,
                  @NonNull String description) {
            this.displayName = displayName;
            this.shortChipLabel = shortChipLabel;
            this.description = description;
        }

        /**
         * Normalizes exact enum names plus common model variations (e.g. {@code "FOLLOW_PLAYER"},
         * {@code "CHOP_WOOD"}, {@code "STOP"}) into one of the 7 canonical wire actions.
         */
        @Nullable
        public static BotAction fromWireOrAlias(@Nullable String raw) {
            if (raw == null) return null;
            String norm = raw.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if (norm.isEmpty()) return null;
            for (BotAction action : values()) {
                if (action.name().equals(norm)) {
                    return action;
                }
            }
            if (norm.contains("COME") || norm.equals("HERE") || norm.equals("TELEPORT") || norm.equals("RALLY")) {
                return COME_HERE;
            }
            if (norm.contains("FOLLOW") || norm.equals("ESCORT") || norm.equals("WITH_ME")) {
                return FOLLOW;
            }
            if (norm.contains("GUARD") || norm.contains("PROTECT") || norm.contains("DEFEND") || norm.contains("ATTACK")) {
                return GUARD;
            }
            if (norm.contains("WOOD") || norm.contains("TREE") || norm.contains("LOG") || norm.contains("LUMBER")) {
                return GATHER_WOOD;
            }
            if (norm.contains("MINE") || norm.contains("ORE") || norm.contains("DIG") || norm.contains("IRON") || norm.contains("DIAMOND")) {
                return MINE_ORES;
            }
            if (norm.contains("TORCH") || norm.contains("LIGHT") || norm.contains("LANTERN")) {
                return PLACE_TORCHES;
            }
            if (norm.contains("IDLE") || norm.contains("STOP") || norm.contains("WAIT") || norm.contains("STAY") || norm.contains("NONE")) {
                return IDLE;
            }
            return null;
        }
    }

    /** Active LLM inference backend selected by the user across the 3 Zyrox AI Brain modes. */
    public enum ProviderMode {
        FREE_ANONYMOUS("Free Internet Router", "FREE ROUTER"),
        ONLINE_API("API Key (Cloud / Gateway)", "API KEY"),
        LOCAL_GGUF("Local GGUF Model", "LOCAL MODEL");

        public final String displayName;
        public final String shortTabLabel;

        ProviderMode(@NonNull String displayName, @NonNull String shortTabLabel) {
            this.displayName = displayName;
            this.shortTabLabel = shortTabLabel;
        }

        @NonNull
        public static ProviderMode fromWire(@Nullable String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return FREE_ANONYMOUS;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            if ("LOCAL_GGUF".equals(norm) || "LOCAL".equals(norm) || "LOCAL_MODEL".equals(norm)) {
                return LOCAL_GGUF;
            }
            if ("ONLINE_API".equals(norm) || "API_KEY".equals(norm)) {
                return ONLINE_API;
            }
            if ("FREE_ANONYMOUS".equals(norm) || "ANONYMOUS".equals(norm) || "FREE_ROUTER".equals(norm)) {
                return FREE_ANONYMOUS;
            }
            return FREE_ANONYMOUS;
        }
    }

    /** How the AI Friend Companion Bot locates and joins the target Minecraft world/server. */
    public enum TargetConnectionMode {
        LAN_WORLD("LAN World"),
        ZYROX_SELF_HOSTED("Zyrox Self-Hosted"),
        SAVED_SERVER("Saved Server");

        public final String displayName;

        TargetConnectionMode(@NonNull String displayName) {
            this.displayName = displayName;
        }

        @NonNull
        public static TargetConnectionMode fromWire(@Nullable String raw) {
            if (raw == null) return LAN_WORLD;
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            for (TargetConnectionMode mode : values()) {
                if (mode.name().equals(norm)) return mode;
            }
            return LAN_WORLD;
        }
    }

    /**
     * Minecraft session authentication mode used by the Companion Bot Bridge.
     *
     * <ul>
     *   <li>{@link #OFFLINE}: Unauthenticated offline/cracked username join ({@code online-mode=false}
     *   servers and offline-account Open-to-LAN worlds).</li>
     *   <li>{@link #MICROSOFT_DEVICE_CODE}: Authenticated Xbox/Minecraft Java profile via Microsoft
     *   device-code flow for {@code online-mode=true} servers (requires a separate licensed MC account
     *   for the bot so it does not collide with the player's own active session).</li>
     * </ul>
     */
    public enum BotAuthMode {
        OFFLINE("Offline / LAN Mode"),
        MICROSOFT_DEVICE_CODE("Microsoft Account (Online Server)");

        public final String displayName;

        BotAuthMode(@NonNull String displayName) {
            this.displayName = displayName;
        }

        @NonNull
        public static BotAuthMode fromWire(@Nullable String raw) {
            if (raw != null && "MICROSOFT_DEVICE_CODE".equalsIgnoreCase(raw.trim())) {
                return MICROSOFT_DEVICE_CODE;
            }
            return OFFLINE;
        }
    }

    /** Memory residency lifecycle state of the active local {@code .gguf} model. */
    public enum ModelLoadState {
        UNLOADED("UNLOADED"),
        LOADING("LOADING..."),
        LOADED("LOADED"),
        UNLOADING("UNLOADING..."),
        ERROR("LOAD ERROR");

        public final String badgeText;

        ModelLoadState(@NonNull String badgeText) {
            this.badgeText = badgeText;
        }
    }

    /** Live connection lifecycle of the Minecraft Companion Bot Bridge session. */
    public enum BotConnectionState {
        DISCONNECTED("BRIDGE OFFLINE"),
        CONNECTING("CONNECTING..."),
        AUTH_REQUIRED("MSA LOGIN NEEDED"),
        CONNECTED("BOT CONNECTED"),
        ERROR("BRIDGE ERROR");

        public final String badgeText;

        BotConnectionState(@NonNull String badgeText) {
            this.badgeText = badgeText;
        }

        @NonNull
        public static BotConnectionState fromWire(@Nullable String raw) {
            if (raw == null) return DISCONNECTED;
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            for (BotConnectionState s : values()) {
                if (s.name().equals(norm)) return s;
            }
            return DISCONNECTED;
        }
    }

    /** Validated structured turn output produced by {@link LlmProvider} + {@link AiResponseParser}. */
    public static final class AiTurnResponse {
        @NonNull public final String say;
        @NonNull public final BotAction action;
        @NonNull public final String modelUsed;
        @NonNull public final ProviderMode providerMode;
        public final long latencyMs;
        public final boolean usedFallbackParser;
        @NonNull public final String rawOutput;

        public AiTurnResponse(@NonNull String say,
                              @NonNull BotAction action,
                              @NonNull String modelUsed,
                              @NonNull ProviderMode providerMode,
                              long latencyMs,
                              boolean usedFallbackParser,
                              @NonNull String rawOutput) {
            this.say = say;
            this.action = action;
            this.modelUsed = modelUsed;
            this.providerMode = providerMode;
            this.latencyMs = Math.max(0L, latencyMs);
            this.usedFallbackParser = usedFallbackParser;
            this.rawOutput = rawOutput;
        }
    }

    /** Single conversation or system event entry rendered in the AI Friend console & overlay. */
    public static final class ChatHistoryEntry {
        public static final String SENDER_USER = "You";
        public static final String SENDER_AI = "AI Friend";
        public static final String SENDER_SYSTEM = "Bridge";
        public static final String SENDER_WORLD = "World Chat";

        public final long timestampMs;
        @NonNull public final String sender;
        @NonNull public final String message;
        @Nullable public final BotAction action;
        @Nullable public final String badgeDetail;

        public ChatHistoryEntry(long timestampMs,
                                @NonNull String sender,
                                @NonNull String message,
                                @Nullable BotAction action,
                                @Nullable String badgeDetail) {
            this.timestampMs = timestampMs;
            this.sender = sender;
            this.message = message;
            this.action = action;
            this.badgeDetail = badgeDetail;
        }
    }

    /** Snapshot of the in-world Mineflayer bot's live telemetry reported by the bridge worker. */
    public static final class BotStatusSnapshot {
        @NonNull public final BotConnectionState state;
        @NonNull public final String botUsername;
        @NonNull public final String endpoint;
        @NonNull public final BotAction activeAction;
        public final float health;
        public final int food;
        public final int blockX;
        public final int blockY;
        public final int blockZ;
        public final boolean hasPosition;
        @NonNull public final String detail;
        @Nullable public final String msaVerificationUri;
        @Nullable public final String msaUserCode;

        public BotStatusSnapshot(@NonNull BotConnectionState state,
                                 @NonNull String botUsername,
                                 @NonNull String endpoint,
                                 @NonNull BotAction activeAction,
                                 float health,
                                 int food,
                                 int blockX,
                                 int blockY,
                                 int blockZ,
                                 boolean hasPosition,
                                 @NonNull String detail,
                                 @Nullable String msaVerificationUri,
                                 @Nullable String msaUserCode) {
            this.state = state;
            this.botUsername = botUsername;
            this.endpoint = endpoint;
            this.activeAction = activeAction;
            this.health = health;
            this.food = food;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.hasPosition = hasPosition;
            this.detail = detail;
            this.msaVerificationUri = msaVerificationUri;
            this.msaUserCode = msaUserCode;
        }

        @NonNull
        public static BotStatusSnapshot disconnected(@NonNull String botUsername, @NonNull String detail) {
            return new BotStatusSnapshot(
                    BotConnectionState.DISCONNECTED,
                    botUsername,
                    "",
                    BotAction.IDLE,
                    20.0f,
                    20,
                    0, 0, 0,
                    false,
                    detail,
                    null,
                    null
            );
        }
    }
}
