package git.artdeell.mojo.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.ChatHistoryEntry;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;

/**
 * Pluggable LLM provider abstraction unifying cloud OpenAI-compatible / OpenRouter {@code :free}
 * endpoints ({@link OnlineFreeApiLlmProvider}) and on-device quantized {@code .gguf} models
 * ({@link LocalGgufLlmProvider}).
 */
public interface LlmProvider {

    @NonNull
    ProviderMode getProviderMode();

    boolean isReady();

    @NonNull
    String getStatusSummary();

    /**
     * Generates a single structured AI Friend turn synchronously on a worker thread.
     *
     * @throws IOException if all candidate models/runtimes fail or the provider is not configured.
     */
    @NonNull
    AiTurnResponse generateTurn(@NonNull String userPrompt,
                                @NonNull AiRole role,
                                @NonNull String botUsername,
                                @Nullable String ownerUsername,
                                @Nullable String worldContextSummary,
                                @Nullable List<ChatHistoryEntry> recentHistory) throws IOException;
}
