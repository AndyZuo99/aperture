# Aperture

A live equities market-data and trading console built on the **Webull OpenAPI**, with an **LLM
analyst** that answers questions by querying the running system rather than from training data.

Java 21 · Spring Boot 3.5 · H2 + Flyway · Anthropic Java SDK · dependency-free frontend

---

## What it does

| | |
|---|---|
| **Live market data** | Streaming Level 1 over Webull's MQTT feed, with batch REST snapshots as the polling path and a local simulator as the fallback. Every quote is labelled with where it came from. |
| **Corporate actions** | Splits and cash dividends, with a back-adjustment engine that restates history so a return spanning an ex-date is a real return. Three bases — unadjusted, split-adjusted, total return — selectable per chart. |
| **Accounts** | The account holder's real Webull accounts across Production and Sandbox, with balances and positions as the broker reports them. |
| **Tradable universe** | The securities the *selected account* can actually trade, driven by its account class: event contracts, futures, crypto or equities. |
| **LLM analyst** | Claude with a read-only toolkit over the live services. Answers questions, and **recommends trades** as a market entry plus a resting GTC exit. It cannot place them, structurally. |
| **Order submission** | A human can send a recommendation's two legs to the broker. Sandbox freely; production behind a two-condition gate. |

---

## The interesting problem

A stock that splits 10-for-1 does not fall 90%, but an unadjusted chart says it did. Every return
crossing that date is wrong by an order of magnitude. Handling that is the core of this project,
and it turned out to be harder than "divide the old prices."

**The trap: Webull's daily bars are already split-adjusted.** NVDA on 2023-07-05 comes back from
`getBatchBars` as **42.19**, not the ~421.90 it actually traded at before the June 2024 10-for-1.
An engine that applies its own split adjustment on top divides by ten a *second* time — and the
result is silently wrong, because a smooth chart at a tenth of the real price looks no more
alarming than a smooth chart at the right one.

So a series here is never just "adjusted" or "not". It carries a
[`PriceBasis`](src/main/java/dev/aperture/corporate/PriceBasis.java) recording what it has already
been restated for, and [`PriceAdjuster`](src/main/java/dev/aperture/corporate/PriceAdjuster.java)
applies **only the difference** between that basis and the one requested:

| Source basis | Requested | What actually happens |
|---|---|---|
| Raw | Split-adjusted | Splits applied |
| Split-adjusted (Webull) | Split-adjusted | **Nothing** — already correct |
| Split-adjusted (Webull) | Total return | Dividends only |
| Split-adjusted (Webull) | Unadjusted | **Refused** — see below |

Other decisions in that engine worth calling out:

- **The ex-date boundary.** The ex-date is the first session trading *without* the entitlement, so
  a bar dated on it is already in post-action terms. Only bars strictly *before* it are adjusted.
  Off by one and you leave a single-session cliff — small enough to survive review, large enough
  to corrupt every return that crosses it.
- **Dividend factors are proportional, not absolute.** `(close − dividend) / close`, taken from the
  close the dividend was actually paid out of. The same $1 dividend is a bigger adjustment on a $20
  stock than a $200 one.
- **Factors are computed from the incoming series up front**, so the result does not depend on the
  order overlapping actions happen to be processed in.
- **It refuses to un-adjust.** Reversing the provider's adjustment is arithmetically easy and
  epistemically not: it needs *every* action the provider used, and Aperture's split table is
  curated rather than exhaustive. Asking for an unadjusted chart returns the split-adjusted series
  with a visible note saying so, rather than confidently wrong prices under the label you asked for.
- **Raw bars are stored; adjustment is a view.** A stored pre-adjusted series has to be rewritten
  whenever a new action appears, and a missed rewrite is silently wrong with nothing to compare
  against.

---

## Running it

```bash
mvn clean package && java -jar target/aperture-1.0.0.jar
```

Then open <http://localhost:8080>.

**It runs with no credentials at all.** Without them the market-data layer falls back to a local
simulator, the analyst switches off, and the UI says so in both cases — a persistent banner for
simulated prices, and a reason string where the analyst would be. Nothing fails to start.

For live data:

