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

beforeEach(() => {
  sockets = []
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

test('stream resubscribes after reconnect', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
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
  vi.advanceTimersByTime(999)
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

test('reconnect delay doubles up to 30s and resets after a successful open', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  const waits: number[] = []
  for (let i = 0; i < 7; i++) {
    sockets[sockets.length - 1].drop()
    const before = sockets.length
    let waited = 0
    while (sockets.length === before) {
      vi.advanceTimersByTime(1000)
      waited += 1000
    }
    waits.push(waited)
  }
  expect(waits).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000])
  sockets[sockets.length - 1].open()
  sockets[sockets.length - 1].drop()
  vi.advanceTimersByTime(1000)
  expect(sockets[sockets.length - 1].readyState).toBe(0)
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
