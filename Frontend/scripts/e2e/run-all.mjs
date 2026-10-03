/**
 * 串行跑全部冒烟脚本并汇总。
 *
 * 前置（脚本不代起，因为都依赖登录态与真实后端）：
 *   1. 后端(8080) + MySQL + Redis + 对象存储在跑
 *   2. cd Frontend && npm run dev        # 必须是 5173
 *   3. 带 --remote-debugging-port=9222 的 headless 浏览器
 *
 * 用法：npm run e2e:smoke
 */
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { assertEnv } from "./fixtures.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));

const SCRIPTS = [
  ["smoke-video.mjs", "视频详情路由（进/退/前进/ESC/深链/返回）"],
  ["smoke-video-extra.mjs", "详情内切视频 + 进作者主页后返回"],
  ["smoke-overlay.mjs", "叠层路由（studio / upload / profile）"],
  ["smoke-menu-orch.mjs", "菜单切换编排 + 详情层开关"],
];

function run(file) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [join(HERE, file)], { stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (d) => {
      out += d;
      process.stdout.write(d);
    });
    child.stderr.on("data", (d) => {
      out += d;
      process.stderr.write(d);
    });
    child.on("close", (code) => resolve({ code: code ?? 1, out }));
  });
}

try {
  await assertEnv();
} catch (e) {
  console.error(String(e.message || e));
  process.exit(1);
}

const summary = [];
for (const [file, label] of SCRIPTS) {
  console.log(`\n${"=".repeat(64)}\n▶ ${file} — ${label}\n${"=".repeat(64)}`);
  const { code, out } = await run(file);
  const skip = /(\d+) SKIP/.exec(out);
  summary.push({ file, ok: code === 0, skip: skip ? Number(skip[1]) : 0 });
}

console.log(`\n${"=".repeat(64)}\n汇总\n${"=".repeat(64)}`);
for (const s of summary) {
  const tail = s.skip ? `（含 ${s.skip} SKIP，非通过）` : "";
  console.log(`${s.ok ? "PASS" : "FAIL"}  ${s.file}${tail}`);
}
const failed = summary.filter((s) => !s.ok);
const skipped = summary.reduce((n, s) => n + s.skip, 0);
console.log(
  `\n${summary.length - failed.length}/${summary.length} 个脚本通过` +
    (skipped ? `，共 ${skipped} 条 SKIP（数据不足，非通过）` : ""),
);
process.exit(failed.length ? 1 : 0);
