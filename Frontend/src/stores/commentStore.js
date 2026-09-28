// @ts-check
/**
 * 评论点赞：详情页与短视频评论面板共用。
 * toggleLike 只返回补丁而不写回列表，由调用方 patch 自己的数据，避免 store 反向修改别人的数组。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { cancelLikeComment, likeComment } from "../api/likeApi";
import { toast } from "../utils/toast";
import { useAuthStore } from "./authStore";

export const useCommentStore = defineStore("comment", () => {
  /** commentId → true，请求中防连点 */
  const likeBusyById = ref({});

  function isLikeBusy(id) {
    return Boolean(likeBusyById.value[String(id)]);
  }

  /**
   * @returns {Promise<{isLike: boolean, likeCount: number}|null>} 成功返回补丁；未登录、连点或失败返回 null
   */
  async function toggleLike(row) {
    if (!row?.id) return null;
    if (!useAuthStore().token) {
      toast("请先登录后再点赞", "info");
      return null;
    }
    const sid = String(row.id);
    if (likeBusyById.value[sid]) return null;
    likeBusyById.value = { ...likeBusyById.value, [sid]: true };
    try {
      if (row.isLike) {
        await cancelLikeComment(row.id);
        return { isLike: false, likeCount: Math.max(0, Number(row.likeCount ?? 0) - 1) };
      }
      await likeComment(row.id);
      return { isLike: true, likeCount: Number(row.likeCount ?? 0) + 1 };
    } catch (e) {
      toast(e?.message || (row.isLike ? "取消点赞失败" : "点赞失败"), "error");
      return null;
    } finally {
      const next = { ...likeBusyById.value };
      delete next[sid];
      likeBusyById.value = next;
    }
  }

  return { likeBusyById, isLikeBusy, toggleLike };
});
