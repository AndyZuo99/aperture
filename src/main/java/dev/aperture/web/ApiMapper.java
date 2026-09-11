package dev.aperture.web;

import dev.aperture.account.AccountBalance;
import dev.aperture.account.AccountPosition;
import dev.aperture.account.BrokerAccount;
import dev.aperture.common.Money;
import dev.aperture.common.Price;
import dev.aperture.corporate.AdjustmentPolicy;
import dev.aperture.corporate.RecordedAction;
import dev.aperture.instrument.Instrument;
import dev.aperture.instrument.ReferenceDataService;
import dev.aperture.marketdata.Bar;
import dev.aperture.marketdata.FeedStatus;
import dev.aperture.marketdata.MarketDepth;
import dev.aperture.marketdata.Quote;
import dev.aperture.ai.AnalysisResult;
import dev.aperture.ai.OrderLeg;
import dev.aperture.ai.RecommendationSet;
import dev.aperture.ai.TradeEconomics;
import dev.aperture.ai.TradeRecommendation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Converts domain objects into the wire shapes in {@link ApiDtos}. */
@Component
public class ApiMapper {

    private final ReferenceDataService referenceData;

    public ApiMapper(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    public ApiDtos.QuoteView toQuoteView(Quote quote, Instant now) {
        Instrument instrument = referenceData.byId(quote.instrumentId()).orElse(null);
        return new ApiDtos.QuoteView(
                instrument == null ? quote.instrumentId().value() : instrument.primarySymbol(),
                instrument == null ? "" : instrument.name(),
                quote.last().toDisplay(),
                quote.bid().toDisplay(),
                quote.ask().toDisplay(),
                quote.bidSize().toDisplay(),
                quote.askSize().toDisplay(),
                quote.spreadBasisPoints(),
                quote.previousClose().toDisplay(),
                quote.changeFromPreviousClose().toDisplay(),
                quote.changePercent(),
                quote.volume().toDisplay(),
                quote.session().label(),
                quote.provenance().label(),
                quote.isLive(),
                Math.max(0, quote.age(now).toSeconds()),
                quote.eventTime().toString());
    }

    public ApiDtos.BarView toBarView(Bar bar) {
        return new ApiDtos.BarView(
                bar.sessionDate().toString(),
                bar.open().toDisplay(),
                bar.high().toDisplay(),
                bar.low().toDisplay(),
                bar.close().toDisplay(),
                bar.volume().toDisplay());
    }

    public ApiDtos.ActionView toActionView(RecordedAction recorded) {
        String symbol = referenceData.byId(recorded.action().instrumentId())
                .map(Instrument::primarySymbol)
                .orElse(recorded.action().instrumentId().value());
        return new ApiDtos.ActionView(
                symbol,
                recorded.action().exDate().toString(),
                recorded.action().describe(),
                recorded.action().affectsShareCount(),
                recorded.source().label(),
                recorded.source().description());
    }

    public ApiDtos.FeedStatusView toFeedStatusView(FeedStatus status) {
        return new ApiDtos.FeedStatusView(
                status.activeSource(),
                status.provenance().description(),
                status.provenance().isLive(),
                status.credentialsPresent(),
                status.connected(),
                status.receivingData(),
                status.streamState(),
                status.summary(),
                status.showSimulatedWarning(),
                status.lastUpdateAt().map(Instant::toString).orElse(null),
                status.detail().orElse(null),
                status.depthLevels().orElse(null));
    }

    public ApiDtos.AccountView toAccountView(BrokerAccount account) {
        return new ApiDtos.AccountView(
                account.accountId(),
                account.displayName(),
                account.maskedNumber(),
                account.accountType(),
                account.accountClass(),
                account.environment().name(),
                account.isReal());
    }

    public ApiDtos.AccountDetailView toAccountDetailView(BrokerAccount account,
                                                         AccountBalance balance,
                                                         List<AccountPosition> positions) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        put(balances, "netLiquidationValue", balance.netLiquidationValue());
        put(balances, "cash", balance.totalCash());
        put(balances, "marketValue", balance.marketValue());
        put(balances, "buyingPower", balance.buyingPower());
        put(balances, "unrealizedPnl", balance.unrealizedProfitLoss());
        put(balances, "dayPnl", balance.dayProfitLoss());
        put(balances, "maintenanceMargin", balance.maintenanceMargin());

        List<ApiDtos.PositionView> views = new ArrayList<>();
        for (AccountPosition position : positions) {
            views.add(new ApiDtos.PositionView(
                    position.symbol(),
                    position.symbolName(),
                    position.quantity().toDisplay(),
                    position.costPrice().toDisplay(),
                    position.lastPrice().toDisplay(),
                    position.marketValue().map(Money::toDisplay).orElse(null),
                    position.unrealizedProfitLoss().map(Money::toDisplay).orElse(null),
                    position.unrealizedProfitLossPercent().orElse(null),
                    position.instrumentType(),
                    position.isLong()));
        }
        return new ApiDtos.AccountDetailView(
                toAccountView(account),
                balances,
                balance.dayTradesLeft().orElse(null),
                views,
                balance.isAvailable(),
                balance.asOf().toString());
    }

