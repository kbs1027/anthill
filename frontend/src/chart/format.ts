import type { Bar } from './merge'

/** 정수부에 천 단위 쉼표만 넣는다. 숫자로 바꾸지 않으므로 자릿수가 그대로 남는다. */
export function groupDigits(s: string): string {
  const neg = s.startsWith('-')
  const body = neg ? s.slice(1) : s
  const dot = body.indexOf('.')
  const int = dot < 0 ? body : body.slice(0, dot)
  const frac = dot < 0 ? '' : body.slice(dot)
  return (neg ? '-' : '') + int.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + frac
}

function decimalsOf(s: string): number {
  const dot = s.indexOf('.')
  return dot < 0 ? 0 : s.slice(dot + 1).replace(/0+$/, '').length
}

/** 가격 축 소수 자릿수: 받은 시·고·저·종가 중 가장 긴 소수부(최대 8). */
export function pricePrecision(bars: Bar[]): number {
  let p = 0
  for (const b of bars) {
    p = Math.max(p, decimalsOf(b.raw.open), decimalsOf(b.raw.high), decimalsOf(b.raw.low), decimalsOf(b.raw.close))
  }
  return Math.min(p, 8)
}

/** 시세가 아직 없으면(시작 직후) '-'. */
export function formatPrice(price: string | null): string {
  return price === null ? '-' : groupDigits(price)
}

export function formatChangeRate(rate: string | null): string {
  if (rate === null) return '-'
  const pct = Number(rate) * 100
  return `${pct > 0 ? '+' : ''}${pct.toFixed(2)}%`
}

/** 종목의 가격 통화: `UPBIT:KRW-BTC` → `KRW`, `BINANCE:BTCUSDT` → `USDT`. */
export function quoteOf(market: string): string {
  const symbol = market.slice(market.indexOf(':') + 1)
  const dash = symbol.indexOf('-')
  if (dash >= 0) return symbol.slice(0, dash)
  return symbol.endsWith('USDT') ? 'USDT' : ''
}

/** 24시간 거래대금을 "백만" 단위로. 원화는 정수 반올림, USDT는 금액이 작아 소수 둘째 자리까지 보인다. */
export function formatAmount24h(amount: string | null, market: string): string {
  if (amount === null) return '-'
  const millions = Number(amount) / 1e6
  const shown = quoteOf(market) === 'USDT' ? millions.toFixed(2) : String(Math.round(millions))
  return `${groupDigits(shown)}백만`
}
