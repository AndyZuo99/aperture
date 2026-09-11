package dev.aperture.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aperture.marketdata.MarketDataService;
import dev.aperture.marketdata.Quote;
import dev.aperture.time.MarketClock;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Pushes quote updates to the browser.
 *
 * <p>Raw WebSocket rather than STOMP deliberately. STOMP would need a client library, which would
 * mean a build step or a CDN script tag - and the frontend here is dependency-free so it can be
 * opened and read without a toolchain. A JSON frame per quote needs no protocol on top.
 */
@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(QuoteWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;
    private final ApiMapper mapper;
    private final MarketClock clock;

    public QuoteWebSocketHandler(MarketDataService marketData, ObjectMapper json,
                                 ApiMapper mapper, MarketClock clock) {
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        marketData.onQuote(this::broadcast);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("Quote stream client connected ({} total)", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    private void broadcast(Quote quote) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(mapper.toQuoteView(quote, clock.now()));
        } catch (IOException e) {
            log.debug("Could not serialise quote: {}", e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                // Sends are synchronized per session: the Spring session is not thread-safe, and
                // quotes arrive from both the streaming thread and the scheduler.
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException | IllegalStateException e) {
                sessions.remove(session);
            }
        }
    }

    public int connectedClients() {
        return sessions.size();
    }
}
