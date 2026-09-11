package dev.aperture.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The shapes the API returns.
 *
 * <p>Separate from the domain records on purpose. Domain types carry {@code Money}, {@code Price}
 * and {@code Optional}, which serialise into shapes a browser should not have to understand, and
 * tying the wire format to the domain means a rename inside the engine silently breaks the UI.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** A row in the watchlist grid. */
    public record QuoteView(
            String symbol,
            String name,
            BigDecimal last,
            BigDecimal bid,
            BigDecimal ask,
            BigDecimal bidSize,
            BigDecimal askSize,
            BigDecimal spreadBps,
            BigDecimal previousClose,
            BigDecimal change,
            BigDecimal changePercent,
            BigDecimal volume,
            String session,
            String dataSource,
            boolean live,
            long ageSeconds,
            String eventTime) {
    }

    /** One bar on the chart. */
    public record BarView(
            String date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume) {
    }

    /**
     * A chart series plus an honest statement of what it is.
     *
     * <p>{@code policy} is what was asked for and {@code basis} is what was delivered. They differ
     * when the request cannot be satisfied - asking for raw prints from a feed that only publishes
     * split-adjusted bars - and {@code basisSatisfied} plus {@code note} say so rather than
     * letting the UI mislabel the chart.
     */
    public record HistoryView(
            String symbol,
            String policy,
            String policyLabel,
            String policyDescription,
            String basis,
            String basisLabel,
            String basisDescription,
            boolean basisSatisfied,
            String note,
            List<BarView> bars,
            BigDecimal windowReturnPercent,
            BigDecimal annualisedVolatilityPercent,
            List<ActionView> actionsInWindow) {
    }

    /** A corporate action, with where it came from. */
    public record ActionView(
            String symbol,
            String exDate,
            String description,
            boolean affectsShareCount,
            String source,
            String sourceDescription) {
    }

    /** The market-data status bar. */
    public record FeedStatusView(
            String activeSource,
            String dataQuality,
            boolean live,
            boolean credentialsPresent,
            boolean connected,
            boolean receivingData,
            String streamState,
            String summary,
            boolean showSimulatedWarning,
            String lastUpdateAt,
            String detail,
            Integer depthLevels) {
    }

    /** An account in the picker. */
    public record AccountView(
            String accountId,
            String displayName,
            String maskedNumber,
            String accountType,
            String accountClass,
            String environment,
            boolean real) {
    }

    /** One environment's availability, so the UI can explain an empty picker. */
    public record EnvironmentView(
            String environment,
            String label,
            boolean available,
            String unavailableReason,
            List<AccountView> accounts) {
    }

    /** Balances plus positions for the selected account. */
    public record AccountDetailView(
            AccountView account,
            Map<String, BigDecimal> balances,
            String dayTradesLeft,
            List<PositionView> positions,
            boolean balancesAvailable,
            String asOf) {
    }

    public record PositionView(
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal costPrice,
            BigDecimal lastPrice,
            BigDecimal marketValue,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedPnlPercent,
            String instrumentType,
            boolean isLong) {
    }

    /** Top of book. */
    public record DepthView(
            String symbol,
            List<DepthLevelView> bids,
            List<DepthLevelView> asks,
            int levels,
            String entitlementNote) {
    }

    public record DepthLevelView(BigDecimal price, BigDecimal size) {
    }

    /** The analyst's answer. */
    public record AnalysisView(
            boolean succeeded,
            String answer,
            List<String> toolCalls,
            String model,
            int turns,
            long elapsedMillis,
            String error) {
    }

    /** Whether live order submission is possible, and why not when it is not. */
    public record TradingGateView(
            boolean liveOrdersPermitted,
            String blockReason,
            String defaultEnvironment) {
    }

    public record AskRequest(String question) {
    }

    public record DeclareSplitRequest(String symbol, String exDate, BigDecimal newShares,
                                      BigDecimal oldShares) {
    }

    public record DeclareDividendRequest(String symbol, String exDate, String payDate,
                                         BigDecimal amountPerShare) {
    }
}
