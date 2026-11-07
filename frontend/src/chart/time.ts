import type { UTCTimestamp } from 'lightweight-charts'
import type { UnitCode } from '../api/types'

const KST_SECONDS = 9 * 3600

/** 차트 라이브러리는 받은 시각을 UTC로 그대로 그린다. 화면을 한국 시간으로 보이게 9시간을 더한다. */
export function toChartTime(isoUtc: string): UTCTimestamp {
  return (Math.floor(Date.parse(isoUtc) / 1000) + KST_SECONDS) as UTCTimestamp
}

/** 분봉 'YYYY-MM-DD HH:mm', 일·주·월봉 'YYYY-MM-DD' (한국 시간). */
export function formatKst(isoUtc: string, unit: UnitCode): string {
  const d = new Date(Date.parse(isoUtc) + KST_SECONDS * 1000)
  const p = (n: number) => String(n).padStart(2, '0')
  const date = `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`
  return unit === 'day' || unit === 'week' || unit === 'month' ? date : `${date} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`
}
