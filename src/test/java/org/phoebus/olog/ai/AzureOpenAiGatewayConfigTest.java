package org.phoebus.olog.ai;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the gateway policy through a real SDK pipeline so it is exercised after the SDK's own
 * api-key credential policy, exactly as in production.
 */
class AzureOpenAiGatewayConfigTest {

    private static final HttpHeaderName API_KEY = HttpHeaderName.fromString("api-key");
    private static final HttpHeaderName X_API_KEY = HttpHeaderName.fromString("x-api-key");

    @Test
    void rewritesPathAndKeyHeaderForGateway() {
        HttpRequest sent = sendChatRequest("/openai", "x-api-key");

        assertEquals("/deployments/gpt-5.4/chat/completions", sent.getUrl().getPath());
        assertEquals("gw.example.org", sent.getUrl().getHost());
        assertTrue(sent.getUrl().getQuery().startsWith("api-version="));
        assertEquals("secret", sent.getHeaders().getValue(X_API_KEY));
        assertNull(sent.getHeaders().getValue(API_KEY));
    }

    @Test
    void normalisesPrefixWithoutLeadingSlashOrWithTrailingSlash() {
        HttpRequest sent = sendChatRequest("openai/", "");

        assertEquals("/deployments/gpt-5.4/chat/completions", sent.getUrl().getPath());
        assertEquals("secret", sent.getHeaders().getValue(API_KEY));
    }

    @Test
    void leavesRequestUntouchedWhenNotConfigured() {
        HttpRequest sent = sendChatRequest("", "");

        assertEquals("/openai/deployments/gpt-5.4/chat/completions", sent.getUrl().getPath());
        assertEquals("secret", sent.getHeaders().getValue(API_KEY));
        assertNull(sent.getHeaders().getValue(X_API_KEY));
    }

    private static HttpRequest sendChatRequest(String stripPathPrefix, String apiKeyHeader) {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient capturingClient = request -> {
            captured.set(request);
            return Mono.error(new IllegalStateException("captured"));
        };

        OpenAIClientBuilder builder = new OpenAIClientBuilder()
                .endpoint("https://gw.example.org")
                .credential(new AzureKeyCredential("secret"))
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                .httpClient(capturingClient);
        new AzureOpenAiGatewayConfig()
                .azureOpenAiGatewayCustomizer(stripPathPrefix, apiKeyHeader)
                .customize(builder);
        OpenAIClient client = builder.buildClient();

        assertThrows(RuntimeException.class, () -> client.getChatCompletions("gpt-5.4",
                new ChatCompletionsOptions(List.of(new ChatRequestUserMessage("hi")))));
        return captured.get();
    }
}
