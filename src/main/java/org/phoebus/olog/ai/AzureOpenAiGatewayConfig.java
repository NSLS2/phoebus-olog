package org.phoebus.olog.ai;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.UrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAIClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Adapts the Azure OpenAI SDK to API gateways (e.g. Azure API Management) that do not expose the
 * standard Azure OpenAI URL layout or key header. Both adaptations are opt-in; with the properties
 * left blank the SDK talks to the endpoint unchanged.
 * <ul>
 *   <li>{@code olog.ai.azure.gateway.strip-path-prefix}: removed from the start of the request path,
 *       e.g. {@code /openai} turns {@code /openai/deployments/...} into {@code /deployments/...}</li>
 *   <li>{@code olog.ai.azure.gateway.api-key-header}: header to send the key in instead of the SDK's
 *       {@code api-key}, e.g. {@code x-api-key}</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass(name = "com.azure.ai.openai.OpenAIClientBuilder")
public class AzureOpenAiGatewayConfig {

    private static final Logger logger = LoggerFactory.getLogger(AzureOpenAiGatewayConfig.class);

    @Bean
    public AzureOpenAIClientBuilderCustomizer azureOpenAiGatewayCustomizer(
            @Value("${olog.ai.azure.gateway.strip-path-prefix:}") String stripPathPrefix,
            @Value("${olog.ai.azure.gateway.api-key-header:}") String apiKeyHeader) {
        return (OpenAIClientBuilder builder) -> {
            GatewayCompatibilityPolicy policy = new GatewayCompatibilityPolicy(stripPathPrefix, apiKeyHeader);
            if (policy.isNoOp()) {
                return;
            }
            logger.info("Azure OpenAI gateway adaptation enabled: strip-path-prefix='{}', api-key-header='{}'",
                    policy.pathPrefix, policy.apiKeyHeader == null ? "" : policy.apiKeyHeader);
            builder.addPolicy(policy);
        };
    }

    static final class GatewayCompatibilityPolicy implements HttpPipelinePolicy {

        private static final HttpHeaderName SDK_API_KEY_HEADER = HttpHeaderName.fromString("api-key");

        private final String pathPrefix;
        private final HttpHeaderName apiKeyHeader;

        GatewayCompatibilityPolicy(String stripPathPrefix, String apiKeyHeader) {
            String prefix = stripPathPrefix == null ? "" : stripPathPrefix.strip();
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!prefix.isEmpty() && !prefix.startsWith("/")) {
                prefix = "/" + prefix;
            }
            this.pathPrefix = prefix;
            String header = apiKeyHeader == null ? "" : apiKeyHeader.strip();
            this.apiKeyHeader = header.isEmpty() || header.equalsIgnoreCase(SDK_API_KEY_HEADER.getCaseInsensitiveName())
                    ? null
                    : HttpHeaderName.fromString(header);
        }

        boolean isNoOp() {
            return pathPrefix.isEmpty() && apiKeyHeader == null;
        }

        // PER_RETRY places this after the SDK's KeyCredentialPolicy, so the api-key header is already set
        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_RETRY;
        }

        @Override
        /**
         * Adapts the outgoing HTTP request by stripping the path prefix and/or updating
         * the {@code api-key} header before passing it to the next policy in the pipeline.
         *
         * <p>Both asynchronous ({@code process}) and synchronous ({@code processSync})
         * methods are implemented because the SDK may invoke either one depending on the
         * operation, or call the asynchronous method and block on the returned {@link Mono}.
         *
         * @param context the context for the current HTTP pipeline call
         * @param next the next policy to invoke in the pipeline
         * @return a {@link Mono} emitting the HTTP response from the downstream pipeline
         */
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            adapt(context.getHttpRequest());
            return next.process();
        }

        @Override
        public HttpResponse processSync(HttpPipelineCallContext context, HttpPipelineNextSyncPolicy next) {
            adapt(context.getHttpRequest());
            return next.processSync();
        }

        /**
         * Adapts the outgoing HTTP request for gateway compatibility by removing the configured
         * path prefix from the request URL when present and by moving the SDK API key header
         * value to the configured gateway-specific API key header.
         *
         * @param request the outgoing HTTP request to adapt
         */
        void adapt(HttpRequest request) {
            if (!pathPrefix.isEmpty()) {
                UrlBuilder url = UrlBuilder.parse(request.getUrl());
                String path = url.getPath();
                if (path != null && path.startsWith(pathPrefix + "/")) {
                    url.setPath(path.substring(pathPrefix.length()));
                    request.setUrl(url.toString());
                }
            }
            if (apiKeyHeader != null) {
                HttpHeaders headers = request.getHeaders();
                String key = headers.getValue(SDK_API_KEY_HEADER);
                if (key != null) {
                    headers.remove(SDK_API_KEY_HEADER);
                    headers.set(apiKeyHeader, key);
                }
            }
        }
    }
}