```bash
export WEBULL_APP_KEY=...        # from developer.webull.com
export WEBULL_APP_SECRET=...
export ANTHROPIC_API_KEY=...     # optional, enables the analyst
```

Live market data additionally needs an **OpenAPI Advanced Quotes** subscription, which is separate
from both API access and any subscription bought in the Webull app. This was built against
*Nasdaq Basic — Non Display*.

---

## The tradable universe

A Webull account is not a general-purpose brokerage account. An Events account trades event
contracts and *only* event contracts; a Futures account trades futures. Showing one watchlist to
every account would be listing instruments most of them cannot buy, so the instrument section is
driven by the account's **class**:

| Account class | Universe | Live count |
|---|---|---|
| `EVENTS_CASH` | Event contracts, grouped by series | 3,165 |
| `FUTURES` | Futures contracts, grouped by product class | 2,284 |
| `CRYPTO` | Spot crypto pairs | 342 |
| `INDIVIDUAL_CASH` · `INDIVIDUAL_MARGIN` · `TRADITIONAL_IRA` | Stocks & ETFs | 20,000 |

Two details that took some care:

**Class decides the universe, not type.** `accountType` only says CASH or MARGIN, and it is
orthogonal: `EVENTS_CASH` and `INDIVIDUAL_CASH` are both CASH accounts that trade entirely
different things. An unrecognised class falls back to equities — the vendor's class strings are
open-ended, and showing stocks to an exotic account is cosmetic where defaulting to futures would
be actively misleading.

**Type decides the capabilities.** Being shortable is a property of the *security*; being able to
short is a property of the *account*. A cash account cannot short or buy on margin at all, so
`Marginable`, `Shortable`, `Easy to borrow` and the margin requirements are suppressed for it
rather than shown as capabilities it does not have — the same AAPL row carries nine attribute
columns in a margin account and four in a cash one.

The four universes return genuinely different vendor shapes, so rather than one wide record where
three quarters of the fields are always null, the class-specific facts live in an ordered
attribute map and the table renders whatever columns the data actually carries.

There is **no simulated fallback** here, unlike quotes. A labelled fake price still lets you
exercise the console; a fake list of tradable instruments would assert that an account can trade
something, which is a claim about entitlements rather than a number standing in for one. With no
vendor connection the section is empty and says why.

---

## The trade recommender

The analyst has a second mode that proposes trades in one shape: **buy now at the market, and rest
a good-til-cancelled sell limit at a target that should clear within about a month.**

The hard part is not generating a suggestion — it is making one that can be checked. So the work
is split:

| The model chooses | Aperture computes |
|---|---|
| Symbol, quantity, target price, horizon, reasoning | Entry price, profit, return, spread cost, historical hit rate, drawdown, reward-to-risk |

The model is never asked for an expected profit. Asked for one it will produce a confident number
that nothing verifies; asked only for a target, its proposal can be priced, tested and rejected.

### Where candidates come from

The watchlist and the candidate pool are deliberately separate:

| | Purpose | Cost |
|---|---|---|
| **Watchlist** (10) | Quoted, streamed, charted | A streaming subscription each |
| **Candidate pool** (~100) | Daily bars only, for the recommender to scan | Six batched bar requests a day |

Subscribing to a hundred names to scan them would mean a hundred streaming subscriptions and an
unreadable quote grid. Backfilling their history instead costs almost nothing, and it widens the
search enormously — in a live run, the two best trades by reward-to-risk (PSX at 0.88, MRK at
0.97) both came from the candidate pool, roughly double the best watchlist name.

Candidates have no cached quote, so the planner fetches one **on demand** before pricing. The
entry price is the single most important number on a recommendation, and the gap between a
previous close and the live ask is the difference between a plan that is priced and one that is
estimated.

### What makes the target falsifiable

For every overlapping historical window,
[`TrendAnalyzer`](src/main/java/dev/aperture/analysis/TrendAnalyzer.java) asks whether the price
**touched** a given gain within the horizon — touch, not close, because that is what fills a
resting limit order. Measuring closes would systematically understate how often the exit actually
gets hit.

A real run, against live prices:

