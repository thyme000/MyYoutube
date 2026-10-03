// @ts-check
/**
 * 转发视频 / 举报评论弹窗：搜索、详情、短视频三处都会触发，弹窗本体仍渲染在 App.vue。
 */
import { defineStore } from "pinia";
import { isBlockedComment } from "../utils/comment.js";
import { toast } from "../utils/toast.js";
import { useAuthStore } from "./authStore";

export const useShareStore = defineStore("share", {
  state: () => ({
    forwardVideoOpen: false,
    forwardVideoCard: null,
    commentReportOpen: false,
    commentReportTarget: null,
  }),
  actions: {
    openForwardVideo(card) {
      if (!useAuthStore().isAuthed) {
        toast("请先登录后使用转发", "info");
        return;
      }
      if (!card?.id) {
        toast("缺少视频信息", "error");
        return;
      }
      this.forwardVideoCard = card;
      this.forwardVideoOpen = true;
    },
    /** 转发动态: 复用转发弹窗, forwardType 区分卡片类型 */
    openForwardDynamic(dynamic) {
      const auth = useAuthStore();
      if (!auth.isAuthed) {
        toast("请先登录后使用转发", "info");
        return;
      }
      if (!dynamic?.id) {
        toast("缺少动态信息", "error");
        return;
      }
      this.forwardVideoCard = { ...dynamic, forwardType: "dynamic" };
      this.forwardVideoOpen = true;
    },
    openCommentReport(comment) {
      const auth = useAuthStore();
      if (!auth.isAuthed) {
        toast("请先登录后再举报", "info");
        return;
      }
      if (!comment?.id) {
        toast("缺少评论信息", "error");
        return;
      }
      if (auth.userId != null && String(comment.userId) === String(auth.userId)) {
        toast("不能举报自己的评论", "info");
        return;
      }
      if (isBlockedComment(comment)) {
        toast("该评论已被处理，无法举报", "info");
        return;
      }
      this.commentReportTarget = comment;
      this.commentReportOpen = true;
    },
  },
});
