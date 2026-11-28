package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.security.LogRedactor;

/**
 * Prompt builder and resilient structured JSON output parser for Zyrox AI Friend.
 *
 * <p>Enforces the canonical schema {@code {"say":"...","action":"FOLLOW|COME_HERE|GUARD|GATHER_WOOD|MINE_ORES|PLACE_TORCHES|IDLE"}}
 * while safely recovering from reasoning tags ({@code <think>...</think>}), Markdown code fences
 * ({@code ```json ... ```}), prose-wrapped JSON objects, or plain-text model completions.</p>
 */
public final class AiResponseParser {

    /** Maximum length of a single vanilla Minecraft chat message (256 hard cap; 220 leaves prefix headroom). */
    public static final int MAX_MINECRAFT_SAY_CHARS = 220;

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile(
            "(?is)<think>.*?</think>");
    private static final Pattern UNCLOSED_THINK_PATTERN = Pattern.compile(
            "(?is)^\\s*<think>.*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "(?is)```(?:json)?\\s*(\\{.*?\\})\\s*```");
    private static final Pattern WHITESPACE_COLLAPSE = Pattern.compile("[\\r\\n\\t]+");

    private AiResponseParser() {
    }

    /**
     * Builds the system prompt instructing the online or local LLM how to speak and act in-game.
     */
    @NonNull
    public static String buildSystemPrompt(@NonNull AiRole role,
                                           @NonNull String botUsername,
                                           @Nullable String ownerUsername,
                                           @Nullable String worldContextSummary) {
        String safeBot = sanitizeIdentifier(botUsername, "ZyroxBuddy");
        String safeOwner = sanitizeIdentifier(ownerUsername, "Player");
        StringBuilder sb = new StringBuilder(640);
        sb.append("You are ").append(safeBot)
                .append(", an AI Friend playing inside Minecraft Java Edition alongside ")
                .append(safeOwner).append(". ");
        sb.append("Role: ").append(role.displayName).append(". ");
        sb.append(role.systemPersonaDirective).append("\n\n");
        if (worldContextSummary != null && !worldContextSummary.trim().isEmpty()) {
            sb.append("Live world status: ").append(worldContextSummary.trim()).append("\n\n");
        }
        sb.append("OUTPUT FORMAT RULES (CRITICAL):\n")
                .append("Respond ONLY with a single valid JSON object and no extra text or markdown:\n")
                .append("{\"say\":\"<friendly in-game reply under 180 chars>\",\"action\":\"<ACTION>\"}\n")
                .append("Allowed ACTION values ONLY:\n")
                .append("- FOLLOW (stay near player)\n")
                .append("- COME_HERE (sprint to player's exact position)\n")
                .append("- GUARD (defend area and attack nearby hostile mobs)\n")
                .append("- GATHER_WOOD (chop nearby logs/trees)\n")
                .append("- MINE_ORES (mine nearby coal/iron/gold/diamond ores)\n")
                .append("- PLACE_TORCHES (place torches in dark spots)\n")
                .append("- IDLE (stop moving/working and wait)\n");
        return sb.toString();
    }

    /**
     * Selects up to {@code maxTurns} recent user/AI exchanges for compact context window usage.
     */
    @NonNull
    public static List<ChatHistoryEntry> selectRecentDialogue(@Nullable List<ChatHistoryEntry> history,
                                                              int maxTurns) {
        List<ChatHistoryEntry> selected = new ArrayList<>();
        if (history == null || history.isEmpty() || maxTurns <= 0) {
            return selected;
        }
        for (int i = history.size() - 1; i >= 0 && selected.size() < maxTurns; i--) {
            ChatHistoryEntry entry = history.get(i);
            if (entry == null) continue;
            if (ChatHistoryEntry.SENDER_USER.equals(entry.sender)
                    || ChatHistoryEntry.SENDER_WORLD.equals(entry.sender)
                    || ChatHistoryEntry.SENDER_AI.equals(entry.sender)) {
                selected.add(0, entry);
            }
        }
        return selected;
    }

    /**
     * Parses raw LLM completion text into a guaranteed-safe {@link AiTurnResponse}.
     * Never throws on malformed JSON, empty responses, markdown wrappers, or reasoning blocks.
     */
    @NonNull
    public static AiTurnResponse parseModelOutput(@Nullable String rawText,
                                                  @Nullable String userPrompt,
                                                  @NonNull AiRole role,
                                                  @NonNull String modelUsed,
                                                  @NonNull ProviderMode providerMode,
                                                  long latencyMs) {
        String safeRaw = rawText == null ? "" : LogRedactor.redact(rawText).trim();
        String stripped = stripReasoningBlocks(safeRaw);

        // 1. Direct JSON object parse
        ParsedPair direct = tryParseJsonObject(stripped);
        if (direct != null) {
            BotAction action = direct.action != null
                    ? direct.action
                    : inferActionFromText(userPrompt, direct.say, role);
            String say = sanitizeMinecraftChat(direct.say, fallbackSayForAction(action, role));
            return new AiTurnResponse(say, action, modelUsed, providerMode, latencyMs, false, safeRaw);
        }

        // 2. Markdown ```json ... ``` code fence extraction
        java.util.regex.Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(stripped);
        if (fenceMatcher.find()) {
            ParsedPair fenced = tryParseJsonObject(fenceMatcher.group(1));
            if (fenced != null) {
                BotAction action = fenced.action != null
                        ? fenced.action
                        : inferActionFromText(userPrompt, fenced.say, role);
                String say = sanitizeMinecraftChat(fenced.say, fallbackSayForAction(action, role));
                return new AiTurnResponse(say, action, modelUsed, providerMode, latencyMs, true, safeRaw);
            }
        }

        // 3. Balanced JSON object scan inside mixed prose
        List<String> candidates = extractBalancedJsonObjects(stripped);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ParsedPair embedded = tryParseJsonObject(candidates.get(i));
            if (embedded != null) {
                BotAction action = embedded.action != null
                        ? embedded.action
                        : inferActionFromText(userPrompt, embedded.say, role);
                String say = sanitizeMinecraftChat(embedded.say, fallbackSayForAction(action, role));
                return new AiTurnResponse(say, action, modelUsed, providerMode, latencyMs, true, safeRaw);
            }
        }

        // 4. Plain-text fallback: scrub markdown fences, infer tactical action, cap chat length
        String cleanedProse = stripped
                .replace("```json", "")
                .replace("```", "")
                .trim();
        BotAction inferredAction = inferActionFromText(userPrompt, cleanedProse, role);
        String say = sanitizeMinecraftChat(cleanedProse, fallbackSayForAction(inferredAction, role));
        return new AiTurnResponse(say, inferredAction, modelUsed, providerMode, latencyMs, true, safeRaw);
    }

