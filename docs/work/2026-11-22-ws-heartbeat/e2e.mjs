// 브라우저 실시간 연결 하트비트(ws-heartbeat) E2E. 계획서 7절의 표를 그대로 옮겼다.
//   node docs/work/2026-11-22-ws-heartbeat/e2e.mjs before   구현 전 동작 → shots/before/
//   node docs/work/2026-11-22-ws-heartbeat/e2e.mjs after    구현 후 동작 → shots/after/
// 전체를 `docker compose up -d --build`로 띄운 뒤 worktree 최상위에서 실행한다(약 5분).
// 게이트웨이를 bootRun으로 띄웠다면 GATEWAY_PID=<pid>를 주면 4단계를 kill -STOP/-CONT로 대신하고 5단계는 건너뛴다.
//
// 화면 모양은 바뀌지 않는 작업이라 근거는 단계마다 남기는 .txt 기록이다(언제 새 연결이 생겼나, REST를 다시 받았나).

import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { runScenario } from '../../../scripts/lib/browser.mjs'

const BASE = process.env.BASE_URL ?? 'http://localhost:3000'
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws'
const ROOT = join(import.meta.dirname, '../../..')
const GATEWAY_PID = process.env.GATEWAY_PID

const expect = (ok, message) => { if (!ok) throw new Error(message) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const legendText = `document.querySelector('.legend')?.textContent ?? ''`

/** 게이트웨이를 멈추고(pause) 풀고(unpause) 다시 시작(restart)한다. */
function gateway(action) {
  if (GATEWAY_PID) {
    if (action === 'restart') throw new Error('GATEWAY_PID 모드에서는 재시작을 할 수 없다')
    execFileSync('kill', [action === 'pause' ? '-STOP' : '-CONT', GATEWAY_PID])
    return
  }
  execFileSync('docker', ['compose', action, 'edge-gateway'], { cwd: ROOT, stdio: 'ignore' })
}

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

/** 범례가 5초 동안 3번 이상 바뀌는 때가 올 때까지 기다린다. */
async function waitForLive(page, timeoutMs) {
  const until = Date.now() + timeoutMs
  while (Date.now() < until) {
    if ((await countLegendChanges(page, 5000)) >= 3) return true
  }
  return false
}

// 화면이 열리기 전에 넣는 감시 코드. /ws 소켓이 만들어진 때·닫힌 때·받은 메시지,
// `[stream] reconnect` 로그, /api 요청을 window.__ws에 모은다. browser.mjs는 console.error만 모으기 때문이다.
const WATCH = `(() => {
  const w = window.__ws = { created: [], closed: [], pings: 0, lastMessageAt: 0, reconnects: [], fetches: [] }
  const Orig = window.WebSocket
  window.WebSocket = class extends Orig {
    constructor(...args) {
      super(...args)
      if (!String(args[0]).endsWith('/ws')) return
      w.created.push(Date.now())
      // stream.ts의 onmessage보다 먼저 등록되므로 먼저 불린다.
      this.addEventListener('message', (ev) => {
        w.lastMessageAt = Date.now()
        if (ev.data === '{"type":"ping"}') w.pings++
      })
      this.addEventListener('close', () => w.closed.push(Date.now()))
    }
  }
  const info = console.info.bind(console)
  console.info = (...args) => {
    if (args[0] === '[stream] reconnect') {
      const at = Date.now()
      w.reconnects.push({ reason: args[1], at, sinceLast: at - w.lastMessageAt })
    }
    info(...args)
  }
  const origFetch = window.fetch.bind(window)
  window.fetch = (input, init) => {
    const url = String(input instanceof Request ? input.url : input)
    if (url.includes('/api/')) w.fetches.push({ url, at: Date.now() })
    return origFetch(input, init)
  }
})()`

const snapshot = (page) => page.evaluate('JSON.parse(JSON.stringify(window.__ws))')
const latestCandles = (f) => f.url.includes('/api/candles') && !f.url.includes('to=')
const markets = (f) => f.url.includes('/api/markets')

await runScenario(import.meta, async ({ page, step, mode }) => {
  const out = join(import.meta.dirname, 'shots', mode)
  mkdirSync(out, { recursive: true })
  const record = (name, lines) => writeFileSync(join(out, `${name}.txt`), lines.join('\n') + '\n')
  const t = (ms, base) => `${ms - base}ms`

  await step('first-screen', async () => {
    await page.send('Page.addScriptToEvaluateOnNewDocument', { source: WATCH })
    await page.goto(BASE)
    await page.waitFor('.market-row')
    await page.waitFor('.legend dd')
    const rows = await page.evaluate(`document.querySelectorAll('.market-row').length`)
    expect(rows > 10, `종목 목록이 ${rows}줄뿐이다`)
    const w = await snapshot(page)
    expect(w.created.length === 1, `웹소켓이 ${w.created.length}개 만들어졌다`)
  })

  await step('idle-ping', async () => {
    // 구독 없이 /ws에 붙으면 보낼 데이터가 없으니 ping만 와야 한다.
    const got = []
    const opened = await new Promise((resolve, reject) => {
      const ws = new WebSocket(WS_URL)
      let openedAt
      ws.onopen = () => { openedAt = Date.now() }
      ws.onmessage = (ev) => got.push({ at: Date.now(), data: String(ev.data) })
      ws.onerror = () => reject(new Error(`${WS_URL}에 붙지 못했다`))
      setTimeout(() => { ws.close(); resolve(openedAt) }, 6000)
    })
    const pings = got.filter((m) => m.data === '{"type":"ping"}')
    const gaps = got.slice(1).map((m, i) => m.at - got[i].at)
    record('02-idle-ping', [
      `열린 시각 기준 받은 메시지: ${got.map((m) => `${t(m.at, opened)} ${m.data}`).join(', ')}`,
      `ping ${pings.length}개, 메시지 사이 간격: ${gaps.join(', ')}ms`,
    ])
    expect(pings.length >= 4, `6초 동안 ping을 ${pings.length}개 받았다`)
    expect(pings.length === got.length, `ping이 아닌 메시지가 왔다: ${got.map((m) => m.data).join(', ')}`)
    expect(got[0].at - opened <= 1500, `첫 ping이 열린 뒤 ${got[0].at - opened}ms에 왔다`)
    expect(gaps.every((g) => g <= 1500), `간격이 1.5초를 넘었다: ${gaps.join(', ')}ms`)
  }, { afterOnly: true, shot: false })

  await step('steady-2min', async () => {
    const before = await snapshot(page)
    const changes = await countLegendChanges(page, 120_000)
    const after = await snapshot(page)
    const reconnects = after.reconnects.length - before.reconnects.length
    const sockets = after.created.length - before.created.length
    record('03-steady-2min', [
      `2분 동안 재연결 로그 ${reconnects}회, 새 웹소켓 ${sockets}개, 범례 변화 ${changes}번, 받은 ping ${after.pings - before.pings}개`,
    ])
    expect(reconnects === 0, `정상인데 재연결을 ${reconnects}번 했다: ${JSON.stringify(after.reconnects.slice(before.reconnects.length))}`)
    expect(sockets === 0, `정상인데 웹소켓이 ${sockets}개 새로 생겼다`)
    expect(changes >= 60, `2분 동안 범례가 ${changes}번 바뀌었다`)
  }, { afterOnly: true })

  await step('half-open-pause', async () => {
    const base = await snapshot(page)
    const pausedAt = Date.now()
    gateway('pause')
    await sleep(8000)
    gateway('unpause')
    const unpausedAt = Date.now()
    const live = await waitForLive(page, 15_000)
    const w = await snapshot(page)
    const reconnects = w.reconnects.slice(base.reconnects.length)
    const created = w.created.slice(base.created.length)
    const refetched = w.fetches.filter((f) => f.at > pausedAt && latestCandles(f))
    record('04-half-open-pause', [
      `멈춘 시각 기준. 풀린 시각: ${t(unpausedAt, pausedAt)}`,
      `재연결 로그: ${reconnects.map((r) => `${t(r.at, pausedAt)} ${r.reason} (마지막 수신 뒤 ${r.sinceLast}ms)`).join(', ') || '없음'}`,
      `새 웹소켓: ${created.map((c) => t(c, pausedAt)).join(', ') || '없음'}`,
      `최신 캔들 재조회(/api/candles, to 없음): ${refetched.map((f) => t(f.at, pausedAt)).join(', ') || '없음'}`,
      `풀린 뒤 15초 안에 실시간 갱신 재개: ${live ? '예' : '아니오'}`,
    ])
    if (mode === 'before') {
      const during = created.filter((c) => c < unpausedAt)
      expect(during.length === 0, `구현 전인데 멈춘 동안 웹소켓이 ${during.length}개 생겼다`)
      expect(live, '풀린 뒤 범례가 다시 바뀌지 않는다')
      return
    }
    expect(reconnects.length > 0, '멈춘 동안 재연결 로그가 없다')
    expect(reconnects[0].reason === 'stale', `첫 재연결 이유가 ${reconnects[0].reason}`)
    expect(reconnects[0].sinceLast >= 3000 && reconnects[0].sinceLast <= 3600, `마지막 수신 뒤 ${reconnects[0].sinceLast}ms에 판정했다`)
    const duringPause = reconnects.filter((r) => r.at < unpausedAt)
    expect(duringPause.every((r) => r.reason === 'stale' || r.reason === 'connect-timeout'), `멈춘 동안 이유가 ${duringPause.map((r) => r.reason).join(', ')}`)
    expect(live, '풀린 뒤 15초 안에 범례가 다시 바뀌지 않는다')
    expect(refetched.length > 0, '재연결 뒤 최신 캔들을 다시 받지 않았다')
  })

  await step('gateway-restart', async () => {
    if (GATEWAY_PID) {
      record('05-gateway-restart', ['GATEWAY_PID 모드라 건너뛰었다(재시작 불가)'])
      return
    }
    const base = await snapshot(page)
    const startedAt = Date.now()
    gateway('restart')
    const live = await waitForLive(page, 60_000)
    const w = await snapshot(page)
    const closedAt = w.closed.slice(base.closed.length)[0]
    const created = w.created.slice(base.created.length)
    const reconnects = w.reconnects.slice(base.reconnects.length)
    const firstRetry = created.find((c) => c >= closedAt)
    const lastOpen = created.at(-1)
    const marketsAgain = w.fetches.filter((f) => f.at > startedAt && markets(f))
    const candlesAgain = w.fetches.filter((f) => f.at > startedAt && latestCandles(f))
    const pricesChanging = await page.evaluate(`(async () => {
      const read = () => [...document.querySelectorAll('.market-row')].slice(0, 20).map((e) => e.textContent).join('|')
      const a = read(); await new Promise((r) => setTimeout(r, 5000)); return read() !== a
    })()`)
    record('05-gateway-restart', [
      `재시작 명령 기준. 끊긴 시각: ${closedAt ? t(closedAt, startedAt) : '없음'}`,
      `끊긴 뒤 첫 재시도까지: ${firstRetry ? `${firstRetry - closedAt}ms` : '없음'}`,
      `재연결 로그: ${reconnects.map((r) => `${t(r.at, startedAt)} ${r.reason}`).join(', ') || '없음'}`,
      `새 웹소켓: ${created.map((c) => t(c, startedAt)).join(', ') || '없음'}`,
      `종목 목록 재조회: ${marketsAgain.map((f) => t(f.at, startedAt)).join(', ') || '없음'}`,
      `최신 캔들 재조회: ${candlesAgain.map((f) => t(f.at, startedAt)).join(', ') || '없음'}`,
      `60초 안에 실시간 갱신 재개: ${live ? '예' : '아니오'}, 종목 목록 가격 변화: ${pricesChanging ? '예' : '아니오'}`,
    ])
    expect(closedAt, '재시작했는데 웹소켓이 닫히지 않았다')
    expect(live, '60초 안에 범례가 다시 바뀌지 않는다')
    if (mode === 'before') {
      expect(firstRetry - closedAt >= 900, `구현 전인데 첫 재시도가 ${firstRetry - closedAt}ms 뒤였다`)
      expect(marketsAgain.length === 0, '구현 전인데 종목 목록을 다시 받았다')
      return
    }
    expect(reconnects[0]?.reason === 'closed', `첫 재연결 이유가 ${reconnects[0]?.reason}`)
    expect(firstRetry - closedAt <= 200, `끊긴 뒤 ${firstRetry - closedAt}ms 뒤에 다시 시도했다`)
    expect(marketsAgain.some((f) => f.at >= lastOpen), '다시 붙은 뒤 종목 목록을 다시 받지 않았다')
    expect(candlesAgain.some((f) => f.at >= lastOpen), '다시 붙은 뒤 최신 캔들을 다시 받지 않았다')
    expect(pricesChanging, '종목 목록 가격이 다시 바뀌지 않는다')
  })

  await step('network-online', async () => {
    await page.send('Network.enable')
    const base = await snapshot(page)
    await page.send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })
    await sleep(2000)
    await page.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })
    const onlineAt = Date.now()
    await sleep(1000)
    let w = await snapshot(page)
    let dispatched = false
    if (!w.reconnects.slice(base.reconnects.length).some((r) => r.reason === 'online')) {
      // 헤드리스 Chrome이 online 이벤트를 내지 않으면 직접 낸다.
      dispatched = true
      await page.evaluate(`window.dispatchEvent(new Event('online'))`)
      await sleep(1000)
      w = await snapshot(page)
    }
    const reconnects = w.reconnects.slice(base.reconnects.length)
    const online = reconnects.find((r) => r.reason === 'online')
    const created = w.created.slice(base.created.length)
    const live = await waitForLive(page, 10_000)
    record('06-network-online', [
      `온라인으로 바꾼 시각 기준. online 이벤트를 직접 냈나: ${dispatched ? '예(헤드리스가 내지 않음)' : '아니오'}`,
      `재연결 로그: ${reconnects.map((r) => `${t(r.at, onlineAt)} ${r.reason}`).join(', ') || '없음'}`,
      `새 웹소켓: ${created.map((c) => t(c, onlineAt)).join(', ') || '없음'}`,
      `10초 안에 실시간 갱신 재개: ${live ? '예' : '아니오'}`,
    ])
    expect(online, 'online으로 다시 연결한 기록이 없다')
    expect(created.some((c) => c >= online.at), 'online 뒤 새 웹소켓이 없다')
    expect(live, '10초 안에 범례가 다시 바뀌지 않는다')
  }, { afterOnly: true })

  await step('no-page-errors', async () => {
    expect(page.errors.length === 0, `화면 오류: ${page.errors.join(' / ')}`)
  }, { shot: false })
})
