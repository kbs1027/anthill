import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { StreamClient, type SocketLike } from './stream'

class FakeSocket implements SocketLike {
  readyState = 0
  sent: unknown[] = []
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  url: string
  constructor(url: string) {
    this.url = url
  }
  send(data: string) {
    if (this.readyState !== 1) throw new Error('not open')
    this.sent.push(JSON.parse(data))
  }
  close() {
    this.drop()
  }
  open() {
    this.readyState = 1
    this.onopen?.()
  }
  drop() {
    if (this.readyState === 3) return
    this.readyState = 3
    this.onclose?.()
  }
  receive(msg: unknown) {
    this.onmessage?.({ data: JSON.stringify(msg) })
  }
}

let sockets: FakeSocket[]
const create = (url: string) => {
  const s = new FakeSocket(url)
  sockets.push(s)
  return s
}

const opts = { createSocket: create, random: () => 1 }

beforeEach(() => {
  sockets = []
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

test('stream resubscribes after reconnect', () => {
  const client = new StreamClient('ws://test/ws', opts)
  client.subscribe('UPBIT:KRW-BTC', '1m')
  client.subscribeTickers()
  expect(sockets).toHaveLength(1)
  sockets[0].open()
  expect(sockets[0].sent).toEqual([
    { op: 'subscribe', market: 'UPBIT:KRW-BTC', unit: '1m' },
    { op: 'subscribeTickers' },
  ])

  const reconnected = vi.fn()
  client.onReconnect(reconnected)
  sockets[0].drop()
  vi.advanceTimersByTime(99)
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(1)
  expect(sockets).toHaveLength(2)
  sockets[1].open()
  expect(sockets[1].sent).toEqual([
    { op: 'subscribe', market: 'UPBIT:KRW-BTC', unit: '1m' },
    { op: 'subscribeTickers' },
  ])
  expect(reconnected).toHaveBeenCalledTimes(1)
})

test('reconnect delay doubles up to 2s', () => {
  const client = new StreamClient('ws://test/ws', opts)
  const waits: number[] = []
  for (let i = 0; i < 7; i++) {
    sockets[sockets.length - 1].drop()
    const before = sockets.length
    let waited = 0
    while (sockets.length === before) {
      vi.advanceTimersByTime(100)
      waited += 100
    }
    waits.push(waited)
  }
  expect(waits).toEqual([100, 200, 400, 800, 1600, 2000, 2000])
  client.close()
})

test('a connection that opens and drops at once keeps backing off', () => {
  const client = new StreamClient('ws://test/ws', opts)
  sockets[0].drop()
  vi.advanceTimersByTime(100)
  sockets[1].open()
  sockets[1].drop()
  vi.advanceTimersByTime(199)
  expect(sockets).toHaveLength(2)
  vi.advanceTimersByTime(1)
  expect(sockets).toHaveLength(3)
  client.close()
})

test('a connection that stayed up resets the delay', () => {
  const client = new StreamClient('ws://test/ws', opts)
  sockets[0].drop()
  vi.advanceTimersByTime(100)
  sockets[1].drop()
  vi.advanceTimersByTime(200)
  sockets[2].open()
  for (let i = 0; i < 11; i++) {
    vi.advanceTimersByTime(1000)
    sockets[2].receive({ type: 'hb', feed: 'live' })
  }
  sockets[2].drop()
  vi.advanceTimersByTime(100)
  expect(sockets).toHaveLength(4)
  client.close()
})

test('jitter spreads the wait between half and all of the delay', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 0 })
  sockets[0].drop()
  vi.advanceTimersByTime(49)
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(1)
  expect(sockets).toHaveLength(2)
  client.close()
})

test('a silent socket is abandoned within 1.5s and replaced', () => {
  const client = new StreamClient('ws://test/ws', opts)
  sockets[0].open()
  for (let i = 0; i < 3; i++) {
    vi.advanceTimersByTime(1000)
    sockets[0].receive({ type: 'hb', feed: 'live' })
  }
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(1750)
  expect(sockets[0].readyState).toBe(3)
  vi.advanceTimersByTime(100)
  expect(sockets).toHaveLength(2)
  client.close()
})

test('a connection stuck while opening is abandoned too', () => {
  const client = new StreamClient('ws://test/ws', opts)
  vi.advanceTimersByTime(1750)
  expect(sockets[0].readyState).toBe(3)
  vi.advanceTimersByTime(100)
  expect(sockets).toHaveLength(2)
  client.close()
})

test('a late late-firing watchdog after the page stalled does not drop a healthy socket', () => {
  const client = new StreamClient('ws://test/ws', opts)
  sockets[0].open()
  sockets[0].receive({ type: 'hb', feed: 'live' })
  vi.setSystemTime(Date.now() + 5000) // 화면 스레드가 5초 멈췄다: 타이머보다 시계가 먼저 간다
  vi.advanceTimersByTime(250)
  expect(sockets[0].readyState).toBe(1)
  sockets[0].receive({ type: 'hb', feed: 'live' })
  vi.advanceTimersByTime(250)
  expect(sockets[0].readyState).toBe(1)
  client.close()
})

