package dev.aperture.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Anthropic client, or nothing at all.
 *
 * <p>Returns null rather than failing startup when no key is configured. The analyst is one
 * feature of the console, not a precondition for it - someone running Aperture to look at quotes
 * should not be stopped by the absence of an unrelated API key.
 */
@Configuration
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    @Bean
    public AnthropicClient anthropicClient(ApertureProperties properties) {
        ApertureProperties.Analyst analyst = properties.analyst();
        if (!analyst.enabled()) {
            log.info("LLM analyst disabled by configuration");
            return null;
        }
        String apiKey = analyst.resolvedApiKey();
        if (apiKey.isBlank()) {
            log.info("No Anthropic API key found (set ANTHROPIC_API_KEY); the analyst is off, "
                    + "everything else works normally");
            return null;
        }
        log.info("LLM analyst enabled using model {}", analyst.model());
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}
