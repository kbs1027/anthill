// 화면을 실제 사용자처럼 조작하고 캡처하는 작은 도구. 설치된 Chrome을 창 없이 띄워 직접 조종한다(추가 설치 없음).
// 필요: Google Chrome, Node 22 이상(내장 WebSocket·fetch). Chrome 위치가 다르면 환경 변수 CHROME으로 알려 준다.
//
// 두 가지를 내보낸다.
//   openBrowser()  브라우저 하나를 띄워 조종하는 객체(page)를 돌려준다.
//   runScenario()  작업별 E2E 시나리오를 실행한다. 단계마다 번호를 매겨 캡처하고 통과·실패를 result.json에 남긴다.
//                  시나리오 틀: .claude/skills/work-e2e/e2e-template.mjs

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Chrome을 띄우고 빈 탭 하나를 조종하는 객체를 돌려준다. 다 쓰면 반드시 close()를 부른다. */
export async function openBrowser({ width = 1440, height = 900, scale = 2, mobile = false } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'anthill-browser-'))
  // 포트 0: 빈 포트를 Chrome이 고르게 해서 다른 실행과 부딪치지 않게 한다. 고른 포트는 프로필 폴더의 파일에 적힌다.
  const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', '--lang=ko-KR', 'about:blank'], { stdio: 'ignore' })
  const exited = new Promise((r) => chrome.once('exit', r))
  let port
  for (let i = 0; i < 100 && !port; i++) {
    await sleep(100)
    try { port = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0] } catch { /* 아직 안 떴다 */ }
  }
  if (!port) {
    chrome.kill()
    throw new Error(`Chrome이 뜨지 않았다: ${CHROME}`)
  }
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error('Chrome에 연결하지 못했다')) })

  let seq = 0
  const pending = new Map()
  const listeners = new Set()
  const errors = []
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id)
      pending.delete(m.id)
      m.error ? reject(new Error(m.error.message)) : resolve(m.result)
      return
    }
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text)
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
    listeners.forEach((l) => l(m))
  }

  /** Chrome DevTools Protocol 명령을 그대로 보낸다. 아래 함수로 안 되는 조작(휠, 키 입력 등)에 쓴다. */
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
  const once = (method, timeoutMs) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => { listeners.delete(on); reject(new Error(`${timeoutMs}ms 안에 ${method}가 오지 않았다`)) }, timeoutMs)
    const on = (m) => { if (m.method === method) { clearTimeout(timer); listeners.delete(on); resolve(m.params) } }
    listeners.add(on)
  })

  /** 화면 안에서 JS 식을 실행해 값을 돌려준다. 식이 오류를 내면 여기서도 오류가 난다. */
  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (exceptionDetails) throw new Error(`화면에서 실행 실패: ${exceptionDetails.exception?.description ?? exceptionDetails.text}`)
    return result.value
  }
  /** JS 식이 참이 될 때까지 기다린다. */
  const waitUntil = async (expression, timeoutMs = 15_000) => {
    const until = Date.now() + timeoutMs
    while (Date.now() < until) {
      if (await evaluate(expression).catch(() => false)) return
      await sleep(200)
    }
    throw new Error(`${timeoutMs}ms 안에 참이 되지 않았다: ${expression}`)
  }
  /** 선택자에 맞는 요소가 화면에 나타날 때까지 기다린다. */
  const waitFor = (selector, timeoutMs) => waitUntil(`!!document.querySelector(${JSON.stringify(selector)})`, timeoutMs)

  // 요소를 찾는 식. text를 주면 선택자에 맞는 것 중 글자가 정확히 같은 것을 고른다.
  const find = (selector, text) => text === undefined
    ? `document.querySelector(${JSON.stringify(selector)})`
    : `[...document.querySelectorAll(${JSON.stringify(selector)})].find((e) => e.textContent.trim() === ${JSON.stringify(text)})`
  const centerOf = async (selector, text) => {
    const point = await evaluate(`(() => { const e = ${find(selector, text)}; if (!e) return null; e.scrollIntoView({ block: 'center', inline: 'center' }); const b = e.getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 } })()`)
    if (!point) throw new Error(`요소가 없다: ${selector}${text === undefined ? '' : ` (글자: ${text})`}`)
    return point
  }

  const page = {
    /** 화면에서 난 오류(잡히지 않은 예외, console.error)가 쌓인다. 시나리오 끝에서 비어 있는지 확인한다. */
    errors,
    send,
    evaluate,
    waitUntil,
    waitFor,
    sleep,
    /** 주소로 이동하고 화면이 다 뜰 때까지 기다린다. */
    async goto(url, timeoutMs = 30_000) {
      const loaded = once('Page.loadEventFired', timeoutMs)
      const { errorText } = await send('Page.navigate', { url })
      if (errorText) {
        loaded.catch(() => {})
        throw new Error(`${url}을 열지 못했다: ${errorText}`)
      }
      await loaded
    },
    /** 화면 크기를 바꾼다. 휴대폰 화면은 { width: 390, height: 844, scale: 3, mobile: true }. */
    viewport: ({ width, height, scale = 2, mobile = false }) => send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile }),
    /** 요소 가운데를 마우스로 누른다. { text }를 주면 그 글자를 가진 요소를 누른다. */
    async click(selector, { text } = {}) {
      const { x, y } = await centerOf(selector, text)
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y })
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
    },
    /** 요소 가운데에 마우스를 올린다. */
    async hover(selector, { text } = {}) {
      const { x, y } = await centerOf(selector, text)
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y })
    },
    /** 입력 칸의 값을 text로 바꾼다(React가 알아차리도록 input 이벤트를 낸다). 빈 문자열이면 지운다. */
    async type(selector, text) {
      const ok = await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) return false; e.focus(); const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, ${JSON.stringify(text)}); e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); return true })()`)
      if (!ok) throw new Error(`입력 칸이 없다: ${selector}`)
    },
    /** 화면을 PNG로 저장한다. selector를 주면 그 요소만 잘라 저장한다. 폴더가 없으면 만든다. */
    async shot(path, selector) {
      let clip
      if (selector) {
        const box = await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) return null; const b = e.getBoundingClientRect(); return { x: b.x, y: b.y, width: b.width, height: b.height } })()`)
        if (!box) throw new Error(`캡처할 요소가 없다: ${selector}`)
        clip = { ...box, scale: 1 }
      }
      const { data } = await send('Page.captureScreenshot', { format: 'png', ...(clip ? { clip } : {}) })
      mkdirSync(dirname(path), { recursive: true })
      writeFileSync(path, Buffer.from(data, 'base64'))
      console.log(`캡처: ${path}`)
    },
    /** Chrome을 끄고 임시 프로필을 지운다. */
    async close() {
      ws.close()
      chrome.kill()
      await exited
      rmSync(profile, { recursive: true, force: true })
    },
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await page.viewport({ width, height, scale, mobile })
  return page
}

