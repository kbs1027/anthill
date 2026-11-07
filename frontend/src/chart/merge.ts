import type { UTCTimestamp } from 'lightweight-charts'
import type { CandleDto, StreamCandleItem } from '../api/types'
import { toChartTime } from './time'

/** 그리기용 숫자와, 화면에 보여 줄 정확한 문자열(raw)을 함께 든다. 숫자 변환은 여기서만 한다. */
export type Bar = { time: UTCTimestamp; open: number; high: number; low: number; close: number; volume: number; raw: CandleDto }

export function toBar(c: CandleDto): Bar {
  return {
    time: toChartTime(c.time),
    open: Number(c.open),
    high: Number(c.high),
    low: Number(c.low),
    close: Number(c.close),
    volume: Number(c.volume),
    raw: c,
  }
}

/**
 * 실시간 항목 하나를 반영한 새 배열을 돌려준다(원본은 건드리지 않는다).
 * live·closed이면서 마지막 bar 이후(같은 시각 포함) → 마지막 교체 또는 뒤에 추가: 'update'(series.update로 충분).
 * corrected·removed, 또는 마지막보다 이른 시각 → 그 자리 교체·삽입·삭제: 'reset'(setData 필요).
 */
export function applyUpdate(bars: Bar[], item: StreamCandleItem): { bars: Bar[]; mode: 'update' | 'reset' } {
  const time = toChartTime(item.time)
  const last = bars.at(-1)
  if (item.state === 'removed' || item.candle === null) {
    return { bars: bars.filter((b) => b.time !== time), mode: 'reset' }
  }
  const bar = toBar(item.candle)
  if ((item.state === 'live' || item.state === 'closed') && (last === undefined || time >= last.time)) {
    return { bars: last !== undefined && last.time === time ? [...bars.slice(0, -1), bar] : [...bars, bar], mode: 'update' }
  }
  const next = bars.filter((b) => b.time !== time)
  const at = next.findIndex((b) => b.time > time)
  next.splice(at === -1 ? next.length : at, 0, bar)
  return { bars: next, mode: 'reset' }
}

/**
 * 재연결 뒤 다시 받은 최신 구간으로 겹치는 부분을 갈아 끼운다. 그보다 오래된 bar는 그대로 둔다.
 * 새 구간이 기존 마지막 bar까지 닿지 않으면(오래 끊겨 그 사이가 빠짐) 통째로 바꾼다: replaced = true.
 */
export function mergeLatest(bars: Bar[], latest: Bar[]): { bars: Bar[]; replaced: boolean } {
  if (latest.length === 0) return { bars, replaced: false }
  const first = latest[0].time
  const last = bars.at(-1)
  if (last !== undefined && first > last.time) return { bars: latest, replaced: true }
  return { bars: [...bars.filter((b) => b.time < first), ...latest], replaced: false }
}
