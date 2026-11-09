import { useState } from 'react'
import { UNITS, type MarketDto, type UnitCode } from './api/types'
import { CandleChart } from './chart/CandleChart'
import { MarketList } from './markets/MarketList'

const LABELS: Record<UnitCode, string> = {
  '1m': '1분', '3m': '3분', '5m': '5분', '10m': '10분', '15m': '15분', '30m': '30분',
  '60m': '1시간', '240m': '4시간', day: '일', week: '주', month: '월',
}

export default function App() {
  const [market, setMarket] = useState('UPBIT:KRW-BTC')
  const [unit, setUnit] = useState<UnitCode>('1m')
  const [names, setNames] = useState<Record<string, string>>({})
  const symbol = market.split(':')[1] ?? market

  return (
    <div className="app">
      <header className="top">
        <span className="brand">anthill</span>
        <span className="top-note">모의 투자 · 차트</span>
      </header>
      <main className="layout">
        <section className="chart-panel" aria-label="차트">
          <div className="chart-head">
            <h1>{names[market] ?? symbol}</h1>
            <span className="code">{symbol}</span>
          </div>
          <div className="units" role="group" aria-label="캔들 단위">
            {UNITS.map((u) => (
              <button key={u} type="button" aria-pressed={u === unit} onClick={() => setUnit(u)}>
                {LABELS[u]}
              </button>
            ))}
          </div>
          <CandleChart market={market} unit={unit} />
        </section>
        <aside className="markets-panel" aria-label="종목 목록">
          <MarketList
            selected={market}
            onSelect={(m: MarketDto) => setMarket(m.market)}
            onLoaded={(list) => setNames(Object.fromEntries(list.map((m) => [m.market, m.koreanName])))}
          />
        </aside>
      </main>
    </div>
  )
}