    /**
     * Infers the best {@link BotAction} from natural-language player input (and optional model prose)
     * when the LLM omits or mangles the {@code "action"} field, or for offline tactical fallback.
     */
    @NonNull
    public static BotAction inferActionFromText(@Nullable String userPrompt,
                                                @Nullable String assistantText,
                                                @NonNull AiRole role) {
        String p = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);

        // Player prompt carries highest intent authority
        if (!p.isEmpty()) {
            if (containsAny(p, "stop", "wait here", "stay here", "stand still", "hold position", "idle", "chill", "cancel")) {
                return BotAction.IDLE;
            }
            if (containsAny(p, "come here", "to me", "over here", "regroup", "rally", "get here")) {
                return BotAction.COME_HERE;
            }
            if (containsAny(p, "torch", "light up", "too dark", "place light", "lantern")) {
                return BotAction.PLACE_TORCHES;
            }
            if (containsAny(p, "gather wood", "chop", "tree", "logs", "lumber", "oak", "birch", "spruce", "wood")) {
                return BotAction.GATHER_WOOD;
            }
            if (containsAny(p, "mine", "dig", "iron", "coal", "diamond", "copper", "gold", "redstone", "ores", "pickaxe")) {
                return BotAction.MINE_ORES;
            }
            if (containsAny(p, "guard", "protect", "defend", "watch my back", "kill", "fight", "hostile", "creeper", "zombie", "skeleton")) {
                return BotAction.GUARD;
            }
            if (containsAny(p, "follow", "with me", "let's go", "lets go", "walk with", "escort")) {
                return BotAction.FOLLOW;
            }
        }

