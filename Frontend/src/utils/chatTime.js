function pad2(n) {
  return String(Math.trunc(n)).padStart(2, "0");
}

export function toTimeMs(v) {
  if (v == null || v === "") return 0;
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const s = v.trim();
    if (!s) return 0;
    if (/^\d+$/.test(s)) {
      const n = Number(s);
      return Number.isFinite(n) ? n : 0;
    }
    const t = new Date(s).getTime();
    return Number.isFinite(t) ? t : 0;
  }
  if (v instanceof Date) {
    const t = v.getTime();
    return Number.isFinite(t) ? t : 0;
  }
  try {
    const t = new Date(v).getTime();
    return Number.isFinite(t) ? t : 0;
  } catch {
    return 0;
  }
}

function dayKey(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

function isSameDay(a, b) {
  if (!a || !b) return false;
  const da = new Date(a);
  const db = new Date(b);
  return (
    da.getFullYear() === db.getFullYear() &&
    da.getMonth() === db.getMonth() &&
    da.getDate() === db.getDate()
  );
}

function diffDays(fromMs, toMs) {
  const from = new Date(fromMs);
  const to = new Date(toMs);
  const a = new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime();
  const b = new Date(to.getFullYear(), to.getMonth(), to.getDate()).getTime();
  return Math.round((b - a) / (24 * 60 * 60 * 1000));
}

function weekdayZh(dow) {
  const map = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"];
  return map[dow] || "星期";
}

function hm(ms) {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function formatChatTimestamp(tsMs, nowMs = Date.now()) {
  const t = Number(tsMs) || 0;
  const now = Number(nowMs) || Date.now();
  if (!t) return "";
  const delta = now - t;
  if (delta >= 0 && delta < 60 * 1000) return "刚刚";
  if (delta >= 60 * 1000 && delta < 60 * 60 * 1000) {
    const mins = Math.max(1, Math.floor(delta / (60 * 1000)));
    return `${mins}分钟前`;
  }

  if (isSameDay(t, now)) {
    return hm(t);
  }
  const daysAgo = diffDays(t, now);
  if (daysAgo === 1) {
    return `昨天 ${hm(t)}`;
  }
  if (daysAgo > 1 && daysAgo <= 6) {
    const d = new Date(t);
    return `${weekdayZh(d.getDay())} ${hm(t)}`;
  }
  const d = new Date(t);
  const y = d.getFullYear();
  const m = pad2(d.getMonth() + 1);
  const dd = pad2(d.getDate());
  const base = `${m}-${dd} ${hm(t)}`;
  const nowY = new Date(now).getFullYear();
  return y === nowY ? base : `${y}-${base}`;
}

export function shouldShowChatTimestamp(prevTsMs, curTsMs, nowMs = Date.now(), gapMs = 5 * 60 * 1000) {
  const cur = Number(curTsMs) || 0;
  if (!cur) return false;
  const prev = Number(prevTsMs) || 0;
  if (!prev) return true;
  if (!isSameDay(prev, cur)) return true;
  return cur - prev >= gapMs;
}

export function annotateChatMessagesWithTime(items, nowMs = Date.now(), gapMs = 5 * 60 * 1000) {
  const arr = Array.isArray(items) ? items : [];
  const out = [];
  let prevTs = 0;
  for (const m of arr) {
    const ts = toTimeMs(m?.createdAt ?? m?.createTime ?? m?.time ?? m?.timestamp);
    const show = shouldShowChatTimestamp(prevTs, ts, nowMs, gapMs);
    const label = show ? formatChatTimestamp(ts, nowMs) : "";
    out.push({ ...m, _showTime: show, _timeText: label });
    if (ts) prevTs = ts;
  }
  return out;
}

