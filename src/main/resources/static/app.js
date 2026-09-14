/*
 * Aperture — trading console.
 *
 * No framework, no build step, no CDN. Everything here runs straight from the file the server
 * ships, which is deliberate: the UI can be read and modified without a toolchain, and there is
 * no bundle that can drift out of step with the API.
 */
'use strict';

const state = {
  environment: 'SANDBOX',
  accountId: null,
  environments: [],
  selectedSymbol: null,
  policy: 'SPLITS_ONLY',
  quotes: new Map(),   // symbol -> latest quote view
  history: null,
  asking: false,
  universe: null,          // resolved asset class for the selected account
  universeQuery: '',
  universeGroup: '',
  universeTradableOnly: true,
  analystMode: 'ask',
  recommending: false,
  orderCapability: null,
  armedSubmit: null,   // symbol whose submit button is armed for confirmation
  // Monotonic request ids. Panels are reloaded by user actions that can fire faster than the
  // requests complete, and the universes differ hugely in cost: a cached equity listing returns
  // in milliseconds while a cold event listing takes seconds. Without these, switching from an
  // events account to an equities one renders the equities correctly and is then overwritten by
  // the slower events response that was already in flight.
  universeRequest: 0,
  historyRequest: 0,
  quotesRequest: 0,
  searchRequest: 0,
  ordersRequest: 0,
  chartUniverse: 'EQUITY',   // the universe the charted symbol belongs to
  chartMode: 'historical',   // 'historical' shows a window of sessions, 'live' shows today
  chartRange: '1Y',
  intraday: null,
  liveSeries: null,
  intradayRequest: 0,
  liveTimer: null,
  livePrice: null,     // the previous headline price, to tint the next one by direction
};

/* ── helpers ─────────────────────────────────────────────────────── */

const $ = (id) => document.getElementById(id);

async function getJson(url) {
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    // 204 is a legitimate "this account has nothing", not a failure.
    if (response.status === 204 || response.status === 404) return null;
    throw new Error(`${response.status} ${response.statusText}`);
  }
  if (response.status === 204) return null;
  return response.json();
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  return response.json();
}

const money = (value) =>
  value === null || value === undefined
    ? '—'
    : Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const compact = (value) => {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  if (n >= 1e9) return (n / 1e9).toFixed(2) + 'B';
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(0) + 'K';
  return String(n);
};

// Bar timestamps are instants; a trader reads them in exchange time, never the browser's.
const clockTime = (iso) => {
  if (!iso) return '';
  return new Date(iso).toLocaleTimeString('en-US', {
    timeZone: 'America/New_York', hour12: false, hour: '2-digit', minute: '2-digit',
  });
};

const signClass = (value) => (Number(value) > 0 ? 'up' : Number(value) < 0 ? 'down' : 'flat');
const signed = (value) => (Number(value) > 0 ? '+' : '') + money(value);

/* ── session clock ───────────────────────────────────────────────── */

