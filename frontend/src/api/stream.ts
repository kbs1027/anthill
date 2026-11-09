import type { StreamCandleItem, StreamTickerItem, UnitCode } from './types'

/** 브라우저 WebSocket 중 이 클라이언트가 쓰는 부분. 테스트는 가짜를 넣는다. */
export interface SocketLike {
  readyState: number
  send(data: string): void
  close(): void
  onopen: (() => void) | null
  onmessage: ((ev: { data: string }) => void) | null
  onclose: (() => void) | null
  onerror: (() => void) | null
}

export type StreamOptions = { createSocket?: (url: string) => SocketLike; minDelayMs?: number; maxDelayMs?: number }

const OPEN = 1

/**
 * `/ws` 연결 하나. 구독 상태를 기억해 두었다가, 끊기면 1초→2초→…→30초 간격으로 다시 붙고 구독을 다시 보낸다.
 * 다시 붙으면 onReconnect 리스너를 부른다: 끊긴 동안 놓친 갱신은 REST로 다시 받아야 한다.
 */
export class StreamClient {
  private readonly url: string
  private readonly createSocket: (url: string) => SocketLike
  private readonly minDelay: number
  private readonly maxDelay: number
  private delay: number
  private socket: SocketLike | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private closed = false
  private everOpened = false
  private readonly subs = new Map<string, { market: string; unit: UnitCode }>()
  private tickers = false
  private readonly candleListeners = new Set<(items: StreamCandleItem[]) => void>()
  private readonly tickerListeners = new Set<(items: StreamTickerItem[]) => void>()
  private readonly reconnectListeners = new Set<() => void>()

  constructor(url: string, options: StreamOptions = {}) {
    this.url = url
    this.createSocket = options.createSocket ?? ((u) => new WebSocket(u) as unknown as SocketLike)
    this.minDelay = options.minDelayMs ?? 1_000
    this.maxDelay = options.maxDelayMs ?? 30_000
    this.delay = this.minDelay
    this.connect()
  }

  subscribe(market: string, unit: UnitCode) {
    const key = `${market}|${unit}`
    if (this.subs.has(key)) return
    this.subs.set(key, { market, unit })
    this.send({ op: 'subscribe', market, unit })
  }

  unsubscribe(market: string, unit: UnitCode) {
    if (!this.subs.delete(`${market}|${unit}`)) return
    this.send({ op: 'unsubscribe', market, unit })
  }

  subscribeTickers() {
    if (this.tickers) return
    this.tickers = true
    this.send({ op: 'subscribeTickers' })
  }

  onCandles(cb: (items: StreamCandleItem[]) => void): () => void {
    this.candleListeners.add(cb)
    return () => {
      this.candleListeners.delete(cb)
    }
  }

  onTickers(cb: (items: StreamTickerItem[]) => void): () => void {
    this.tickerListeners.add(cb)
    return () => {
      this.tickerListeners.delete(cb)
    }
  }

  onReconnect(cb: () => void): () => void {
    this.reconnectListeners.add(cb)
    return () => {
      this.reconnectListeners.delete(cb)
    }
  }

  close() {
    this.closed = true
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    const s = this.socket
    this.socket = null
    s?.close()
  }

  private connect() {
    const s = this.createSocket(this.url)
    this.socket = s
    s.onopen = () => {
      const again = this.everOpened
      this.everOpened = true
      this.delay = this.minDelay
      for (const { market, unit } of this.subs.values()) this.send({ op: 'subscribe', market, unit })
      if (this.tickers) this.send({ op: 'subscribeTickers' })
      if (again) this.reconnectListeners.forEach((cb) => cb())
    }
    s.onmessage = (ev) => this.dispatch(ev.data)
    s.onerror = () => {} // 이어서 onclose가 온다
    s.onclose = () => {
      if (this.socket !== s) return
      this.socket = null
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect() {
    if (this.closed || this.timer) return
    const wait = this.delay
    this.delay = Math.min(this.delay * 2, this.maxDelay)
    this.timer = setTimeout(() => {
      this.timer = null
      this.connect()
    }, wait)
  }

  private send(msg: object) {
    const s = this.socket
    if (s && s.readyState === OPEN) s.send(JSON.stringify(msg))
  }

  private dispatch(data: string) {
    let msg: { type?: string; items?: unknown; message?: string }
    try {
      msg = JSON.parse(data)
    } catch {
      return
    }
    if (msg.type === 'candles') this.candleListeners.forEach((cb) => cb(msg.items as StreamCandleItem[]))
    else if (msg.type === 'tickers') this.tickerListeners.forEach((cb) => cb(msg.items as StreamTickerItem[]))
    else if (msg.type === 'error') console.warn('stream error:', msg.message)
  }
}

let shared: StreamClient | null = null

/** 화면 전체가 함께 쓰는 연결(같은 출처의 /ws). */
export function sharedStream(): StreamClient {
  if (!shared) {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
    shared = new StreamClient(`${scheme}//${location.host}/ws`)
  }
  return shared
}
