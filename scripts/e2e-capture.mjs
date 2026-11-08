#!/usr/bin/env node
// E2E 캡처: 띄워 둔 전체 스택(브라우저 → gateway → marketdata → adapter → 업비트)을 실제 사용자처럼 조작하며
// 단계마다 화면을 PNG로 남기고, 화면에 보인 값을 업비트 REST와 대조한 결과를 JSON으로 남긴다.
// 사용: node scripts/e2e-capture.mjs [--url http://localhost:5173] [--out build/e2e] [--reconnect] [--at 'YYYY-MM-DD HH:mm']
//   --at: 십자선 단계에서 이 분(KST)의 캔들을 찾아 가리킨다(업비트 화면 캡처와 같은 캔들을 나란히 보려고).
//   --reconnect: edge-gateway 컨테이너를 30초 내렸다 올려, 열린 화면이 다시 붙어 갱신을 받는지도 캡처한다.
// 필요: Google Chrome, Node 26(내장 WebSocket·fetch), docker-compose.full.yml 스택.

import { execSync, spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]?.startsWith('--') ? true : (all[i + 1] ?? true)]] : acc), []),
)
const URL_ = args.url ?? 'http://localhost:5173'
const OUT = args.out ?? 'build/e2e'
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const PORT = 9333
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
mkdirSync(OUT, { recursive: true })

// ---- Chrome + CDP ---------------------------------------------------------------------
const profile = mkdtempSync(join(tmpdir(), 'anthill-e2e-'))
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', '--lang=ko-KR', 'about:blank'], { stdio: 'ignore' })
let targets
for (let i = 0; i < 50 && !targets; i++) {
  await sleep(200)
  targets = await fetch(`http://127.0.0.1:${PORT}/json/list`).then((r) => r.json()).catch(() => undefined)
}
const page = targets.find((t) => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((r) => (ws.onopen = r))
let seq = 0
const pending = new Map()
const listeners = []
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id)
    pending.delete(m.id)
    m.error ? reject(new Error(`${m.error.message}`)) : resolve(m.result)
  } else if (m.method) listeners.forEach((l) => l(m))
}
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++seq
  pending.set(id, { resolve, reject })
  ws.send(JSON.stringify({ id, method, params }))
})
const evaluate = async (expression) => (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result.value
const waitFor = async (expression, timeoutMs = 15000) => {
  const until = Date.now() + timeoutMs
  while (Date.now() < until) {
    if (await evaluate(expression)) return true
    await sleep(200)
  }
  throw new Error(`timed out waiting for ${expression}`)
}
const shot = async (name, clipSelector) => {
  let clip
  if (clipSelector) {
    const r = await evaluate(`(() => { const b = document.querySelector(${JSON.stringify(clipSelector)}).getBoundingClientRect(); return {x: b.x, y: b.y, width: b.width, height: b.height} })()`)
    clip = { ...r, scale: 1 }
  }
  const { data } = await send('Page.captureScreenshot', { format: 'png', ...(clip ? { clip } : {}) })
  writeFileSync(join(OUT, `${name}.png`), Buffer.from(data, 'base64'))
  console.log(`captured ${name}.png`)
}
const legend = () => evaluate(`document.querySelector('.legend')?.innerText.replace(/\\s+/g, ' ').trim()`)
const clickUnit = (label) => evaluate(`[...document.querySelectorAll('.units button')].find(b => b.textContent === ${JSON.stringify(label)}).click()`)
const search = (text) => evaluate(`(() => { const i = document.querySelector('.search input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, ${JSON.stringify(text)}); i.dispatchEvent(new Event('input', { bubbles: true })); })()`)
const chartBox = () => evaluate(`(() => { const b = document.querySelector('.chart').getBoundingClientRect(); return {x: b.x, y: b.y, w: b.width, h: b.height} })()`)
const hoverAt = async (label) => {
  const b = await chartBox()
  for (let x = b.x + b.w - 70; x > b.x + 10; x -= 3) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y: b.y + b.h * 0.45 })
    if ((await legend())?.startsWith(label)) return true
  }
  return false
}
const desktop = () => send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 2, mobile: false })