function tickClock() {
  // Exchange time, not the viewer's. A trader in London still thinks in market hours.
  const now = new Date();
  const time = now.toLocaleTimeString('en-US', {
    timeZone: 'America/New_York', hour12: false,
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  $('sessionClock').textContent = `${time} ET`;
}

/* ── feed status ─────────────────────────────────────────────────── */

async function refreshStatus() {
  try {
    const status = await getJson('/api/market/status');
    if (!status) return;

    $('feedText').textContent = status.summary;
    const dot = $('feedDot');
    dot.className = 'dot ' + (status.live ? 'live' : status.connected ? 'sim' : 'down');

    const banner = $('simBanner');
    banner.hidden = !status.showSimulatedWarning;
    if (status.showSimulatedWarning) {
      $('simBannerDetail').textContent =
        (status.detail ? status.detail + '. ' : '') +
        'These prices are generated locally and are not market data.';
    }
  } catch (e) {
    $('feedText').textContent = 'Status unavailable';
    $('feedDot').className = 'dot down';
  }
}

/* ── watchlist ───────────────────────────────────────────────────── */

function renderQuoteRow(quote) {
  // Every universe's quotes arrive on one WebSocket channel, so a push for an instrument this
  // account cannot trade must be dropped rather than appended to the grid.
  const current = state.universe || 'EQUITY';
  if (quote.universe && quote.universe !== current) return;

  const previous = state.quotes.get(quote.symbol);
  state.quotes.set(quote.symbol, quote);

  let row = document.querySelector(`tr[data-symbol="${quote.symbol}"]`);
  if (!row) {
    row = document.createElement('tr');
    row.dataset.symbol = quote.symbol;
    row.addEventListener('click', () => selectSymbol(quote.symbol, state.universe, quote.name));
    $('quoteBody').appendChild(row);
  }

  const badge = quote.live ? 'badge-live' : 'badge-sim';
  row.innerHTML = `
    <td class="sym">${quote.symbol}</td>
    <td class="num">${money(quote.last)}</td>
    <td class="num ${signClass(quote.change)}">${signed(quote.change)}</td>
    <td class="num ${signClass(quote.changePercent)}">${signed(quote.changePercent)}%</td>
    <td class="num">${money(quote.bid)}<span class="flat"> ×${compact(quote.bidSize)}</span></td>
    <td class="num">${money(quote.ask)}<span class="flat"> ×${compact(quote.askSize)}</span></td>
    <td class="num flat">${quote.spreadBps ? Number(quote.spreadBps).toFixed(1) + 'bp' : '—'}</td>
    <td class="num flat">${compact(quote.volume)}</td>
    <td class="src"><span class="badge ${badge}">${quote.dataSource}</span></td>`;

  if (state.selectedSymbol === quote.symbol) row.classList.add('is-selected');

  // Flash the row on a price change so movement is visible without staring at it.
  if (previous && Number(previous.last) !== Number(quote.last)) {
    const direction = Number(quote.last) > Number(previous.last) ? 'flash-up' : 'flash-down';
    row.classList.remove('flash-up', 'flash-down');
    void row.offsetWidth;            // restart the animation
    row.classList.add(direction);
  }
}

function sortQuoteRows() {
  const body = $('quoteBody');
  const rows = Array.from(body.querySelectorAll('tr[data-symbol]'));
  rows.sort((a, b) => a.dataset.symbol.localeCompare(b.dataset.symbol));
  rows.forEach((row) => body.appendChild(row));
}

async function loadQuotes() {
  const universe = state.universe || 'EQUITY';
  const requestId = ++state.quotesRequest;
  const quotes = await getJson(`/api/market/quotes?universe=${universe}`);
  if (requestId !== state.quotesRequest) return;

  // Futures have no market data on this entitlement. Say so rather than leaving a blank grid,
  // which reads as a broken feed.
  if (!quotes || !quotes.length) {
    const availability = await getJson(`/api/market/quotes/availability?universe=${universe}`);
    if (requestId !== state.quotesRequest) return;
    if (availability && !availability.quotable) {
      $('quoteBody').innerHTML =
        `<tr class="unquotable"><td colspan="9">${availability.reason}</td></tr>`;
      $('quoteCount').textContent = '';
      state.quotes.clear();
    }
    return;
  }

  // Rebuild rather than merge: switching universe replaces the instrument set entirely, and
  // merging would leave the previous account's symbols sitting in the grid.
  const known = new Set(quotes.map((q) => q.symbol));
  $('quoteBody').querySelectorAll('tr').forEach((row) => {
    if (!row.dataset.symbol || !known.has(row.dataset.symbol)) row.remove();
  });
  [...state.quotes.keys()].forEach((symbol) => {
    if (!known.has(symbol)) state.quotes.delete(symbol);
  });

  quotes.forEach(renderQuoteRow);
  sortQuoteRows();
  $('quoteCount').textContent = `${quotes.length} instruments`;

  // Auto-select only when nothing has been chosen yet. Previously this also re-selected whenever
  // the charted symbol was absent from the watchlist - which is exactly the case for anything
  // found through search, so a searched security was dropped back to the first watchlist row on
  // the next refresh a few seconds later. A deliberate choice outranks the default.
  // applyUniverse() clears the selection when the account changes, which is what lets the new
  // universe pick its own first row.
  if (!state.selectedSymbol && quotes.length) {
    selectSymbol(quotes[0].symbol, universe);
  }
}

/* ── live quote stream ───────────────────────────────────────────── */

function openQuoteStream() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/ws/quotes`);

  socket.addEventListener('message', (event) => {
    try {
      renderQuoteRow(JSON.parse(event.data));
    } catch (e) { /* a malformed frame must not kill the stream */ }
  });

  // Reconnect rather than silently going stale. A dead socket looks exactly like a quiet market.
  socket.addEventListener('close', () => setTimeout(openQuoteStream, 3000));
  socket.addEventListener('error', () => socket.close());
}

/* ── chart ───────────────────────────────────────────────────────── */

function selectSymbol(symbol, universe, name) {
  state.selectedSymbol = symbol;
  state.livePrice = null;
  state.liveSeries = null;
  state.chartUniverse = universe || state.universe || 'EQUITY';
  // Both tables can select: the watchlist grid and the tradable-instruments panel.
  document.querySelectorAll('tr[data-symbol]').forEach((row) =>
    row.classList.toggle('is-selected', row.dataset.symbol === symbol));
  document.querySelectorAll('tr[data-chart]').forEach((row) =>
    row.classList.toggle('is-selected', row.dataset.chart === symbol));

  const quote = state.quotes.get(symbol);
  $('chartSymbol').textContent = symbol;
  $('chartName').textContent = name || (quote ? quote.name : '');
  // A searched security has no row in the grid, so say where it came from rather than leaving
  // the chart looking like it lost its place in the watchlist.
  $('chartOffWatchlist').hidden = state.quotes.has(symbol);
  refreshChart();
}

/* ── chart modes ─────────────────────────────────────────────────── */

/*
 * Two views of one security, answering two different questions.
 *
 * Historical: over a chosen window, what has this done - the return, how violently it got there,
 * and how far underwater it went on the way. Live: what is it doing in this session, against the
 * open, today's range, and the volume-weighted average everyone else has been paying.
 *
 * They are kept as separate loads rather than one endpoint with a flag because their refresh
 * behaviour is nothing alike: a year of daily bars is fetched once and is then done, while the
 * session has to keep re-reading itself for as long as the trader is watching it.
 */
function setChartMode(mode) {
  state.chartMode = mode;
  document.querySelectorAll('.chart-mode-btn').forEach((button) =>
    button.classList.toggle('is-active', button.dataset.chartMode === mode));
  $('historicalMode').hidden = mode !== 'historical';
  $('liveMode').hidden = mode !== 'live';
  refreshChart();
}

function refreshChart() {
  // Only one mode polls, and only while it is the one on screen.
  stopLiveRefresh();
  if (state.chartMode === 'live') {
    loadIntraday();
    startLiveRefresh();
  } else {
    loadHistory();
  }
}

function startLiveRefresh() {
  // Fast enough to be worth calling live, slow enough not to hammer the vendor: the minute bars
  // themselves only change once a minute, but the quote on top of them moves continuously.
  state.liveTimer = setInterval(loadIntraday, 15000);
}

function stopLiveRefresh() {
  if (state.liveTimer) {
    clearInterval(state.liveTimer);
    state.liveTimer = null;
  }
}

function setChartRange(range) {
  state.chartRange = range;
  document.querySelectorAll('.range-btn').forEach((button) =>
    button.classList.toggle('is-active', button.dataset.range === range));
  if (state.chartMode === 'historical') loadHistory();
}

/* ── historical ──────────────────────────────────────────────────── */

async function loadHistory() {
  if (!state.selectedSymbol) return;
  const requestId = ++state.historyRequest;
  try {
    const history = await getJson(
      `/api/market/history/${encodeURIComponent(state.selectedSymbol)}` +
      `?policy=${state.policy}&range=${state.chartRange}&universe=${state.chartUniverse}`);
    if (requestId !== state.historyRequest) return;
    state.history = history;

    if (!history || !history.bars.length) {
      $('chartEmpty').hidden = false;
      $('chartEmpty').textContent = state.chartUniverse === 'FUTURES'
        ? 'Futures market data is a separate Webull entitlement, so this contract cannot be charted.'
        : `No ${state.chartRange} history available for ${state.selectedSymbol}.`;
      $('chartCanvas').hidden = true;
      ['chartReturn', 'chartVol', 'chartHigh', 'chartLow', 'chartDrawdown', 'chartExtremes',
        'chartAvgVol', 'chartBasis', 'chartWindow'].forEach((id) => { $(id).textContent = '—'; });
      $('chartNote').hidden = true;
      return;
    }

    $('chartEmpty').hidden = true;
    $('chartCanvas').hidden = false;

    const ret = history.windowReturnPercent;
    $('chartReturn').innerHTML = ret === null || ret === undefined
      ? '—' : `<span class="${signClass(ret)}">${signed(ret)}%</span>`;
    $('chartVol').textContent = history.annualisedVolatilityPercent
      ? Number(history.annualisedVolatilityPercent).toFixed(1) + '%' : '—';
    $('chartHigh').textContent = money(history.rangeHigh);
    $('chartLow').textContent = money(history.rangeLow);
    // Peak-to-trough on closes: the number that says what holding this actually felt like, which
    // a return alone never does.
    $('chartDrawdown').innerHTML = history.maxDrawdownPercent === null
      || history.maxDrawdownPercent === undefined
      ? '—' : `<span class="down">${money(history.maxDrawdownPercent)}%</span>`;
    $('chartExtremes').innerHTML = history.bestBarPercent === null
      || history.bestBarPercent === undefined
      ? '—'
      : `<span class="up">${signed(history.bestBarPercent)}%</span> / ` +
        `<span class="down">${signed(history.worstBarPercent)}%</span>`;
    $('chartAvgVol').textContent = compact(history.averageVolume);
    $('chartBasis').textContent = history.basisLabel;
    // The dates actually delivered, not the ones the button implies: 5Y stops at the vendor's
    // 1,200-session ceiling, and a recent listing has less history than any button promises.
    $('chartWindow').textContent = history.firstDate
      ? `${history.firstDate} → ${history.lastDate} (${history.bars.length} bars)` : '—';

    // Shown only when the delivered basis is not the one asked for — the UI must never label a
    // chart with a basis it does not actually have.
    const note = $('chartNote');
    note.hidden = history.basisSatisfied;
    if (!history.basisSatisfied) note.textContent = history.note;

    drawChart(history);
  } catch (e) {
    if (requestId !== state.historyRequest) return;
    $('chartEmpty').hidden = false;
    $('chartEmpty').textContent = 'Could not load history: ' + e.message;
    $('chartCanvas').hidden = true;
  }
}

/* ── live session ────────────────────────────────────────────────── */

async function loadIntraday() {
  if (!state.selectedSymbol || state.chartMode !== 'live') return;
  const requestId = ++state.intradayRequest;
  try {
    const session = await getJson(
      `/api/market/intraday/${encodeURIComponent(state.selectedSymbol)}` +
      `?universe=${state.chartUniverse}`);
    if (requestId !== state.intradayRequest || state.chartMode !== 'live') return;
    state.intraday = session;

    if (!session || !session.bars.length) {
      $('chartEmpty').hidden = false;
      $('chartEmpty').textContent = state.chartUniverse === 'FUTURES'
        ? 'Futures market data is a separate Webull entitlement, so this contract cannot be charted.'
        : `No intraday data available for ${state.selectedSymbol}.`;
      $('chartCanvas').hidden = true;
      clearLiveStats();
      return;
    }

    $('chartEmpty').hidden = true;
    $('chartCanvas').hidden = false;
    renderLiveStats(session);
    // A session that is not today's cannot change - an event contract that settled in July, or a
    // chart left open over a weekend. Polling it forever is pure noise against the vendor.
    if (session.note) stopLiveRefresh();
    state.liveSeries = {
      bars: session.bars,
      intraday: true,
      // Both are lines a day trader watches price against, so they belong on the chart rather
      // than only in the numbers above it. Including them in the scale keeps a gap visible.
      referenceLines: [
        { value: session.previousClose, style: 'faint', label: 'prev close' },
        { value: session.vwap, style: 'accent', label: 'VWAP' },
      ].filter((line) => line.value !== null && line.value !== undefined),
      baseline: session.previousClose ?? session.open,
    };
    drawChart(state.liveSeries);
  } catch (e) {
    if (requestId !== state.intradayRequest) return;
    $('chartEmpty').hidden = false;
    $('chartEmpty').textContent = 'Could not load the session: ' + e.message;
    $('chartCanvas').hidden = true;
  }
}

function clearLiveStats() {
  ['livePrice', 'liveOpen', 'liveHigh', 'liveLow', 'liveVwap', 'liveBidAsk', 'liveSpread',
    'liveVolume', 'liveVsOpen'].forEach((id) => { $(id).textContent = '—'; });
  $('liveChange').textContent = '';
  $('liveSession').textContent = '';
  $('liveUpdated').textContent = '';
  $('liveNote').hidden = true;
}

function renderLiveStats(session) {
  // The headline is the change against the previous close when there is one — that is the
  // number quoted everywhere else — and against the open otherwise.
  const usePrior = session.changeFromPreviousClose !== null
    && session.changeFromPreviousClose !== undefined;
  const change = usePrior ? session.changeFromPreviousClose : session.changeFromOpen;
  const percent = usePrior ? session.changeFromPreviousClosePercent : session.changeFromOpenPercent;

  const price = $('livePrice');
  price.textContent = money(session.last);
  // A one-shot tint on the direction of the move, so a glance catches it without reading digits.
  if (state.livePrice !== null && Number(session.last) !== state.livePrice) {
    price.classList.remove('tick-up', 'tick-down');
    void price.offsetWidth;   // restart the animation rather than let it be a no-op
    price.classList.add(Number(session.last) > state.livePrice ? 'tick-up' : 'tick-down');
  }
  state.livePrice = Number(session.last);

  $('liveChange').innerHTML =
    `<span class="${signClass(change)}">${signed(change)} (${signed(percent)}%)</span>` +
    (usePrior ? ' vs prev close' : ' vs open');
  $('liveSession').textContent =
    `${session.sessionDate} · ${session.session}${session.marketOpen ? '' : ' · closed'}`;
  $('liveUpdated').textContent = session.note
    ? 'session closed — not updating'
    : 'updated ' + new Date().toLocaleTimeString('en-US',
        { timeZone: 'America/New_York', hour12: false });

  $('liveOpen').textContent = money(session.open);
  $('liveHigh').textContent = money(session.high);
  $('liveLow').textContent = money(session.low);
  // Above or below VWAP is the read, not the number on its own.
  $('liveVwap').innerHTML = session.vwap === null || session.vwap === undefined
    ? '—'
    : `${money(session.vwap)} <span class="${session.aboveVwap ? 'up' : 'down'}">` +
      `${session.aboveVwap ? 'above' : 'below'}</span>`;

  const quote = session.quote;
  $('liveBidAsk').textContent = quote && quote.bid !== null && quote.bid !== undefined
    ? `${money(quote.bid)} / ${money(quote.ask)}` : '—';
  $('liveSpread').textContent = quote && quote.spreadBps !== null
    && quote.spreadBps !== undefined
    ? Number(quote.spreadBps).toFixed(1) + ' bp' : '—';
  $('liveVolume').textContent = compact(session.volume);
  $('liveVsOpen').innerHTML =
    `<span class="${signClass(session.changeFromOpen)}">${signed(session.changeFromOpen)} ` +
    `(${signed(session.changeFromOpenPercent)}%)</span>`;

  const position = Number(session.rangePosition);
  $('liveRangeLow').textContent = money(session.low);
  $('liveRangeHigh').textContent = money(session.high);
  $('liveRangeFill').style.left = position + '%';
  $('liveRangeMeter').title =
    `Last trade sits ${position.toFixed(0)}% of the way up today's range`;

  $('liveNote').hidden = !session.note;
  if (session.note) $('liveNote').textContent = session.note;
}

/* ── canvas ──────────────────────────────────────────────────────── */

/**
 * Draws a price series. Takes the shape both views share — bars, whether they are intraday, and
 * any reference lines — so the historical and live charts are one renderer rather than two that
 * drift apart.
 */
function drawChart(series) {
  const canvas = $('chartCanvas');
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = 260;

  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const bars = series.bars;
  const closes = bars.map((b) => Number(b.close));
  const references = (series.referenceLines || []).map((line) => Number(line.value));
  // Reference lines share the scale: a gap that puts the previous close outside the day's range
  // must be visible as a gap, not silently clipped to the edge of the plot.
  const min = Math.min(...closes, ...references);
  const max = Math.max(...closes, ...references);
  const range = max - min || 1;

  const padLeft = 54, padRight = 12, padTop = 14, padBottom = 24;
  const plotWidth = width - padLeft - padRight;
  const plotHeight = height - padTop - padBottom;

  const x = (i) => padLeft + (i / Math.max(1, bars.length - 1)) * plotWidth;
  const y = (v) => padTop + (1 - (v - min) / range) * plotHeight;

  const styles = getComputedStyle(document.documentElement);
  const gridColor = styles.getPropertyValue('--border-soft').trim();
  const textColor = styles.getPropertyValue('--text-faint').trim();
  const up = styles.getPropertyValue('--up').trim();
  const down = styles.getPropertyValue('--down').trim();
  // Against the baseline where one is given — intraday, green means up on the day, not up since
  // whichever minute the window happens to start at.
  const base = series.baseline !== null && series.baseline !== undefined
    ? Number(series.baseline) : closes[0];
  const lineColor = closes[closes.length - 1] >= base ? up : down;

  // Horizontal gridlines with price labels.
  ctx.font = '10px ui-monospace, Menlo, monospace';
  ctx.fillStyle = textColor;
  ctx.strokeStyle = gridColor;
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const value = min + (range * i) / 4;
    const py = Math.round(y(value)) + 0.5;
    ctx.beginPath();
    ctx.moveTo(padLeft, py);
    ctx.lineTo(width - padRight, py);
    ctx.stroke();
    ctx.textAlign = 'right';
    ctx.fillText(value.toFixed(2), padLeft - 8, py + 3);
  }

  // Axis labels at either end: clock times within a session, dates across sessions.
  const label = (bar) => (series.intraday ? clockTime(bar.time) : bar.date);
  ctx.fillStyle = textColor;
  ctx.textAlign = 'left';
  ctx.fillText(label(bars[0]), padLeft, height - 8);
  ctx.textAlign = 'right';
  ctx.fillText(label(bars[bars.length - 1]), width - padRight, height - 8);

  // Ex-date markers, so a discontinuity in the raw series has a visible cause.
  (series.actionsInWindow || []).forEach((action) => {
    const index = bars.findIndex((b) => b.date >= action.exDate);
    if (index < 0) return;
    const px = Math.round(x(index)) + 0.5;
    ctx.save();
    ctx.strokeStyle = styles.getPropertyValue('--warn').trim();
    ctx.globalAlpha = 0.55;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(px, padTop);
    ctx.lineTo(px, padTop + plotHeight);
    ctx.stroke();
    ctx.restore();
  });

  // Reference lines (previous close, VWAP), labelled in place so they need no legend.
  (series.referenceLines || []).forEach((line) => {
    const py = Math.round(y(Number(line.value))) + 0.5;
    ctx.save();
    ctx.strokeStyle = line.style === 'accent'
      ? styles.getPropertyValue('--accent').trim() : textColor;
    ctx.globalAlpha = line.style === 'accent' ? 0.7 : 0.45;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(padLeft, py);
    ctx.lineTo(width - padRight, py);
    ctx.stroke();
    // The line stays subtle, but the label has to be readable — it is the only thing that says
    // which of the two references this is.
    ctx.setLineDash([]);
    ctx.globalAlpha = 1;
    ctx.fillStyle = line.style === 'accent'
      ? styles.getPropertyValue('--accent').trim() : styles.getPropertyValue('--text-dim').trim();
    ctx.textAlign = 'left';
    ctx.fillText(line.label, padLeft + 4, py - 3);
    ctx.restore();
  });

  // Filled area under the line.
  ctx.beginPath();
  bars.forEach((bar, i) => {
    const px = x(i), py = y(Number(bar.close));
    i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
  });
  ctx.lineTo(x(bars.length - 1), padTop + plotHeight);
  ctx.lineTo(x(0), padTop + plotHeight);
  ctx.closePath();
  const gradient = ctx.createLinearGradient(0, padTop, 0, padTop + plotHeight);
  gradient.addColorStop(0, lineColor + '33');
  gradient.addColorStop(1, lineColor + '00');
  ctx.fillStyle = gradient;
  ctx.fill();

  // The price line itself.
  ctx.beginPath();
  bars.forEach((bar, i) => {
    const px = x(i), py = y(Number(bar.close));
    i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
  });
  ctx.strokeStyle = lineColor;
  ctx.lineWidth = 1.5;
  ctx.stroke();
}

