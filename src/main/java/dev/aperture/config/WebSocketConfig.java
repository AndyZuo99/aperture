package dev.aperture.config;

import dev.aperture.web.QuoteWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the live quote stream. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final QuoteWebSocketHandler quoteHandler;

    public WebSocketConfig(QuoteWebSocketHandler quoteHandler) {
        this.quoteHandler = quoteHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin only. The page is served by this application, so there is no reason to
        // accept a socket from anywhere else.
        registry.addHandler(quoteHandler, "/ws/quotes");
    }
}
