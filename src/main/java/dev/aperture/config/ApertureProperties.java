package dev.aperture.config;

import dev.aperture.account.TradingEnvironment;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All of Aperture's tunables, bound from {@code application.yml} under the {@code aperture} key.
 *
 * <p>Records rather than mutable beans: configuration read at startup should not be something a
 * component can change at runtime.
 */
@ConfigurationProperties(prefix = "aperture")
public record ApertureProperties(
        @DefaultValue Webull webull,
        @DefaultValue MarketData marketData,
        @DefaultValue Analyst analyst) {

    /**
     * Webull OpenAPI credentials and the live-trading gate.
     *
     * <p>Credentials come from the environment, never from a checked-in file. The app key alone
     * is enough to authenticate as the account holder.
     */
    public record Webull(
            @DefaultValue("") String appKey,
            @DefaultValue("") String appSecret,
            @DefaultValue("") String sandboxAppKey,
            @DefaultValue("") String sandboxAppSecret,
            @DefaultValue("us") String regionId,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("SANDBOX") String defaultEnvironment,
            @DefaultValue("false") boolean allowLiveTrading,
            @DefaultValue("") String liveTradingConfirmation,
            @DefaultValue("6m") Duration initialisationTimeout) {

        /** The phrase {@link #liveTradingConfirmation} must match for live orders to be allowed. */
        public static final String REQUIRED_CONFIRMATION = "I ACCEPT REAL MONEY ORDERS";

        public boolean hasCredentials() {
            return !appKey.isBlank() && !appSecret.isBlank();
        }

        public boolean isUsable() {
            return enabled && hasCredentials();
        }

        /**
         * Whether separate sandbox credentials have been supplied.
         *
         * <p>The sandbox is a <strong>separate credential domain</strong>, not the same account at
         * a different address. Sending a production app key to {@code api.sandbox.webull.com}
         * returns {@code 401 UNAUTHORIZED: Invalid credentials. Please verify your credentials and
         * ensure you are connecting to the correct environment} - which reads like the key is
         * wrong rather than like it is being used in the wrong place. Aperture checks for
         * dedicated sandbox keys up front so it can say that plainly instead of surfacing a 401.
         */
        public boolean hasSandboxCredentials() {
            return !sandboxAppKey.isBlank() && !sandboxAppSecret.isBlank();
        }

        /** The key for an environment, or blank if that environment has no credentials. */
        public String appKeyFor(boolean sandbox) {
            if (!sandbox) {
                return appKey;
            }
            return hasSandboxCredentials() ? sandboxAppKey : "";
        }

        public String appSecretFor(boolean sandbox) {
            if (!sandbox) {
                return appSecret;
            }
            return hasSandboxCredentials() ? sandboxAppSecret : "";
        }

        public TradingEnvironment defaultTradingEnvironment() {
            return TradingEnvironment.parseOrSandbox(defaultEnvironment);
        }

        /**
         * Whether an order may be submitted to the live brokerage.
         *
         * <p>Two independent conditions, deliberately. A boolean alone is one typo, one merged
         * config file, or one copied environment variable away from routing paper orders at real
         * money - and the failure is unrecoverable in the direction that matters. The second
         * condition is a phrase nobody sets by accident.
         *
         * <p>Read-only production access - balances, positions, quotes - is not gated by this.
         * Looking at the real account is safe; sending it orders is not.
         */
        public boolean liveOrdersPermitted() {
            return allowLiveTrading
                    && REQUIRED_CONFIRMATION.equals(liveTradingConfirmation.trim());
        }

        /** Why live orders are blocked, for display. Empty when they are permitted. */
        public String liveOrderBlockReason() {
            if (!allowLiveTrading) {
                return "aperture.webull.allow-live-trading is false";
            }
            if (!REQUIRED_CONFIRMATION.equals(liveTradingConfirmation.trim())) {
                return "aperture.webull.live-trading-confirmation is not set to the required phrase";
            }
            return "";
        }
    }

    /** Market-data behaviour: what to watch, how often to poll, when to call a quote stale. */
    public record MarketData(
            @DefaultValue({"AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "JPM", "V", "SPY"})
            List<String> watchlist,
            /*
             * Names backfilled for the recommender to scan, but NOT quoted or streamed. The
             * watchlist is what you are watching; this is the pool the analyst searches. Keeping
             * them separate means a few hundred candidates cost a handful of daily bar requests
             * rather than a few hundred streaming subscriptions.
             */
            @DefaultValue({}) List<String> candidateUniverse,

            /*
             * Per-universe watchlists. What you watch has to follow what the account can trade:
             * a futures account has no use for a grid of equities it cannot buy.
             */
            @DefaultValue({"BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "DOGEUSD", "LTCUSD",
                    "AVAXUSD", "LINKUSD"})
            List<String> cryptoWatchlist,

            /*
             * Event SERIES, not contract symbols. An individual market is dated -
             * KXFEDDECISION-26SEP-H26 - and stops existing after it settles, so a hardcoded list
             * of them silently empties within weeks. The series is the stable identifier; the
             * nearest live market in each is resolved at runtime.
             */
            @DefaultValue({"KXFEDDECISION", "KXCPIYOY", "KXPAYROLLS", "KXU3", "KXAAAGASM",
                    "INXI", "KXETHD"})
            List<String> eventSeriesWatchlist,

            /*
             * Listed for completeness. Futures market data is a separate Webull entitlement, so
             * these carry no quote - the grid says so rather than showing blanks.
             */
            @DefaultValue({"ESZ6", "NQZ6", "CLX6", "GCZ6"})
            List<String> futuresWatchlist,

            @DefaultValue("2s") Duration pollInterval,
            @DefaultValue("15s") Duration staleAfter,
            @DefaultValue("250") int historyDays,
            @DefaultValue("true") boolean streamingEnabled,
            @DefaultValue("5m") Duration resubscribeInterval) {

        /**
         * A quote older than this is shown greyed out rather than as current.
         *
         * <p>Staleness is about age only. Folding "is the market open" into it produces the
         * unactionable message "received 0 seconds ago; too stale" outside regular hours - the
         * session is a separate question, answered by {@code MarketClock}.
         */
        public boolean isStale(java.time.Instant receivedAt, java.time.Instant now) {
            return receivedAt.plus(staleAfter).isBefore(now);
        }

        /** The configured watchlist for a universe. Events resolve from series separately. */
        public List<String> watchlistFor(dev.aperture.instrument.TradableUniverse universe) {
            return switch (universe) {
                case EQUITY -> watchlist;
                case CRYPTO -> cryptoWatchlist;
                case FUTURES -> futuresWatchlist;
                case EVENT -> eventSeriesWatchlist;
            };
        }
    }

    /** The LLM analyst. */
    public record Analyst(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("8") int maxToolIterations,
            @DefaultValue("16000") int maxTokens) {

        /**
         * Falls back to the standard {@code ANTHROPIC_API_KEY} environment variable, which is
         * what the SDK reads by default and what most people already have set.
         */
        public String resolvedApiKey() {
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            String fromEnv = System.getenv("ANTHROPIC_API_KEY");
            return fromEnv == null ? "" : fromEnv;
        }

        public boolean isUsable() {
            return enabled && !resolvedApiKey().isBlank();
        }
    }
}
