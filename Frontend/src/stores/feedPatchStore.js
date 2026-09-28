/**
 * 跨列表补丁广播：同一份视频/作者数据会同时出现在首页、搜索、关注、赞过、主页、
 * 创作中心等多份列表里，任一处变更都要同步其余副本。
 * 各域 store 只提供「改自己列表」的 action，本 store 负责把它们串起来。
 * 依赖方向单向：本 store 是唯一的上层，各域不反向依赖它（它们经注册钩子消费补丁），
 * 因此不会形成环。
 */
import { defineStore } from "pinia";
import { useCommentPanelStore } from "./commentPanelStore.js";
import { useHomeStore } from "./homeStore.js";
import { useLikedStore } from "./likedStore.js";
import { useProfileStore } from "./profileStore.js";
import { useSearchStore } from "./searchStore.js";
import { useShortsStore } from "./shortsStore.js";
import { useStudioStore } from "./studioStore.js";

export const useFeedPatchStore = defineStore("feedPatch", {
  actions: {
    /** 点赞数/评论数等字段变更后，同步到所有出现该视频的列表 */
    patchVideoById(videoId, patch) {
      const id = String(videoId ?? "");
      if (!id || !patch || typeof patch !== "object") return;
      useHomeStore().patchVideoById(id, patch);
      useShortsStore().patchVideoById(id, patch);
      useLikedStore().patchVideoById(id, patch);
      useProfileStore().patchVideoById(id, patch);
      useStudioStore().patchVideoById(id, patch);
      useSearchStore().patchVideoById(id, patch);
      useCommentPanelStore().patchVideoById(id, patch);
    },

    /** 关注/取关后，同步该作者名下所有视频条目的 isFollow */
    patchAuthorFollow(authorId, isFollow) {
      const aid = String(authorId ?? "");
      if (!aid) return;
      useHomeStore().patchAuthorFollow(aid, isFollow);
      useShortsStore().patchAuthorFollow(aid, isFollow);
      useLikedStore().patchAuthorFollow(aid, isFollow);
      useProfileStore().patchAuthorFollow(aid, isFollow);
      useSearchStore().patchAuthorFollow(aid, isFollow);
    },

    /** 删除视频后，从所有客户端列表里移除 */
    removeVideoById(videoId) {
      const id = String(videoId ?? "");
      if (!id) return;
      useHomeStore().removeVideoById(id);
      useShortsStore().removeVideoById(id);
      useLikedStore().removeVideoById(id);
      useProfileStore().removeVideoById(id);
      useStudioStore().removeVideoById(id);
    },
  },
});
