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
  /** 즉시 재시도가 실패한 뒤 첫 대기. 이후 두 배씩 [maxDelayMs]까지. */
  minDelayMs?: number
  maxDelayMs?: number
  /** 이만큼 아무것도 못 받으면 끊긴 것으로 본다. 게이트웨이는 1초 동안 보낼 게 없으면 ping을 보내므로 3번 놓친 셈이다. */
  staleMs?: number
  /** 무소식인지 확인하는 간격. */
  checkMs?: number
  /** 연결 시도가 이 안에 열리지 않으면 버린다. 멈춘 서버에 붙으면 "연결 중"에서 끝나지 않을 수 있다. */
  connectTimeoutMs?: number
  random?: () => number
  now?: () => number
}

/** 다시 연결하는 이유. 로그(`[stream] reconnect`)에 남는다. */
export type ReconnectReason = 'stale' | 'closed' | 'online' | 'connect-timeout'

const OPEN = 1

/**
 * `/ws` 연결 하나. 구독 상태를 기억해 두었다가 다시 붙으면 구독을 다시 보내고 onReconnect 리스너를 부른다
 * (끊긴 동안 놓친 갱신은 REST로 다시 받아야 한다).
 *
 * 끊김 감지: 게이트웨이는 1초 동안 보낸 게 없으면 {"type":"ping"}을 보낸다. 그래서 정상이면 1초 남짓마다 무언가 온다.
 * ping이든 데이터든 3초 동안 아무것도 못 받으면(신호를 3번 놓치면) 반쯤 끊긴 것으로 보고 그 소켓을 버린다.
 * 시각을 비교하므로 백그라운드 탭에서 타이머가 늦게 돌아도 잘못 판정하지 않는다.
 *
 * 다시 연결: 끊기면 첫 시도는 즉시 한다. 그것도 실패하면 1초→2초→…→30초에 무작위 편차(×0.5~1)를 섞어 기다린다.
 * 실패 횟수는 열린 뒤 첫 메시지를 받았을 때 0이 된다(받자마자 끊는 서버에 즉시 재시도를 끝없이 반복하지 않게).
 */
export class StreamClient {
  private readonly url: string
  private readonly createSocket: (url: string) => SocketLike
  private readonly minDelay: number
  private readonly maxDelay: number
  private readonly staleMs: number
  private readonly checkMs: number
  private readonly connectTimeoutMs: number
  private readonly random: () => number
  private readonly now: () => number
  private failures = 0
  private socket: SocketLike | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private watchTimer: ReturnType<typeof setInterval> | null = null
  private connectTimer: ReturnType<typeof setTimeout> | null = null
  private lastMessageAt = 0
  private gotMessage = false
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
    this.staleMs = options.staleMs ?? 3_000
    this.checkMs = options.checkMs ?? 500
    this.connectTimeoutMs = options.connectTimeoutMs ?? 5_000
    this.random = options.random ?? Math.random
    this.now = options.now ?? Date.now
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

  /** 기다리지 않고 지금 소켓을 버린 뒤 바로 다시 연결한다. 네트워크가 돌아왔을 때(online) 부른다. */
  reconnectNow(reason: ReconnectReason = 'online') {
    if (this.closed) return
    this.cancelWait()
    this.discard()
    console.info('[stream] reconnect', reason)
    this.connect()
  }

  /** 지금 확인한다: 열려 있으면 무소식인지 보고, 다시 연결을 기다리는 중이면 기다리지 않고 연결한다. 탭이 다시 보일 때 부른다. */
  checkNow() {
    if (this.closed) return
    const s = this.socket
    if (s && s.readyState === OPEN) {
      this.checkStale()
    } else if (this.timer) {
      this.cancelWait()
      this.connect()
    }
    // 연결 중이면 그대로 둔다(5초 제한이 걸려 있다).
  }

  close() {
    this.closed = true
    this.cancelWait()
    this.discard()
  }

