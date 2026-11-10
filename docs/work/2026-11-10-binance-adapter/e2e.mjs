// 바이낸스 시세 연동 E2E. 계획서 7절의 표를 그대로 옮겼다.
//   node docs/work/2026-11-10-binance-adapter/e2e.mjs before   구현 전 화면 → shots/before/
//   node docs/work/2026-11-10-binance-adapter/e2e.mjs after    구현 후 화면 → shots/after/
// 로컬 실행(Vite)은 BASE_URL=http://localhost:5173 을 붙인다. 10단계는 binance-adapter dev 프로필(:8083)이 필요하다.

import { runScenario } from '../../../scripts/lib/browser.mjs'

const BASE = process.env.BASE_URL ?? 'http://localhost:3000'
const ADAPTER = process.env.BINANCE_ADAPTER_URL ?? 'http://localhost:8083'
const BINANCE = 'https://api.binance.com'

const expect = (ok, message) => { if (!ok) throw new Error(message) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const getJson = async (url) => {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url} → ${res.status}`)
  return res.json()
}
const same = (a, b) => Number(a) === Number(b)
const legendText = `document.querySelector('.legend')?.textContent ?? ''`
// 범례 값(dt → dd). 쉼표는 뗀다.
const legendValues = `Object.fromEntries([...document.querySelectorAll('.legend dt')].map((dt) => [dt.textContent, dt.nextElementSibling.textContent.replaceAll(',', '')]))`

/** ms 동안 범례 글자가 몇 번 바뀌는지 센다. */
async function countLegendChanges(page, ms) {
  let last = await page.evaluate(legendText)
  let changes = 0
  for (const until = Date.now() + ms; Date.now() < until;) {
    await sleep(200)
    const now = await page.evaluate(legendText)
    if (now !== last) changes++
    last = now
  }
  return changes
}

/** 1분봉이 빈칸 없이 이어지는지 본다. */
function assertNoGaps(candles, label) {
  for (let i = 1; i < candles.length; i++) {
    const gap = Date.parse(candles[i].time) - Date.parse(candles[i - 1].time)
    expect(gap === 60_000, `${label}: ${candles[i - 1].time} 다음이 ${candles[i].time}`)
  }
}

await runScenario(import.meta, async ({ page, step, mode }) => {
  await step('first-screen', async () => {
    await page.goto(BASE)
    await page.waitFor('.market-row')
    await page.waitFor('.legend dd')
    const rows = await page.evaluate(`document.querySelectorAll('.market-row').length`)
    expect(rows > 100, `종목 목록이 ${rows}줄뿐이다`)
    const code = await page.evaluate(`document.querySelector('.chart-head .code').textContent`)
    if (mode === 'before') {
      expect(code === 'KRW-BTC', `첫 종목이 ${code}`)
      return
    }
    expect(code === 'BTCUSDT', `첫 종목이 ${code}`)
    const pressed = await page.evaluate(`document.querySelector('.source-tabs [aria-pressed="true"]')?.textContent`)
    expect(pressed === '바이낸스', `눌린 탭이 ${pressed}`)
    const codes = await page.evaluate(`[...document.querySelectorAll('.market-row small')].map((e) => e.textContent)`)
    expect(codes.every((c) => c.endsWith('USDT')), '바이낸스 탭에 USDT가 아닌 종목이 있다')
  })

  await step('live-every-second', async () => {
    const changes = await countLegendChanges(page, 5000)
    expect(changes >= 3, `5초 동안 범례가 ${changes}번 바뀌었다`)
  }, { afterOnly: true })

  await step('ten-minute', async () => {
    await page.click('.units button', { text: '10분' })
    await page.waitUntil(`!!document.querySelector('.legend dd')`)
    const page10 = await getJson(`${BASE}/api/candles?market=BINANCE:BTCUSDT&unit=10m&count=50`)
    for (let i = 1; i < page10.candles.length; i++) {
      const gap = Date.parse(page10.candles[i].time) - Date.parse(page10.candles[i - 1].time)
      expect(gap === 600_000, `10분봉 빈칸: ${page10.candles[i - 1].time} 다음이 ${page10.candles[i].time}`)
    }
    expect(page10.live, '진행 중 10분봉이 없다')
    const changes = await countLegendChanges(page, 5000)
    expect(changes >= 2, `5초 동안 10분봉 범례가 ${changes}번 바뀌었다`)
  }, { afterOnly: true, clip: '.chart-panel' })

  await step('day-week-month', async () => {
    for (const [label, unit] of [['일', 'day'], ['주', 'week'], ['월', 'month']]) {
      await page.click('.units button', { text: label })
      await page.waitUntil(`!!document.querySelector('.legend dd')`)
      const body = await getJson(`${BASE}/api/candles?market=BINANCE:BTCUSDT&unit=${unit}&count=10`)
      expect(body.candles.length > 0, `${label}봉이 비었다`)
      if (unit === 'week') {
        const bad = body.candles.find((c) => new Date(c.time).getUTCDay() !== 1)
        expect(!bad, `주봉이 월요일에 시작하지 않는다: ${bad?.time}`)
      }
    }
    await page.click('.units button', { text: '1분' })
  }, { afterOnly: true, clip: '.chart-panel' })

  await step('quiet-symbol', async () => {
    const rows = await page.evaluate(`[...document.querySelectorAll('.market-row small')].map((e) => e.textContent)`)
    const quiet = rows.at(-1)
    await page.click('.market-row small', { text: quiet })
    await page.waitUntil(`document.querySelector('.chart-head .code').textContent === ${JSON.stringify(quiet)}`)
    await page.waitFor('.legend dd')
    const body = await getJson(`${BASE}/api/candles?market=BINANCE:${quiet}&unit=1m&count=120`)
    expect(body.candles.length > 60, `${quiet} 1분봉이 ${body.candles.length}개뿐이다`)
    assertNoGaps(body.candles, quiet)
  }, { afterOnly: true })

  await step('search-symbol', async () => {
    await page.type('.search input', 'btc')
    await page.waitUntil(`[...document.querySelectorAll('.market-row small')].some((e) => e.textContent === 'BTCUSDT')`)
    const row = await page.evaluate(`(() => { const s = [...document.querySelectorAll('.market-row small')].find((e) => e.textContent === 'BTCUSDT'); return s.previousElementSibling.textContent })()`)
    expect(row === 'BTC', `BTCUSDT 줄의 이름이 ${row}`)
  }, { afterOnly: true })

  await step('crosshair-vs-binance', async () => {
    await page.type('.search input', '')
    await page.click('.market-row small', { text: 'BTCUSDT' })
    await page.waitUntil(`document.querySelector('.chart-head .code').textContent === 'BTCUSDT'`)
    await page.waitFor('.legend dd')
    await sleep(1000)
    const box = await page.evaluate(`(() => { const b = document.querySelector('.chart').getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height } })()`)
    await page.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x + box.w * 0.6, y: box.y + box.h * 0.4 })
    await sleep(500)
    const time = await page.evaluate(`document.querySelector('.legend-time').textContent`)
    const v = await page.evaluate(legendValues)
    // 범례 시각은 한국 시간 'YYYY-MM-DD HH:mm'
    const startMs = Date.parse(`${time.replace(' ', 'T')}:00+09:00`)
    const [k] = await getJson(`${BINANCE}/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=${startMs}&limit=1`)
    expect(k[0] === startMs, `바이낸스에 ${time} 분봉이 없다`)
    const want = { 시: k[1], 고: k[2], 저: k[3], 종: k[4], 거래량: k[5], 거래대금: k[7] }
    for (const [name, value] of Object.entries(want)) {
      expect(same(v[name], value), `${time} ${name}: 화면 ${v[name]}, 바이낸스 ${value}`)
    }
  }, { afterOnly: true })

  await step('scroll-back', async () => {
    const box = await page.evaluate(`(() => { const b = document.querySelector('.chart').getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height } })()`)
    const y = box.y + box.h * 0.5
    const before = await page.evaluate(`performance.getEntriesByType('resource').filter((e) => e.name.includes('/api/candles') && e.name.includes('to=')).length`)
    // 가로 휠로 차트를 과거 쪽(왼쪽 끝)까지 민다. 첫 캔들 근처에 닿으면 차트가 이전 200개를 요청한다.
    const loaded = `performance.getEntriesByType('resource').filter((e) => e.name.includes('/api/candles') && e.name.includes('to=')).length > ${before}`
    for (let i = 0; i < 30 && !(await page.evaluate(loaded)); i++) {
      await page.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: box.x + box.w * 0.5, y, deltaX: -800, deltaY: 0 })
      await sleep(150)
    }
    await page.waitUntil(loaded)
    await sleep(800)
  }, { afterOnly: true, clip: '.chart-panel' })

  await step('upbit-still-works', async () => {
    if (mode === 'after') {
      await page.click('.source-tabs button', { text: '업비트' })
      await page.waitUntil(`[...document.querySelectorAll('.market-row small')].some((e) => e.textContent === 'KRW-BTC')`)
      const amounts = await page.evaluate(`[...document.querySelectorAll('.market-row .num.muted')].slice(0, 5).map((e) => e.textContent)`)
      expect(amounts.every((a) => a.endsWith('백만')), `업비트 거래대금 표기: ${amounts}`)
    }
    await page.click('.market-row small', { text: 'KRW-BTC' })
    await page.waitUntil(`document.querySelector('.chart-head .code').textContent === 'KRW-BTC'`)
    await page.waitFor('.legend dd')
    // 업비트는 체결이 있을 때만 진행 중 캔들이 움직인다(한밤에는 몇 초에 한 번). 그래서 기준을 느슨하게 둔다.
    const changes = await countLegendChanges(page, 10_000)
    expect(changes >= 1, `10초 동안 업비트 범례가 한 번도 바뀌지 않았다`)
  })

  await step('after-reconnect', async () => {
    await page.click('.source-tabs button', { text: '바이낸스' })
    await page.waitFor('.market-row')
    await page.click('.market-row small', { text: 'BTCUSDT' })
    await page.waitUntil(`document.querySelector('.chart-head .code').textContent === 'BTCUSDT'`)
    const res = await fetch(`${ADAPTER}/admin/stream/pause?seconds=20`, { method: 'POST' })
    expect(res.ok, `스트림 멈추기 실패: ${res.status}`)
    await sleep(40_000)
    const changes = await countLegendChanges(page, 5000)
    expect(changes >= 3, `다시 붙은 뒤 5초 동안 범례가 ${changes}번 바뀌었다`)
    // 진행 중 1분봉: 시가는 같아야 하고, 거래량은 두 요청 사이 1~2초 차이만큼만 다를 수 있다.
    const ours = (await getJson(`${BASE}/api/candles?market=BINANCE:BTCUSDT&unit=1m&count=1`)).live
    const [k] = await getJson(`${BINANCE}/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=1`)
    expect(Date.parse(ours.time) === k[0], `진행 중 칸 시각이 다르다: ${ours.time} / ${new Date(k[0]).toISOString()}`)
    expect(same(ours.open, k[1]), `진행 중 시가: 우리 ${ours.open}, 바이낸스 ${k[1]}`)
    expect(Number(ours.high) <= Number(k[2]) && Number(ours.low) >= Number(k[3]), `진행 중 고저가 바이낸스 범위를 벗어났다: ${ours.high}/${ours.low} vs ${k[2]}/${k[3]}`)
    expect(Number(ours.volume) <= Number(k[5]) * 1.0001, `진행 중 거래량이 바이낸스보다 많다: ${ours.volume} > ${k[5]}`)
  }, { afterOnly: true })
})
