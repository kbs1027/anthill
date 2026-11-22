import { afterEach, beforeEach, expect, test, vi, type MockInstance } from 'vitest'
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

let info: MockInstance<typeof console.info>

beforeEach(() => {
  sockets = []
  vi.useFakeTimers()
  // 재연결 로그(`[stream] reconnect`)를 확인하고, 테스트 출력에는 찍히지 않게 한다.
  info = vi.spyOn(console, 'info').mockImplementation(() => {})
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

/** 가장 최근 소켓. */
const latest = () => sockets[sockets.length - 1]

/** 지금 예약된 재연결이 새 소켓을 만들 때까지 걸린 시간(ms). 0초 예약부터 먼저 돌린다. */
function waitForNextSocket(step = 100): number {
  const before = sockets.length
  let waited = 0
  vi.advanceTimersByTime(0)
  while (sockets.length === before) {
    vi.advanceTimersByTime(step)
    waited += step
  }
  return waited
}

// 참고: 가짜 타이머는 타이머 콜백 안에서 예약한 0초 타이머를 1ms 뒤로 미룬다(무한 반복 방지).
// 그래서 무소식 판정·연결 제한처럼 타이머 안에서 일어나는 즉시 재시도는 1ms를 더 돌려야 새 소켓이 생긴다.

/** 로그에 남은 재연결 이유들. */
const reasons = () => info.mock.calls.filter((c) => c[0] === '[stream] reconnect').map((c) => c[1])

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
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(0) // 첫 재시도는 기다리지 않는다
  expect(sockets).toHaveLength(2)
  sockets[1].open()
  expect(sockets[1].sent).toEqual([
    { op: 'subscribe', market: 'UPBIT:KRW-BTC', unit: '1m' },
    { op: 'subscribeTickers' },
  ])
  expect(reconnected).toHaveBeenCalledTimes(1)
})

test('끊기면 첫 재시도는 즉시, 그 뒤로는 1초부터 30초까지 두 배씩 기다리고, 첫 메시지를 받으면 처음으로 돌아간다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 1 })
  const waits: number[] = []
  for (let i = 0; i < 8; i++) {
    latest().drop()
    waits.push(waitForNextSocket())
  }
  expect(waits).toEqual([0, 1000, 2000, 4000, 8000, 16000, 30000, 30000])
  // 열린 뒤 첫 메시지를 받으면 다시 즉시 재시도로 돌아간다.
  latest().open()
  latest().receive({ type: 'ping' })
  latest().drop()
  const before = sockets.length
  vi.advanceTimersByTime(0)
  expect(sockets).toHaveLength(before + 1)
  client.close()
})

test('열린 뒤 3초 동안 아무것도 안 오면 그 소켓을 버리고 즉시 다시 연결한다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  client.subscribe('UPBIT:KRW-BTC', '1m')
  const reconnected = vi.fn()
  client.onReconnect(reconnected)
  const old = sockets[0]
  old.open()
  vi.advanceTimersByTime(2999)
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(1) // 3초: 판정
  expect(reasons()).toEqual(['stale'])
  vi.advanceTimersByTime(1) // 즉시 재시도(가짜 타이머라 1ms)
  expect(sockets).toHaveLength(2)
  // 옛 소켓은 닫히고 핸들러가 떼어져 있다.
  expect(old.readyState).toBe(3)
  expect(old.onmessage).toBeNull()
  expect(old.onclose).toBeNull()
  latest().open()
  expect(latest().sent).toEqual([{ op: 'subscribe', market: 'UPBIT:KRW-BTC', unit: '1m' }])
  expect(reconnected).toHaveBeenCalledTimes(1)
  client.close()
})

test('ping이 1초마다 오면 10초가 지나도 다시 연결하지 않고, ping은 캔들·시세 리스너에 전달하지 않는다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  const candles = vi.fn()
  const tickers = vi.fn()
  client.onCandles(candles)
  client.onTickers(tickers)
  sockets[0].open()
  for (let i = 0; i < 10; i++) {
    vi.advanceTimersByTime(1000)
    sockets[0].receive({ type: 'ping' })
  }
  expect(sockets).toHaveLength(1)
  expect(reasons()).toEqual([])
  expect(candles).not.toHaveBeenCalled()
  expect(tickers).not.toHaveBeenCalled()
  client.close()
})

test('마지막 수신 뒤 2.9초에는 다시 연결하지 않고, 3.5초 안에는 다시 연결한다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  sockets[0].open()
  vi.advanceTimersByTime(200)
  sockets[0].receive({ type: 'ping' }) // 마지막 수신: 열린 뒤 200ms
  vi.advanceTimersByTime(2900)
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(600) // 마지막 수신 뒤 3.5초. 확인이 0.5초마다라 3.0~3.5초 사이에 판정한다
  expect(sockets).toHaveLength(2)
  expect(reasons()).toEqual(['stale'])
  client.close()
})

