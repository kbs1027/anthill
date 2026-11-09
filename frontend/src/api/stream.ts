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

export type StreamOptions = {
  createSocket?: (url: string) => SocketLike
  minDelayMs?: number
  maxDelayMs?: number
  /** 이만큼 아무 메시지도 없으면 연결이 죽은 것으로 본다. 게이트웨이는 0.5초마다 생존 신호를 보낸다. */
  silenceMs?: number
  /** 연결이 이만큼 유지됐을 때만 재연결 대기를 처음으로 돌린다. */
  stableMs?: number
  random?: () => number
  /** 게이트웨이 이름들. 주면 `?gw=이름`으로 붙고, 연결을 잃을 때마다 다음 게이트웨이로 넘어간다. */
  routes?: string[]
}

/** 실시간: 시세가 들어오는 중. 재연결 중: 게이트웨이와 끊김. 시세 지연: 게이트웨이는 살아 있으나 시세 원천이 멈춤. */
export type ConnectionStatus = 'live' | 'reconnecting' | 'delayed'

const OPEN = 1
const WATCH_MS = 250
/** 감시 타이머가 이보다 늦게 돌았으면 화면 스레드가 멈췄던 것이다. 쌓인 메시지를 먼저 처리하도록 이번 판정은 건너뛴다. */
const STALLED_MS = 1_500
const WAKE_QUIET_MS = 1_000

/**
 * `/ws` 연결 하나. 구독 상태를 기억해 두었다가, 끊기면 0.1초부터 두 배씩 최대 2초 간격으로 다시 붙고 구독을 다시 보낸다.
 * 닫힘 이벤트가 오지 않는 조용한 끊김(잠자기, Wi-Fi 전환, 멈춘 서버)은 [silenceMs] 동안 메시지가 없는 것으로 알아챈다.
 * 다시 붙으면 onReconnect 리스너를 부른다: 끊긴 동안 놓친 갱신은 REST로 다시 받아야 한다.
 */
export class StreamClient {
  private readonly url: string
  private readonly createSocket: (url: string) => SocketLike
  private readonly minDelay: number
  private readonly maxDelay: number
  private readonly silence: number
  private readonly stable: number
  private readonly random: () => number
  private readonly routes: string[]
  private route = 0
  private delay: number
  private socket: SocketLike | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private watch: ReturnType<typeof setInterval> | null = null
  private closed = false
  private everOpened = false
  private openedAt: number | null = null
  private lastMessage = 0
  private lastCheck = 0
  private current: ConnectionStatus = 'reconnecting'
  private readonly subs = new Map<string, { market: string; unit: UnitCode }>()
  private tickers = false
  private readonly candleListeners = new Set<(items: StreamCandleItem[]) => void>()
  private readonly tickerListeners = new Set<(items: StreamTickerItem[]) => void>()
  private readonly reconnectListeners = new Set<() => void>()
  private readonly statusListeners = new Set<(s: ConnectionStatus) => void>()

