import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { retryUntil } from './retry'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

test('keeps trying with growing waits until it succeeds', async () => {
  const fn = vi.fn().mockRejectedValueOnce(new Error('502')).mockRejectedValueOnce(new Error('502')).mockResolvedValue('page')
  const result = retryUntil(fn, { stop: () => false })
  await vi.advanceTimersByTimeAsync(0)
  expect(fn).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(199)
  expect(fn).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(fn).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(400)
  expect(fn).toHaveBeenCalledTimes(3)
  await expect(result).resolves.toBe('page')
})

test('waits never exceed the cap', async () => {
  const fn = vi.fn().mockRejectedValue(new Error('502'))
  let stopped = false
  const result = retryUntil(fn, { stop: () => stopped, baseMs: 1000, maxMs: 2000 })
  await vi.advanceTimersByTimeAsync(0)
  await vi.advanceTimersByTimeAsync(1000)
  await vi.advanceTimersByTimeAsync(2000)
  await vi.advanceTimersByTimeAsync(2000)
  expect(fn).toHaveBeenCalledTimes(4)
  stopped = true
  await vi.advanceTimersByTimeAsync(2000)
  await expect(result).resolves.toBeUndefined()
})

test('stops without a result once told to', async () => {
  const fn = vi.fn().mockResolvedValue('page')
  await expect(retryUntil(fn, { stop: () => true })).resolves.toBeUndefined()
  expect(fn).not.toHaveBeenCalled()
})

test('drops a result that arrives after it was told to stop', async () => {
  let stopped = false
  const fn = vi.fn(async () => {
    stopped = true
    return 'old page'
  })
  await expect(retryUntil(fn, { stop: () => stopped })).resolves.toBeUndefined()
})
