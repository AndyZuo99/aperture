package dev.aperture.marketdata;

import com.webull.openapi.core.http.HttpApiConfig;
import com.webull.openapi.data.DataClient;
import com.webull.openapi.trade.TradeClientV3;
import dev.aperture.account.TradingEnvironment;
import dev.aperture.config.ApertureProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the Webull SDK clients and keeps their construction off the startup path.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>{@code new DataClient(config)} is not lazy, and its failure mode is worse than throwing. It
 * authenticates during construction. On bad credentials it throws a 401 - recoverable. But when
 * the token comes back {@code PENDING}, the SDK's initialiser <strong>blocks the calling thread
 * for up to 300 seconds</strong>, polling and logging every five seconds. Constructing it inside
 * a {@code @Bean} method therefore does not fail, it <em>hangs</em>, and Spring Boot never
 * finishes starting. Catching the exception does not help, because nothing is thrown.
 *
 * <p>So every client is built on a daemon thread and published through an
 * {@link AtomicReference}. Startup drops from a five-minute hang to about three seconds, and
 * Aperture serves the simulated feed until the real client arrives.
 *
 * <h2>Why market data always uses the production endpoint</h2>
 *
 * <p>The market-data entitlement is attached to the real account, not to a sandbox one, and the
 * sandbox endpoint does not carry a Nasdaq feed. Paper trading against simulated prices would
 * also be close to useless - the point of paper trading is real prices with simulated fills. So
 * the quote feed is pinned to production while the <em>trade</em> client follows the selected
 * environment.
 */
@Component
public class WebullClientHolder {

    private static final Logger log = LoggerFactory.getLogger(WebullClientHolder.class);

    private final ApertureProperties.Webull config;
    private final AtomicReference<DataClient> dataClient = new AtomicReference<>();
    private final Map<TradingEnvironment, AtomicReference<TradeClientV3>> tradeClients =
            new EnumMap<>(TradingEnvironment.class);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_CONFIGURED);
    private final AtomicReference<String> detail = new AtomicReference<>();
    private final Map<TradingEnvironment, String> unavailable =
            new java.util.concurrent.ConcurrentHashMap<>();
    private ExecutorService executor;

    public WebullClientHolder(ApertureProperties properties) {
        this.config = properties.webull();
        for (TradingEnvironment environment : TradingEnvironment.values()) {
            tradeClients.put(environment, new AtomicReference<>());
        }
        start();
    }

    private void start() {
        if (!config.enabled()) {
            detail.set("Webull integration disabled by configuration");
            log.info("Webull disabled; Aperture will run on the simulated feed");
            return;
        }
        if (!config.hasCredentials()) {
            detail.set("No credentials - set WEBULL_APP_KEY and WEBULL_APP_SECRET");
            log.info("No Webull credentials found; Aperture will run on the simulated feed");
            return;
        }

        status.set(Status.INITIALISING);
        detail.set("Connecting to Webull");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "webull-init");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::connect);
    }

    private void connect() {
        Instant started = Instant.now();
        log.info("Connecting to Webull in the background; Aperture is live on the simulated feed "
                + "until this completes");
        try {
            dataClient.set(new DataClient(configFor(TradingEnvironment.PRODUCTION)));
            for (TradingEnvironment environment : TradingEnvironment.values()) {
                if (!hasCredentialsFor(environment)) {
                    // Not a failure - the environment simply has no keys. Attempting it anyway
                    // would spend a request to be told 401 and would log an alarming stack trace
                    // for an entirely expected situation.
                    unavailable.put(environment, environment == TradingEnvironment.SANDBOX
                            ? "No sandbox credentials. The Webull sandbox issues its own app key "
                              + "and secret; production keys are rejected there. Set "
                              + "WEBULL_SANDBOX_APP_KEY and WEBULL_SANDBOX_APP_SECRET."
                            : "No credentials configured");
                    continue;
                }
                try {
                    tradeClients.get(environment)
                            .set(new TradeClientV3(configFor(environment)));
                } catch (RuntimeException e) {
                    // One environment being unavailable must not take the other down with it.
                    unavailable.put(environment, messageOf(e));
                    log.warn("Webull {} trade client unavailable: {}",
                            environment.label(), messageOf(e));
                }
            }
            status.set(Status.READY);
            long seconds = Duration.between(started, Instant.now()).toSeconds();
            detail.set("Connected in " + seconds + "s");
            log.info("Webull clients ready after {}s. Note that market data is a separate "
                    + "entitlement from API access - quotes can still be refused.", seconds);
        } catch (Throwable t) {
            status.set(Status.FAILED);
            detail.set(messageOf(t));
            log.warn("Webull client could not be created after {}s ({}). Staying on the "
                            + "simulated feed.",
                    Duration.between(started, Instant.now()).toSeconds(), detail.get());
        }
    }

    public Optional<DataClient> dataClient() {
        return Optional.ofNullable(dataClient.get());
    }

    public Optional<TradeClientV3> tradeClient(TradingEnvironment environment) {
        return Optional.ofNullable(tradeClients.get(environment).get());
    }

    public Status status() {
        return status.get();
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail.get());
    }

    public boolean isConfigured() {
        return status.get() != Status.NOT_CONFIGURED;
    }

    public boolean isConnected() {
        return dataClient.get() != null;
    }

    public boolean isInitialising() {
        return status.get() == Status.INITIALISING;
    }

    public HttpApiConfig configFor(TradingEnvironment environment) {
        boolean sandbox = environment == TradingEnvironment.SANDBOX;
        return HttpApiConfig.builder()
                .appKey(config.appKeyFor(sandbox))
                .appSecret(config.appSecretFor(sandbox))
                .endpoint(environment.endpoint())
                .regionId(config.regionId())
                .build();
    }

    /** Whether credentials exist for an environment at all. */
    public boolean hasCredentialsFor(TradingEnvironment environment) {
        boolean sandbox = environment == TradingEnvironment.SANDBOX;
        return !config.appKeyFor(sandbox).isBlank() && !config.appSecretFor(sandbox).isBlank();
    }

    /** Why an environment has no usable client, for display in the account picker. */
    public Optional<String> unavailableReason(TradingEnvironment environment) {
        if (tradeClient(environment).isPresent()) {
            return Optional.empty();
        }
        return Optional.ofNullable(unavailable.get(environment))
                .or(() -> Optional.of(isInitialising() ? "Connecting" : "Not connected"));
    }

    private static String messageOf(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Lifecycle of the background handshake. */
    public enum Status {
        /** No credentials, or the integration is switched off. */
        NOT_CONFIGURED,
        /** Handshake in flight on the daemon thread. */
        INITIALISING,
        /** Clients constructed. Says nothing about the market-data entitlement. */
        READY,
        /** Handshake failed. Aperture stays on the simulated feed. */
        FAILED
    }
}
