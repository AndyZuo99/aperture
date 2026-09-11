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
| **LLM analyst** | Claude with a read-only toolkit over the live services. It cannot trade, structurally. |

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

## Architecture

```
web/          REST controllers, DTOs, raw-WebSocket quote push, error handling
ai/           Read-only analyst toolkit, tool schemas, manual tool-use loop
marketdata/   QuoteSource implementations, price history, scheduler
corporate/    CorporateAction hierarchy, PriceBasis, PriceAdjuster
account/      Webull accounts, balances, positions, environment model
instrument/   Instrument registry and symbol→id mapping
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
mvn test        # 91 tests
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
- **No order placement.** The wiring and the gate are here; the submission path is deliberately not.
  Given the goal — a portfolio piece, not a trading bot — a bug reaching real money is a genuine
  loss with no upside.
- **Depth is one level.** Nasdaq Basic is BBO. The touch is real and the liquidity behind it is
  invisible, which the UI states rather than rendering as an empty book.
- **Sandbox trades on simulated fills against real prices.** Market data is always sourced from
  the production endpoint, because the Nasdaq entitlement lives on the real account and paper
  trading against invented prices would defeat the point. Verified against five Webull paper
  accounts.
- **The UI falls back to Production** when Sandbox has no credentials, rather than showing an empty
  panel. Read-only, and the environment badge and banner both turn red — but it is a deliberate
  choice worth knowing about.
- **In-memory price history.** Re-fetched from the vendor on startup; fine at watchlist scale,
  not at thousands of symbols.
