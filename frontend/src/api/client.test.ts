import { afterEach, expect, test, vi } from 'vitest'
import { fetchCandles, fetchMarkets } from './client'

afterEach(() => vi.unstubAllGlobals())

test('fetchCandles builds the query and returns the page', async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ market: 'UPBIT:KRW-BTC', unit: '1m', candles: [], live: null, hasMore: true })))
  vi.stubGlobal('fetch', fetch)
  const page = await fetchCandles('UPBIT:KRW-BTC', '1m', '2026-11-08T10:20:00Z', 200)
  expect(page).toEqual({ candles: [], live: null, hasMore: true })
  const url = new URL(String((fetch.mock.calls[0] as unknown[])[0]), 'http://x')
  expect(url.pathname).toBe('/api/candles')
  expect(Object.fromEntries(url.searchParams)).toEqual({ market: 'UPBIT:KRW-BTC', unit: '1m', to: '2026-11-08T10:20:00Z', count: '200' })
})

test('non-2xx responses throw', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":"unknown market"}', { status: 404 })))
  await expect(fetchMarkets()).rejects.toThrow('404')
})
