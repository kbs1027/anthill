#!/usr/bin/env node
// 실시간 비교 도구 (spec 8-5). 업비트 웹소켓 candle.1m과 우리 /ws(같은 종목 1m 구독)를 동시에 기록해 판정한다.
//   지연     : 업비트 분봉 메시지마다, 우리 푸시가 그 거래량에 2초 안에 도달한다.
//   마감 값  : 우리 closed 푸시 값 = 그 분의 마지막 업비트 메시지 값 (값 기준 비교).
//   중복 없음: 우리 live 푸시의 거래량이 그 분의 마지막 업비트 거래량을 넘은 적이 없다.
// 사용: node scripts/compare-live.mjs [--minutes 10] [--markets KRW-BTC,KRW-ETH] [--gateway http://localhost:8080]
// Node 26 내장 WebSocket·fetch만 쓴다. 숫자는 JSON 원문 그대로 받아 값으로 비교한다(부동소수 변환 없음).

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
)
const MINUTES = Number(args.minutes ?? 10)
const GATEWAY = (args.gateway ?? 'http://localhost:8080').replace(/\/$/, '')
const UPBIT_WS = 'wss://api.upbit.com/websocket/v1'
const LATENCY_MS = 2000
const WARMUP_MS = 5000

// ---- 정확한 십진수 비교 -------------------------------------------------------------
function dec(s) {
  const m = /^(-?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(String(s).trim())
  if (!m) throw new Error(`not a decimal: ${s}`)
  const frac = m[3] ?? ''
  let digits = BigInt((m[2] || '0') + frac) * (m[1] ? -1n : 1n)
  let scale = frac.length - Number(m[4] ?? 0)
  while (scale > 0 && digits % 10n === 0n) { digits /= 10n; scale-- }
  while (scale < 0) { digits *= 10n; scale++ }
  return { digits, scale }
}
function cmp(a, b) {
  const x = dec(a), y = dec(b)
  const s = Math.max(x.scale, y.scale)
  const xa = x.digits * 10n ** BigInt(s - x.scale), ya = y.digits * 10n ** BigInt(s - y.scale)
  return xa === ya ? 0 : xa < ya ? -1 : 1
}
const FIELDS = ['open', 'high', 'low', 'close', 'volume', 'amount']
const sameValues = (a, b) => FIELDS.every((f) => cmp(a[f], b[f]) === 0)

/** 숫자를 원문 문자열로 남기는 JSON.parse (Node 21+의 reviver context.source). */
const parseExact = (text) => JSON.parse(text, (_k, v, ctx) => (typeof v === 'number' ? ctx.source : v))

// ---- 종목 고르기 ---------------------------------------------------------------------
async function pickMarkets() {
  if (args.markets) return args.markets.split(',').map((m) => m.trim())
  const list = await (await fetch(`${GATEWAY}/api/markets`)).json()
  const traded = list.filter((m) => m.amount24h !== null && cmp(m.amount24h, '0') > 0)
  const top = traded.slice(0, 3)
  const bottom = traded.slice(-2)
  return [...top, ...bottom].map((m) => m.market.split(':')[1])
}

// ---- 기록 ------------------------------------------------------------------------------
const upbit = new Map() // `${code}|${start}` → [{ at, v }]
const ours = new Map() // `${code}|${start}` → [{ at, state, v }]
const ourIntervals = new Map() // code → 받은 시각들
const push = (map, key, rec) => (map.get(key) ?? map.set(key, []).get(key)).push(rec)

function recordUpbit(text) {
  const m = parseExact(text)
  if (!String(m.type).startsWith('candle.1m') || m.stream_type !== 'REALTIME') return
  push(upbit, `${m.code}|${m.candle_date_time_utc}Z`, {
    at: Date.now(),
    v: {
      open: m.opening_price, high: m.high_price, low: m.low_price, close: m.trade_price,
      volume: m.candle_acc_trade_volume, amount: m.candle_acc_trade_price,
    },
  })
}

function recordOurs(text) {
  const msg = JSON.parse(text) // 우리 서버는 숫자를 모두 문자열로 보낸다
  if (msg.type !== 'candles') return
  const at = Date.now()
  for (const it of msg.items) {
    if (it.unit !== '1m' || !it.candle) continue
    const code = it.market.split(':')[1]
    push(ours, `${code}|${it.time}`, { at, state: it.state, v: it.candle })
    ;(ourIntervals.get(code) ?? ourIntervals.set(code, []).get(code)).push(at)
  }
}

function open(url, onOpen, onText) {
  const ws = new WebSocket(url)
  ws.binaryType = 'arraybuffer'
  const decoder = new TextDecoder()
  ws.onopen = () => onOpen(ws)
  ws.onmessage = (ev) => onText(typeof ev.data === 'string' ? ev.data : decoder.decode(ev.data))
  ws.onerror = (ev) => console.error(`websocket error ${url}:`, ev.message ?? ev.type)
  return ws
}

// ---- 판정 ------------------------------------------------------------------------------
function judge(startedAt, endedAt) {
  const latency = [], latencyViolations = [], closedViolations = [], overVolume = []
  let closedChecked = 0
  for (const [key, msgs] of upbit) {
    const pushes = ours.get(key) ?? []
    // 지연: 업비트 메시지 거래량에 우리 푸시가 언제 도달했나
    for (const u of msgs) {
      if (u.at < startedAt + WARMUP_MS || u.at > endedAt - LATENCY_MS - 1000) continue
      const reached = pushes.find((p) => cmp(p.v.volume, u.v.volume) >= 0)
      const lag = reached ? reached.at - u.at : Infinity
      latency.push(lag)
      if (lag > LATENCY_MS) latencyViolations.push({ key, upbitAt: new Date(u.at).toISOString(), lagMs: lag, volume: u.v.volume })
    }
    // 마감 값·중복: 기록 구간 안에서 시작과 마감을 모두 본 분만
    const start = Date.parse(key.split('|')[1])
    if (start < startedAt + 60_000 || start + 75_000 > endedAt) continue
    const last = msgs.at(-1).v
    for (const p of pushes) {
      if (p.state === 'live' && cmp(p.v.volume, last.volume) > 0) overVolume.push({ key, ours: p.v.volume, upbitLast: last.volume })
    }
    const closed = pushes.filter((p) => p.state === 'closed')
    if (closed.length === 0) continue
    closedChecked++
    for (const c of closed) {
      if (sameValues(c.v, last)) continue
      // 엄격 판정은 그대로(위반). 참고로 그 뒤 정정 푸시가 화면 값을 업비트와 같게 만들었는지도 남긴다.
      const fixedBy = pushes.find((p) => p.at > c.at && p.state === 'corrected' && sameValues(p.v, last))
      closedViolations.push({ key, ours: c.v, upbitLast: last, correctedLaterMs: fixedBy ? fixedBy.at - c.at : null })
    }
  }
  const finite = latency.filter(Number.isFinite).sort((a, b) => a - b)
  const q = (p) => (finite.length ? finite[Math.min(finite.length - 1, Math.floor(p * finite.length))] : null)
  const gaps = [...ourIntervals.values()].flatMap((ts) => ts.slice(1).map((t, i) => t - ts[i]))
  const sub1s = gaps.filter((g) => g < 900).length
  return {
    upbitMessages: [...upbit.values()].reduce((n, l) => n + l.length, 0),
    ourCandleItems: [...ours.values()].reduce((n, l) => n + l.length, 0),
    latency: { checked: latency.length, p50: q(0.5), p95: q(0.95), max: finite.at(-1) ?? null, violations: latencyViolations.length },
    closed: { checked: closedChecked, violations: closedViolations.length },
    duplicates: { violations: overVolume.length },
    pushGapsUnder900ms: sub1s,
    details: { latencyViolations: latencyViolations.slice(0, 10), closedViolations: closedViolations.slice(0, 10), overVolume: overVolume.slice(0, 10) },
  }
}

// ---- 실행 ------------------------------------------------------------------------------
const codes = await pickMarkets()
console.log(`markets: ${codes.join(', ')} · ${MINUTES} min`)
const ourUrl = GATEWAY.replace(/^http/, 'ws') + '/ws'
const sockets = [
  open(UPBIT_WS, (ws) => ws.send(JSON.stringify([{ ticket: `anthill-compare-${Date.now()}` }, { type: 'candle.1m', codes }, { format: 'DEFAULT' }])), recordUpbit),
  open(ourUrl, (ws) => codes.forEach((c) => ws.send(JSON.stringify({ op: 'subscribe', market: `UPBIT:${c}`, unit: '1m' }))), recordOurs),
]
const startedAt = Date.now()
const tick = setInterval(() => process.stdout.write(`\r${Math.round((Date.now() - startedAt) / 1000)}s recorded   `), 5000)
await new Promise((r) => setTimeout(r, MINUTES * 60_000))
clearInterval(tick)
const endedAt = Date.now()
sockets.forEach((s) => s.close())
const result = judge(startedAt, endedAt)
console.log('\n' + JSON.stringify(result, null, 2))
const failed = result.latency.violations + result.closed.violations + result.duplicates.violations
console.log(failed === 0 ? 'PASS: 0 violations' : `FAIL: ${failed} violations`)
process.exit(failed === 0 ? 0 : 1)
