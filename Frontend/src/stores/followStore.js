// @ts-check
/**
 * 作者关注状态的乐观覆盖表：关注/取关后先于接口回包生效，
 * 详情页、短视频、个人主页读同一张表，避免同一作者在不同入口显示不一致。
 */
import { defineStore } from "pinia";
import { ref } from "vue";

export const useFollowStore = defineStore("follow", () => {
  /** authorId → isFollow */
  const overrideByAuthorId = ref({});

  /** 无覆盖时返回 undefined，调用方回落到接口给的 isFollow */
  function getOverride(authorId) {
    const k = String(authorId ?? "");
    if (!k) return undefined;
    return Object.prototype.hasOwnProperty.call(overrideByAuthorId.value, k)
      ? overrideByAuthorId.value[k]
      : undefined;
  }

  function setOverride(authorId, isFollow) {
    const k = String(authorId ?? "");
    if (!k) return;
    overrideByAuthorId.value = { ...overrideByAuthorId.value, [k]: Boolean(isFollow) };
  }

  return { overrideByAuthorId, getOverride, setOverride };
});