  constructor(url: string, options: StreamOptions = {}) {
    this.url = url
    this.createSocket = options.createSocket ?? ((u) => new WebSocket(u) as unknown as SocketLike)
    this.minDelay = options.minDelayMs ?? 100
    this.maxDelay = options.maxDelayMs ?? 2_000
    this.silence = options.silenceMs ?? 1_500
    this.stable = options.stableMs ?? 10_000
    this.random = options.random ?? Math.random
    this.routes = options.routes ?? []
    this.route = this.routes.length > 0 ? Math.floor(this.random() * this.routes.length) % this.routes.length : 0
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

  get status(): ConnectionStatus {
    return this.current
  }

  onStatus(cb: (s: ConnectionStatus) => void): () => void {
    this.statusListeners.add(cb)
    return () => {
      this.statusListeners.delete(cb)
    }
  }

  /** 탭으로 돌아왔거나 네트워크가 돌아왔을 때 부른다. 재연결 대기 중이면 바로 붙고, 조용한 소켓은 바꾼다. */
  wake() {
    if (this.closed) return
    if (!this.socket) {
      if (this.timer) clearTimeout(this.timer)
      this.timer = null
      this.delay = this.minDelay
      this.connect()
    } else if (this.socket.readyState === OPEN && Date.now() - this.lastMessage > WAKE_QUIET_MS) {
      this.abandon()
    }
  }

  close() {
    this.closed = true
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.stopWatch()
    const s = this.socket
    this.socket = null
    s?.close()
  }

  private connect() {
    const name = this.routes[this.route]
    const s = this.createSocket(name === undefined ? this.url : `${this.url}?gw=${name}`)
    this.socket = s
    this.lastMessage = Date.now()
    this.startWatch()
    s.onopen = () => {
      if (this.socket !== s) return
      const again = this.everOpened
      this.everOpened = true
      this.openedAt = Date.now()
      this.lastMessage = this.openedAt
      for (const { market, unit } of this.subs.values()) this.send({ op: 'subscribe', market, unit })
      if (this.tickers) this.send({ op: 'subscribeTickers' })
      if (again) this.reconnectListeners.forEach((cb) => cb())
    }
    s.onmessage = (ev) => {
      if (this.socket !== s) return
      this.lastMessage = Date.now()
      this.dispatch(ev.data)
    }
    s.onerror = () => {} // 이어서 onclose가 온다
    s.onclose = () => {
      if (this.socket === s) this.lost()
    }
  }

  /** 지금 소켓을 잃었다. 재연결을 예약한다. */
  private lost() {
    this.socket = null
    // 멈춘 게이트웨이는 연결을 받아 주기만 하고 답이 없다. 같은 곳에 다시 붙으면 계속 기다리게 되므로 항상 다른 쪽으로 간다.
    if (this.routes.length > 0) this.route = (this.route + 1) % this.routes.length
    this.stopWatch()
    if (this.openedAt !== null && Date.now() - this.openedAt >= this.stable) this.delay = this.minDelay
    this.openedAt = null
    this.setStatus('reconnecting')
    this.scheduleReconnect()
  }

  /** 응답 없는 소켓을 포기한다. 먼저 잃은 것으로 처리해, 닫기가 늦어도 재연결을 기다리게 하지 않는다. */
  private abandon() {
    const s = this.socket
    this.lost()
    s?.close()
  }

  private startWatch() {
    this.stopWatch()
    this.lastCheck = Date.now()
    this.watch = setInterval(() => this.check(), WATCH_MS)
  }

  private stopWatch() {
    if (this.watch) clearInterval(this.watch)
    this.watch = null
  }

  private check() {
    const now = Date.now()
    const stalled = now - this.lastCheck > STALLED_MS
    this.lastCheck = now
    if (!stalled && now - this.lastMessage > this.silence) this.abandon()
  }

  private setStatus(s: ConnectionStatus) {
    if (s === this.current) return
    this.current = s
    this.statusListeners.forEach((cb) => cb(s))
  }

  private scheduleReconnect() {
    if (this.closed || this.timer) return
    const wait = this.delay * (0.5 + 0.5 * this.random())   // 게이트웨이가 죽어도 전원이 같은 순간에 몰리지 않게
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
    let msg: { type?: string; items?: unknown; message?: string; feed?: string }
    try {
      msg = JSON.parse(data)
    } catch {
      return
    }
    if (msg.type === 'hb') this.setStatus(msg.feed === 'live' ? 'live' : 'delayed')
    else if (msg.type === 'candles') this.candleListeners.forEach((cb) => cb(msg.items as StreamCandleItem[]))
    else if (msg.type === 'tickers') this.tickerListeners.forEach((cb) => cb(msg.items as StreamTickerItem[]))
    else if (msg.type === 'error') console.warn('stream error:', msg.message)
  }
}

let shared: StreamClient | null = null

/** 화면 전체가 함께 쓰는 연결(같은 출처의 /ws). */
export function sharedStream(): StreamClient {
  if (!shared) {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const stream = new StreamClient(`${scheme}//${location.host}/ws`, { routes: ['a', 'b'] })
    shared = stream
    window.addEventListener('online', () => stream.wake())
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) stream.wake()
    })
  }
  return shared
}
