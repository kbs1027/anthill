import type { CandlePage, MarketDto, UnitCode } from './types'

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url} → ${res.status}`)
  return (await res.json()) as T
}

/** [to] 직전(배타)까지의 마감 캔들 [count]개(오름차순)와, [to]가 없으면 진행 중 캔들. */
export async function fetchCandles(market: string, unit: UnitCode, to?: string, count = 200): Promise<CandlePage> {
  const q = new URLSearchParams({ market, unit, count: String(count) })
  if (to) q.set('to', to)
  const body = await getJson<CandlePage>(`/api/candles?${q}`)
  return { candles: body.candles, live: body.live, hasMore: body.hasMore }
}

/** 거래 중인 종목, 24시간 거래대금 큰 순. */
export function fetchMarkets(): Promise<MarketDto[]> {
  return getJson<MarketDto[]>('/api/markets')
}
