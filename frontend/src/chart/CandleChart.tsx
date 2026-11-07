import {
  CandlestickSeries,
  ColorType,
  createChart,
  CrosshairMode,
  HistogramSeries,
  type IChartApi,
  type ISeriesApi,
  type LogicalRange,
  type MouseEventParams,
  type Time,
} from 'lightweight-charts'
import { useEffect, useRef, useState } from 'react'
import { fetchCandles } from '../api/client'
import { sharedStream } from '../api/stream'
import type { CandlePage, StreamCandleItem, UnitCode } from '../api/types'
import { pricePrecision } from './format'
import { Legend } from './Legend'
import { applyUpdate, type Bar, mergeLatest, toBar } from './merge'

const MINUTE_UNITS: ReadonlySet<UnitCode> = new Set(['1m', '3m', '5m', '10m', '15m', '30m', '60m', '240m'])

type Palette = { bg: string; text: string; grid: string; line: string; up: string; down: string; upSoft: string; downSoft: string }

function palette(): Palette {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string) => css.getPropertyValue(name).trim()
  return {
    bg: v('--surface'), text: v('--muted'), grid: v('--grid'), line: v('--line'),
    up: v('--up'), down: v('--down'), upSoft: v('--up-soft'), downSoft: v('--down-soft'),
  }
}

type View = { chart: IChartApi; candles: ISeriesApi<'Candlestick'>; volume: ISeriesApi<'Histogram'>; colors: Palette }

function styleChart(view: View) {
  const p = view.colors
  view.chart.applyOptions({
    layout: { background: { type: ColorType.Solid, color: p.bg }, textColor: p.text },
    grid: { vertLines: { color: p.grid }, horzLines: { color: p.grid } },
    rightPriceScale: { borderColor: p.line },
    timeScale: { borderColor: p.line },
  })
  view.candles.applyOptions({ upColor: p.up, downColor: p.down, borderUpColor: p.up, borderDownColor: p.down, wickUpColor: p.up, wickDownColor: p.down })
}

function withLive(page: CandlePage, market: string, unit: UnitCode): Bar[] {
  const bars = page.candles.map(toBar)
  return page.live ? applyUpdate(bars, { market, unit, state: 'live', time: page.live.time, candle: page.live }).bars : bars
}

/**
 * 한 종목·한 단위의 캔들 차트. 처음 200개를 받아 그린 뒤 /ws 갱신을 반영하고,
 * 왼쪽 끝 가까이 스크롤하면 이전 200개를 앞에 붙인다. 재연결하면 최신 구간을 다시 받아 맞춘다.
 */