// ---- 정확한 십진수 비교 (화면 문자열 vs 업비트 JSON 원문) ----------------------------------------
const norm = (s) => {
  let [i, f = ''] = String(s).replace(/,/g, '').split('.')
  f = f.replace(/0+$/, '')
  return f ? `${BigInt(i)}.${f}` : `${BigInt(i)}`
}
const parseExact = (text) => JSON.parse(text, (_k, v, ctx) => (typeof v === 'number' ? ctx.source : v))

const report = { startedAt: new Date().toISOString(), url: URL_, steps: [] }
const step = (name, data) => { report.steps.push({ name, at: new Date().toISOString(), ...data }); console.log(name, JSON.stringify(data)) }

const requests = []
listeners.push((m) => { if (m.method === 'Network.requestWillBeSent') requests.push(m.params.request.url) })

try {
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  await desktop()

  // 1. 첫 화면: KRW-BTC 1분봉과 거래대금 순 종목 목록
  await send('Page.navigate', { url: URL_ })
  await waitFor(`!!document.querySelector('.legend dd') && document.querySelectorAll('.market-row').length > 10`)
  await sleep(2500)
  await shot('01-first-screen')
  step('first-screen', { header: await evaluate(`document.querySelector('.chart-head').innerText`), legend: await legend(), markets: await evaluate(`document.querySelectorAll('.market-row').length`) })

  // 2. 실시간 갱신: 진행 중 캔들이 바뀔 때까지(최대 30초) 기다렸다가 전후를 캡처한다(체결이 없는 초에는 바뀌지 않는다)
  const liveA = await legend(); await shot('02-live-a', '.chart-panel')
  const t0 = Date.now()
  let liveB = liveA
  while (liveB === liveA && Date.now() - t0 < 30_000) { await sleep(500); liveB = await legend() }
  await shot('03-live-b', '.chart-panel')
  step('live-update', { before: liveA, after: liveB, changed: liveA !== liveB, secondsUntilChange: liveA !== liveB ? (Date.now() - t0) / 1000 : null })

  // 3. 십자선: 지난 캔들 하나를 가리켜 범례 값을 업비트 REST와 대조
  const box = await chartBox()
  if (args.at) {
    if (!(await hoverAt(String(args.at)))) throw new Error(`no candle at ${args.at} on screen`)
  } else {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x + box.w * 0.55, y: box.y + box.h * 0.45 })
  }
  await sleep(500)
  const hovered = await legend()
  await shot('04-crosshair', '.chart-panel')
  const m = hovered.match(/(\d{4}-\d{2}-\d{2}) (\d{2}):(\d{2}) 시 ([\d,.]+) 고 ([\d,.]+) 저 ([\d,.]+) 종 ([\d,.]+) 거래량 ([\d,.]+) 거래대금 ([\d,.]+)/)
  if (m) {
    const startKst = new Date(`${m[1]}T${m[2]}:${m[3]}:00+09:00`)
    const toUtc = new Date(startKst.getTime() + 60_000).toISOString().replace('.000Z', 'Z')
    const up = parseExact(await (await fetch(`https://api.upbit.com/v1/candles/minutes/1?market=KRW-BTC&count=1&to=${toUtc}`, { headers: { accept: 'application/json' } })).text())[0]
    const pairs = { open: [m[4], up.opening_price], high: [m[5], up.high_price], low: [m[6], up.low_price], close: [m[7], up.trade_price], volume: [m[8], up.candle_acc_trade_volume], amount: [m[9], up.candle_acc_trade_price] }
    const same = Object.fromEntries(Object.entries(pairs).map(([k, [ours, theirs]]) => [k, norm(ours) === norm(theirs)]))
    step('crosshair-vs-upbit', { legend: hovered, upbitStartUtc: up.candle_date_time_utc, ours: Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v[0]])), upbit: Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v[1]])), same })
  } else step('crosshair-vs-upbit', { legend: hovered, note: 'legend did not match the expected format' })
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 5, y: 5 })

  // 4. 단위 전환: 일봉, 주봉
  await clickUnit('일'); await sleep(3000); await shot('05-unit-day')
  step('unit-day', { legend: await legend() })
  await clickUnit('주'); await sleep(3000); await shot('06-unit-week')
  step('unit-week', { legend: await legend() })

  // 5. 과거 스크롤: 1분봉에서 줄여 왼쪽 끝에 닿으면 이전 200개를 받아 앞에 붙인다
  await clickUnit('1분'); await sleep(3000)
  const before = requests.filter((u) => u.includes('/api/candles') && u.includes('to=')).length
  const b2 = await chartBox()
  for (let i = 0; i < 40; i++) {
    await send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: b2.x + b2.w * 0.5, y: b2.y + b2.h * 0.5, deltaX: 0, deltaY: 120 })
    await sleep(60)
  }
  await sleep(3000)
  await shot('07-history-scroll')
  const older = requests.filter((u) => u.includes('/api/candles') && u.includes('to=')).slice(before)
  step('history-scroll', { olderPageRequests: older.map((u) => decodeURIComponent(u.slice(u.indexOf('?')))) })

  // 6. 검색
  await search('이더'); await sleep(800); await shot('08-search')
  step('search', { query: '이더', rows: await evaluate(`[...document.querySelectorAll('.market-row .name')].map(n => n.innerText.replace(/\\n/g, ' '))`) })

  // 7. 거래가 드문 종목: 빈 분은 건너뛰고 붙여 그린다
  await search('KRW-USDG'); await sleep(500)
  await evaluate(`document.querySelector('.market-row').click()`)
  await sleep(3500)
  await search(''); await sleep(300)
  await shot('09-sparse-usdg')
  step('sparse-market', { header: await evaluate(`document.querySelector('.chart-head').innerText`), legend: await legend() })

  // 8. 재연결(선택): gateway를 30초 내렸다 올린다
  if (args.reconnect) {
    await evaluate(`document.querySelector('.market-row').click()`) // 거래대금 1위로 돌아간다
    await sleep(3000)
    const r0 = await legend()
    execSync('docker compose -f docker-compose.full.yml stop edge-gateway', { stdio: 'ignore' })
    const stoppedAt = new Date().toISOString()
    await sleep(20000)
    const r1 = await legend(); await shot('10-reconnect-during', '.chart-panel')
    execSync('docker compose -f docker-compose.full.yml start edge-gateway', { stdio: 'ignore' })
    const startedAt = new Date().toISOString()
    let r2 = r1
    const until = Date.now() + 90_000
    while (Date.now() < until && r2 === r1) { await sleep(1000); r2 = await legend() }
    const resumedAt = new Date().toISOString()
    await sleep(2000); await shot('11-reconnect-after', '.chart-panel')
    step('reconnect', { before: r0, during: r1, after: r2, gatewayStoppedAt: stoppedAt, gatewayStartedAt: startedAt, firstUpdateAfterRestartAt: resumedAt, resumed: r2 !== r1 })
  }

  // 9. 휴대폰 폭(390)
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 3, mobile: true })
  await send('Page.reload'); await sleep(500)
  await waitFor(`!!document.querySelector('.legend dd')`)
  await sleep(2500)
  await shot('12-mobile')
  step('mobile', { horizontalScroll: await evaluate(`document.documentElement.scrollWidth > document.documentElement.clientWidth`) })
} finally {
  report.finishedAt = new Date().toISOString()
  writeFileSync(join(OUT, 'e2e-report.json'), JSON.stringify(report, null, 2))
  ws.close()
  chrome.kill()
}
