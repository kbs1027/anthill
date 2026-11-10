import { expect, test } from 'vitest'
import type { MarketDto } from '../api/types'
import { filterMarkets, sourceOf } from './filter'

const m = (market: string, koreanName: string, englishName: string): MarketDto => ({
  market, koreanName, englishName, price: null, changeRate: null, amount24h: null,
})

// 서버가 준 순서(거래소마다 거래대금 순)가 섞여 온다
const markets = [
  m('BINANCE:BTCUSDT', 'BTC', 'BTC'),
  m('UPBIT:KRW-BTC', '비트코인', 'Bitcoin'),
  m('BINANCE:ETHUSDT', 'ETH', 'ETH'),
  m('UPBIT:KRW-ETH', '이더리움', 'Ethereum'),
  m('BINANCE:WBTCUSDT', 'WBTC', 'WBTC'),
]
const ids = (list: MarketDto[]) => list.map((x) => x.market)

test('sourceOf reads the exchange from the market id', () => {
  expect(sourceOf('BINANCE:BTCUSDT')).toBe('BINANCE')
  expect(sourceOf('UPBIT:KRW-BTC')).toBe('UPBIT')
})

test('a tab shows only its exchange, in server order', () => {
  expect(ids(filterMarkets(markets, 'BINANCE', ''))).toEqual(['BINANCE:BTCUSDT', 'BINANCE:ETHUSDT', 'BINANCE:WBTCUSDT'])
  expect(ids(filterMarkets(markets, 'UPBIT', '  '))).toEqual(['UPBIT:KRW-BTC', 'UPBIT:KRW-ETH'])
})

test('search matches korean name, english name and code within the tab', () => {
  expect(ids(filterMarkets(markets, 'UPBIT', '비트'))).toEqual(['UPBIT:KRW-BTC'])
  expect(ids(filterMarkets(markets, 'UPBIT', 'ether'))).toEqual(['UPBIT:KRW-ETH'])
  expect(ids(filterMarkets(markets, 'UPBIT', 'krw-eth'))).toEqual(['UPBIT:KRW-ETH'])
  expect(ids(filterMarkets(markets, 'UPBIT', 'BTC'))).toEqual(['UPBIT:KRW-BTC'])
})

test('binance symbols are searched case-insensitively', () => {
  expect(ids(filterMarkets(markets, 'BINANCE', 'btc'))).toEqual(['BINANCE:BTCUSDT', 'BINANCE:WBTCUSDT'])
  expect(ids(filterMarkets(markets, 'BINANCE', 'EthUsdt'))).toEqual(['BINANCE:ETHUSDT'])
})

test('a name from the other exchange does not match', () => {
  // 바이낸스 종목 이름은 심볼 그대로라 한국어 이름으로는 찾지 못한다
  expect(filterMarkets(markets, 'BINANCE', '비트코인')).toEqual([])
})
