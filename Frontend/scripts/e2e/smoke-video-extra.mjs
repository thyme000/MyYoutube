/**
 * 补充冒烟：详情内切视频 replace 不叠历史；详情 → 作者主页 → 后退恢复详情。
 * 前置同 smoke-video.mjs。用法：node scripts/e2e/smoke-video-extra.mjs
 */
import { Cdp, browserEndpoint, evaluate } from "./cdp.mjs";

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
  set('input[placeholder="Captcha"]', '000000');
  document.querySelector('form.auth-panel button[type="submit"]').click();
  return 'submitted';
})()`);
await waitFor(`!!document.querySelector(".app-layout")`, 15000);
await waitFor(`document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000);
await js(`document.querySelector(".video-card:not(.video-card--skeleton)").dispatchEvent(new MouseEvent("click", { bubbles: true }))`);
await waitFor(`/^\\/video\\//.test(location.pathname)`, 12000);

// 详情内切视频（侧栏作品项）→ replace 不叠历史：back 应回菜单页而非上一个视频
const switchRes = await js(`(async () => {
  const before = location.pathname;
  const item = document.querySelector('.video-detail-side-item');
  if (!item) return 'NO SIDEBAR ITEM';
  item.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 2500));
  return JSON.stringify({ before, after: location.pathname });
})()`);
console.log("详情内切视频:", switchRes);
await js(`history.back(); 1`);
await sleep(1800);
const afterBack = await js("location.pathname");
console.log("切视频后 back 落点:", afterBack, afterBack === "/" ? "PASS(replace 不叠历史)" : "CHECK");

// 详情 → 作者主页 → 后退恢复详情
await waitFor(`document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000);
await js(`document.querySelector(".video-card:not(.video-card--skeleton)").dispatchEvent(new MouseEvent("click", { bubbles: true }))`);
await waitFor(`/^\\/video\\//.test(location.pathname)`, 12000);
const vid = await js("location.pathname");
await js(`(async () => {
  const av = document.querySelector('img.video-detail-channel-avatar');
  if (av) { av.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 'clicked'; }
  return 'NO AVATAR';
})()`);
await sleep(2500);
const atProfile = await js("location.pathname");
await js(`history.back(); 1`);
const restored = await waitFor(`location.pathname === "${vid}" && !!document.querySelector('[class*=detail]')`, 12000);
console.log("进主页落点:", atProfile, "| 后退恢复详情:", restored ? "PASS" : "FAIL " + (await js("location.pathname")));
process.exit(restored ? 0 : 1);
