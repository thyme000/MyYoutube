/**
 * 菜单编排下沉冒烟：侧边栏切换各菜单、首页快照恢复、沉浸/关注双 tab 切换、详情开关回归。
 * 前置：vite dev(5173) + 后端(8080) 在跑；headless 浏览器 --remote-debugging-port=9222。
 * 用法：node scripts/e2e/smoke-menu-orch.mjs
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
check("登录进入首页", await waitFor(`!!document.querySelector(".app-layout") && location.pathname === "/"`, 15000));
check("首页卡片加载", await waitFor(`document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000));

// 侧边栏按菜单名点击
async function clickMenu(label) {
  return js(`(async () => {
    const item = [...document.querySelectorAll("aside button, aside a, nav button, nav a, [class*=sidebar] *, [class*=menu] *")]
      .find((el) => el.childElementCount === 0 && el.textContent.trim() === "${label}");
    if (!item) return 'NOT FOUND';
    item.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await new Promise((r) => setTimeout(r, 1500));
    return location.pathname;
  })()`);
}

// 首页 → 音乐 → 首页（快照恢复）
console.log("切音乐:", await clickMenu("音乐"));
check("切到 /music", await waitFor(`location.pathname === "/music"`));
console.log("切回首页:", await clickMenu("首页"));
check("切回 / 且卡片恢复", await waitFor(`location.pathname === "/" && document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000));

// 首页 → 沉浸 → 关注（双 tab 切换分支）
console.log("切沉浸:", await clickMenu("沉浸"));
check("切到 /immersive", await waitFor(`location.pathname === "/immersive"`));
console.log("切关注:", await clickMenu("关注"));
check("切到 /subscribed", await waitFor(`location.pathname === "/subscribed"`));
check("关注页视频流渲染", await waitFor(`document.querySelector(".content--immersive") || document.querySelectorAll("[class*=feed], [class*=card]").length > 0`, 15000));

// 关注 → 历史 → 赞过
console.log("切历史:", await clickMenu("历史播放记录"));
check("切到 /history", await waitFor(`location.pathname === "/history"`));
console.log("切赞过:", await clickMenu("赞过的视频"));
check("切到 /liked", await waitFor(`location.pathname === "/liked"`));

// 赞过 → 聊天
console.log("切聊天:", await clickMenu("聊天"));
check("切到 /chat", await waitFor(`location.pathname === "/chat"`));

// 回首页后详情开关回归
console.log("切首页:", await clickMenu("首页"));
check("回 / 且卡片加载", await waitFor(`location.pathname === "/" && document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`, 20000));
await js(`document.querySelector(".video-card:not(.video-card--skeleton)").dispatchEvent(new MouseEvent("click", { bubbles: true }))`);
// 首页混排: 首卡可能是动态(/dynamic/:id)也可能是视频(/video/:id), 两者都算详情层打开
check("详情仍可打开", await waitFor(`/^\\/(video|dynamic)\\/\\d+$/.test(location.pathname)`, 12000));
await js(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); 1`);
check("ESC 关详情", await waitFor(`location.pathname === "/"`, 12000));

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} PASS`);
process.exit(failed.length ? 1 : 0);
