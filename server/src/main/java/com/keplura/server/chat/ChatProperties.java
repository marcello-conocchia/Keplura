package com.keplura.server.chat;

import com.keplura.server.gateway.ProviderPreset;

import java.util.Optional;

/**
 * Resolved configuration of the M4b chat flow (server-side, ENV-driven).
 * Immutable, built once by {@link ChatConfiguration}. Like
 * {@link com.keplura.server.gateway.GatewayProperties} these raw values
 * never reach a response or a log line — but none of them are secrets: the
 * model NAME is the only one that surfaces at all (as the assistant-message
 * audit tag), which is exactly what the transcript needs to be honest.
 *
 * <p>M4c adds the provider preset coupling: the model NAME defaults to what
 * the chosen backend actually serves (Ollama tag, vLLM served alias, hosted
 * model id) instead of one dev string, and Ollama-selected chat sends an
 * explicit {@code max_tokens} cap because Ollama's OpenAI-compat layer has no
 * sane completion-length default (without it a small model can loop forever
 * and burn the stream deadline).
 *
 * @param model             gateway model name the chat uses (server-fixed —
 *                          a model-picker UI is an explicit Non-Goal of M4b)
 * @param contextMessages   how many transcript messages ride along upstream
 * @param ragTopK           chunks retrieved per RAG turn (kb-pinned chat only)
 * @param maxPromptChars    hard cap on one user prompt (400 beyond)
 * @param contextChunkChars max chars of each retrieved chunk pasted into the
 *                          system prompt (bounds request bytes per hit)
 * @param maxTokens         completion cap sent upstream when &gt; 0; 0 means
 *                          the field is omitted (provider decides). Never
 *                          exceeds the gateway validator's 1024 clamp.
 */
public record ChatProperties(String model,
                             int contextMessages,
                             int ragTopK,
                             int maxPromptChars,
                             int contextChunkChars,
                             int maxTokens) {

    /** Same cap the M3d search API clamps topK to (kept in sync deliberately). */
    public static final int MAX_RAG_TOPK = 50;

    /** The gateway validator clamps max_tokens at 1024 — stay inside it. */
    public static final int MAX_TOKENS_CAP = 1024;

    /**
     * Defensive normalisation — boot must never fail on an operational typo,
     * and no bound may end up at zero or negative. An EMPTY model is not a
     * typo: it is "let the provider preset decide" (M4c), resolved here from
     * the same raw provider value (unknown values already failed the gateway
     * bean earlier in the same context refresh).
     */
    public static ChatProperties of(String model, String rawProvider, int contextMessages, int ragTopK,
                                    int maxPromptChars, int contextChunkChars, int maxTokens) {
        Optional<ProviderPreset> preset = ProviderPreset.resolve(rawProvider);
        String m = (model == null) ? "" : model.strip();
        if (m.isEmpty()) {
            m = preset.map(ProviderPreset::defaultModel).orElse("local-chat-dev");
        }
        int tokens = Math.max(0, maxTokens);
        if (tokens == 0 && preset.isPresent() && preset.get() == ProviderPreset.OLLAMA) {
            // Ollama loop guard (documented in ProviderPreset's javadoc).
            tokens = MAX_TOKENS_CAP;
        }
        return new ChatProperties(
                m,
                Math.min(Math.max(contextMessages, 2), 100),
                Math.min(Math.max(ragTopK, 1), MAX_RAG_TOPK),
                Math.min(Math.max(maxPromptChars, 1), 16000),
                Math.min(Math.max(contextChunkChars, 64), 4000),
                Math.min(tokens, MAX_TOKENS_CAP));
    }

    /** 5-arg shape without provider coupling (tests + legacy callers). */
    public static ChatProperties of(String model, int contextMessages, int ragTopK,
                                    int maxPromptChars, int contextChunkChars) {
        return of(model, "", contextMessages, ragTopK, maxPromptChars, contextChunkChars, 0);
    }

    /**
     * ADMIN-KI overlay (docs/adr/0008 §5.2): when the DB resolves a default
     * chat model, its technical id replaces the ENV-pinned name and an
     * explicit row cap replaces the ENV max-tokens (0 = keep ENV). All other
     * bounds stay exactly as booted — the admin surface never touches the
     * transport/prompt caps. Validation mirrors {@link #of}: the cap stays
     * inside the gateway validator's clamp.
     */
    public ChatProperties withModelResolution(String technicalModelId, Integer rowMaxOutputTokens) {
        String m = (technicalModelId == null || technicalModelId.isBlank())
                ? model : technicalModelId.strip();
        int tokens = (rowMaxOutputTokens == null || rowMaxOutputTokens <= 0)
                ? maxTokens : Math.min(rowMaxOutputTokens, MAX_TOKENS_CAP);
        return new ChatProperties(m, contextMessages, ragTopK, maxPromptChars, contextChunkChars, tokens);
    }
}