    public ApiDtos.DepthView toDepthView(String symbol, MarketDepth depth, String note) {
        return new ApiDtos.DepthView(
                symbol,
                depth.bids().stream().map(ApiMapper::toLevel).toList(),
                depth.asks().stream().map(ApiMapper::toLevel).toList(),
                depth.levelCount(),
                note);
    }

    public ApiDtos.AnalysisView toAnalysisView(AnalysisResult result) {
        return new ApiDtos.AnalysisView(
                result.succeeded(),
                result.answer(),
                result.toolCalls(),
                result.model(),
                result.turns(),
                result.elapsed().toMillis(),
                result.error());
    }

    public ApiDtos.RecommendationSetView toRecommendationSetView(RecommendationSet set) {
        List<ApiDtos.RecommendationView> views = new ArrayList<>();
        for (TradeRecommendation recommendation : set.recommendations()) {
            views.add(toRecommendationView(recommendation));
        }
        return new ApiDtos.RecommendationSetView(
                set.succeeded(),
                set.commentary(),
                set.accountLabel(),
                set.environment(),
                set.marketDataLive(),
                set.totalCapitalRequired().toDisplay(),
                views,
                set.model(),
                set.turns(),
                set.toolCalls(),
                set.elapsed().toMillis(),
                set.error());
    }

    private ApiDtos.RecommendationView toRecommendationView(TradeRecommendation recommendation) {
        List<ApiDtos.OrderLegView> legs = new ArrayList<>();
        for (OrderLeg leg : recommendation.legs()) {
            legs.add(new ApiDtos.OrderLegView(
                    leg.side().name(),
                    leg.type().name(),
                    leg.quantity().toDisplay(),
                    leg.limitPrice().map(Price::toDisplay).orElse(null),
                    leg.timeInForce().name(),
                    leg.purpose(),
                    leg.describe()));
        }
        TradeEconomics economics = recommendation.economics();
        return new ApiDtos.RecommendationView(
                recommendation.symbol(),
                recommendation.name(),
                recommendation.universe(),
                recommendation.conviction(),
                recommendation.rationale(),
                recommendation.horizonSessions(),
                recommendation.horizonDescription(),
                legs,
                new ApiDtos.EconomicsView(
                        economics.entryPrice().toDisplay(),
                        economics.targetPrice().toDisplay(),
                        economics.notional().toDisplay(),
                        economics.grossProfit().toDisplay(),
                        economics.returnPercent(),
                        economics.spreadCostPercent(),
                        economics.netReturnAfterSpreadPercent(),
                        economics.historicalHitRatePercent().orElse(null),
                        economics.historicalWindows().orElse(null),
                        economics.medianSessionsToHit().orElse(null),
                        economics.medianDrawdownPercent(),
                        economics.rewardToRisk().orElse(null),
                        economics.warnings(),
                        economics.viable()));
    }

    public String policyLabel(AdjustmentPolicy policy) {
        return policy.label();
    }

    private static ApiDtos.DepthLevelView toLevel(MarketDepth.Level level) {
        return new ApiDtos.DepthLevelView(level.price().toDisplay(), level.size().toDisplay());
    }

    private static void put(Map<String, BigDecimal> target, String key, Optional<Money> value) {
        value.ifPresent(money -> target.put(key, money.toDisplay()));
    }
}
