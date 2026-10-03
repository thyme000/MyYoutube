/**
 * 补充冒烟：详情内切视频 replace 不叠历史；详情 → 作者主页 → 后退恢复详情。
 * 前置：vite dev(5173) + 后端(8080) 在跑；headless 浏览器 --remote-debugging-port=9222。
 *
 * 数据前置：「详情内切视频」需要当前视频的作者还有别的作品（侧栏才有可切换项）。
 * 侧栏数据源 GET /videos/{authorId} 过滤 status=PUBLISHED 且 visibility=PUBLIC，
 * 而 PUT /videos/publish 建出来的是待审核（PENDING），且脚本无法走审核流程 ——
 * 所以本机无法用脚本补出「同作者两条已发布视频」，该步骤会明确报 SKIP（不是 FAIL）：
 * 它没被测到，而不是测过了。该语义已由 detailStore 单测覆盖
 * （「详情内切视频的地址栏语义」，断言 replace 而非 push）；此处 SKIP 只是少了 UI 层的冗余验证。
 *
 * 用法：node scripts/e2e/smoke-video-extra.mjs
 */
import { Cdp, browserEndpoint, evaluate } from "./cdp.mjs";
import { assertEnv, openVideoDetail, resolveVideoIdWithSiblings } from "./fixtures.mjs";

const results = [];
/** @param {"PASS"|"FAIL"|"SKIP"} state */
const check = (state, name, detail = "") => {
  results.push({ name, state });
  console.log(`${state}  ${name}${detail ? `  (${detail})` : ""}`);
};

await assertEnv();

const wsUrl = await browserEndpoint(9222);
const cdp = await Cdp.connect(wsUrl);
const tab = await (await fetch("http://127.0.0.1:9222/json/new?about:blank", { method: "PUT" })).json();
const { sessionId } = await cdp.send("Target.attachToTarget", { targetId: tab.id, flatten: true });
await cdp.send("Page.enable", {}, sessionId);

const js = (expr, t = 20000) => evaluate(cdp, sessionId, expr, t);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(expr, ms = 12000, step = 400) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (await js(expr)) return true;
    await sleep(step);
  }
  return false;
}

await cdp.send("Page.navigate", { url: "http://localhost:5173/" }, sessionId);
await sleep(2500);
await js(`(async () => {
  if (!document.querySelector('form.auth-panel')) return 'already-authed';
  const set = (sel, v) => { const el = document.querySelector(sel); el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); };
  set('input[placeholder="User name"]', 'test');
  set('input[placeholder="Password"]', 'password');
  document.querySelector('form.auth-panel button[type="submit"]').click();
  return 'submitted';
})()`);
await waitFor(`!!document.querySelector(".app-layout")`, 15000);
await waitFor(`document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000);

// 优先挑「作者还有别的作品」的视频，让侧栏切视频真的能跑；挑不到就退回任意一条
const siblingId = await resolveVideoIdWithSiblings(js);
const entered = await openVideoDetail(js, waitFor, { preferId: siblingId });
check("进入视频详情", !!entered.vid, `入口=${entered.via} vid=${entered.vid || "无"}`);
if (!entered.vid) {
  console.log("\n拿不到真实视频 id（/videos/list 为空），后续用例无法执行");
  console.log(`\n${results.filter((r) => r.state !== "FAIL").length}/${results.length} PASS`);
  process.exit(1);
}

// ---- 详情内切视频（侧栏作品项）→ replace 不叠历史：back 应回菜单页而非上一个视频 ----
// 必须点「非当前播放」的侧栏项：openFromSidebar 对同一视频是 no-op，点 --current 等于没切
const switchRes = await js(`(async () => {
  const before = location.pathname;
  const item = document.querySelector('.video-detail-side-item:not(.video-detail-side-item--current)');
  if (!item) return JSON.stringify({ skipped: 'NO OTHER SIDEBAR ITEM' });
  item.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 2500));
  return JSON.stringify({ before, after: location.pathname });
})()`);
const sw = JSON.parse(switchRes);
if (sw.skipped) {
  check(
    "SKIP",
    "详情内切视频(replace 不叠历史)",
    "无同作者两条已发布视频：侧栏过滤 PUBLISHED+PUBLIC，publish 只产出 PENDING；该语义由 detailStore 单测覆盖",
  );
} else {
  check(sw.after !== sw.before ? "PASS" : "FAIL", "详情内切视频(replace 不叠历史)", `${sw.before} → ${sw.after}`);
}
await js(`history.back(); 1`);
await sleep(1800);
const afterBack = await js("location.pathname");
check(afterBack === "/" ? "PASS" : "FAIL", "切视频后 back 落回菜单页", afterBack);

// ---- 详情 → 作者主页 → 后退恢复详情 ----
await waitFor(`document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000);
const reopened = await openVideoDetail(js, waitFor, { preferId: siblingId });
check("重新进入视频详情", !!reopened.vid, `入口=${reopened.via}`);
if (!reopened.vid) {
  console.log(`\n${results.filter((r) => r.state !== "FAIL").length}/${results.length} PASS`);
  process.exit(1);
}
const avatarClicked = await js(`(() => {
  const av = document.querySelector('img.video-detail-channel-avatar');
  if (!av) return false;
  av.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  return true;
})()`);
await sleep(2500);
const atProfile = await js("location.pathname");
check(avatarClicked && /^\/profile\//.test(atProfile) ? "PASS" : "FAIL", "详情内点作者头像进主页", atProfile);
await js(`history.back(); 1`);
check(
  (await waitFor(
    `location.pathname === ${JSON.stringify("/video/" + reopened.vid)} && !!document.querySelector('[class*=detail]')`,
    12000,
  ))
    ? "PASS"
    : "FAIL",
  "后退恢复详情",
  await js("location.pathname"),
);

const failed = results.filter((r) => r.state === "FAIL");
const skipped = results.filter((r) => r.state === "SKIP");
console.log(
  `\n${results.length - failed.length - skipped.length}/${results.length} PASS` +
    (skipped.length ? `，${skipped.length} SKIP（数据不足，非通过）` : ""),
);
process.exit(failed.length ? 1 : 0);
