import type { UnitCode } from '../api/types'
import { groupDigits } from './format'
import type { Bar } from './merge'
import { formatKst } from './time'

/** 십자선이 가리키는 캔들(없으면 마지막 캔들)의 값. 숫자는 서버가 준 문자열을 그대로 보여 준다. */
export function Legend({ bar, unit }: { bar: Bar | null; unit: UnitCode }) {
  if (!bar) return <div className="legend" aria-live="off" />
  const tone = bar.close > bar.open ? 'up' : bar.close < bar.open ? 'down' : 'flat'
  const r = bar.raw
  return (
    <dl className={`legend ${tone}`}>
      <div className="legend-time">{formatKst(r.time, unit)}</div>
      <div><dt>시</dt><dd>{groupDigits(r.open)}</dd></div>
      <div><dt>고</dt><dd>{groupDigits(r.high)}</dd></div>
      <div><dt>저</dt><dd>{groupDigits(r.low)}</dd></div>
      <div><dt>종</dt><dd className="close">{groupDigits(r.close)}</dd></div>
      <div><dt>거래량</dt><dd>{groupDigits(r.volume)}</dd></div>
      <div><dt>거래대금</dt><dd>{groupDigits(r.amount)}</dd></div>
    </dl>
  )
}
