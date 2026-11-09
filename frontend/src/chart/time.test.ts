import { expect, test } from 'vitest'
import { formatKst, toChartTime } from './time'

test('day candle 00Z shows as 09:00 KST date', () => expect(formatKst('2026-11-05T00:00:00Z', 'day')).toBe('2026-11-05'))
test('toChartTime adds 9h', () => expect(toChartTime('2026-11-05T00:00:00Z')).toBe(Date.UTC(2026, 10, 5, 9) / 1000))
test('minute candle crosses midnight in KST', () => expect(formatKst('2026-11-05T15:30:00Z', '1m')).toBe('2026-11-06 00:30'))
test('week and month show the KST date', () => {
  expect(formatKst('2026-11-02T00:00:00Z', 'week')).toBe('2026-11-02')
  expect(formatKst('2026-11-01T00:00:00Z', 'month')).toBe('2026-11-01')
})