        // Check if assistant prose explicitly mentions an action token
        if (assistantText != null && !assistantText.isEmpty()) {
            for (BotAction candidate : BotAction.values()) {
                if (assistantText.toUpperCase(Locale.ROOT).contains(candidate.name())) {
                    return candidate;
                }
            }
        }

        return role.defaultAction;
    }

    /**
     * Sanitizes text so it is safe to send in vanilla/modded Minecraft chat:
     * strips section signs ({@code §}), control characters, newlines, and truncates cleanly at
     * {@link #MAX_MINECRAFT_SAY_CHARS} characters.
     */
    @NonNull
    public static String sanitizeMinecraftChat(@Nullable String text, @NonNull String fallback) {
        if (text == null) {
            return fallback;
        }
        String collapsed = WHITESPACE_COLLAPSE.matcher(text).replaceAll(" ").trim();
        StringBuilder clean = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == '\u00A7' || c < 0x20 || c == 0x7F) {
                continue;
            }
            clean.append(c);
        }
        String out = clean.toString().trim();
        // Prevent accidental slash-command execution if an untrusted model starts `say` with `/`
        while (out.startsWith("/")) {
            out = out.substring(1).trim();
        }
        if (out.isEmpty()) {
            return fallback;
        }
        if (out.length() > MAX_MINECRAFT_SAY_CHARS) {
            out = out.substring(0, MAX_MINECRAFT_SAY_CHARS - 3).trim() + "...";
        }
        return out;
    }

    @NonNull
    public static String fallbackSayForAction(@NonNull BotAction action, @NonNull AiRole role) {
        switch (action) {
            case COME_HERE:
                return "On my way to your position!";
            case GUARD:
                return "Perimeter locked down — watching for hostile mobs.";
            case GATHER_WOOD:
                return "Heading out to harvest nearby logs for us.";
            case MINE_ORES:
                return "Scanning exposed rock faces for ore veins now.";
            case PLACE_TORCHES:
                return "Lighting up dark spots so mobs cannot spawn.";
            case IDLE:
                return "Holding position right here. Let me know when we roll out!";
            case FOLLOW:
            default:
                return "Right behind you (" + role.displayName + " ready)!";
        }
    }

    @NonNull
    static String stripReasoningBlocks(@NonNull String input) {
        String withoutClosed = THINK_BLOCK_PATTERN.matcher(input).replaceAll("").trim();
        if (withoutClosed.startsWith("<think>") || withoutClosed.startsWith("<THINK>")) {
            return UNCLOSED_THINK_PATTERN.matcher(withoutClosed).replaceAll("").trim();
        }
        return withoutClosed;
    }

    @Nullable
    private static ParsedPair tryParseJsonObject(@Nullable String candidate) {
        if (candidate == null) return null;
        String trimmed = candidate.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            JsonElement elem = JsonParser.parseString(trimmed);
            if (!elem.isJsonObject()) return null;
            JsonObject obj = elem.getAsJsonObject();

            String say = getStringField(obj, "say");
            if (say == null) say = getStringField(obj, "message");
            if (say == null) say = getStringField(obj, "reply");
            if (say == null) say = getStringField(obj, "text");

            String rawAction = getStringField(obj, "action");
            if (rawAction == null) rawAction = getStringField(obj, "command");
            BotAction action = BotAction.fromWireOrAlias(rawAction);

            if (say == null && action == null) {
                return null;
            }
            return new ParsedPair(say, action);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String getStringField(@NonNull JsonObject obj, @NonNull String key) {
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return null;
    }

    @NonNull
    static List<String> extractBalancedJsonObjects(@NonNull String text) {
        List<String> results = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        results.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return results;
    }

    private static boolean containsAny(@NonNull String haystack, @NonNull String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    @NonNull
    private static String sanitizeIdentifier(@Nullable String raw, @NonNull String fallback) {
        if (raw == null) return fallback;
        String clean = raw.replaceAll("[^A-Za-z0-9_\\- ]", "").trim();
        if (clean.isEmpty()) return fallback;
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    private static final class ParsedPair {
        @Nullable final String say;
        @Nullable final BotAction action;

        ParsedPair(@Nullable String say, @Nullable BotAction action) {
            this.say = say;
            this.action = action;
        }
    }
}
