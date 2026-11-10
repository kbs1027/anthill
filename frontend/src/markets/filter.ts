import type { MarketDto } from '../api/types'

/** 종목 ID의 거래소 부분: `BINANCE:BTCUSDT` → `BINANCE`. */
export const sourceOf = (market: string) => market.split(':')[0]

/** 탭(거래소)과 검색어로 거른다. 순서는 서버가 준 그대로(거래대금 순). 검색은 한국어 이름·영문 이름·코드, 대소문자 무관. */
export function filterMarkets(markets: MarketDto[], source: string, query: string): MarketDto[] {
  const q = query.trim().toLowerCase()
  return markets.filter(
    (m) =>
      sourceOf(m.market) === source &&
      (!q || m.koreanName.toLowerCase().includes(q) || m.englishName.toLowerCase().includes(q) || m.market.toLowerCase().includes(q)),
  )
}
