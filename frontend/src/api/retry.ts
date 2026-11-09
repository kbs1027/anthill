export type RetryOptions = { stop: () => boolean; baseMs?: number; maxMs?: number }

/**
 * 성공할 때까지 호출한다. 대기는 [baseMs]부터 두 배씩 [maxMs]까지. [stop]이 참이 되면(화면을 떠났거나 더 새로운 재시도가 시작됨)
 * 결과를 버리고 undefined를 돌려준다.
 */
export async function retryUntil<T>(fn: () => Promise<T>, { stop, baseMs = 200, maxMs = 2000 }: RetryOptions): Promise<T | undefined> {
  let wait = baseMs
  while (!stop()) {
    try {
      const value = await fn()
      return stop() ? undefined : value
    } catch {
      await new Promise((resolve) => setTimeout(resolve, wait))
      wait = Math.min(wait * 2, maxMs)
    }
  }
  return undefined
}