  private connect() {
    const s = this.createSocket(this.url)
    this.socket = s
    this.gotMessage = false
    this.connectTimer = setTimeout(() => {
      if (this.socket !== s) return
      this.discard()
      this.scheduleReconnect('connect-timeout')
    }, this.connectTimeoutMs)
    s.onopen = () => {
      if (this.socket !== s) return
      this.clearConnectTimer()
      const again = this.everOpened
      this.everOpened = true
      this.lastMessageAt = this.now() // 열린 순간부터 3초를 센다
      this.watchTimer = setInterval(() => this.checkStale(), this.checkMs)
      for (const { market, unit } of this.subs.values()) this.send({ op: 'subscribe', market, unit })
      if (this.tickers) this.send({ op: 'subscribeTickers' })
      if (again) this.reconnectListeners.forEach((cb) => cb())
    }
    s.onmessage = (ev) => {
      if (this.socket !== s) return
      this.lastMessageAt = this.now()
      if (!this.gotMessage) {
        this.gotMessage = true
        this.failures = 0
      }
      this.dispatch(ev.data)
    }
    s.onerror = () => {} // 이어서 onclose가 온다
    s.onclose = () => {
      if (this.socket !== s) return
      this.discard()
      this.scheduleReconnect('closed')
    }
  }

  /** 열린 소켓이 [staleMs] 넘게 아무것도 못 받았으면 버리고 다시 연결한다. */
  private checkStale() {
    const s = this.socket
    if (!s || s.readyState !== OPEN) return
    if (this.now() - this.lastMessageAt < this.staleMs) return
    this.discard()
    this.scheduleReconnect('stale')
  }

  /** 지금 소켓을 버린다. 핸들러를 먼저 떼므로 늦게 오는 onclose·onmessage가 아무것도 하지 않는다. */
  private discard() {
    this.clearConnectTimer()
    if (this.watchTimer) clearInterval(this.watchTimer)
    this.watchTimer = null
    const s = this.socket
    if (!s) return
    this.socket = null
    s.onopen = null
    s.onmessage = null
    s.onclose = null
    s.onerror = null
    try {
      s.close()
    } catch {
      // 이미 닫히는 중
    }
  }

  private scheduleReconnect(reason: ReconnectReason) {
    if (this.closed || this.timer) return
    console.info('[stream] reconnect', reason)
    const n = this.failures++
    const wait = n === 0 ? 0 : Math.min(this.minDelay * 2 ** (n - 1), this.maxDelay) * (0.5 + 0.5 * this.random())
    // 0초도 타이머로 예약한다. 다시 연결하는 길을 하나로 모아 대기 중 표시(timer)와 중복 예약 막기를 함께 쓴다.
    this.timer = setTimeout(() => {
      this.timer = null
      this.connect()
    }, wait)
  }

  private cancelWait() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  private clearConnectTimer() {
    if (this.connectTimer) clearTimeout(this.connectTimer)
    this.connectTimer = null
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
    if (msg.type === 'ping') return // 살아 있다는 신호일 뿐이다(받은 시각은 onmessage에서 이미 적었다)
    if (msg.type === 'candles') this.candleListeners.forEach((cb) => cb(msg.items as StreamCandleItem[]))
    else if (msg.type === 'tickers') this.tickerListeners.forEach((cb) => cb(msg.items as StreamTickerItem[]))
    else if (msg.type === 'error') console.warn('stream error:', msg.message)
  }
}

let shared: StreamClient | null = null

/**
 * 화면 전체가 함께 쓰는 연결(같은 출처의 /ws).
 * 네트워크가 돌아오면(online) 기다리지 않고 다시 연결하고, 탭이 다시 보이면 바로 확인한다(잠자기에서 깨어난 경우).
 */
export function sharedStream(): StreamClient {
  if (!shared) {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const client = new StreamClient(`${scheme}//${location.host}/ws`)
    shared = client
    window.addEventListener('online', () => client.reconnectNow('online'))
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') client.checkNow()
    })
  }
  return shared
}
