/**
 * 逐路由抓取「DOM 结构 + 计算样式」快照，用于 CSS / 路由改动的前后回归对比。
 *
 * 用法:
 *   npm run e2e:snapshot -- --name base --login test:password   # 首次，自动登录并录 fixtures
 *   npm run e2e:snapshot -- --name before                       # 改之前
 *   npm run e2e:snapshot -- --name after                        # 改之后
 *   npm run e2e:diff before after
 *
 * 三种取数模式:
 *   off     不动 fetch，直连真实后端。要求后端在跑且登录态有效。
 *   record  同上，额外把每个响应按 "METHOD path?query" 存进 fixtures，供后续离线回放。
 *   replay  拦截 fetch 回放 fixtures，不依赖后端与登录态，结果完全确定 —— 回归就用它。
 */
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Cdp, browserEndpoint, evaluate } from "./cdp.mjs";
import { MENU_ROUTES } from "../../src/router/menuRoutes.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(HERE, "../..");
const SNAP_DIR = path.join(HERE, ".snapshots");
const FIXTURES = path.join(SNAP_DIR, "fixtures.json");

const STYLE_PROPS = [
  "display", "position", "top", "left", "right", "bottom", "width", "height",
  "marginTop", "marginRight", "marginBottom", "marginLeft", "paddingTop",
  "paddingRight", "paddingBottom", "paddingLeft", "fontSize", "fontWeight",
  "lineHeight", "color", "backgroundColor", "borderRadius", "borderTopWidth",
  "borderTopColor", "opacity", "flexDirection", "justifyContent", "alignItems",
  "gap", "overflow", "textAlign", "zIndex", "visibility", "boxShadow",
  "transform", "gridTemplateColumns",
];

// vite dev 按组件注入 <style>，标签数量随组件树变化，必须从快照里排除
const SKIP_TAGS = ["STYLE", "SCRIPT", "LINK", "META", "TITLE", "HEAD", "BASE", "NOSCRIPT"];

const BROWSERS = [
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (!a.startsWith("--")) continue;
    const eq = a.indexOf("=");
    if (eq !== -1) {
      out[a.slice(2, eq)] = a.slice(eq + 1);
      continue;
    }
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      out[key] = next;
      i += 1;
    } else {
      out[key] = "true";
    }
  }
  return out;
}

