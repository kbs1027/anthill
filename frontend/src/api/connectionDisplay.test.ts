import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { delayDegrade } from './connectionDisplay'

let shown: string[]
beforeEach(() => {
  shown = []
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

test('a blip shorter than the delay never shows', () => {
  const set = delayDegrade((s) => shown.push(s), 800)
  set('live')
  set('reconnecting')
  vi.advanceTimersByTime(500)
  set('live')
  vi.advanceTimersByTime(2000)
  expect(shown).toEqual(['live'])
})

test('a real outage shows after the delay and recovery shows at once', () => {
  const set = delayDegrade((s) => shown.push(s), 800)
  set('live')
  set('reconnecting')
  vi.advanceTimersByTime(800)
  expect(shown).toEqual(['live', 'reconnecting'])
  set('live')
  expect(shown).toEqual(['live', 'reconnecting', 'live'])
})

test('moving between bad states while one is showing is immediate', () => {
  const set = delayDegrade((s) => shown.push(s), 800)
  set('reconnecting')
  vi.advanceTimersByTime(800)
  set('delayed')
  expect(shown).toEqual(['reconnecting', 'delayed'])
})
