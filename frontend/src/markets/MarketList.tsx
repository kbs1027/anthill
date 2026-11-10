import { useEffect, useEffectEvent, useMemo, useState } from 'react'
import { fetchMarkets } from '../api/client'
import { sharedStream } from '../api/stream'
import type { MarketDto } from '../api/types'
import { formatAmount24h, formatChangeRate, formatPrice } from '../chart/format'
import { filterMarkets, sourceOf } from './filter'

type Props = { selected: string; onSelect: (market: MarketDto) => void; onLoaded?: (markets: MarketDto[]) => void }

/** 거래소 탭. 바이낸스가 왼쪽이고 처음 선택된다(첫 종목이 바이낸스). */
const SOURCES = [
  { id: 'BINANCE', label: '바이낸스' },
  { id: 'UPBIT', label: '업비트' },
] as const

const tone = (rate: string | null) => (rate === null ? 'flat' : Number(rate) > 0 ? 'up' : Number(rate) < 0 ? 'down' : 'flat')

/** 종목 목록: 거래소 탭마다 24시간 거래대금 순, 한글명·영문명·코드로 찾기, 시세는 /ws 티커로 갱신. */
export function MarketList({ selected, onSelect, onLoaded }: Props) {
  const [markets, setMarkets] = useState<MarketDto[]>([])
  const [query, setQuery] = useState('')
  // 탭은 선택한 종목의 거래소로 시작하고, 다른 거래소 종목이 선택되면 그 탭으로 옮긴다.
  const [source, setSource] = useState(() => sourceOf(selected))
  const [tabFor, setTabFor] = useState(selected)
  if (tabFor !== selected) {
    setTabFor(selected)
    setSource(sourceOf(selected))
  }
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const notifyLoaded = useEffectEvent((list: MarketDto[]) => onLoaded?.(list))

  useEffect(() => {
    let cancelled = false
    fetchMarkets()
      .then((list) => {
        if (cancelled) return
        setMarkets(list)
        setStatus('ready')
        notifyLoaded(list)
      })
      .catch(() => !cancelled && setStatus('error'))
    const stream = sharedStream()
    stream.subscribeTickers()
    const off = stream.onTickers((items) => {
      const byMarket = new Map(items.map((t) => [t.market, t]))
      setMarkets((prev) =>
        prev.map((m) => {
          const t = byMarket.get(m.market)
          return t ? { ...m, price: t.price, changeRate: t.changeRate, amount24h: t.amount24h } : m
        }),
      )
    })
    return () => {
      cancelled = true
      off()
    }
  }, [])

  const shown = useMemo(() => filterMarkets(markets, source, query), [markets, source, query])

  return (
    <div className="markets">
      <div className="source-tabs" role="group" aria-label="거래소">
        {SOURCES.map((s) => (
          <button key={s.id} type="button" aria-pressed={s.id === source} onClick={() => setSource(s.id)}>
            {s.label}
          </button>
        ))}
      </div>
      <label className="search">
        <span className="sr-only">종목 검색</span>
        <input type="search" placeholder="이름·코드 검색 (예: 비트코인, BTC)" value={query} onChange={(e) => setQuery(e.target.value)} />
      </label>
      <div className="market-head" aria-hidden="true">
        <span>종목</span>
        <span>현재가</span>
        <span>전일대비</span>
        <span>거래대금</span>
      </div>
      {status === 'loading' && <p className="markets-note">종목을 불러오는 중…</p>}
      {status === 'error' && <p className="markets-note">종목 목록을 불러오지 못했습니다.</p>}
      {status === 'ready' && shown.length === 0 && (
        <p className="markets-note">{query.trim() ? `'${query}'에 맞는 종목이 없습니다.` : '이 거래소의 종목이 아직 없습니다.'}</p>
      )}
      <ul className="market-rows">
        {shown.map((m) => (
          <li key={m.market}>
            <button type="button" className="market-row" aria-current={m.market === selected ? 'true' : undefined} onClick={() => onSelect(m)}>
              <span className="name">
                <b>{m.koreanName}</b>
                <small>{m.market.split(':')[1]}</small>
              </span>
              <span className={`num ${tone(m.changeRate)}`}>{formatPrice(m.price)}</span>
              <span className={`num ${tone(m.changeRate)}`}>{formatChangeRate(m.changeRate)}</span>
              <span className="num muted">{formatAmount24h(m.amount24h, m.market)}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