/** 开发态后端不校验验证码，登录即可换取令牌；每次录制都重新登录，避免令牌过期 */
async function fetchToken(backend, user, pass) {
  const res = await fetch(`${backend}/login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username: user, password: pass }),
  });
  const body = await res.json();
  if (body.code !== 200 || !body.data) throw new Error(`登录失败: ${JSON.stringify(body)}`);
  return body.data;
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

async function reachable(url) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(1500) });
    return res.ok;
  } catch (err) {
    void err;
    return false;
  }
}

async function waitFor(url, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await reachable(url)) return true;
    await sleep(300);
  }
  throw new Error(`等待 ${label} 超时: ${url}`);
}

/** 注入到每个页面最前面：给登录态，并按模式回放 / 录制接口响应 */
function runtimeSource(mode, token, fixtures, delay) {
  return `(function () {
  var TOKEN = ${JSON.stringify(token || "")};
  var MODE = ${JSON.stringify(mode)};
  var FIXTURES = ${JSON.stringify(fixtures || {})};
  var DELAY = ${Number(delay) || 0};
  window.__e2eRecorded = {};
  window.__e2eMissed = {};
  window.__e2eSeen = {};
  window.__e2ePending = 0;
  if (TOKEN) { try { localStorage.setItem("myyoutube_token", TOKEN); } catch (e) { void e; } }
  function keyOf(input, init) {
    var url = typeof input === "string" ? input : input.url;
    var m = (init && init.method) || (typeof input === "object" && input && input.method) || "GET";
    var u = new URL(url, location.origin);
    return String(m).toUpperCase() + " " + u.pathname + u.search;
  }
  function json(body) {
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
  }
  function dec() { window.__e2ePending = Math.max(0, (window.__e2ePending || 0) - 1); }
  var real = window.fetch.bind(window);
  window.fetch = function (input, init) {
    window.__e2ePending = (window.__e2ePending || 0) + 1;
    var key = keyOf(input, init);
    window.__e2eSeen[key] = true;
    if (MODE === "replay") {
      var hit = Object.prototype.hasOwnProperty.call(FIXTURES, key);
      if (!hit) { window.__e2eMissed[key] = true; }
      // 必须走宏任务: 微任务立即兑现会让应用启动时序塌缩, 路由相关加载被跳过(实测 /liked 会错配成首页)
      var p = new Promise(function (resolve) { setTimeout(resolve, DELAY); }).then(function () {
        return hit ? json(FIXTURES[key]) : json({ code: 0, message: "ok", data: null });
      });
      return p.then(function (r) { dec(); return r; }, function (e) { dec(); throw e; });
    }
    return real(input, init).then(function (res) {
      dec();
      if (MODE === "record") {
        res.clone().json().then(function (body) { window.__e2eRecorded[key] = body; }).catch(function (e) { void e; });
      }
      return res;
    }, function (e) { dec(); throw e; });
  };
})()`;
}

function captureExpression() {
  return `(function () {
  var PROPS = ${JSON.stringify(STYLE_PROPS)};
  var SKIP = ${JSON.stringify(SKIP_TAGS)};
  var nodes = [];
  var all = document.querySelectorAll("*");
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (SKIP.indexOf(el.tagName) !== -1) { continue; }
    var cs = getComputedStyle(el);
    var style = {};
    for (var j = 0; j < PROPS.length; j++) { style[PROPS[j]] = cs[PROPS[j]]; }
    nodes.push({
      tag: el.tagName,
      cls: typeof el.className === "string" ? el.className : "",
      id: el.id || "",
      text: el.children.length === 0 ? String(el.textContent || "").slice(0, 80) : "",
      style: style
    });
  }
  var nav = document.querySelector(".sidebar .nav-item.active .nav-label");
  var main = document.querySelector("main.content");
  return JSON.stringify({
    title: document.title,
    path: location.pathname,
    nav: nav ? String(nav.textContent || "").trim() : "",
    mainClass: main ? String(main.className || "") : "",
    nodes: nodes
  });
})()`;
}

// 只看元素数会在「请求已发出、DOM 还没补上」时误判稳定，必须把在途请求一起看
const STABLE_EXPR =
  "String(window.__e2ePending || 0) + ':' + (document.querySelectorAll('*').length - document.querySelectorAll('style,script,link').length)";

async function waitStable(cdp, sessionId, tries = 20, gap = 400) {
  let prev = "";
  for (let i = 0; i < tries; i += 1) {
    await sleep(gap);
    const snap = String(await evaluate(cdp, sessionId, STABLE_EXPR));
    if (snap.startsWith("0:") && snap === prev) return true;
    prev = snap;
  }
  return false;
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  const name = args.name || "snapshot";
  // 默认用 localhost 而不是 127.0.0.1：vite 默认只监听 [::1]，IPv4 回环会拿到 502
  let base = (args.base || "http://localhost:5173").replace(/\/$/, "");
  const backend = (args.backend || "http://localhost:8080").replace(/\/$/, "");
  let token = args.token || process.env.E2E_TOKEN || "";
  const login = args.login || process.env.E2E_LOGIN || "";
  if (!token && login) {
    const [user, pass] = login.split(":");
    if (!user || !pass) throw new Error("--login 需形如 user:password");
    token = await fetchToken(backend, user, pass);
    console.error(`已登录 ${user}`);
  }

  await mkdir(SNAP_DIR, { recursive: true });

  let fixtures = {};
  let mode;
  if (args.record === "true") {
    if (!token) throw new Error("--record 需要 --token 或 --login");
    mode = "record";
  } else if (existsSync(FIXTURES)) {
    mode = "replay";
    fixtures = JSON.parse(await readFile(FIXTURES, "utf8"));
  } else if (token) {
    // 首次运行且能登录：直接录一份，省掉单独再跑一次 --record
    mode = "record";
  } else {
    mode = "off";
  }
  if (mode === "off") {
    console.error("警告: 既无 fixtures 也无法登录，接口会 401 退回登录页，快照几乎没有覆盖。");
    console.error("      先跑一次: npm run e2e:snapshot -- --name base --login user:password");
  }

  const bin = BROWSERS.find((p) => existsSync(p));
  if (!bin) throw new Error("未找到 Edge / Chrome / Chromium");

  // 自己起 vite 时换一个空闲端口，避免与已有的 dev server 抢 5173
  let vite = null;
  if (!(await reachable(base))) {
    const port = await freePort();
    base = `http://127.0.0.1:${port}`;
    vite = spawn(
      path.join(FRONTEND, "node_modules/.bin/vite"),
      ["--host", "127.0.0.1", "--port", String(port), "--strictPort"],
      { cwd: FRONTEND, stdio: "ignore" }
    );
    await waitFor(base, 60000, "vite dev server");
  }

  const dbgPort = await freePort();
  const profile = path.join(SNAP_DIR, ".profile");
  await mkdir(profile, { recursive: true });
  const browser = spawn(
    bin,
    [
      "--headless=new",
      `--remote-debugging-port=${dbgPort}`,
      `--user-data-dir=${profile}`,
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-extensions",
      "--hide-scrollbars",
      "--force-device-scale-factor=1",
      "--window-size=1440,900",
      "--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    ],
    { stdio: "ignore", detached: true }
  );

  const cdp = await (async () => {
    await waitFor(`http://127.0.0.1:${dbgPort}/json/version`, 30000, "CDP 端点");
    return Cdp.connect(await browserEndpoint(dbgPort));
  })();

  try {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    await cdp.send("Page.enable", {}, sessionId);
    await cdp.send("Runtime.enable", {}, sessionId);

    const errors = [];
    cdp.on((msg) => {
      if (msg.sessionId !== sessionId) return;
      if (msg.method === "Runtime.exceptionThrown") {
        const d = msg.params.exceptionDetails || {};
        errors.push(d.exception?.description || d.text || "exceptionThrown");
      } else if (msg.method === "Runtime.consoleAPICalled" && msg.params.type === "error") {
        errors.push((msg.params.args || []).map((a) => a.value ?? a.description ?? "").join(" "));
      }
    });

    await cdp.send(
      "Page.addScriptToEvaluateOnNewDocument",
      { source: runtimeSource(mode, token, fixtures, Number(args.delay ?? 40)) },
      sessionId
    );

    const routes = [];
    // 每条路由各自回读：页面导航会重置 window，录制结果不能只在最后读一次
    const recorded = {};
    for (const route of MENU_ROUTES) {
      errors.length = 0;
      await cdp.send("Page.navigate", { url: base + route.path }, sessionId);
      let status = "ok";
      let data = null;
      try {
        await waitStable(cdp, sessionId);
        data = JSON.parse(await evaluate(cdp, sessionId, captureExpression(), 30000));
      } catch (err) {
        status = `抓取失败: ${err.message}`;
      }
      if (status === "ok") {
        if (mode === "record") {
          const chunk = await evaluate(cdp, sessionId, "JSON.stringify(window.__e2eRecorded)", 30000);
          Object.assign(recorded, JSON.parse(chunk || "{}"));
        } else if (mode === "replay") {
          const missed = await evaluate(cdp, sessionId, "JSON.stringify(Object.keys(window.__e2eMissed))", 15000);
          const list = JSON.parse(missed || "[]");
          if (list.length) console.error(`  未命中 fixtures ${list.length} 个: ${list.slice(0, 3).join(" | ")}`);
        }
        if (args.verbose === "true") {
          const seen = await evaluate(cdp, sessionId, "JSON.stringify(Object.keys(window.__e2eSeen))", 15000);
          for (const k of JSON.parse(seen || "[]")) console.error(`    请求过 ${k}`);
        }
      }
      routes.push({ key: route.key, path: route.path, status, ...(data || { nodes: [] }), errors: [...errors] });
      console.error(
        `${route.path.padEnd(12)} ${status === "ok" ? `${String(data.nodes.length).padStart(5)} 元素` : status}`
      );
    }

    if (mode === "record") {
      await writeFile(FIXTURES, JSON.stringify(recorded, null, 2));
      console.error(`已录制 ${Object.keys(recorded).length} 条接口响应 -> ${FIXTURES}`);
    }

    const out = {
      createdAt: new Date().toISOString(),
      mode,
      base,
      viewport: "1440x900",
      routes,
    };
    const file = path.join(SNAP_DIR, `${name}.json`);
    await writeFile(file, JSON.stringify(out));
    console.error(`快照已写入 ${file}（模式 ${mode}）`);
  } finally {
    cdp.close();
    try {
      process.kill(-browser.pid, "SIGKILL");
    } catch (err) {
      void err;
    }
    if (vite) vite.kill("SIGKILL");
  }
}

run().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