test('status follows the socket and the feed', () => {
  const client = new StreamClient('ws://test/ws', opts)
  const seen: string[] = []
  client.onStatus((s) => seen.push(s))
  expect(client.status).toBe('reconnecting')
  sockets[0].open()
  sockets[0].receive({ type: 'hb', feed: 'live' })
  sockets[0].receive({ type: 'hb', feed: 'live' })
  sockets[0].receive({ type: 'hb', feed: 'delayed' })
  sockets[0].drop()
  expect(seen).toEqual(['live', 'delayed', 'reconnecting'])
  client.close()
})

test('wake reconnects at once while waiting out a delay', () => {
  const client = new StreamClient('ws://test/ws', opts)
  for (let i = 0; i < 5; i++) {
    sockets[sockets.length - 1].drop()
    vi.advanceTimersByTime(2000)
  }
  const before = sockets.length
  sockets[sockets.length - 1].drop()
  client.wake()
  expect(sockets).toHaveLength(before + 1)
  client.close()
})

test('wake replaces an open socket that has gone quiet', () => {
  const client = new StreamClient('ws://test/ws', opts)
  sockets[0].open()
  vi.setSystemTime(Date.now() + 2000)
  client.wake()
  expect(sockets[0].readyState).toBe(3)
  client.close()
})

test('unsubscribe is sent and not replayed', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  sockets[0].open()
  client.subscribe('UPBIT:KRW-BTC', '1m')
  client.subscribe('UPBIT:KRW-BTC', '1m') // 같은 구독은 한 번만 보낸다
  client.unsubscribe('UPBIT:KRW-BTC', '1m')
  expect(sockets[0].sent).toEqual([
    { op: 'subscribe', market: 'UPBIT:KRW-BTC', unit: '1m' },
    { op: 'unsubscribe', market: 'UPBIT:KRW-BTC', unit: '1m' },
  ])
  sockets[0].drop()
  vi.advanceTimersByTime(1000)
  sockets[1].open()
  expect(sockets[1].sent).toEqual([])
})

test('candles and tickers reach their listeners; close stops reconnecting', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  sockets[0].open()
  const candles = vi.fn()
  const tickers = vi.fn()
  const off = client.onCandles(candles)
  client.onTickers(tickers)
  const items = [{ market: 'UPBIT:KRW-BTC', unit: '1m', state: 'live', time: '2026-11-08T10:20:00Z', candle: null }]
  sockets[0].receive({ type: 'candles', items })
  sockets[0].receive({ type: 'tickers', items: [{ market: 'UPBIT:KRW-BTC', price: '1', changeRate: '0', amount24h: '2' }] })
  sockets[0].receive({ type: 'error', message: 'unknown op' })
  expect(candles).toHaveBeenCalledWith(items)
  expect(tickers).toHaveBeenCalledTimes(1)
  off()
  sockets[0].receive({ type: 'candles', items })
  expect(candles).toHaveBeenCalledTimes(1)
  client.close()
  vi.advanceTimersByTime(60_000)
  expect(sockets).toHaveLength(1)
})

test('after losing a connection the next attempt goes to the other gateway', () => {
  const client = new StreamClient('ws://test/ws', { ...opts, routes: ['a', 'b'] })
  const first = sockets[0].url
  expect(first).toMatch(/\?gw=(a|b)$/)
  sockets[0].open()
  sockets[0].drop()
  vi.advanceTimersByTime(100)
  expect(sockets[1].url).not.toBe(first)
  sockets[1].drop()
  vi.advanceTimersByTime(200)
  expect(sockets[2].url).toBe(first)
  client.close()
})

test('a socket given up for silence also moves to the other gateway', () => {
  const client = new StreamClient('ws://test/ws', { ...opts, routes: ['a', 'b'] })
  const first = sockets[0].url
  sockets[0].open()
  vi.advanceTimersByTime(1750)
  vi.advanceTimersByTime(100)
  expect(sockets[1].url).not.toBe(first)
  client.close()
})

test('the first gateway is picked at random to spread viewers', () => {
  const pick = (random: () => number) => {
    sockets = []
    const client = new StreamClient('ws://test/ws', { createSocket: create, random, routes: ['a', 'b'] })
    client.close()
    return sockets[0].url
  }
  expect(pick(() => 0)).toBe('ws://test/ws?gw=a')
  expect(pick(() => 0.99)).toBe('ws://test/ws?gw=b')
})

test('without routes the url is used as is', () => {
  const client = new StreamClient('ws://test/ws', opts)
  expect(sockets[0].url).toBe('ws://test/ws')
  client.close()
})
