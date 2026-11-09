import { expect, test } from 'vitest'
import type { CandleDto } from '../api/types'
import { toBar } from './merge'
import { formatAmount24h, formatChangeRate, formatPrice, groupDigits, pricePrecision } from './format'

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

test('formatAmount24h shows millions of won', () => {
  expect(formatAmount24h('123456789012.5')).toBe('123,457백만')
  expect(formatAmount24h('400000')).toBe('0백만')
})

test('markets without a ticker yet show a dash', () => {
  // marketdata가 막 시작했을 때 /api/markets는 시세 없는 종목을 null로 준다
  expect(formatPrice(null)).toBe('-')
  expect(formatChangeRate(null)).toBe('-')
  expect(formatAmount24h(null)).toBe('-')
  expect(formatPrice('103493000')).toBe('103,493,000')
})
