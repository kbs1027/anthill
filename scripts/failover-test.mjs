#!/usr/bin/env node
// 실제로 장애를 일으켜 화면이 시세를 못 받은 시간을 잰다. 화면과 똑같은 StreamClient 코드로 3000번(nginx)에 붙는다.
// 사용: node scripts/failover-test.mjs [--only gateway-kill,marketdata-kill] [--clients 6] [--watch 12]
// 공백 = 장애 시작 전후로 연속한 시세 메시지 사이의 최대 간격. 시세는 1초마다 나가므로 정상값은 1초 남짓이다.
import { exec } from 'node:child_process'
import { promisify } from 'node:util'
import { StreamClient } from '../frontend/src/api/stream.ts'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []))
const URL_WS = args.url ?? 'ws://localhost:3000/ws'
const CLIENTS = Number(args.clients ?? 6)
const WATCH_S = Number(args.watch ?? 12)
const only = args.only?.split(',')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// 비동기로 부른다: 동기 실행은 그동안 이 프로세스의 수신을 막아 없는 공백을 만든다.
const run = promisify(exec)
const sh = async (c) => (await run(c)).stdout.trim()
const docker = (verb, name) => sh(`docker ${verb} anthill-${name}-1`)
const leader = () => sh(`docker exec anthill-db-1 psql -U anthill -d anthill -Atc "select holder from leader_lease where name='marketdata'"`)
const pauseStream = (port, seconds) => sh(`curl -fsS -X POST "http://localhost:${port}/admin/stream/pause?seconds=${seconds}"`)

class Viewer {
  constructor() {
    this.data = []
    this.statuses = []
    this.client = new StreamClient(URL_WS, { createSocket: (u) => new WebSocket(u), routes: ['a', 'b'] })
    this.client.subscribe('UPBIT:KRW-BTC', '1m')
    this.client.subscribeTickers()
    this.client.onCandles(() => this.data.push(Date.now()))
    this.client.onTickers(() => this.data.push(Date.now()))
    this.client.onStatus((s) => this.statuses.push([Date.now(), s]))
  }
  maxGap(from, to) {
    const ts = [from, ...this.data.filter((t) => t >= from && t <= to), to]
    return ts.slice(1).reduce((m, t, i) => Math.max(m, t - ts[i]), 0)
  }
  /** [from, to]에서 live가 아니었던 시간(ms). */
  notLive(from, to) {
    let state = [...this.statuses].reverse().find(([t]) => t <= from)?.[1] ?? 'live'
    let at = from
    let sum = 0
    for (const [t, s] of this.statuses.filter(([t]) => t > from && t <= to)) {
      if (state !== 'live') sum += t - at
      state = s
      at = t
    }
    return state !== 'live' ? sum + to - at : sum
  }
}

const scenarios = [
  { name: 'gateway-a-kill', hit: () => docker('kill', 'edge-gateway-a'), heal: () => docker('start', 'edge-gateway-a') },
  { name: 'gateway-b-kill', hit: () => docker('kill', 'edge-gateway-b'), heal: () => docker('start', 'edge-gateway-b') },
  { name: 'gateway-a-freeze', hit: () => docker('pause', 'edge-gateway-a'), heal: () => docker('unpause', 'edge-gateway-a') },
  {
    name: 'marketdata-leader-kill',
    async hit() { this.who = await leader(); return docker('kill', `marketdata-${this.who}`) },
    heal() { return docker('start', `marketdata-${this.who}`) },
  },
  {
    name: 'marketdata-leader-freeze',
    async hit() { this.who = await leader(); return docker('pause', `marketdata-${this.who}`) },
    heal() { return docker('unpause', `marketdata-${this.who}`) },
  },
  { name: 'adapter-a-kill', hit: () => docker('kill', 'upbit-adapter-a'), heal: () => docker('start', 'upbit-adapter-a') },
  { name: 'adapter-b-freeze', hit: () => docker('pause', 'upbit-adapter-b'), heal: () => docker('unpause', 'upbit-adapter-b') },
  { name: 'upbit-socket-a-drop', hit: () => pauseStream(8082, 8), heal: () => {} },
  { name: 'upbit-both-sockets-drop', hit: () => Promise.all([pauseStream(8082, 5), pauseStream(18082, 5)]), heal: () => {} },
]

const viewers = Array.from({ length: CLIENTS }, () => new Viewer())
await sleep(5000)
const live = () => viewers.every((v) => v.client.status === 'live')
if (!live()) console.log('warning: not every viewer is live yet:', viewers.map((v) => v.client.status).join(','))

const rows = []
for (const s of scenarios.filter((x) => !only || only.includes(x.name))) {
  const baseFrom = Date.now()
  await sleep(8000)
  const baseline = Math.max(...viewers.map((v) => v.maxGap(baseFrom, Date.now())))
  const at = Date.now()
  await s.hit()
  await sleep(WATCH_S * 1000)
  const end = Date.now()
  const gaps = viewers.map((v) => v.maxGap(at - 1000, end))
  const off = viewers.map((v) => v.notLive(at, end))
  await s.heal()
  rows.push({ scenario: s.name, baselineMs: baseline, worstGapMs: Math.max(...gaps), medianGapMs: gaps.toSorted((a, b) => a - b)[gaps.length >> 1], worstNotLiveMs: Math.max(...off) })
  console.log(JSON.stringify(rows.at(-1)))
  // 되살아나 다시 두 대가 될 때까지 기다린다
  const until = Date.now() + 90_000
  await sleep(25_000)
  while (!live() && Date.now() < until) await sleep(1000)
}
console.table(rows)
viewers.forEach((v) => v.client.close())
process.exit(0)