```
NVDA   BUY 40 MARKET  ·  SELL 40 LIMIT @ 224.90 GTC
       entry 218.38   target 224.90   profit +260.80 (+2.99%)
       hit rate 88% of 229 windows   median 3 sessions to hit
       median drawdown -6.63%   reward:risk 0.45
```

That last line is the point. An 88% hit rate looks excellent until you see that reaching a 3%
target historically meant sitting through a 6.6% drawdown — a reward-to-risk below 0.5. Both
numbers are computed here and shown together, because a recommender that surfaced only the
flattering one would be worse than useless.

### Checks the model does not run on itself

[`TradePlanner`](src/main/java/dev/aperture/ai/TradePlanner.java) marks a plan unviable when:

- the target is at or below the entry — arithmetic nonsense however good the reasoning sounds;
- the gain does not clear the spread it must cross to get in, so the position is under water the
  moment it opens.

It also flags simulated pricing, thin historical samples, and weak hit rates. The entry is the
**ask**, not the last print, because a market buy lifts the offer.

### Submitting the orders

Each recommendation carries a **Submit orders** button that sends both legs to the broker.

**The model still cannot trade.** Submission lives in its own `trading` package, is absent from
`AnalystToolkit`, and is reachable only from an HTTP endpoint a human triggers.
`AnalystToolkitIsReadOnlyTest` still fails the build if a mutating method appears on the toolkit,
so the separation is enforced rather than intended.

Four things the submission path does that a naive "place the order" would not:

**It re-plans before sending.** The endpoint takes the plan's *inputs* — symbol, quantity, target —
never its computed economics, and re-prices everything server-side. A browser tab left open for an
hour cannot submit against a price that has moved on. If the live ask has drifted more than 1% from
what the card showed, it refuses: a market order offers no protection from a gap the operator never
agreed to.

**It sequences the legs.** The exit is a sell for shares the account does not own until the entry
fills. Firing both at once risks the venue rejecting the sell, or accepting it as a short. So the
entry goes first, its fill is polled, and **the exit is sized to what actually filled** — a partial
fill must not leave a sell order for more stock than is held.

**It knows a market order is a regular-hours instrument.** Outside 09:30–16:00 the venue accepts
limit orders only. Rather than discovering that from an error code, Aperture refuses with a reason
— or, if extended hours are explicitly allowed, converts the entry to a **marketable limit** 20bp
above the ask. That is both what the venue permits and the safer instrument: an unpriced order into
a thin after-hours book is how people get filled badly.

**It reports each leg separately.** An entry can fill while the exit is rejected, which looks like
success and is not. That state is flagged `unprotected`, because a filled position with no resting
exit is the one outcome worth shouting about.

A real sandbox submission, outside market hours:

```
Submitted to SANDBOX
  BUY  55 LIMIT @ 145.19 (DAY)  — FILLED, filled 144.33
  SELL 55 LIMIT @ 148.50 (GTC)  — SUBMITTED
  The market is closed, so the entry was sent as a marketable limit at 145.19 rather than a
  market order — extended-hours trading accepts limit orders only.
```

### Production stays gated

Sandbox submits freely; it is paper. Production requires **both** conditions on
`ApertureProperties.Webull` — the boolean *and* the confirmation phrase — checked before a trading
client is even acquired. The UI disables the button and shows why.
[`OrderSubmissionGateTest`](src/test/java/dev/aperture/trading/OrderSubmissionGateTest.java)
asserts each condition alone is insufficient, and that a refused submission never reaches for a
client at all.

The button itself is two-click: the first arms, the second sends, and the confirm step spells out
the exact orders (`Confirm: BUY 55 MARKET (DAY) then SELL 55 LIMIT @ 148.50 (GTC)`). A market
entry is not undoable, so one stray click should not reach a venue.

---

## Architecture

```
web/          REST controllers, DTOs, raw-WebSocket quote push, error handling
ai/           Read-only analyst toolkit, tool schemas, manual tool-use loop
marketdata/   QuoteSource implementations, price history, scheduler
corporate/    CorporateAction hierarchy, PriceBasis, PriceAdjuster
account/      Webull accounts, balances, positions, environment model
instrument/   Instrument registry, symbol→id mapping, tradable universe + catalog
trading/      Order submission - separate from ai/, and unreachable from it
analysis/     Forward-window hit rates, drawdowns, moving averages
time/         Exchange calendar, session clock
persistence/  JPA entity + repository for corporate actions
```

