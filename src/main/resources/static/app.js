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
  const previous = state.quotes.get(quote.symbol);
  state.quotes.set(quote.symbol, quote);

  let row = document.querySelector(`tr[data-symbol="${quote.symbol}"]`);
  if (!row) {
    row = document.createElement('tr');
    row.dataset.symbol = quote.symbol;
    row.addEventListener('click', () => selectSymbol(quote.symbol));
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
  const quotes = await getJson('/api/market/quotes');
  if (!quotes || !quotes.length) return;

  const placeholder = $('quoteBody').querySelector('tr.empty');
  if (placeholder) placeholder.remove();

  quotes.forEach(renderQuoteRow);
  sortQuoteRows();
  $('quoteCount').textContent = `${quotes.length} instruments`;

  if (!state.selectedSymbol && quotes.length) selectSymbol(quotes[0].symbol);
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

function selectSymbol(symbol) {
  state.selectedSymbol = symbol;
  document.querySelectorAll('tr[data-symbol]').forEach((row) =>
    row.classList.toggle('is-selected', row.dataset.symbol === symbol));

  const quote = state.quotes.get(symbol);
  $('chartSymbol').textContent = symbol;
  $('chartName').textContent = quote ? quote.name : '';
  loadHistory();
}

async function loadHistory() {
  if (!state.selectedSymbol) return;
  const requestId = ++state.historyRequest;
  try {
    const history = await getJson(
      `/api/market/history/${state.selectedSymbol}?policy=${state.policy}&days=250`);
    if (requestId !== state.historyRequest) return;
    state.history = history;

    if (!history || !history.bars.length) {
      $('chartEmpty').hidden = false;
      $('chartCanvas').hidden = true;
      $('chartReturn').textContent = '—';
      $('chartVol').textContent = '—';
      $('chartBasis').textContent = '—';
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
    $('chartBasis').textContent = history.basisLabel;

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

function drawChart(history) {
  const canvas = $('chartCanvas');
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = 260;

  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const bars = history.bars;
  const closes = bars.map((b) => Number(b.close));
  const min = Math.min(...closes);
  const max = Math.max(...closes);
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
  const lineColor = closes[closes.length - 1] >= closes[0] ? up : down;

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

  // Date labels at either end.
  ctx.textAlign = 'left';
  ctx.fillText(bars[0].date, padLeft, height - 8);
  ctx.textAlign = 'right';
  ctx.fillText(bars[bars.length - 1].date, width - padRight, height - 8);

  // Ex-date markers, so a discontinuity in the raw series has a visible cause.
  (history.actionsInWindow || []).forEach((action) => {
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
  // The account class decides the universe, so a change of account changes the instrument set.
  state.universeGroup = '';
  loadUniverse();
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

/* ── corporate actions ───────────────────────────────────────────── */

async function loadActions() {
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
    row.addEventListener('click', () => selectSymbol(row.dataset.symbolAction)));

  $('actionHint').textContent = `${actions.length} recorded`;
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
  } catch (e) {
    if (requestId === state.universeRequest) {
      $('universeHint').textContent = 'Could not load instruments';
    }
  }
}

function renderUniverse(data) {
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
  note.hidden = data.available;
  if (!data.available) note.textContent = data.unavailableReason;

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
    const reason = data.available ? 'No instruments match.' : 'Unavailable.';
    body.innerHTML = `<tr class="empty"><td colspan="${3 + data.columns.length}">${reason}</td></tr>`;
    return;
  }

  body.innerHTML = data.instruments.map((i) => `
    <tr class="${i.tradable ? '' : 'not-tradable'}">
      <td class="sym">${i.symbol}${i.tradable ? '' : ' <span class="badge badge-halted">halted</span>'}</td>
      <td class="name" title="${escapeAttr(i.name)}">${i.name}</td>
      <td class="flat">${i.group}</td>
      ${data.columns.map((c) => `<td class="attr">${i.attributes[c] || '—'}</td>`).join('')}
    </tr>`).join('');
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
    const entry = rec.legs[0].description;
    const exit = rec.legs[1].description;
    button.textContent = `Confirm: ${entry} then ${exit}`;
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
    });
    renderSubmission(box, result);
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
    loadAccountDetail();
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

  document.querySelectorAll('.chip').forEach((chip) =>
    chip.addEventListener('click', () => {
      $('askInput').value = chip.textContent;
      ask(chip.textContent);
    }));

  // Redraw on resize: the canvas is sized in device pixels and would otherwise blur.
  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (state.history) drawChart(state.history); }, 150);
  });
}

function start() {
  wireEvents();
  tickClock();
  setInterval(tickClock, 1000);

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
  setInterval(loadActions, 120000);
}

document.addEventListener('DOMContentLoaded', start);
