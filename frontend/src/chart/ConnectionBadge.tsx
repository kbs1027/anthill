import { useEffect, useState } from 'react'
import { delayDegrade } from '../api/connectionDisplay'
import { sharedStream, type ConnectionStatus } from '../api/stream'

const LABEL: Record<ConnectionStatus, string> = { live: '실시간', reconnecting: '재연결 중', delayed: '시세 지연' }

/** 시세가 지금 믿을 만한지 보여 준다. 멈춘 차트를 정상으로 착각하지 않게 한다. */
export function ConnectionBadge() {
  const [status, setStatus] = useState<ConnectionStatus>(() => sharedStream().status)
  useEffect(() => {
    const stream = sharedStream()
    const show = delayDegrade(setStatus)
    show(stream.status)
    return stream.onStatus(show)
  }, [])
  return (
    <div className={`connection ${status}`} role="status">
      <i aria-hidden="true" />
      {LABEL[status]}
    </div>
  )
}