test('메시지 없이 열렸다 닫히기를 되풀이하면 즉시 재시도는 한 번뿐이고 그다음은 1초, 2초로 늘어난다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 1 })
  const waits: number[] = []
  for (let i = 0; i < 3; i++) {
    latest().open()
    latest().drop()
    waits.push(waitForNextSocket())
  }
  expect(waits).toEqual([0, 1000, 2000])
  client.close()
})

test('무작위 값이 0이면 대기가 절반이 된다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 0 })
  const waits: number[] = []
  for (let i = 0; i < 4; i++) {
    latest().drop()
    waits.push(waitForNextSocket())
  }
  expect(waits).toEqual([0, 500, 1000, 2000])
  client.close()
})

test('5초 동안 열리지 않으면 그 시도를 버리고 다음 시도로 넘어간다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  vi.advanceTimersByTime(4999)
  expect(sockets).toHaveLength(1)
  vi.advanceTimersByTime(1) // 5초: 포기
  vi.advanceTimersByTime(1) // 즉시 재시도(가짜 타이머라 1ms)
  expect(sockets).toHaveLength(2)
  expect(sockets[0].readyState).toBe(3)
  expect(reasons()).toEqual(['connect-timeout'])
  client.close()
})

test('reconnectNow()는 대기를 건너뛰고 지금 소켓을 버린 뒤 바로 연결한다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 1 })
  // 두 번 실패해 1초 대기 중으로 만든다.
  latest().drop()
  waitForNextSocket()
  latest().drop()
  expect(sockets).toHaveLength(2)
  client.reconnectNow()
  expect(sockets).toHaveLength(3)
  expect(reasons().at(-1)).toBe('online')
  vi.advanceTimersByTime(1000) // 취소된 대기는 소켓을 더 만들지 않는다
  expect(sockets).toHaveLength(3)
  // 열려 있는 소켓도 버리고 새로 연결한다.
  const open = latest()
  open.open()
  client.reconnectNow()
  expect(open.readyState).toBe(3)
  expect(sockets).toHaveLength(4)
  client.close()
})

test('checkNow()는 대기 중이면 바로 연결하고, 열려 있으면 3초 넘게 무소식일 때만 다시 연결하며, 연결 중이면 그대로 둔다', () => {
  // 0.5초 확인이 먼저 판정하지 않도록 확인 간격을 길게 둔다.
  const client = new StreamClient('ws://test/ws', { createSocket: create, random: () => 1, checkMs: 60_000 })
  // 연결 중: 그대로
  client.checkNow()
  expect(sockets).toHaveLength(1)
  // 대기 중: 바로 연결
  latest().drop()
  waitForNextSocket()
  latest().drop()
  expect(sockets).toHaveLength(2)
  client.checkNow()
  expect(sockets).toHaveLength(3)
  // 열려 있고 3초 안: 그대로(첫 메시지를 받아 정상 연결이 된 상태)
  latest().open()
  latest().receive({ type: 'ping' })
  vi.advanceTimersByTime(2999)
  client.checkNow()
  vi.advanceTimersByTime(0)
  expect(sockets).toHaveLength(3)
  // 열려 있고 3초 넘음: stale로 다시 연결
  vi.advanceTimersByTime(1)
  client.checkNow()
  vi.advanceTimersByTime(0)
  expect(sockets).toHaveLength(4)
  expect(reasons().at(-1)).toBe('stale')
  client.close()
})

test('close() 뒤에는 무소식 판정·연결 제한·재연결이 모두 멈추고 reconnectNow()·checkNow()도 아무것도 하지 않는다', () => {
  const opened = new StreamClient('ws://test/ws', { createSocket: create })
  sockets[0].open()
  opened.close()
  const connecting = new StreamClient('ws://test/ws', { createSocket: create })
  connecting.close()
  vi.advanceTimersByTime(60_000)
  opened.reconnectNow()
  opened.checkNow()
  connecting.reconnectNow()
  connecting.checkNow()
  vi.advanceTimersByTime(60_000)
  expect(sockets).toHaveLength(2)
  expect(reasons()).toEqual([])
})

test('버린 소켓에 늦게 onclose·onmessage가 와도 재연결을 더 예약하거나 리스너를 부르지 않는다', () => {
  const client = new StreamClient('ws://test/ws', { createSocket: create })
  const candles = vi.fn()
  client.onCandles(candles)
  const old = sockets[0]
  old.open()
  const lateClose = old.onclose
  const lateMessage = old.onmessage
  vi.advanceTimersByTime(3001) // stale로 버리고 즉시 재시도
  expect(sockets).toHaveLength(2)
  latest().open()
  const items = [{ market: 'UPBIT:KRW-BTC', unit: '1m', state: 'live', time: '2026-11-22T10:20:00Z', candle: null }]
  lateMessage?.({ data: JSON.stringify({ type: 'candles', items }) })
  lateClose?.()
  vi.advanceTimersByTime(1000) // 새 소켓이 무소식으로 판정되기 전까지만 본다
  expect(candles).not.toHaveBeenCalled()
  expect(sockets).toHaveLength(2)
  expect(reasons()).toEqual(['stale'])
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
