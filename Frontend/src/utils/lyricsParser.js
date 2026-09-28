// @ts-check
/**
 * 解析 LRC / 增强 LRC（行内 <mm:ss.xx> 逐字时间戳）为统一结构。
 * QRC 等为二进制，此处不支持；可改用纯文本 LRC。
 */

function parseTimeTriple(mm, ss, frac) {
  const m = Number(mm) || 0;
  const s = Number(ss) || 0;
  let ms = 0;
  if (frac != null && frac !== "") {
    const f = String(frac);
    if (f.length === 2) ms = Number(f) * 10;
    else if (f.length >= 3) ms = Number(f.slice(0, 3));
    else ms = Number(f) * 10;
  }
  return (m * 60 + s) * 1000 + ms;
}

/** 匹配 [mm:ss.xx] 或 [mm:ss.xxx] */
const LINE_TIME_RE = /^\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\]\s*(.*)$/;

function splitMainAndTranslation(text) {
  const t = String(text || "").trim();
  if (!t) return { main: "", trans: "" };
  const m = t.split(/\s*[｜/]\s*/);
  if (m.length >= 2) {
    return { main: m[0].trim(), trans: m.slice(1).join(" ").trim() };
  }
  return { main: t, trans: "" };
}

function parseWordsInLine(rest) {
  const s = String(rest);
  const re = /<(\d{2}):(\d{2})(?:\.(\d{2,3}))?>/g;
  const times = [];
  let match;
  while ((match = re.exec(s)) !== null) {
    times.push({
      index: match.index,
      end: match.index + match[0].length,
      t: parseTimeTriple(match[1], match[2], match[3]),
    });
  }
  if (!times.length) {
    const { main, trans } = splitMainAndTranslation(s);
    return { text: main, translation: trans, words: null };
  }
  const words = [];
  for (let i = 0; i < times.length; i++) {
    const start = times[i].t;
    const textStart = times[i].end;
    const textEnd = i + 1 < times.length ? times[i + 1].index : s.length;
    const chunk = s.slice(textStart, textEnd).trim();
    if (chunk) {
      words.push({ timeMs: start, text: chunk });
    }
  }
  const plain = s.replace(/<\d{2}:\d{2}(?:\.\d{2,3})?>/g, " ").replace(/\s+/g, " ").trim();
  const { main, trans } = splitMainAndTranslation(plain);
  return { text: main || plain, translation: trans, words };
}

/**
 * @param {string} raw
 * @returns {{ lines: Array<{ timeMs: number, text: string, translation: string, words: Array<{timeMs:number,text:string}>|null }> }}
 */
export function parseLyricsText(raw) {
  if (!raw || typeof raw !== "string") {
    return { lines: [] };
  }
  const lines = [];
  const seen = raw.split(/\r?\n/);
  for (const row of seen) {
    const line = row.trim();
    if (!line || line.startsWith("#")) continue;
    const m = line.match(LINE_TIME_RE);
    if (!m) continue;
    const timeMs = parseTimeTriple(m[1], m[2], m[3]);
    const rest = m[4] || "";
    const parsed = parseWordsInLine(rest);
    lines.push({
      timeMs,
      text: parsed.text || rest.replace(/<\d{2}:\d{2}(?:\.\d{2,3})?>/g, "").trim(),
      translation: parsed.translation,
      words: parsed.words,
    });
  }
  lines.sort((a, b) => a.timeMs - b.timeMs);
  return { lines };
}

/**
 * 简单 QRC 文本提示：若为明显二进制则返回空并提示
 */
export function sniffLyricsFormat(text) {
  if (!text) return "empty";
  const t = text.slice(0, 200);
  if (/[\x00-\x08\x0B\x0C\x0E-\x1F]/.test(t) && text.length > 50) {
    return "binary";
  }
  if (text.includes("[") && text.includes("]")) return "lrc";
  return "unknown";
}
