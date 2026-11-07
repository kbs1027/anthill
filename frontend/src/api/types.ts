/** 백엔드와 주고받는 모양. 숫자는 정확한 값을 지키려고 모두 문자열이다. */
export type UnitCode = '1m' | '3m' | '5m' | '10m' | '15m' | '30m' | '60m' | '240m' | 'day' | 'week' | 'month'

export const UNITS: readonly UnitCode[] = ['1m', '3m', '5m', '10m', '15m', '30m', '60m', '240m', 'day', 'week', 'month']

export type CandleDto = {
  instrument: string
  unit: UnitCode
  time: string
  open: string
  high: string
  low: string
  close: string
  volume: string
  amount: string
}

export type CandlePage = { candles: CandleDto[]; live: CandleDto | null; hasMore: boolean }

export type MarketDto = { market: string; koreanName: string; englishName: string; price: string; changeRate: string; amount24h: string }

export type CandleState = 'live' | 'closed' | 'corrected' | 'removed'

export type StreamCandleItem = { market: string; unit: UnitCode; state: CandleState; time: string; candle: CandleDto | null }

export type StreamTickerItem = { market: string; price: string; changeRate: string; amount24h: string }
