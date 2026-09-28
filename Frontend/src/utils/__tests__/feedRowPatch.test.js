import { describe, it, expect } from "vitest";
import {
  patchRowById,
  patchRowsByAuthorId,
  patchRowsById,
  removeRowById,
} from "../feedRowPatch.js";

const A = { id: "1", authorId: "10", likeCount: 1, isFollow: false };
const B = { id: "2", authorId: "10", likeCount: 2, isFollow: false };
const C = { id: "3", authorId: "20", likeCount: 3, isFollow: true };

describe("patchRowById", () => {
  it("按字符串 id 浅合并命中行", () => {
    const out = patchRowById([A, C], "1", { likeCount: 9 });
    expect(out[0]).toEqual({ ...A, likeCount: 9 });
    expect(out[1]).toBe(C);
  });

  it("id 必须由调用方归一成字符串：传数字不会命中", () => {
    // 比较用的是 String(row.id) === id，故 feedPatchStore 会先 String(id) 再往下传
    const list = [A];
    expect(patchRowById(list, 1, { likeCount: 9 })).toBe(list);
    expect(patchRowById(list, "1", { likeCount: 9 })[0].likeCount).toBe(9);
  });

  it("未命中时返回原数组（保持引用，避免无谓的响应式更新）", () => {
    const list = [A, C];
    expect(patchRowById(list, "999", { likeCount: 9 })).toBe(list);
  });

  it("非数组或空数组原样返回", () => {
    expect(patchRowById(null, "1", {})).toBeNull();
    const empty = [];
    expect(patchRowById(empty, "1", {})).toBe(empty);
  });

  it("忽略列表里的空行", () => {
    const list = [null, A];
    expect(patchRowById(list, "1", { likeCount: 9 })[1].likeCount).toBe(9);
  });
});

describe("patchRowsByAuthorId", () => {
  it("改同一作者名下的所有行，其它作者不动", () => {
    const out = patchRowsByAuthorId([A, B, C], "10", true);
    expect(out[0].isFollow).toBe(true);
    expect(out[1].isFollow).toBe(true);
    expect(out[2]).toBe(C);
  });

  it("取关（false）同样写入，不会被当成空值跳过", () => {
    const out = patchRowsByAuthorId([{ ...A, isFollow: true }], "10", false);
    expect(out[0].isFollow).toBe(false);
  });

  it("未命中时返回原数组", () => {
    const list = [A, B];
    expect(patchRowsByAuthorId(list, "999", true)).toBe(list);
  });
});

describe("patchRowsById", () => {
  it("用户列表里 id 即用户自身 id，据此同步 isFollow", () => {
    const out = patchRowsById([{ id: "10", isFollow: false }, { id: "20", isFollow: false }], "10", true);
    expect(out[0].isFollow).toBe(true);
    expect(out[1].isFollow).toBe(false);
  });

  it("未命中时返回原数组", () => {
    const list = [{ id: "10", isFollow: false }];
    expect(patchRowsById(list, "99", true)).toBe(list);
  });
});

describe("removeRowById", () => {
  it("按 id 移除，其余行保持原引用", () => {
    const out = removeRowById([A, B, C], "1");
    expect(out).toEqual([B, C]);
    expect(out[0]).toBe(B);
  });

  it("id 必须由调用方归一成字符串：传数字不会命中", () => {
    expect(removeRowById([A], 1)).toHaveLength(1);
    expect(removeRowById([A], "1")).toEqual([]);
  });

  it("列表里没有该 id 时返回等值的新数组", () => {
    const list = [A, C];
    const out = removeRowById(list, "999");
    expect(out).not.toBe(list);
    expect(out).toEqual(list);
  });

  it("非数组或空数组原样返回", () => {
    expect(removeRowById(null, "1")).toBeNull();
    const empty = [];
    expect(removeRowById(empty, "1")).toBe(empty);
  });
});
