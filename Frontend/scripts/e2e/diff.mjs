import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SNAP_DIR = path.join(HERE, ".snapshots");

const [nameA, nameB] = process.argv.slice(2);
if (!nameA || !nameB) {
  console.error("用法: npm run e2e:diff -- <基线名> <对比名>");
  process.exit(1);
}

const a = JSON.parse(await readFile(path.join(SNAP_DIR, `${nameA}.json`), "utf8"));
const b = JSON.parse(await readFile(path.join(SNAP_DIR, `${nameB}.json`), "utf8"));

const byKey = (snap) => new Map(snap.routes.map((r) => [r.key, r]));
const mapA = byKey(a);
const mapB = byKey(b);
const keys = [...new Set([...mapA.keys(), ...mapB.keys()])];

function nodeLabel(n) {
  const cls = String(n.cls || "").trim().split(/\s+/).filter(Boolean).join(".");
  return `<${String(n.tag).toLowerCase()}${n.id ? `#${n.id}` : ""}${cls ? `.${cls}` : ""}>`;
}

let total = 0;
const lines = [];
const add = (line) => {
  total += 1;
  if (lines.length < 60) lines.push(line);
};

for (const key of keys) {
  const ra = mapA.get(key);
  const rb = mapB.get(key);
  const route = rb || ra;
  lines.push(`--- ${route.path} (${key}) ---`);

  if (!ra || !rb) {
    add(`  快照缺失: ${!ra ? nameA : nameB}`);
    continue;
  }
  if (ra.status !== rb.status) add(`  状态: ${ra.status} -> ${rb.status}`);
  if (ra.title !== rb.title) add(`  标题: "${ra.title}" -> "${rb.title}"`);
  if (ra.nav !== rb.nav) add(`  侧栏高亮: "${ra.nav}" -> "${rb.nav}"`);
  if (ra.mainClass !== rb.mainClass) add(`  内容区 class: "${ra.mainClass}" -> "${rb.mainClass}"`);

  const ea = ra.errors || [];
  const eb = rb.errors || [];
  for (let i = ea.length; i < eb.length; i += 1) add(`  新增运行时报错: ${eb[i]}`);

  const na = ra.nodes || [];
  const nb = rb.nodes || [];
  if (na.length !== nb.length) {
    add(`  元素数: ${na.length} -> ${nb.length}`);
  }
  const shared = Math.min(na.length, nb.length);
  for (let i = 0; i < shared; i += 1) {
    const x = na[i];
    const y = nb[i];
    if (x.tag !== y.tag || x.cls !== y.cls || x.id !== y.id) {
      add(`  [${i}] 结构: ${nodeLabel(x)} -> ${nodeLabel(y)}`);
      continue;
    }
    if (x.text !== y.text) add(`  [${i}] 文本 ${nodeLabel(x)}: "${x.text}" -> "${y.text}"`);
    const sa = x.style || {};
    const sb = y.style || {};
    for (const p of Object.keys(sa)) {
      if (sa[p] !== sb[p]) add(`  [${i}] 样式 ${nodeLabel(x)} ${p}: "${sa[p]}" -> "${sb[p]}"`);
    }
  }
  for (let i = shared; i < na.length; i += 1) add(`  [${i}] 仅存在于 ${nameA}: ${nodeLabel(na[i])}`);
  for (let i = shared; i < nb.length; i += 1) add(`  [${i}] 仅存在于 ${nameB}: ${nodeLabel(nb[i])}`);
}

for (const line of lines) console.log(line);
if (total > lines.length) console.log(`... 另有 ${total - lines.length} 处未列出`);
console.log(`\n总计: ${total} 处差异（${nameA} -> ${nameB}，模式 ${a.mode} -> ${b.mode}）`);
process.exit(total > 0 ? 1 : 0);