/* ── symbol search ───────────────────────────────────────────────── */

/*
 * Searches the selected account's own tradable universe, so the results are always things this
 * account could actually buy. Charting is not restricted to the watchlist: a security you can
 * trade is one you should be able to look at.
 */
async function searchSymbols(query) {
  const results = $('searchResults');
  if (!query || query.trim().length < 1) {
    results.hidden = true;
    return;
  }
  const requestId = ++state.searchRequest;
  const params = new URLSearchParams({
    environment: state.environment,
    q: query.trim(),
    limit: '10',
    tradableOnly: 'true',
  });
  if (state.accountId) params.set('accountId', state.accountId);

  try {
    const data = await getJson('/api/instruments/tradable?' + params.toString());
    if (requestId !== state.searchRequest) return;
    if (!data || !data.instruments.length) {
      // "Still loading" and "nothing matches" look identical to a user unless you say which.
      results.innerHTML = data && data.loading
        ? '<div class="search-empty">Loading this account\'s instruments…</div>'
        : `<div class="search-empty">No ${
            (data && data.label ? data.label : 'instruments').toLowerCase()} match “${query}”.</div>`;
      results.hidden = false;
      return;
    }
    results.innerHTML = data.instruments.map((i) => `
      <button type="button" class="search-result" data-symbol="${escapeAttr(i.symbol)}"
              data-name="${escapeAttr(i.name)}" data-universe="${data.universe}">
        <span class="sym">${i.symbol}</span>
        <span class="nm">${i.name}</span>
      </button>`).join('');
    results.querySelectorAll('.search-result').forEach((button) =>
      button.addEventListener('click', () => {
        selectSymbol(button.dataset.symbol, button.dataset.universe, button.dataset.name);
        results.hidden = true;
        $('symbolSearch').value = '';
      }));
    results.hidden = false;
  } catch (e) {
    results.innerHTML = '<div class="search-empty">Search failed.</div>';
    results.hidden = false;
  }
}

