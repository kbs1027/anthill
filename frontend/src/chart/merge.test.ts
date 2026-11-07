import { describe, expect, test } from 'vitest'
import type { CandleDto, StreamCandleItem } from '../api/types'
import { applyUpdate, mergeLatest, toBar } from './merge'
import { toChartTime } from './time'

const T0 = '2026-11-08T10:20:00Z'
const T1 = '2026-11-08T10:21:00Z'
const T2 = '2026-11-08T10:22:00Z'

function candle(time: string, close: string, volume = '1.5'): CandleDto {
  return { instrument: 'UPBIT:KRW-BTC', unit: '1m', time, open: '100', high: '110', low: '90', close, volume, amount: '150' }
}

function item(state: StreamCandleItem['state'], time: string, close?: string): StreamCandleItem {
  return { market: 'UPBIT:KRW-BTC', unit: '1m', state, time, candle: close === undefined ? null : candle(time, close) }
}

describe('applyUpdate', () => {
  test('live update replaces last bar', () => {
    const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102'))]
    const r = applyUpdate(bars, item('live', T1, '105'))
    expect(r.mode).toBe('update')
    expect(r.bars).toHaveLength(2)
    expect(r.bars[1].close).toBe(105)
    expect(r.bars[1].raw.close).toBe('105')
    expect(bars[1].close).toBe(102) // 원본은 그대로
  })

  test('closed then next live appends', () => {
    const bars = [toBar(candle(T0, '101'))]
    const closed = applyUpdate(bars, item('closed', T0, '103'))
    expect(closed.mode).toBe('update')
    const next = applyUpdate(closed.bars, item('live', T1, '104'))
    expect(next.mode).toBe('update')
    expect(next.bars.map((b) => b.time)).toEqual([toChartTime(T0), toChartTime(T1)])
    expect(next.bars.map((b) => b.raw.close)).toEqual(['103', '104'])
  })

  test('corrected older bar resets', () => {
    const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102')), toBar(candle(T2, '103'))]
    const r = applyUpdate(bars, item('corrected', T1, '99.5'))
    expect(r.mode).toBe('reset')
    expect(r.bars.map((b) => b.raw.close)).toEqual(['101', '99.5', '103'])
  })

  test('corrected missing bar is inserted in order', () => {
    const bars = [toBar(candle(T0, '101')), toBar(candle(T2, '103'))]
    const r = applyUpdate(bars, item('corrected', T1, '102'))
    expect(r.mode).toBe('reset')
    expect(r.bars.map((b) => b.raw.time)).toEqual([T0, T1, T2])
  })

  test('removed bar is dropped', () => {
    const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102'))]
    const r = applyUpdate(bars, item('removed', T0))
    expect(r.mode).toBe('reset')
    expect(r.bars.map((b) => b.raw.time)).toEqual([T1])
  })

  test('late live item for an older bar resets', () => {
    const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102'))]
    const r = applyUpdate(bars, item('live', T0, '100'))
    expect(r.mode).toBe('reset')
    expect(r.bars.map((b) => b.raw.close)).toEqual(['100', '102'])
  })

  test('first bar on an empty chart is an update', () => {
    const r = applyUpdate([], item('live', T0, '101'))
    expect(r.mode).toBe('update')
    expect(r.bars).toHaveLength(1)
  })
})

test('toBar keeps exact strings in raw and numbers for drawing', () => {
  const b = toBar(candle(T0, '108328000.00000000', '0.00012345'))
  expect(b.close).toBe(108328000)
  expect(b.volume).toBe(0.00012345)
  expect(b.raw.close).toBe('108328000.00000000')
  expect(b.time).toBe(toChartTime(T0))
})

test('mergeLatest keeps older history and replaces the overlap', () => {
  const T3 = '2026-11-08T10:23:00Z'
  const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102')), toBar(candle(T2, '103'))]
  const merged = mergeLatest(bars, [toBar(candle(T1, '200')), toBar(candle(T3, '201'))])
  expect(merged.replaced).toBe(false)
  expect(merged.bars.map((b) => b.raw.time)).toEqual([T0, T1, T3])
  expect(merged.bars.map((b) => b.raw.close)).toEqual(['101', '200', '201'])
  expect(mergeLatest(bars, []).bars).toEqual(bars)
})

test('mergeLatest replaces everything when the new page does not reach the old bars', () => {
  // 리뷰 I7: 밤새 잠든 노트북이 다시 붙으면 최근 200분만 받는다. 이어 붙이면 어젯밤 막대가 오늘 아침 막대에 바로 붙은 채 빈 구간이 영영 안 채워진다.
  const bars = [toBar(candle(T0, '101')), toBar(candle(T1, '102'))]
  const later = ['2026-11-09T08:00:00Z', '2026-11-09T08:01:00Z'].map((t) => toBar(candle(t, '300')))
  const merged = mergeLatest(bars, later)
  expect(merged.replaced).toBe(true)
  expect(merged.bars).toEqual(later)
})