/**
 * 작업별 E2E 시나리오를 실행한다. 시나리오 파일에서 `await runScenario(import.meta, async ({ page, step, mode }) => { ... })`로 부른다.
 *
 * - 실행: `node <시나리오 파일> after` (구현 전 화면은 `before`). 캡처는 시나리오 파일 옆 `shots/<before|after>/`에 쌓인다.
 * - `step(이름, 동작, 옵션)`: 동작을 실행하고 끝난 화면을 `NN-이름.png`로 캡처한다. 동작이 오류를 던지면 그 단계는 실패다.
 *     옵션 clip: 이 선택자의 요소만 잘라 캡처 / afterOnly: before 실행에서는 건너뜀(새로 생기는 화면) / shot: false면 캡처 안 함
 * - 한 단계가 실패하면 그때 화면을 `NN-이름-FAILED.png`로 남기고 나머지 단계는 건너뛴다.
 * - 결과는 `shots/<before|after>/result.json`에 남는다(단계별 통과·실패, 화면에서 난 오류). 실패가 있으면 종료 코드 1.
 */
export async function runScenario(meta, body, { viewport } = {}) {
  const mode = process.argv[2] ?? 'after'
  if (mode !== 'before' && mode !== 'after') {
    console.error('사용: node <시나리오 파일> [before|after]')
    process.exit(2)
  }
  const out = join(meta.dirname, 'shots', mode)
  const result = { mode, startedAt: new Date().toISOString(), steps: [] }
  const page = await openBrowser(viewport)
  let failed = false

  const step = async (name, action, { clip, afterOnly = false, shot = true } = {}) => {
    const n = String(result.steps.length + 1).padStart(2, '0')
    const entry = { n, name, status: 'pass' }
    result.steps.push(entry)
    if (failed || (afterOnly && mode === 'before')) {
      entry.status = 'skip'
    } else {
      try {
        await action()
        if (shot) {
          await page.shot(join(out, `${n}-${name}.png`), clip)
          entry.shot = `${n}-${name}.png`
        }
      } catch (e) {
        failed = true
        entry.status = 'fail'
        entry.error = e.message
        await page.shot(join(out, `${n}-${name}-FAILED.png`)).then(() => { entry.shot = `${n}-${name}-FAILED.png` }, () => {})
      }
    }
    console.log(`${{ pass: '통과', fail: '실패', skip: '건너뜀' }[entry.status]} | ${n} ${name}${entry.error ? ` | ${entry.error}` : ''}`)
  }

  try {
    await body({ page, step, mode })
  } catch (e) {
    failed = true
    result.error = e.message
    console.log(`실패 | 단계 밖에서 오류 | ${e.message}`)
  } finally {
    result.pageErrors = [...page.errors]
    result.finishedAt = new Date().toISOString()
    await page.close()
  }
  mkdirSync(out, { recursive: true })
  writeFileSync(join(out, 'result.json'), `${JSON.stringify(result, null, 2)}\n`)
  const count = (status) => result.steps.filter((s) => s.status === status).length
  console.log(`\n${mode}: 통과 ${count('pass')} · 실패 ${count('fail')} · 건너뜀 ${count('skip')} · 화면 오류 ${result.pageErrors.length}건 → ${join(out, 'result.json')}`)
  process.exitCode = failed ? 1 : 0
}