/* ── accounts ────────────────────────────────────────────────────── */

async function loadEnvironments() {
  const environments = await getJson('/api/accounts/environments');
  state.environments = environments || [];

  document.querySelectorAll('.env-btn').forEach((button) => {
    const env = state.environments.find((e) => e.environment === button.dataset.env);
    const available = env && env.available;
    button.disabled = !available;
    button.title = available ? '' : (env && env.unavailableReason) || 'Unavailable';
  });

  // Prefer an environment that actually has accounts, rather than showing an empty picker.
  const current = state.environments.find((e) => e.environment === state.environment);
  if (!current || !current.available) {
    const usable = state.environments.find((e) => e.available);
    if (usable) state.environment = usable.environment;
  }
  applyEnvironment();
}

function applyEnvironment() {
  document.querySelectorAll('.env-btn').forEach((button) =>
    button.setAttribute('aria-pressed', String(button.dataset.env === state.environment)));

  $('prodBanner').hidden = state.environment !== 'PRODUCTION';

  const env = state.environments.find((e) => e.environment === state.environment);
  const select = $('accountSelect');
  select.innerHTML = '';

  if (!env || !env.accounts.length) {
    const option = document.createElement('option');
    option.textContent = env && env.unavailableReason ? 'Unavailable' : 'No accounts';
    select.appendChild(option);
    select.disabled = true;
    state.accountId = null;
    $('accountHint').textContent =
      (env && env.unavailableReason) ? env.unavailableReason : 'No accounts in this environment';
    $('balances').innerHTML = '';
    $('positionBody').innerHTML = '<tr class="empty"><td colspan="6">No account selected.</td></tr>';
    loadOrders();
    loadUniverse();
    return;
  }

  select.disabled = false;
  env.accounts.forEach((account) => {
    const option = document.createElement('option');
    option.value = account.accountId;
    option.textContent = `${account.displayName} · ${account.maskedNumber}`;
    select.appendChild(option);
  });
  state.accountId = env.accounts[0].accountId;
  select.value = state.accountId;
  loadAccountDetail();
  loadOrders();
  // The account class decides the universe, so a change of account changes the instrument set.
  state.universeGroup = '';
  applyUniverse(env.accounts[0]);
  loadUniverse();
}

/*
 * Switches the grid, chart and prompts to an account's universe straight away.
 *
 * <p>Driven by the account rather than by the instrument catalog: the catalog has to enumerate
 * thousands of contracts and takes seconds, and making the UI wait on it means selecting an
 * events account shows equities for as long as that takes.
 */
function applyUniverse(account) {
  if (!account || !account.universe || account.universe === state.universe) return;
  state.universe = account.universe;
  renderAskSuggestions(account.universe);
  applyCorporateActionsVisibility();
  $('watchlistUniverse').textContent = account.universeLabel || '';
  $('quoteBody').innerHTML = '<tr class="empty"><td colspan="9">Loading…</td></tr>';
  state.quotes.clear();
  state.selectedSymbol = null;
  loadQuotes();
}

async function loadAccountDetail() {
  if (!state.accountId) return;
  try {
    const detail = await getJson(`/api/accounts/${state.environment}/${state.accountId}`);
    if (!detail) return;

    const labels = {
      netLiquidationValue: 'Net liq',
      cash: 'Cash',
      buyingPower: 'Buying power',
      marketValue: 'Market value',
      unrealizedPnl: 'Unrealised',
      dayPnl: 'Day P&L',
    };
    const balances = $('balances');
    balances.innerHTML = '';
    Object.entries(labels).forEach(([key, label]) => {
      if (detail.balances[key] === undefined) return;
      const value = detail.balances[key];
      const cell = document.createElement('div');
      cell.className = 'balance';
      const cls = key.includes('Pnl') ? signClass(value) : '';
      cell.innerHTML = `<span class="balance-label">${label}</span>
        <span class="balance-value ${cls}">${key.includes('Pnl') ? signed(value) : money(value)}</span>`;
      balances.appendChild(cell);
    });

    $('accountHint').textContent = detail.balancesAvailable
      ? `${detail.positions.length} position${detail.positions.length === 1 ? '' : 's'}`
      : 'Balances unavailable';

    const body = $('positionBody');
    if (!detail.positions.length) {
      body.innerHTML = '<tr class="empty"><td colspan="6">No open positions.</td></tr>';
      return;
    }
    body.innerHTML = detail.positions.map((p) => `
      <tr>
        <td class="sym">${p.symbol}</td>
        <td class="num">${p.quantity}</td>
        <td class="num">${money(p.costPrice)}</td>
        <td class="num">${money(p.lastPrice)}</td>
        <td class="num">${money(p.marketValue)}</td>
        <td class="num ${signClass(p.unrealizedPnl)}">${signed(p.unrealizedPnl)}</td>
      </tr>`).join('');
  } catch (e) {
    $('accountHint').textContent = 'Could not load account';
  }
}

/* ── order history ───────────────────────────────────────────────── */

const ORDER_STATUS_CLASS = {
  FILLED: 'status-filled',
  PARTIALLY_FILLED: 'status-partial',
  WORKING: 'status-working',
  CANCELLED: 'status-cancelled',
  REJECTED: 'status-rejected',
  UNKNOWN: 'status-unknown',
};

