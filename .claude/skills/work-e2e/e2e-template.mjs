// E2E 시나리오 틀. 작업폴더에 e2e.mjs로 복사한 뒤 계획서 7절의 표를 step으로 옮긴다.
//   cp .claude/skills/work-e2e/e2e-template.mjs docs/work/<날짜>-<작업이름>/e2e.mjs
//   node docs/work/<날짜>-<작업이름>/e2e.mjs before   구현 전 화면 → shots/before/
//   node docs/work/<날짜>-<작업이름>/e2e.mjs after    구현 후 화면 → shots/after/
// 단계별 통과·실패는 shots/<before|after>/result.json에 남는다. 쓸 수 있는 기능은 scripts/lib/browser.mjs의 주석에 있다.

import { runScenario } from '../../../scripts/lib/browser.mjs'

// 컨테이너 전체 실행은 3000, 로컬 실행(Vite)은 BASE_URL=http://localhost:5173 으로 바꿔 실행한다.
const BASE = process.env.BASE_URL ?? 'http://localhost:3000'

// 기대한 결과가 아니면 오류를 던져 그 단계를 실패로 만든다.
const expect = (ok, message) => { if (!ok) throw new Error(message) }

await runScenario(import.meta, async ({ page, step }) => {
  // 단계 이름은 캡처 파일 이름이 된다(01-first-screen.png). 영어 소문자와 하이픈으로 쓴다.
  // 캡처는 동작이 끝난 직후에 찍힌다. 보여 줄 것이 다 그려질 때까지 기다린 뒤에 끝낸다
  // (여기서는 종목 목록과, 차트가 그려지면 생기는 범례 값 `.legend dd`).
  await step('first-screen', async () => {
    await page.goto(BASE)
    await page.waitFor('.market-row')
    await page.waitFor('.legend dd')
    const rows = await page.evaluate(`document.querySelectorAll('.market-row').length`)
    expect(rows > 10, `종목 목록이 ${rows}줄뿐이다`)
  })

  // clip: 그 요소만 잘라 캡처한다.
  await step('day-unit', async () => {
    await page.click('.units button', { text: '일' })
    await page.waitUntil(`!!document.querySelector('.legend dd')`)
  }, { clip: '.chart-panel' })

  // afterOnly: 이번 작업으로 새로 생기는 화면. before 실행에서는 건너뛴다.
  // await step('new-screen', async () => { ... }, { afterOnly: true })
})
