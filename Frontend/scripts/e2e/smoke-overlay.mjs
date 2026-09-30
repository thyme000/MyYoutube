/**
 * 叠层专项冒烟：/studio 路由渲染、/upload 归底来路视图（StudioView 保持挂载、Tab 状态不重置）、
 * 深链 /upload 无底层、ESC 关上传回 /studio、/profile 深链与刷新恢复。
 * 前置：vite dev(5173) + 后端在跑；headless 浏览器 --remote-debugging-port=9222。
 * 用法：node scripts/e2e/smoke-overlay.mjs
 */
import { Cdp, browserEndpoint, evaluate } from "./cdp.mjs";

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
};
const wsUrl = await browserEndpoint(9222);
const cdp = await Cdp.connect(wsUrl);
const tab = await (await fetch("http://127.0.0.1:9222/json/new?about:blank", { method: "PUT" })).json();
const { sessionId } = await cdp.send("Target.attachToTarget", { targetId: tab.id, flatten: true });
await cdp.send("Page.enable", {}, sessionId);
const js = (expr, t = 20000) => evaluate(cdp, sessionId, expr, t);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(expr, ms = 12000, step = 400) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) { if (await js(expr)) return true; await sleep(step); }
  return false;
}
const url = (u) => `location.href = '${u}'`;

try {
  // 登录
  await js(url("http://localhost:5173/"));
  await waitFor(`!!document.querySelector('.auth-panel, .video-card, .card-grid')`);
  if (await js(`!!document.querySelector('.auth-panel')`)) {
    await js(`(async () => {
      const inp = [...document.querySelectorAll('.input-group input')];
      const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
      set(inp[0], 'test'); set(inp[1], 'password');
      document.querySelector('.btn-submit')?.click();
    })()`);
    await waitFor(`!document.querySelector('.auth-panel')`, 15000);
  }
  check("登录", await js(`!!document.querySelector('.app-layout')`));

  // /studio 深链：路由渲染 StudioView
  await js(url("http://localhost:5173/studio"));
  check("studio 深链渲染 StudioView", await waitFor(`!!document.querySelector('.content--studio') && !!document.querySelector('.studio-side-link, .studio-tab, [class*=studio]')`));
  await waitFor(`!!document.querySelector('.studio-tab.active')`);
  const tabBefore = await js(`document.querySelector('.studio-tab.active')?.textContent?.trim() || ''`);

  // /upload 叠上：StudioView 保持挂载（归底）
  await js(`history.pushState({}, '', '/upload'); window.dispatchEvent(new PopStateEvent('popstate'));`);
  await sleep(600);
  // 直接走路由 API 更可靠
  await js(`fetch('/__noop').catch(()=>{}); location.hash=''; `);
  await js(url("http://localhost:5173/upload"));
  await waitFor(`location.pathname === '/upload'`);
  check("upload 深链弹窗渲染", await waitFor(`!!document.querySelector('.upload-modal')`));
  check("深链 /upload 无底层（不渲染 studio）", await js(`!document.querySelector('.content--studio')`));
  check("studio flag 未打开", await js(`document.querySelector('.topbar .action-btn')?.textContent?.includes('创建')`));

  // 从 studio 进 upload（真实入口），验证归底
  await js(url("http://localhost:5173/studio"));
  await waitFor(`!!document.querySelector('.content--studio')`);
  await js(`[...document.querySelectorAll('.topbar .action-btn')].find(b => b.textContent.includes('上传视频'))?.click()`);
  await waitFor(`location.pathname === '/upload'`);
  check("studio→upload 路由", await js(`location.pathname === '/upload'`));
  check("upload 弹窗渲染", await waitFor(`!!document.querySelector('.upload-modal')`));
  check("StudioView 归底保持挂载", await js(`!!document.querySelector('.content--studio')`));
  check("studio flag 仍开（顶栏=上传视频）", await js(`[...document.querySelectorAll('.topbar .action-btn')].some(b => b.textContent.includes('上传视频'))`));
  // ESC 关上传（App 仲裁）回 /studio
  await js(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));`);
  await waitFor(`location.pathname === '/studio'`);
  check("关上传回 /studio", await js(`location.pathname === '/studio' && !!document.querySelector('.content--studio')`));
  check("studio Tab 状态未重置", await js(`(document.querySelector('.studio-tab.active')?.textContent?.trim() || '') === ${JSON.stringify(tabBefore)}`));

  // profile 深链刷新
  await js(url("http://localhost:5173/profile"));
  await waitFor(`!!document.querySelector('.profile-side-card, .profile-hero, [class*=profile-]')`);
  check("profile 深链渲染", await js(`!!document.querySelector('.profile-side-card, .profile-hero')`));
  await js(`location.reload()`);
  await waitFor(`!!document.querySelector('.profile-side-card, .profile-hero') || !!document.querySelector('.auth-panel')`, 15000);
  check("profile 刷新恢复", await js(`!!document.querySelector('.profile-side-card, .profile-hero')`));
} catch (e) {
  check("异常终止", false, String(e).slice(0, 120));
}
console.log(`\
${results.filter(r => r.ok).length}/${results.length} PASS`);
process.exit(results.every(r => r.ok) ? 0 : 1);