**Quote source precedence** is streaming → REST → simulator, falling through only when the one
above is genuinely unavailable. Callers never choose; they get the best available plus a
`QuoteProvenance` saying which it was, so no caller can accidentally treat a simulated price as
live. A cached quote is only replaced by a strictly better one, so a slow REST poll cannot
overwrite a fresher streaming tick.

**Only corporate actions are persisted.** Quotes and bars are re-fetchable in a second, and a stale
local copy of market data is worse than none because it looks authoritative. A hand-declared
split is the one thing here that cannot be re-derived — lose it on restart and an adjusted chart
silently reverts.

### Safety

Live order submission is gated on **two independent conditions**: a boolean *and* a confirmation
phrase that nobody sets by accident. One stray environment variable or merged config file must not
be enough, because the failure direction is real money.
[`LiveTradingGateTest`](src/test/java/dev/aperture/account/LiveTradingGateTest.java) asserts each
condition alone is insufficient — a control only ever tested in its firing state is a control
nobody has checked can refuse. Read-only production access is deliberately *not* gated; looking at
an account is safe, sending it orders is not.

The analyst's safety is **structural**. `AnalystToolkit` has no mutating method — not a disabled
one, not one behind a check. The model can do exactly what that class can do, which is a far
stronger property than a prompt asking it not to trade.
[`AnalystToolkitIsReadOnlyTest`](src/test/java/dev/aperture/ai/AnalystToolkitIsReadOnlyTest.java)
fails the build if a public method is added whose name merely *suggests* mutation.

### The LLM loop

The tool-use loop is written by hand rather than using the SDK's tool runner, which instantiates
tool classes reflectively and so cannot reach Spring beans — and querying the *real* services is
the entire point. The loop is bounded; an unbounded agentic loop against a metered API is a way to
spend money by accident. Model is `claude-opus-5` with adaptive thinking.

---

## What the Webull OpenAPI actually does

Verified against a live account on 2026-09-11. Several of these contradict the published docs.

**Works**
- `getSnapshots(symbols, category, extendHour=true, overnight=false)` — batch Level 1, complete:
  bid/ask with sizes *and* last/open/high/low/preClose/volume in one call.
- MQTT streaming — but Level 1 arrives as **two message types that must be merged**. `SNAPSHOT`
  carries the last price and no bid/ask; `QUOTE` carries the book and no last price. Subscribe to
  one alone and you get a quote that looks complete and is half empty.
- `getBatchBars` — daily OHLCV, 1200+ sessions. Already split-adjusted (see above).
- `getDividendCalendar(symbol, category)` — real cash dividends. Note the argument order; reversing
  it returns `417 UNSUPPORTED_CATEGORY` naming the *symbol* as the bad category.
- `getQuote(depth=1)`, `getInstruments`, `getCompanyProfile`, and the whole v3 trade API.
- **Order placement has three undocumented required fields**, each discovered from a rejection:
  `comboType` (`NORMAL` for a standalone order, else `invalid combo_type`), `entrustType` (`QTY`),
  and `supportTradingSession`. That last one is the interesting one: `N` means regular hours and is
  the *only* value a MARKET order accepts, while `ALL` permits extended hours and is valid on LIMIT
  orders only — sending `ALL` with a market order fails as
  `invalid support_trading_session, value: ALL`. Together those encode a real brokerage rule:
  extended-hours trading is limit-only.
- **The batch caps differ per endpoint and are enforced strictly.** `getBatchBars` takes a hard
  **20** symbols (`417 ILLEGAL_PARAMETER: symbols size must be between 1 and 20`), while
  `getSnapshots` handles 50+ comfortably. Sharing one batch-size constant between them is a latent
  bug that stays hidden while the watchlist is small and fires the moment it is not.
