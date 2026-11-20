package com.keplura.server.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the M4b chat flow. Like the gateway beans, these are ALWAYS present:
 * an unconfigured model gateway keeps the chat routes answering 503 cleanly
 * (see {@link ChatService}), not failing the boot.
 *
 * <p>All values are ENV-driven (see application.properties). Since M4c the
 * model NAME defaults to EMPTY, which means "whatever the provider preset
 * serves" — an explicit KEPLURA_CHAT_MODEL always wins. The name is an
 * operational pin, not a secret, and a model-picker UI stays an explicit
 * Non-Goal. An unknown provider value already failed the boot in
 * {@link com.keplura.server.gateway.GatewayConfiguration}; resolving the
 * preset again here is pure derivation over validated input.
 */
@Configuration
public class ChatConfiguration {

    @Bean
    ChatProperties chatProperties(
            @Value("${keplura.chat.model:}") String model,
            @Value("${keplura.provider:}") String provider,
            @Value("${keplura.chat.context-messages:20}") int contextMessages,
            @Value("${keplura.chat.rag-top-k:4}") int ragTopK,
            @Value("${keplura.chat.max-prompt-chars:4000}") int maxPromptChars,
            @Value("${keplura.chat.context-chunk-chars:700}") int contextChunkChars,
            @Value("${keplura.chat.max-tokens:0}") int maxTokens) {
        return ChatProperties.of(model, provider, contextMessages, ragTopK,
                maxPromptChars, contextChunkChars, maxTokens);
    }
}
