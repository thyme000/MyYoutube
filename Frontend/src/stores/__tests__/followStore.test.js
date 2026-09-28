import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useFollowStore } from "../followStore";

beforeEach(() => {
  setActivePinia(createPinia());
});

describe("followStore 乐观覆盖表", () => {
  it("无覆盖时返回 undefined，调用方可回落到接口给的 isFollow", () => {
    const store = useFollowStore();
    expect(store.getOverride(9)).toBeUndefined();
  });

  it("false 覆盖不会被当成「无覆盖」丢掉", () => {
    const store = useFollowStore();
    store.setOverride(9, true);
    expect(store.getOverride(9)).toBe(true);
    store.setOverride(9, false);
    expect(store.getOverride(9)).toBe(false);
  });

  it("写入时统一转成布尔，并把 id 归一成字符串", () => {
    const store = useFollowStore();
    store.setOverride(9, "yes");
    expect(store.overrideByAuthorId["9"]).toBe(true);
    store.setOverride("9", 0);
    expect(store.overrideByAuthorId["9"]).toBe(false);
    expect(store.getOverride("9")).toBe(false);
  });

  it("空 id 读写都安全，不产生 '' 键", () => {
    const store = useFollowStore();
    store.setOverride(null, true);
    store.setOverride(undefined, true);
    store.setOverride("", true);
    expect(store.overrideByAuthorId).toEqual({});
    expect(store.getOverride(null)).toBeUndefined();
    expect(store.getOverride("")).toBeUndefined();
  });

  it("每次写入替换整个对象（保持不可变，触发响应式更新）", () => {
    const store = useFollowStore();
    const before = store.overrideByAuthorId;
    store.setOverride(1, true);
    expect(store.overrideByAuthorId).not.toBe(before);
    expect(before).toEqual({});
  });
});
