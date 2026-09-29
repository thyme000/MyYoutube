// @ts-check
/**
 * Toast 域：普通提示与实时通知毛玻璃卡片。DOM 渲染在 components/ToastStack.vue。
 * 点击跳转依赖 detailStore / notificationStore / chatNavigator，均为运行期调用，允许互相引用。
 */
import { defineStore } from "pinia";
import { playNotificationSound } from "../utils/uiSounds";
import { navigateToChat } from "../utils/chatNavigator";
import { parseDigitSnowflakeId } from "../utils/snowflakeId";
import { markNotificationRead } from "../api/notificationApi";
import { useDetailStore } from "./detailStore";
import { useNotificationStore } from "./notificationStore";

export const useToastStore = defineStore("toast", {
  state: () => ({
    /** 当前展示的 toast（最多保留 3 条） */
    toasts: /** @type {Array<object>} */ ([]),
    seed: 0,
  }),
  actions: {
    pushToast(message, type = "info") {
      const text = String(message || "").trim();
      if (!text) return;
      this.seed += 1;
      const id = `toast-${Date.now()}-${this.seed}`;
      const next = [...this.toasts, { id, message: text, type }];
      this.toasts = next.slice(-3);
      window.setTimeout(() => {
        this.removeToast(id);
      }, 3000);
    },
    removeToast(id) {
      this.toasts = this.toasts.filter((t) => t.id !== id);
    },
    /** 实时通知（WebSocket）：毛玻璃卡片，含头像与正文，可点进视频 / 标记已读 / 打开聊天 */
    pushNotifyLiveToast({
      avatarUrl,
      title,
      content,
      videoId,
      notificationId,
      conversationId,
      notifyKind = null,
    }) {
      const ttl = String(title || "通知").trim() || "通知";
      const body = String(content || "").trim();
      this.seed += 1;
      const id = `toast-${Date.now()}-${this.seed}`;
      const vidRaw = videoId != null && videoId !== "" ? String(videoId).trim() : null;
      const nidRaw =
        notificationId != null && notificationId !== "" ? String(notificationId).trim() : null;
      const convRaw =
        conversationId != null && conversationId !== "" ? String(conversationId).trim() : null;
      const next = [
        ...this.toasts,
        {
          id,
          type: "live-notify",
          notify: {
            avatarUrl: String(avatarUrl || "").trim(),
            title: ttl,
            content: body,
            videoId: vidRaw && /^\d+$/.test(String(vidRaw)) ? String(vidRaw) : null,
            notificationId: nidRaw && /^\d+$/.test(String(nidRaw)) ? String(nidRaw) : null,
            conversationId: convRaw || null,
            notifyKind: notifyKind || null,
          },
        },
      ];
      this.toasts = next.slice(-3);
      playNotificationSound();
      window.setTimeout(() => this.removeToast(id), 6500);
    },
    async onNotifyToastClick(t) {
      const n = t?.notify;
      if (!n) return;
      if (String(n.notifyKind || "") === "CHAT_NOTICE") {
        this.removeToast(t.id);
        this.pushToast(String(n.content || "该群聊已解散").trim() || "该群聊已解散", "info");
        return;
      }
      const hasConv = n.conversationId != null && String(n.conversationId).trim() !== "";
      const hasVid = n.videoId != null && n.videoId !== "";
      const hasNid = n.notificationId != null && n.notificationId !== "";
      if (!hasVid && !hasNid && !hasConv) return;

      if (hasConv) {
        this.removeToast(t.id);
        navigateToChat(String(n.conversationId).trim());
        return;
      }

      const notificationStore = useNotificationStore();
      if (hasNid) {
        try {
          await markNotificationRead(n.notificationId);
          await notificationStore.refreshNotifyUnreadCount();
          if (notificationStore.notifyPanelOpen) {
            void notificationStore.loadNotifyListPage(true);
          }
          notificationStore.markRowRead(n.notificationId);
        } catch (e) {
          this.pushToast(e?.message || "标记已读失败", "error");
        }
      }

      if (hasVid) {
        const vid = parseDigitSnowflakeId(n.videoId);
        if (vid != null) {
          this.removeToast(t.id);
          await useDetailStore().openDetail({ id: vid });
          return;
        }
      }

      if (hasNid) {
        this.removeToast(t.id);
      }
    },
  },
});
