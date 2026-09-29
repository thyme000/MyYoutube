/**
 * 视频详情路由化冒烟：真实登录态验证 /video/:id 的进、退、前进恢复、ESC、深链、详情内返回。
 * 前置：vite dev(5173) + 后端(8080) 在跑；headless 浏览器开着 --remote-debugging-port=9222。
 * 用法：node scripts/e2e/smoke-video.mjs
 */
import { Cdp, browserEndpoint, evaluate } from "./cdp.mjs";

const FRONT = "http://localhost:5173";
const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
};

const wsUrl = await browserEndpoint(9222);
const cdp = await Cdp.connect(wsUrl);
const tab = await (await fetch("http://127.0.0.1:9222/json/new?about:blank", { method: "PUT" })).json();
const { sessionId } = await cdp.send("Target.attachToTarget", { targetId: tab.id, flatten: true });
await cdp.send("Page.enable", {}, sessionId);
await cdp.send("Runtime.enable", {}, sessionId);

const js = (expr, t = 20000) => evaluate(cdp, sessionId, expr, t);
const nav = (url) => cdp.send("Page.navigate", { url }, sessionId);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** 轮询直到 expression 为真或超时 */
async function waitFor(expr, ms = 12000, step = 400) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (await js(expr)) return true;
    await sleep(step);
  }
  return false;
}

await nav(`${FRONT}/`);
await sleep(2500);

// ---- 登录（已有登录态则跳过）----
await js(`(async () => {
  if (!document.querySelector('form.auth-panel')) return 'already-authed';
  const set = (sel, v) => {
    const el = document.querySelector(sel);
    el.value = v;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  };
  set('input[placeholder="User name"]', "test");
  set('input[placeholder="Password"]', "password");
  set('input[placeholder="Captcha"]', "000000");
  document.querySelector('form.auth-panel button[type="submit"]').click();
  return 'submitted';
})()`);
check("登录成功进入首页", await waitFor(`!!document.querySelector(".app-layout") && location.pathname === "/"`, 15000));

// ---- 首页点开一个真实视频卡 → /video/:id ----
const cardFound = await waitFor(
  `document.querySelectorAll(".video-card:not(.video-card--skeleton)").length > 0`,
  20000
);
check("首页卡片加载完成", cardFound);
await js(`window.__errs = []; window.addEventListener("error", (e) => window.__errs.push(String(e.message)));`);
await js(`document.querySelector(".video-card:not(.video-card--skeleton)")
  .dispatchEvent(new MouseEvent("click", { bubbles: true }));`);
check("点卡进入 /video/:id", await waitFor(`/^\\/video\\/\\d+$/.test(location.pathname)`), await js("location.pathname"));
check("详情层已渲染", await js(`!!document.querySelector("[class*=detail]")`), (await js("JSON.stringify(window.__errs)")) || "");

const vid = await js("location.pathname.slice('/video/'.length)");

// ---- 浏览器后退 → 详情关、回菜单页 ----
await js(`history.back(); 'ok'`);
check("后退关闭详情回首页", await waitFor(`location.pathname === "/"`), await js("location.pathname"));

// ---- 前进 → 快照恢复详情 ----
await js(`history.forward(); 'ok'`);
check(
  "前进恢复详情(同一视频)",
  await waitFor(`location.pathname === "/video/${vid}" && !!document.querySelector("[class*=detail]")`),
  await js("location.pathname"),
);

// ---- ESC 关闭 ----
await js(`document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); 'ok'`);
check("ESC 关详情回首页", await waitFor(`location.pathname === "/"`), await js("location.pathname"));

// ---- 深链 /video/:id 直接打开 ----
await nav(`${FRONT}/video/${vid}`);
check("深链直达详情", await waitFor(`location.pathname === "/video/${vid}" && !!document.querySelector("[class*=detail]")`));

// ---- 详情内「返回」按钮 → 回菜单页 ----
await js(`(() => {
  const btn = [...document.querySelectorAll("button")].find((b) => b.textContent.trim() === "返回");
  if (btn) btn.click();
  return !!btn;
})()`);
check("详情内返回按钮回菜单页", await waitFor(`location.pathname === "/"`), await js("location.pathname"));

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} PASS`);
process.exit(failed.length ? 1 : 0);