export function CandleChart({ market, unit }: { market: string; unit: UnitCode }) {
  const box = useRef<HTMLDivElement>(null)
  const view = useRef<View | null>(null)
  const bars = useRef<Bar[]>([])
  const [hover, setHover] = useState<Bar | null>(null)
  const [last, setLast] = useState<Bar | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')

  useEffect(() => {
    const chart = createChart(box.current!, {
      autoSize: true,
      crosshair: { mode: CrosshairMode.Normal },
      localization: { locale: 'ko-KR' },
      timeScale: { rightOffset: 6, secondsVisible: false },
    })
    const candles = chart.addSeries(CandlestickSeries, { priceLineVisible: true })
    candles.priceScale().applyOptions({ scaleMargins: { top: 0.08, bottom: 0.24 } })
    const volume = chart.addSeries(HistogramSeries, { priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false })
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    const v: View = { chart, candles, volume, colors: palette() }
    styleChart(v)
    view.current = v

    const onMove = (param: MouseEventParams<Time>) => {
      setHover(param.time === undefined ? null : (bars.current.find((b) => b.time === param.time) ?? null))
    }
    chart.subscribeCrosshairMove(onMove)
    const scheme = matchMedia('(prefers-color-scheme: dark)')
    const onScheme = () => {
      v.colors = palette()
      styleChart(v)
      volume.setData(bars.current.map((b) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? v.colors.upSoft : v.colors.downSoft })))
    }
    scheme.addEventListener('change', onScheme)
    return () => {
      scheme.removeEventListener('change', onScheme)
      chart.unsubscribeCrosshairMove(onMove)
      chart.remove()
      view.current = null
    }
  }, [])

  useEffect(() => {
    const v = view.current
    if (!v) return
    const { chart, candles, volume } = v
    const stream = sharedStream()
    let cancelled = false
    let ready = false
    let loading = true
    let hasMore = false
    let precision = -1
    const pending: StreamCandleItem[] = []

    const volumeOf = (b: Bar) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? v.colors.upSoft : v.colors.downSoft })
    const candleOf = (b: Bar) => ({ time: b.time, open: b.open, high: b.high, low: b.low, close: b.close })
    const setPrecision = (list: Bar[]) => {
      const p = pricePrecision(list)
      if (p !== precision) {
        precision = p
        const nf = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: p, maximumFractionDigits: p })
        candles.applyOptions({ priceFormat: { type: 'custom', minMove: 10 ** -p, formatter: (x: number) => nf.format(x) } })
      }
    }
    const drawAll = (list: Bar[]) => {
      candles.setData(list.map(candleOf))
      volume.setData(list.map(volumeOf))
      setPrecision(list)
      setLast(list.at(-1) ?? null)
    }
    const apply = (item: StreamCandleItem) => {
      const r = applyUpdate(bars.current, item)
      bars.current = r.bars
      if (r.mode === 'reset') return drawAll(r.bars)
      const b = r.bars.at(-1)!
      candles.update(candleOf(b))
      volume.update(volumeOf(b))
      if (pricePrecision([b]) > precision) setPrecision(r.bars)
      setLast(b)
    }

    bars.current = []
    candles.setData([])
    volume.setData([])
    setLast(null)
    setHover(null)
    setStatus('loading')
    chart.applyOptions({ timeScale: { timeVisible: MINUTE_UNITS.has(unit) } })

    const offCandles = stream.onCandles((items) => {
      for (const item of items) {
        if (item.market !== market || item.unit !== unit) continue
        if (ready) apply(item)
        else pending.push(item)
      }
    })
    stream.subscribe(market, unit)

    const load = async () => {
      try {
        const page = await fetchCandles(market, unit)
        if (cancelled) return
        bars.current = withLive(page, market, unit)
        hasMore = page.hasMore
        drawAll(bars.current)
        ready = true
        for (const item of pending.splice(0)) apply(item)
        setStatus('ready')
      } catch {
        if (!cancelled) setStatus('error')
      } finally {
        loading = false
      }
    }

    const loadOlder = async () => {
      const first = bars.current[0]
      if (!first || loading || !hasMore) return
      loading = true
      try {
        const page = await fetchCandles(market, unit, first.raw.time, 200)
        if (cancelled) return
        hasMore = page.hasMore
        const older = page.candles.map(toBar).filter((b) => b.time < bars.current[0].time)
        if (older.length > 0) {
          bars.current = [...older, ...bars.current]
          drawAll(bars.current)
        }
      } catch {
        // 다음 스크롤 때 다시 시도한다
      } finally {
        loading = false
      }
    }
    const onRange = (range: LogicalRange | null) => {
      if (range && range.from < 20) void loadOlder()
    }
    chart.timeScale().subscribeVisibleLogicalRangeChange(onRange)

    const offReconnect = stream.onReconnect(async () => {
      if (!ready) return
      try {
        const page = await fetchCandles(market, unit)
        if (cancelled) return
        bars.current = mergeLatest(bars.current, withLive(page, market, unit))
        drawAll(bars.current)
      } catch {
        // 다음 갱신이나 재연결 때 맞춰진다
      }
    })

    void load()
    return () => {
      cancelled = true
      offCandles()
      offReconnect()
      stream.unsubscribe(market, unit)
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRange)
    }
  }, [market, unit])

  return (
    <div className="chart-wrap">
      <Legend bar={hover ?? last} unit={unit} />
      <div className="chart" ref={box} />
      {status !== 'ready' && (
        <div className="chart-status" role="status">
          {status === 'loading' ? '차트를 불러오는 중…' : '차트를 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요.'}
        </div>
      )}
    </div>
  )
}
