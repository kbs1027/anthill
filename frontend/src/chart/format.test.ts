import { expect, test } from 'vitest'
import type { CandleDto } from '../api/types'
import { toBar } from './merge'
import { formatAmount24h, formatChangeRate, formatPrice, groupDigits, pricePrecision, quoteOf } from './format'

test('groupDigits adds separators without touching the digits', () => {
  expect(groupDigits('108328000')).toBe('108,328,000')
  expect(groupDigits('1234.5678')).toBe('1,234.5678')
  expect(groupDigits('-1234')).toBe('-1,234')
  expect(groupDigits('0.00012345')).toBe('0.00012345')
  expect(groupDigits('999')).toBe('999')
})

const c = (open: string, close: string): CandleDto => ({
  instrument: 'UPBIT:KRW-X', unit: '1m', time: '2026-11-08T10:20:00Z', open, high: open, low: close, close, volume: '1', amount: '1',
})

test('pricePrecision is the most decimals any price shows', () => {
  expect(pricePrecision([toBar(c('108328000', '108329000'))])).toBe(0)
  expect(pricePrecision([toBar(c('12.5', '12.55')), toBar(c('0.0123', '0.012'))])).toBe(4)
  expect(pricePrecision([toBar(c('0.000000001', '1'))])).toBe(8)
  expect(pricePrecision([])).toBe(0)
})

test('formatChangeRate shows a signed percent', () => {
  expect(formatChangeRate('0.0123')).toBe('+1.23%')
  expect(formatChangeRate('-0.005')).toBe('-0.50%')
  expect(formatChangeRate('0')).toBe('0.00%')
})

test('quoteOf reads the quote currency from the market id', () => {
  expect(quoteOf('UPBIT:KRW-BTC')).toBe('KRW')
  expect(quoteOf('BINANCE:BTCUSDT')).toBe('USDT')
  expect(quoteOf('BINANCE:1000SATSUSDT')).toBe('USDT')
})

test('formatAmount24h shows millions of won', () => {
  expect(formatAmount24h('123456789012.5', 'UPBIT:KRW-BTC')).toBe('123,457백만')
  expect(formatAmount24h('400000', 'UPBIT:KRW-BTC')).toBe('0백만')
})

test('formatAmount24h shows USDT millions with two decimals', () => {
  expect(formatAmount24h('435851324.12', 'BINANCE:BTCUSDT')).toBe('435.85백만')
  expect(formatAmount24h('123456.7', 'BINANCE:ZILUSDT')).toBe('0.12백만')
  expect(formatAmount24h('1234567890123', 'BINANCE:BTCUSDT')).toBe('1,234,567.89백만')
  expect(formatAmount24h('0', 'BINANCE:ZILUSDT')).toBe('0.00백만')
})

test('markets without a ticker yet show a dash', () => {
  // marketdata가 막 시작했을 때 /api/markets는 시세 없는 종목을 null로 준다
  expect(formatPrice(null)).toBe('-')
  expect(formatChangeRate(null)).toBe('-')
  expect(formatAmount24h(null, 'UPBIT:KRW-BTC')).toBe('-')
  expect(formatAmount24h(null, 'BINANCE:BTCUSDT')).toBe('-')
  expect(formatPrice('103493000')).toBe('103,493,000')
})