/**
 * A fill duration a human can read, across the five orders of magnitude these actually span.
 *
 * <p>A market order fills in tens of milliseconds and a resting GTC order can fill days later, so
 * neither seconds nor minutes works alone: seconds renders a 39ms fill as "0s", and minutes
 * renders a two-day fill as "2485m 58s".
 */
function fillDuration(millis) {
  if (millis === null || millis === undefined) return '—';
  if (millis < 1000) return millis + 'ms';
  if (millis < 60000) return (millis / 1000).toFixed(1) + 's';
  if (millis < 3600000) {
    const minutes = Math.floor(millis / 60000);
    const seconds = Math.round((millis % 60000) / 1000);
    return minutes + 'm' + (seconds ? ' ' + seconds + 's' : '');
  }
  if (millis < 86400000) {
    const hours = Math.floor(millis / 3600000);
    const minutes = Math.round((millis % 3600000) / 60000);
    return hours + 'h' + (minutes ? ' ' + minutes + 'm' : '');
  }
  const days = Math.floor(millis / 86400000);
  const hours = Math.round((millis % 86400000) / 3600000);
  return days + 'd' + (hours ? ' ' + hours + 'h' : '');
}

/** Date and time in exchange time, which is the only clock an order log should be read in. */
function orderTime(iso) {
  if (!iso) return '—';
  // Numeric and compact: this is the last of thirteen columns, and "Sep 11, 18:33" is the one
  // that pushes the row past the panel.
  const date = new Date(iso);
  return date.toLocaleString('en-US', {
    timeZone: 'America/New_York', hour12: false,
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).replace(',', '');
}

/** Just the date, with the year: history spans years, so "since 09/18" is ambiguous. */
function orderDate(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString('en-US', {
    timeZone: 'America/New_York', month: '2-digit', day: '2-digit', year: 'numeric',
  });
}

async function loadOrders() {
  if (!state.accountId) {
    $('orderBody').innerHTML =
      '<tr class="empty"><td colspan="10">No account selected.</td></tr>';
    clearOrderMetrics();
    return;
  }
  const requestId = ++state.ordersRequest;
  try {
    const history = await getJson(
      `/api/accounts/${state.environment}/${encodeURIComponent(state.accountId)}/orders`);
    // Accounts differ wildly in how many orders they have, and switching between them fires
    // faster than the vendor answers.
    if (requestId !== state.ordersRequest) return;
    if (!history) {
      clearOrderMetrics();
      $('orderBody').innerHTML =
        '<tr class="empty"><td colspan="10">No orders for this account.</td></tr>';
      return;
    }

    renderOrderMetrics(history.metrics);
    $('ordersNote').hidden = !history.note;
    if (history.note) $('ordersNote').textContent = history.note;
    // The span actually delivered, not a claim of "all time": an account that opened last month
    // has a month of history however wide the window asked for was.
    const oldest = history.orders.length
      ? history.orders[history.orders.length - 1].placedAt : null;
    $('ordersHint').textContent = history.metrics.totalOrders
      ? `${history.metrics.totalOrders} order${history.metrics.totalOrders === 1 ? '' : 's'}` +
        (oldest ? ` since ${orderDate(oldest)}` : '') + ` · ${history.environment}`
      : history.environment;

    const body = $('orderBody');
    if (!history.orders.length) {
      body.innerHTML = history.connected
        ? '<tr class="empty"><td colspan="10">No orders for this account.</td></tr>'
        : '<tr class="empty"><td colspan="10">Not connected to this environment.</td></tr>';
      return;
    }
    body.innerHTML = history.orders.map(renderOrderRow).join('');
  } catch (e) {
    if (requestId !== state.ordersRequest) return;
    $('ordersHint').textContent = 'Could not load orders';
  }
}

function renderOrderRow(order) {
  const statusClass = ORDER_STATUS_CLASS[order.status] || 'status-unknown';
  const sideClass = order.side === 'BUY' ? 'side-buy' : 'side-sell';
  // Event contracts trade as two instruments; without the outcome the row is ambiguous.
  const outcome = order.eventOutcome
    ? `<span class="outcome-tag">${order.eventOutcome}</span>` : '';

  const improvement = order.priceImprovement === null || order.priceImprovement === undefined
    ? '—'
    : `<span class="${signClass(order.priceImprovement)}">${signed(order.priceImprovement)}` +
      (order.priceImprovementPercent !== null && order.priceImprovementPercent !== undefined
        ? ` (${signed(order.priceImprovementPercent)}%)` : '') + '</span>';

  // What was asked for and what was got, read together. A market order has no limit, so the
  // arrow would point from nothing.
  const limit = order.limitPrice === undefined ? null : money(order.limitPrice);
  const fill = order.filledPrice === undefined ? null : money(order.filledPrice);
  const prices = limit && fill ? `${limit}<span class="fill-arrow">→</span>${fill}`
    : (fill || limit || '—');

  return `
    <tr class="${order.restingExit ? 'is-resting' : ''}"
        title="${order.restingExit ? 'Resting GTC exit — this position has a live exit order' : ''}">
      <td class="sym">${order.symbol}${outcome}</td>
      <td class="${sideClass}">${order.side}</td>
      <td>${order.orderType}<span class="pair"> · ${order.timeInForce}</span></td>
      <td><span class="order-status ${statusClass}">${order.statusLabel}</span></td>
      <td class="num">${order.filledQuantity}<span class="pair">/${order.totalQuantity}</span></td>
      <td class="num">${prices}</td>
      <td class="num">${improvement}</td>
      <td class="num">${order.filledNotional === undefined ? '—' : money(order.filledNotional)}</td>
      <td class="num">${fillDuration(order.timeToFillMillis)}</td>
      <td class="num">${orderTime(order.placedAt)}</td>
    </tr>`;
}

function renderOrderMetrics(metrics) {
  $('omTotal').textContent = metrics.totalOrders;
  $('omFilled').innerHTML =
    `${metrics.filled}<span class="stat-label"> · ${metrics.filledOrderPercent}%</span>` +
    (metrics.partiallyFilled ? `<span class="stat-label"> +${metrics.partiallyFilled} partial</span>` : '');
  $('omWorking').textContent = metrics.working;
  $('omCancelled').textContent = metrics.cancelled;
  $('omRejected').innerHTML = metrics.rejected
    ? `<span class="down">${metrics.rejected}</span>` : '0';
  $('omNotional').textContent = money(metrics.filledNotional);
  $('omFillTime').textContent = fillDuration(metrics.averageTimeToFillMillis);
  // Positive is money saved against the limits that were set — the figure that says whether the
  // prices being asked for were realistic.
  $('omImprovement').innerHTML = metrics.ordersWithFills
    ? `<span class="${signClass(metrics.totalPriceImprovement)}">${signed(metrics.totalPriceImprovement)}</span>` +
      (metrics.averagePriceImprovementPercent !== null
        && metrics.averagePriceImprovementPercent !== undefined
        ? `<span class="stat-label"> · ${signed(metrics.averagePriceImprovementPercent)}% avg</span>` : '')
    : '—';
  $('omCost').textContent = money(metrics.totalCost);
  $('omResting').innerHTML = metrics.restingExits
    ? `<span class="up">${metrics.restingExits}</span>` : '0';
}

function clearOrderMetrics() {
  ['omTotal', 'omFilled', 'omWorking', 'omCancelled', 'omRejected', 'omNotional',
    'omFillTime', 'omImprovement', 'omCost', 'omResting'].forEach((id) => {
      $(id).textContent = '—';
    });
  $('ordersHint').textContent = '';
  $('ordersNote').hidden = true;
}

/* ── corporate actions ───────────────────────────────────────────── */

/*
 * Corporate actions apply to equities and nothing else: a crypto pair does not split, an event
 * contract settles rather than paying a dividend, and a futures contract rolls. Showing an
 * equities dividend calendar to a crypto account is not just noise, it implies the account holds
 * something it cannot.
 */
function showsCorporateActions() {
  return (state.universe || 'EQUITY') === 'EQUITY';
}

async function loadActions() {
  if (!showsCorporateActions()) {
    return;
  }
  const actions = await getJson('/api/market/actions');
  const body = $('actionBody');
  if (!actions || !actions.length) {
    body.innerHTML = '<tr class="empty"><td colspan="4">None recorded.</td></tr>';
    return;
  }
  const badgeFor = (source) =>
    source === 'Vendor' ? 'badge-vendor' : source === 'Declared' ? 'badge-sim' : 'badge-ref';

  body.innerHTML = actions.slice(0, 40).map((a) => `
    <tr data-symbol-action="${a.symbol}">
      <td class="sym">${a.symbol}</td>
      <td class="flat">${a.exDate}</td>
      <td>${a.description}</td>
      <td class="src"><span class="badge ${badgeFor(a.source)}" title="${a.sourceDescription}">${a.source}</span></td>
    </tr>`).join('');

  body.querySelectorAll('tr[data-symbol-action]').forEach((row) =>
    row.addEventListener('click', () => selectSymbol(row.dataset.symbolAction, 'EQUITY')));

  $('actionHint').textContent = `${actions.length} recorded`;
}

/* ── ask suggestions ─────────────────────────────────────────────── */

/*
 * The prompts follow the account's universe. "Compare NVDA unadjusted vs total return" is a
 * useless suggestion to an events account that cannot buy a share, and it teaches the wrong thing
 * about what this account does.
 */
const ASK_SUGGESTIONS = {
  EQUITY: [
    'Which candidate has the best reward-to-risk for a 3% target this month?',
    "Compare NVDA's 12-month return unadjusted vs total return.",
    'Which watchlist name has the widest spread right now?',
  ],
  CRYPTO: [
    'How volatile is BTCUSD over the last 250 sessions?',
    'How often has ETHUSD gained 5% within a month historically?',
    'Which crypto pairs are open for trading right now?',
  ],
  EVENT: [
    'What event contracts can this account trade right now?',
    'What are the YES and NO prices on the Fed decision markets?',
    'Which event series have the most markets listed?',
  ],
  FUTURES: [
    'Which futures product classes can this account trade?',
    'Why can you not analyse futures contracts on this account?',
    'Summarise this account: balances, buying power and open positions.',
  ],
};

function renderAskSuggestions(universe) {
  const prompts = ASK_SUGGESTIONS[universe] || ASK_SUGGESTIONS.EQUITY;
  const container = $('suggestions');
  container.innerHTML = prompts
    .map((prompt) => `<button type="button" class="chip">${prompt}</button>`).join('');
  container.querySelectorAll('.chip').forEach((chip) =>
    chip.addEventListener('click', () => {
      $('askInput').value = chip.textContent;
      ask(chip.textContent);
    }));
}

/* ── tradable universe ───────────────────────────────────────────── */

/*
 * Which securities the SELECTED account can trade. The universe follows the account's class
 * (events / futures / crypto / equities), so switching accounts switches the instrument set -
 * showing a stock list to a futures account would be listing things it cannot buy.
 */
async function loadUniverse() {
  const requestId = ++state.universeRequest;
  const params = new URLSearchParams({
    environment: state.environment,
    tradableOnly: String(state.universeTradableOnly),
    limit: '250',
  });
  if (state.accountId) params.set('accountId', state.accountId);
  if (state.universeQuery) params.set('q', state.universeQuery);
  if (state.universeGroup) params.set('group', state.universeGroup);

  try {
    const data = await getJson('/api/instruments/tradable?' + params.toString());
    // A newer request has been issued since this one left; its answer is the current truth.
    if (requestId !== state.universeRequest) return;
    if (data) renderUniverse(data);
    // A cold universe is fetched in the background; check back rather than leaving "Loading…".
    if (data && data.loading) {
      setTimeout(() => { if (requestId === state.universeRequest) loadUniverse(); }, 4000);
    }
  } catch (e) {
    if (requestId === state.universeRequest) {
      $('universeHint').textContent = 'Could not load instruments';
    }
  }
}

function renderUniverse(data) {
  // The universe itself comes from the account (see applyUniverse); this panel only renders the
  // instrument listing, which is far slower to fetch.
  state.universe = data.universe;

  $('universeLabel').textContent = data.label.toLowerCase();
  const badge = $('universeBadge');
  badge.textContent = data.label;
  badge.dataset.universe = data.universe;
  badge.title = data.description;

  $('universeFor').textContent = data.accountLabel
    ? `for ${data.accountLabel}${data.accountClass ? ' · ' + data.accountClass : ''}`
    : '';

  const note = $('universeNote');
  note.hidden = data.available || data.loading;
  if (!data.available && !data.loading) note.textContent = data.unavailableReason;

  $('universeHint').textContent = data.available
    ? `${data.matching.toLocaleString()} of ${data.total.toLocaleString()}` +
      (data.truncated ? ` · showing first ${data.instruments.length}` : '')
    : '';

  renderGroupChips(data.groups);

  // Columns are whatever the data carries: a stock has Marginable/Shortable, a futures contract
  // has a product class, an event contract has a settlement date.
  $('universeHead').innerHTML = `
    <tr>
      <th class="sym">Symbol</th>
      <th>Name</th>
      <th>Group</th>
      ${data.columns.map((c) => `<th class="num">${c}</th>`).join('')}
    </tr>`;

  const body = $('universeBody');
  if (!data.instruments.length) {
    const reason = data.loading ? 'Loading…'
      : data.available ? 'No instruments match.' : 'Unavailable.';
    body.innerHTML = `<tr class="empty"><td colspan="${3 + data.columns.length}">${reason}</td></tr>`;
    return;
  }

  body.innerHTML = data.instruments.map((i) => `
    <tr class="${i.tradable ? '' : 'not-tradable'}${
        i.symbol === state.selectedSymbol ? ' is-selected' : ''}"
        data-chart="${escapeAttr(i.symbol)}" data-chart-name="${escapeAttr(i.name)}">
      <td class="sym">${i.symbol}${i.tradable ? '' : ' <span class="badge badge-halted">halted</span>'}</td>
      <td class="name" title="${escapeAttr(i.name)}">${i.name}</td>
      <td class="flat">${i.group}</td>
      ${data.columns.map((c) => `<td class="attr">${i.attributes[c] || '—'}</td>`).join('')}
    </tr>`).join('');

  // These rows are the other way into the chart - this panel has its own search, and a row you
  // have just filtered down to is exactly the thing you want to look at.
  body.querySelectorAll('tr[data-chart]').forEach((row) =>
    row.addEventListener('click', () =>
      selectSymbol(row.dataset.chart, data.universe, row.dataset.chartName)));
}

function renderGroupChips(groups) {
  const container = $('universeGroups');
  if (!groups || groups.length <= 1) {
    container.innerHTML = '';
    return;
  }
  // Biggest groups first, capped: the event universe has hundreds of series.
  const top = [...groups].sort((a, b) => b.count - a.count).slice(0, 14);
  const all = `<button type="button" class="group-chip ${state.universeGroup ? '' : 'is-active'}"
      data-group="">All</button>`;
  container.innerHTML = all + top.map((g) => `
    <button type="button" class="group-chip ${state.universeGroup === g.group ? 'is-active' : ''}"
      data-group="${escapeAttr(g.group)}">${g.group}<span class="count">${g.count}</span></button>`).join('');

  container.querySelectorAll('.group-chip').forEach((chip) =>
    chip.addEventListener('click', () => {
      state.universeGroup = chip.dataset.group;
      loadUniverse();
    }));
}

const escapeAttr = (value) => String(value).replace(/"/g, '&quot;');

/**
 * Shows the corporate-actions panel only for accounts that trade equities.
 *
 * <p>The body class reflows the grid so the analyst spans the full width rather than sitting
 * beside an empty column where the panel used to be.
 */
function applyCorporateActionsVisibility() {
  const show = showsCorporateActions();
  document.querySelector('.actions-panel').hidden = !show;
  document.body.classList.toggle('no-corporate-actions', !show);
  if (show) {
    loadActions();
  }
}

/* ── analyst ─────────────────────────────────────────────────────── */

async function loadOrderCapability() {
  try {
    state.orderCapability = await getJson('/api/orders/capability');
  } catch (e) { /* submission simply stays disabled if this cannot be read */ }
}

async function loadAnalystStatus() {
  try {
    const status = await getJson('/api/analyst/status');
    if (!status) return;
    $('analystHint').textContent = status.available ? status.model : status.reason;
    $('askInput').disabled = !status.available;
    $('askButton').disabled = !status.available;
    $('recommendButton').disabled = !status.available;
    $('capitalInput').disabled = !status.available;
  } catch (e) { /* the analyst is optional; its absence must not break the page */ }
}

async function ask(question) {
  if (!question.trim() || state.asking) return;
  state.asking = true;
  $('askButton').disabled = true;
  $('askButton').textContent = 'Thinking…';
  $('answer').hidden = false;
  $('answerMeta').textContent = '';
  $('answerBody').className = 'answer-body';
  $('answerBody').textContent = 'Consulting the live book…';

  try {
    const result = await postJson('/api/analyst/ask', { question });
    if (result.succeeded) {
      $('answerBody').textContent = result.answer;
      const tools = result.toolCalls.length ? ` · ${result.toolCalls.join(', ')}` : '';
      $('answerMeta').textContent =
        `${result.model} · ${result.turns} turn${result.turns === 1 ? '' : 's'} · ` +
        `${(result.elapsedMillis / 1000).toFixed(1)}s${tools}`;
    } else {
      $('answerBody').className = 'answer-body is-error';
      $('answerBody').textContent = result.error || 'The analyst could not answer.';
    }
  } catch (e) {
    $('answerBody').className = 'answer-body is-error';
    $('answerBody').textContent = 'Request failed: ' + e.message;
  } finally {
    state.asking = false;
    $('askButton').disabled = false;
    $('askButton').textContent = 'Ask';
  }
}

/* ── recommendations ─────────────────────────────────────────────── */

/*
 * A recommendation is a two-leg plan: buy at the market now, rest a GTC sell limit at a target
 * that history says is reachable inside the horizon. Everything numeric here was computed
 * server-side from live quotes and the instrument's own bars - the model supplied only the
 * symbol, the size and the target.
 */
async function requestRecommendations() {
  if (state.recommending) return;
  state.recommending = true;
  $('recommendButton').disabled = true;
  $('recommendButton').textContent = 'Working…';
  $('recList').innerHTML = '';
  $('recommendStatus').hidden = false;
  $('recommendMeta').textContent = '';
  $('recommendBody').className = 'answer-body';
  $('recommendBody').textContent =
    'Scanning candidates, checking what this account can trade, and testing targets against history…';

  const capital = Number($('capitalInput').value) || 0;
  try {
    const result = await postJson('/api/analyst/recommend', {
      environment: state.environment,
      accountId: state.accountId,
      capital,
    });

    if (!result.succeeded) {
      $('recommendBody').className = 'answer-body is-error';
      $('recommendBody').textContent = result.error || 'The recommender could not complete.';
      return;
    }

    const tools = result.toolCalls.length ? ` · ${result.toolCalls.length} tool calls` : '';
    $('recommendMeta').textContent =
      `${result.model} · ${result.turns} turns · ${(result.elapsedMillis / 1000).toFixed(0)}s` +
      `${tools} · ${result.account || 'no account'} (${result.environment})` +
      `${result.marketDataLive ? '' : ' · SIMULATED DATA'}`;
    $('recommendBody').textContent = result.commentary || '';

    if (!result.recommendations.length) {
      $('recList').innerHTML =
        '<div class="rec"><div class="rec-rationale">No trades recommended.</div></div>';
      return;
    }
    state.lastRecommendations = result.recommendations;
    $('recList').innerHTML = result.recommendations.map(renderRecommendation).join('');
    $('recList').querySelectorAll('.submit-btn').forEach((button) =>
      button.addEventListener('click', () => submitRecommendation(button.dataset.symbol, button)));
  } catch (e) {
    $('recommendBody').className = 'answer-body is-error';
    $('recommendBody').textContent = 'Request failed: ' + e.message;
  } finally {
    state.recommending = false;
    $('recommendButton').disabled = false;
    $('recommendButton').textContent = 'Recommend trades';
  }
}

function renderRecommendation(rec) {
  const e = rec.economics;

  const econ = [
    ['Entry', money(e.entryPrice)],
    ['Target', money(e.targetPrice)],
    ['Cost', money(e.notional)],
    ['Profit', `<span class="${signClass(e.grossProfit)}">${signed(e.grossProfit)}</span>`],
    ['Return', `<span class="${signClass(e.returnPercent)}">${signed(e.returnPercent)}%</span>`],
    // The evidence for the target being reachable at all.
    ['Hit rate', e.historicalHitRatePercent === null || e.historicalHitRatePercent === undefined
      ? '—'
      : `${Number(e.historicalHitRatePercent).toFixed(0)}%` +
        (e.historicalWindows ? `<span class="flat"> /${e.historicalWindows}</span>` : '')],
    ['Time to hit', e.medianSessionsToHit ? `${e.medianSessionsToHit}d` : '—'],
    // The part that is easy to leave out of a pitch.
    ['Drawdown', `<span class="down">${money(e.medianDrawdownPercent)}%</span>`],
    ['Reward:risk', e.rewardToRisk === null || e.rewardToRisk === undefined
      ? '—' : Number(e.rewardToRisk).toFixed(2)],
  ];

  const legs = rec.legs.map((l) => `
    <div class="rec-leg ${l.side.toLowerCase()}">
      <span>${l.description}</span>
      <span class="leg-purpose">${l.purpose}</span>
    </div>`).join('');

  const warnings = e.warnings && e.warnings.length ? `
    <div class="rec-warnings">
      <ul>${e.warnings.map((w) => `<li>${w}</li>`).join('')}</ul>
    </div>` : '';

  return `
    <div class="rec ${e.viable ? '' : 'not-viable'}">
      <div class="rec-head">
        <span class="rec-symbol">${rec.symbol}</span>
        ${rec.eventOutcome ? `<span class="outcome outcome-${rec.eventOutcome}">${rec.eventOutcome}</span>` : ''}
        ${rec.conviction ? `<span class="conviction conviction-${rec.conviction}">${rec.conviction}</span>` : ''}
        <span class="flat">${rec.universe}</span>
        <span class="rec-horizon">${rec.horizon}</span>
      </div>
      <div class="rec-legs">${legs}</div>
      <div class="rec-econ">
        ${econ.map(([label, value]) => `
          <div class="econ"><span class="econ-label">${label}</span>
            <span class="econ-value">${value}</span></div>`).join('')}
      </div>
      <div class="rec-rationale">${rec.rationale}</div>
      ${warnings}
      ${renderSubmitRow(rec)}
      <div class="submit-result" id="submit-result-${rec.symbol}" hidden></div>
    </div>`;
}

/*
 * Submitting is a two-click action: the first arms, the second sends. Orders are outward-facing
 * and a market entry is not undoable, so a single stray click should not reach the venue.
 */
function renderSubmitRow(rec) {
  if (!rec.economics.viable) {
    return '<div class="rec-actions"><span class="submit-note">' +
      'Marked unviable — submission is refused for this plan.</span></div>';
  }
  const production = state.environment === 'PRODUCTION';
  const blocked = production && !(state.orderCapability || {}).productionSubmission;
  const reason = blocked
    ? `Production submission is disabled: ${(state.orderCapability || {}).productionBlockReason || ''}`
    : `Sends both legs to ${production ? 'PRODUCTION' : 'Sandbox'}.`;

  return `
    <div class="rec-actions">
      <button type="button" class="submit-btn" data-symbol="${rec.symbol}"
              ${blocked ? 'disabled' : ''}>Submit orders</button>
      <label class="ext-toggle" title="Outside 09:30-16:00 ET a market order is not accepted; the entry is sent as a marketable limit instead.">
        <input type="checkbox" class="ext-hours" data-symbol="${rec.symbol}">
        <span>Allow extended hours</span>
      </label>
      <span class="submit-note">${reason}</span>
    </div>`;
}

async function submitRecommendation(symbol, button) {
  const rec = (state.lastRecommendations || []).find((r) => r.symbol === symbol);
  if (!rec) return;

  // First click arms; second click within the window actually sends.
  if (state.armedSubmit !== symbol) {
    state.armedSubmit = symbol;
    document.querySelectorAll('.submit-btn').forEach((b) => {
      b.classList.remove('confirming');
      if (b.dataset.symbol !== symbol) b.textContent = 'Submit orders';
    });
    button.classList.add('confirming');
    const side = rec.eventOutcome ? ` ${rec.eventOutcome}` : '';
    const entry = rec.legs[0].description;
    const exit = rec.legs[1].description;
    button.textContent = `Confirm${side}: ${entry} then ${exit}`;
    setTimeout(() => {
      if (state.armedSubmit === symbol) {
        state.armedSubmit = null;
        button.classList.remove('confirming');
        button.textContent = 'Submit orders';
      }
    }, 8000);
    return;
  }

  state.armedSubmit = null;
  button.classList.remove('confirming');
  button.disabled = true;
  button.textContent = 'Submitting…';

  const extended = document.querySelector(`.ext-hours[data-symbol="${symbol}"]`);
  const box = $(`submit-result-${symbol}`);
  box.hidden = false;
  box.className = 'submit-result warn';
  box.textContent = 'Sending…';

  try {
    const result = await postJson('/api/orders/submit', {
      environment: state.environment,
      accountId: state.accountId,
      symbol: rec.symbol,
      universe: rec.universe,
      quantity: Number(rec.legs[0].quantity),
      targetPrice: Number(rec.economics.targetPrice),
      horizonSessions: rec.horizonSessions,
      // Lets the server refuse if the market has moved away from what this card shows.
      expectedEntryPrice: Number(rec.economics.entryPrice),
      allowExtendedHours: extended ? extended.checked : false,
      // Required for event contracts: YES and NO are separately priced instruments on the same
      // market, and the vendor rejects an event order without a side.
      eventOutcome: rec.eventOutcome || null,
    });
    renderSubmission(box, result);
    // The order log is the record of what was just done, so it should not lag behind the card
    // that says it happened. The account's cash and positions move with it.
    if (result.accepted) {
      loadOrders();
      loadAccountDetail();
    }
  } catch (e) {
    box.className = 'submit-result failed';
    box.textContent = 'Request failed: ' + e.message;
  } finally {
    button.disabled = false;
    button.textContent = 'Submit orders';
  }
}

function renderSubmission(box, result) {
  if (!result.accepted) {
    box.className = 'submit-result failed';
    const legError = result.entry && result.entry.error ? `<br>${result.entry.error}` : '';
    box.innerHTML = (result.refusedReason || 'Not submitted.') + legError;
    return;
  }
  const leg = (l) => l
    ? `<span class="leg">${l.side} ${l.quantity} ${l.orderType}` +
      `${l.limitPrice ? ' @ ' + money(l.limitPrice) : ''} (${l.timeInForce}) — ${l.status}` +
      `${l.filledPrice ? ' filled ' + money(l.filledPrice) : ''}${l.error ? ' — ' + l.error : ''}</span>`
    : '';
  // "unprotected" means the entry filled but no exit is resting - the one state worth shouting
  // about, because it looks like success and is not.
  box.className = 'submit-result ' + (result.unprotected ? 'warn' : 'ok');
  box.innerHTML = `<strong>Submitted to ${result.environment}</strong>`
    + leg(result.entry) + leg(result.exit)
    + (result.notes.length ? `<ul>${result.notes.map((n) => `<li>${n}</li>`).join('')}</ul>` : '');
}

/* ── wiring ──────────────────────────────────────────────────────── */

function wireEvents() {
  document.querySelectorAll('.env-btn').forEach((button) =>
    button.addEventListener('click', () => {
      if (button.disabled) return;
      state.environment = button.dataset.env;
      applyEnvironment();
    }));

  $('accountSelect').addEventListener('change', (event) => {
    state.accountId = event.target.value;
    state.universeGroup = '';
    const env = state.environments.find((e) => e.environment === state.environment);
    const account = env && env.accounts.find((a) => a.accountId === state.accountId);
    applyUniverse(account);
    loadAccountDetail();
    loadOrders();
    loadUniverse();
  });

  // Debounced: the filter runs server-side over a cached universe of thousands of instruments,
  // and firing per keystroke would queue a request behind every letter.
  let searchTimer;
  $('universeSearch').addEventListener('input', (event) => {
    clearTimeout(searchTimer);
    const value = event.target.value;
    searchTimer = setTimeout(() => {
      state.universeQuery = value;
      loadUniverse();
    }, 250);
  });

  let symbolSearchTimer;
  $('symbolSearch').addEventListener('input', (event) => {
    clearTimeout(symbolSearchTimer);
    const value = event.target.value;
    symbolSearchTimer = setTimeout(() => searchSymbols(value), 220);
  });
  $('symbolSearch').addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      $('searchResults').hidden = true;
      event.target.value = '';
    }
  });
  // Dismiss the dropdown on an outside click, the way a search box is expected to behave.
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.symbol-search')) $('searchResults').hidden = true;
  });

  $('universeTradableOnly').addEventListener('change', (event) => {
    state.universeTradableOnly = event.target.checked;
    loadUniverse();
  });

  document.querySelectorAll('.policy-btn').forEach((button) =>
    button.addEventListener('click', () => {
      state.policy = button.dataset.policy;
      document.querySelectorAll('.policy-btn').forEach((b) =>
        b.classList.toggle('is-active', b === button));
      loadHistory();
    }));

  document.querySelectorAll('.range-btn').forEach((button) =>
    button.addEventListener('click', () => setChartRange(button.dataset.range)));

  document.querySelectorAll('.chart-mode-btn').forEach((button) =>
    button.addEventListener('click', () => setChartMode(button.dataset.chartMode)));

  // Polling a session nobody is looking at is pure waste, and a tab left open overnight would
  // otherwise keep asking the vendor for a market that closed hours ago.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      stopLiveRefresh();
    } else if (state.chartMode === 'live' && state.selectedSymbol) {
      loadIntraday();
      startLiveRefresh();
    }
  });

  $('askForm').addEventListener('submit', (event) => {
    event.preventDefault();
    ask($('askInput').value);
  });

  $('recommendForm').addEventListener('submit', (event) => {
    event.preventDefault();
    requestRecommendations();
  });

  document.querySelectorAll('.mode-btn').forEach((button) =>
    button.addEventListener('click', () => {
      state.analystMode = button.dataset.mode;
      document.querySelectorAll('.mode-btn').forEach((b) =>
        b.classList.toggle('is-active', b === button));
      $('askMode').hidden = state.analystMode !== 'ask';
      $('recommendMode').hidden = state.analystMode !== 'recommend';
    }));

  // Redraw on resize: the canvas is sized in device pixels and would otherwise blur.
  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(redrawChart, 150);
  });
}

/** Repaints the chart currently on screen, from data already loaded. */
function redrawChart() {
  if (state.chartMode === 'live') {
    if (state.liveSeries) drawChart(state.liveSeries);
  } else if (state.history && state.history.bars.length) {
    drawChart(state.history);
  }
}

function start() {
  wireEvents();
  tickClock();
  setInterval(tickClock, 1000);

  renderAskSuggestions(state.universe || 'EQUITY');
  applyCorporateActionsVisibility();
  refreshStatus();
  loadOrderCapability();
  loadQuotes();
  loadEnvironments();
  loadActions();
  loadAnalystStatus();
  openQuoteStream();

  // The stream carries quotes; these poll the things that have no push channel.
  setInterval(refreshStatus, 5000);
  setInterval(loadQuotes, 15000);
  setInterval(loadAccountDetail, 20000);
  setInterval(loadOrders, 20000);
  setInterval(loadActions, 120000);
}

document.addEventListener('DOMContentLoaded', start);
