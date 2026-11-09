import type { ConnectionStatus } from './stream'

/**
 * 순간적인 재연결로 표시가 깜빡이지 않게, 나빠질 때만 [delayMs] 기다렸다 보여 준다.
 * 회복은 바로 보여 준다. 이미 나쁜 상태를 보여 주는 중이면 바뀐 상태도 바로 보여 준다.
 */
export function delayDegrade(show: (s: ConnectionStatus) => void, delayMs = 800): (s: ConnectionStatus) => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  let showing: ConnectionStatus | null = null
  const commit = (s: ConnectionStatus) => {
    showing = s
    show(s)
  }
  return (s) => {
    if (timer) clearTimeout(timer)
    timer = null
    if (s === 'live' || (showing !== null && showing !== 'live')) {
      if (s !== showing) commit(s)
      return
    }
    timer = setTimeout(() => {
      timer = null
      commit(s)
    }, delayMs)
  }
}