- `getInstrumentsV2` / `getCryptoInstrument` / `getFuturesProducts` / `getEventSeriesList` — the
  four tradable universes. Two wrinkles: `getInstrumentsV2` ignores `pageSize`, returns a thousand
  rows ordered by instrument id, and **the first page is entirely ETFs** — a single call yields an
  equity universe containing no actual equities, so it has to be paged via `paginationKey`. And
  there is no "list every event contract" endpoint: `getEventInstrumentsList` rejects a request
  with no series symbol (`series_symbol is blank`), so the event universe is gathered series by
  series, paced to stay under the rate limit.

**Does not**
- `getCorpAction` → `404 UnknownServerError`. Retired. Its `EventType` dictionary only ever covered
  splits anyway, so **splits cannot be pulled from Webull** — hence the curated reference table,
  labelled as such in the UI.
- `getQuote(depth>1)` → `417 depth not more than 1`. Nasdaq Basic is BBO only. Aperture parses the
  cap out of that message and honours it rather than failing every subsequent request.
- `overnight=true` on any quote call → `403 MARKET_DATA_NOT_SUBSCRIBED: please subscribe to NIGHT
  TRADING STOCK QUOTES`. This one is a trap: the error says "market data not subscribed", so it
  reads exactly like having no entitlement at all. It is one optional product missing, and treating
  it as a dead feed would switch off a working one.
- **Sandbox rejects production credentials** with `401 UNAUTHORIZED`. The sandbox is a separate
  credential domain, not the same account at a different address. Aperture checks for dedicated
  sandbox keys up front so it can say that plainly instead of surfacing an auth error.

**Four SDK landmines**
- **The two SDKs disagree about OkHttp, and it only fails at runtime.** Webull depends on okhttp
  3.14.9 (Java); the Anthropic client requires 4.12.0 (the Kotlin rewrite). Webull's copy is one
  level shallower, so Maven's nearest-wins picks 3.x, everything compiles, the app starts, market
  data works — and the *first analyst call* dies with
  `NoSuchFieldError: Class okhttp3.HttpUrl does not have member field 'HttpUrl$Companion'`.
  OkHttp 4 is deliberately binary-compatible with 3 for Java callers, so `dependencyManagement`
  pins it up to 4.12.0 and both SDKs are happy.
