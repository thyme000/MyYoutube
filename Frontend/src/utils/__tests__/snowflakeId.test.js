import { describe, it, expect } from "vitest";
import { safeSnowflakeId, parseDigitSnowflakeId } from "../snowflakeId.js";

describe("safeSnowflakeId", () => {
  it("数字/字符串/bigint 一律转字符串", () => {
    expect(safeSnowflakeId(123)).toBe("123");
    expect(safeSnowflakeId(" 456 ")).toBe("456");
    expect(safeSnowflakeId(9007199254740993n)).toBe("9007199254740993");
  });

  it("空值回退 fallback", () => {
    expect(safeSnowflakeId(null, "fb")).toBe("fb");
    expect(safeSnowflakeId("", "fb")).toBe("fb");
    expect(safeSnowflakeId(undefined)).toBe("");
  });
});

describe("parseDigitSnowflakeId", () => {
  it("纯数字串原样返回", () => {
    expect(parseDigitSnowflakeId("1234567890123456789")).toBe("1234567890123456789");
    expect(parseDigitSnowflakeId(42)).toBe("42");
  });

  it("过滤非数字 id（如 mock 卡片 id）", () => {
    expect(parseDigitSnowflakeId("immersive-1")).toBeNull();
    expect(parseDigitSnowflakeId("local-short-2")).toBeNull();
    expect(parseDigitSnowflakeId("")).toBeNull();
    expect(parseDigitSnowflakeId(null)).toBeNull();
  });
});
