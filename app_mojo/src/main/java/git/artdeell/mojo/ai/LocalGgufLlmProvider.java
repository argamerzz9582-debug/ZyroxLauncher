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
 * {@link LlmProvider} implementation backed by {@link GgufModelManager} and
 * {@link LlamaCppRuntimeBridge} for offline / local {@code .gguf} inference.
 */
public final class LocalGgufLlmProvider implements LlmProvider {

    private final GgufModelManager modelManager;

    public LocalGgufLlmProvider(@NonNull GgufModelManager modelManager) {
        this.modelManager = modelManager;
    }

    @NonNull
    @Override
    public ProviderMode getProviderMode() {
        return ProviderMode.LOCAL_GGUF;
    }

    @Override
    public boolean isReady() {
        return modelManager.getRuntimeBridge().isModelLoaded();
    }

    @NonNull
    @Override
    public String getStatusSummary() {
        String loaded = modelManager.getLoadedModelFileName();
        if (loaded != null) {
            return "Local GGUF · Loaded (" + loaded + ")";
        }
        return "Local GGUF · Unloaded";
    }

    @NonNull
    @Override
    public AiTurnResponse generateTurn(@NonNull String userPrompt,
                                       @NonNull AiRole role,
                                       @NonNull String botUsername,
                                       @Nullable String ownerUsername,
                                       @Nullable String worldContextSummary,
                                       @Nullable List<ChatHistoryEntry> recentHistory) throws IOException {
        return modelManager.getRuntimeBridge().generateWithLoadedModel(
                userPrompt,
                role,
                botUsername,
                ownerUsername,
                worldContextSummary,
                recentHistory
        );
    }
}
