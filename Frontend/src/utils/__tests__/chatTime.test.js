import { describe, it, expect } from "vitest";
import {
  toTimeMs,
  formatChatTimestamp,
  shouldShowChatTimestamp,
  annotateChatMessagesWithTime,
} from "../chatTime.js";

/** 固定“现在”：2026-09-27 12:00:00 本地时区，保证用例确定性 */
const NOW = new Date(2026, 8, 27, 12, 0, 0).getTime();

describe("toTimeMs", () => {
  it("数字与纯数字字符串直通", () => {
    expect(toTimeMs(1758945600000)).toBe(1758945600000);
    expect(toTimeMs("1758945600000")).toBe(1758945600000);
  });

  it("非法/空值返回 0", () => {
    expect(toTimeMs(null)).toBe(0);
    expect(toTimeMs("")).toBe(0);
    expect(toTimeMs("not-a-date")).toBe(0);
  });

  it("Date 对象与日期字符串可解析", () => {
    expect(toTimeMs(new Date(NOW))).toBe(NOW);
    expect(toTimeMs(new Date(NOW).toISOString())).toBe(NOW);
  });
});

describe("formatChatTimestamp", () => {
  it("一分钟内显示“刚刚”", () => {
    expect(formatChatTimestamp(NOW - 30 * 1000, NOW)).toBe("刚刚");
  });

  it("一小时内显示 N分钟前", () => {
    expect(formatChatTimestamp(NOW - 5 * 60 * 1000, NOW)).toBe("5分钟前");
  });

  it("同天显示 HH:mm", () => {
    expect(formatChatTimestamp(new Date(2026, 8, 27, 9, 5).getTime(), NOW)).toBe("09:05");
  });

  it("昨天带前缀", () => {
    expect(formatChatTimestamp(new Date(2026, 8, 26, 23, 30).getTime(), NOW)).toBe("昨天 23:30");
  });

  it("2-6 天前显示星期", () => {
    const t = new Date(2026, 8, 22, 8, 0).getTime();
    const dow = new Date(t).getDay();
    const expectMap = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"];
    expect(formatChatTimestamp(t, NOW)).toBe(`${expectMap[dow]} 08:00`);
  });

  it("当年更早显示 MM-DD HH:mm, 跨年带年份", () => {
    expect(formatChatTimestamp(new Date(2026, 0, 5, 10, 0).getTime(), NOW)).toBe("01-05 10:00");
    expect(formatChatTimestamp(new Date(2025, 11, 30, 10, 0).getTime(), NOW)).toBe(
      `2025-12-30 10:00`
    );
  });
});

describe("shouldShowChatTimestamp", () => {
  it("首条/跨天/超间隔显示, 间隔内不显示", () => {
    expect(shouldShowChatTimestamp(0, NOW, NOW)).toBe(true);
    expect(shouldShowChatTimestamp(NOW - 60 * 1000, NOW, NOW)).toBe(false);
    expect(shouldShowChatTimestamp(NOW - 10 * 60 * 1000, NOW, NOW)).toBe(true);
  });
});

describe("annotateChatMessagesWithTime", () => {
  it("兼容多种时间字段名并标注 _showTime/_timeText", () => {
    const msgs = [
      { id: 1, createdAt: NOW - 10 * 60 * 1000 },
      { id: 2, time: NOW - 4 * 60 * 1000 },
      { id: 3, timestamp: NOW - 30 * 1000 },
    ];
    const out = annotateChatMessagesWithTime(msgs, NOW);
    expect(out.map((m) => m._showTime)).toEqual([true, true, false]);
    expect(out[0]._timeText).toBe("10分钟前");
    expect(out[2]._timeText).toBe("");
  });
});