- **`StopReason` is not a Java enum.** It is a final class implementing the SDK's own `Enum`
  interface, with static constants and a real `equals()`. Comparing it with `==` / `!=` compares
  object identity and is *always* false, so a tool-use turn reads as a finished answer: the
  model's preamble text gets returned and every tool call is silently dropped. Nothing throws, and
  the reply looks like a plausible non-answer ("I'll pull quotes, feed status, and adjustment
  comparisons.") — which is what makes it worth writing down. Use `.equals()`.
- `new DataClient(config)` is not lazy and worse than "throws": when the token comes back `PENDING`
  it **blocks the calling thread for up to 300 seconds**. In a `@Bean` method that is not a failure,
  it is a hang, and Spring Boot never starts. Every client here is built on a daemon thread
  ([`WebullClientHolder`](src/main/java/dev/aperture/marketdata/WebullClientHolder.java)); startup
  is ~2s and the app serves simulated data until the handshake lands.
- The SDK signs requests with `javax.xml.bind.DatatypeConverter`, removed from the JDK in Java 11
  and **not declared as a dependency**. It needs `javax.xml.bind:jaxb-api`, *not* the jakarta
  artifact pinned back — Spring Boot manages that one at 4.x for Hibernate and downgrading it
  breaks Hibernate instead. See the comments in [`pom.xml`](pom.xml).

---

## Tests

```bash
mvn test        # 140 tests
```

The ones worth reading:

- [`PriceAdjusterTest`](src/test/java/dev/aperture/corporate/PriceAdjusterTest.java) — builds series
  that **contain** the discontinuity. Testing adjustment against a smooth series proves nothing:
  adjustment would then be *creating* a jump rather than removing one, and the test passes either
  way. Covers the double-adjustment guard, the ex-date boundary, compounding, and the refusal to
  un-adjust.
- [`LiveTradingGateTest`](src/test/java/dev/aperture/account/LiveTradingGateTest.java) — asserts the
  gate stays **shut** for each condition alone.
- [`AnalystToolkitIsReadOnlyTest`](src/test/java/dev/aperture/ai/AnalystToolkitIsReadOnlyTest.java) —
  reflection guard over the LLM's entire reachable surface.
- [`MarketCalendarTest`](src/test/java/dev/aperture/time/MarketCalendarTest.java) — holidays derived
  from rules rather than a table that expires, including Good Friday and weekend observance.
- [`TradableUniverseTest`](src/test/java/dev/aperture/instrument/TradableUniverseTest.java) — the
  account-class mapping against every class the live account actually returns, plus the
  fallback for classes that do not exist yet.
- [`TrendAnalyzerTest`](src/test/java/dev/aperture/analysis/TrendAnalyzerTest.java) — pins down
  what a "hit rate" counts: a series that closes flat every day but whose highs reach the target
  is a 100% hit rate, not 0%. Also the horizon boundary, cut deliberately on both sides.
- [`TradePlannerTest`](src/test/java/dev/aperture/ai/TradePlannerTest.java) — that a target below
  the entry, or a gain smaller than the spread, is rejected rather than priced.
- [`OrderSubmissionGateTest`](src/test/java/dev/aperture/trading/OrderSubmissionGateTest.java) —
  every case asserts submission is *refused*, because a gate is only worth having if it has been
  shown to shut.

---

## Limitations

Stated plainly, because pretending otherwise would be the more serious flaw.

- **The split table is curated, not a feed.** Seven real historical splits for the default
  watchlist. Webull's corporate-action endpoint is dead and Aperture has no other source, so an
  unlisted split on a symbol you add will not be adjusted for. The UI labels every action's source
  so a `Reference` adjustment is never mistaken for an observed one. A production system would buy
  a corporate-actions vendor.
- **The dividend calendar is forward-looking.** It does not backfill years of history, so a
  total-return series only reflects dividends within its reach.
- **Production order submission is off by default** and needs two deliberate configuration
  changes to enable. Sandbox submission is fully working and verified. Given the goal — a
  portfolio piece, not a trading bot — a bug reaching real money is a genuine loss with no upside.
- **The two legs are sent as separate orders, not a venue-native bracket.** Webull supports
  OTO/OCO/OTOCO combos, which would let the venue trigger the exit off the entry's fill instead of
  Aperture polling for it. That is the better shape and is not implemented: the current path polls
  for up to six seconds and, if the entry has not filled, reports that the exit was not placed
  rather than leaving it to chance.
- **Futures can be listed but not analysed.** `getFuturesBars` returns `403
  MARKET_DATA_NOT_SUBSCRIBED` on this entitlement, so no historical odds can be computed for a
  futures contract and the recommender declines to propose one. Equities, crypto and event
  contracts all have working history.
- **Hit rates come from overlapping windows**, which are heavily autocorrelated — 229 windows over
  250 sessions are nothing like 229 independent trials. The window count is shown next to every
  rate, and samples under 60 are flagged as insufficient rather than quoted.
- **Past behaviour is not a forecast**, and the recommender says so in its own output. A target
  reached in 88% of past windows is a statement about history, not a probability for next month.
- **Depth is one level.** Nasdaq Basic is BBO. The touch is real and the liquidity behind it is
  invisible, which the UI states rather than rendering as an empty book.
- **Sandbox trades on simulated fills against real prices.** Market data is always sourced from
  the production endpoint, because the Nasdaq entitlement lives on the real account and paper
  trading against invented prices would defeat the point. Verified against five Webull paper
  accounts.
- **The UI falls back to Production** when Sandbox has no credentials, rather than showing an empty
  panel. Read-only, and the environment badge and banner both turn red — but it is a deliberate
  choice worth knowing about.
- **The equity universe is capped at 20,000 instruments** (20 pages). That covers the US listed
  universe including preferreds, rights and units, but it is a prefix rather than a guarantee —
  the cap exists so a vendor that keeps returning pagination keys cannot spin the load into an
  unbounded fetch.
- **In-memory price history.** Re-fetched from the vendor on startup; fine at watchlist scale,
  not at thousands of symbols.
