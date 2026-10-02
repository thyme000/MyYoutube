// @ts-check
/**
 * 通知域：铃铛未读数、通知列表面板与推送信号分拣。
 * 实时推送为「轻量门铃」模式：登录后全局 SSE(utils/notifySse.js) 只发信号，
 * 收到信号后拉取数据再弹 toast；聊天页实时报文走聊天 WS(utils/chatWebSocket.js)。
 */
import { defineStore } from "pinia";
import {
  fetchNotificationPage,
  markNotificationRead,
  deleteNotification,
  fetchUnreadNotificationCount,
  NOTIFICATION_TYPE_LABELS,
} from "../api/notificationApi";
import { getToken } from "../api/http";
import { startNotifySse, stopNotifySse } from "../utils/notifySse";
import { isChatWebSocketOpen, disconnectChatWebSocket } from "../utils/chatWebSocket";
import {
  pickNotifyWsNickname,
  previewChatIncomingWsBody,
} from "../utils/notifyWs";
import { parseDigitSnowflakeId } from "../utils/snowflakeId";
import { useAuthStore } from "./authStore";
import { useDynamicStore } from "./dynamicStore";
import { useNavigationStore } from "./navigationStore";
import { useDetailStore } from "./detailStore";
import { useToastStore } from "./toastStore";
import {
  tryIngestChatWebSocket,
  chatActiveConversationId,
  getChatSelfUserId,
  refreshConversationList,
  chatConversations,
} from "./chatStore";

const NOTIFY_PAGE_SIZE = 15;
/** 与轮询配合：未读数基准，避免 WS 已推过仍重复提示 */
let notifyUnreadPollBaseline = -1;
let notifyUnreadPollTimer = null;

export const useNotificationStore = defineStore("notification", {
  state: () => ({
    notifyPanelOpen: false,
    notifyUnreadCount: 0,
    /** @type {Array<object>} */
    notifyList: [],
    notifyLoading: false,
    notifyPageNo: 1,
    notifyPages: 0,
    notifyTotal: 0,
    notifyErrorMsg: "",
  }),
  actions: {
    async refreshNotifyUnreadCount() {
      if (!getToken()) {
        this.notifyUnreadCount = 0;
        notifyUnreadPollBaseline = -1;
        return;
      }
      try {
        const c = await fetchUnreadNotificationCount();
        this.notifyUnreadCount = c;
        notifyUnreadPollBaseline = c;
      } catch {
        /* ignore */
      }
    },
    startNotifyUnreadPoll() {
      this.stopNotifyUnreadPoll();
      notifyUnreadPollTimer = window.setInterval(async () => {
        if (!getToken()) return;
        try {
          const c = await fetchUnreadNotificationCount();
          if (notifyUnreadPollBaseline >= 0 && c > notifyUnreadPollBaseline) {
            useToastStore().pushToast("您有新的通知，请点击顶部铃铛查看", "info");
          }
          this.notifyUnreadCount = c;
          notifyUnreadPollBaseline = c;
        } catch {
          /* ignore */
        }
      }, 22000);
    },
    stopNotifyUnreadPoll() {
      if (notifyUnreadPollTimer != null) {
        window.clearInterval(notifyUnreadPollTimer);
        notifyUnreadPollTimer = null;
      }
      notifyUnreadPollBaseline = -1;
    },
    async loadNotifyListPage(reset) {
      if (!getToken()) return;
      this.notifyLoading = true;
      if (reset) {
        this.notifyErrorMsg = "";
      }
      const page = reset ? 1 : this.notifyPageNo;
      if (reset) {
        this.notifyPageNo = 1;
      }
      try {
        const { total, pages, list } = await fetchNotificationPage({
          pageNo: page,
          pageSize: NOTIFY_PAGE_SIZE,
        });
        this.notifyPages = pages;
        this.notifyTotal = total;
        this.notifyList = reset ? list : [...this.notifyList, ...list];
      } catch (e) {
        this.notifyErrorMsg = e?.message || "加载通知失败";
      } finally {
        this.notifyLoading = false;
      }
    },
    toggleNotifyPanel() {
      this.notifyPanelOpen = !this.notifyPanelOpen;
      if (this.notifyPanelOpen) {
        useNavigationStore().pageHint = "通知";
        void this.refreshNotifyUnreadCount();
        void this.loadNotifyListPage(true);
      }
    },
    closeNotifyPanel() {
      this.notifyPanelOpen = false;
    },
    async loadMoreNotifications() {
      if (this.notifyLoading) return;
      if (this.notifyPageNo >= this.notifyPages) return;
      this.notifyPageNo += 1;
      await this.loadNotifyListPage(false);
    },
    /** 已读打补丁：面板列表与 toast 点击共用 */
    markRowRead(nid) {
      this.notifyList = this.notifyList.map((r) =>
        String(r.id) === String(nid) ? { ...r, isRead: true } : r
      );
    },
    async onNotifyItemClick(item) {
      if (!item?.id) return;
      const toastStore = useToastStore();
      try {
        await markNotificationRead(item.id);
        await this.refreshNotifyUnreadCount();
      } catch (e) {
        toastStore.pushToast(e?.message || "标记已读失败", "error");
        return;
      }
      this.markRowRead(item.id);
      if (String(item.type || "").toUpperCase() === "CHAT_NOTICE") {
        this.closeNotifyPanel();
        toastStore.pushToast(String(item.content || "该群聊已解散").trim() || "该群聊已解散", "info");
        return;
      }
      const vid = item.videoId != null ? parseDigitSnowflakeId(item.videoId) : null;
      if (vid == null) {
        toastStore.pushToast("暂无法跳转到视频（可能评论已删除）", "info");
        return;
      }
      this.closeNotifyPanel();
      await useDetailStore().openDetail({ id: vid });
    },
    async onNotifyDelete(item) {
      if (!item?.id) return;
      try {
        await deleteNotification(item.id);
        this.notifyList = this.notifyList.filter((r) => r.id !== item.id);
        this.notifyTotal = Math.max(0, this.notifyTotal - 1);
        const ps = Math.max(1, Math.ceil(this.notifyTotal / NOTIFY_PAGE_SIZE) || 1);
        this.notifyPages = ps;
        await this.refreshNotifyUnreadCount();
      } catch (e) {
        useToastStore().pushToast(e?.message || "删除失败", "error");
      }
    },
    /** SSE 门铃信号分拣：通知红点 / 聊天红点(未进聊天页时) / 转码完成 */
    onSseSignal(type, payload) {
      if (type === "NOTIFY_DIRTY") {
        void this.onNotifyDirty();
        return;
      }
      if (type === "CHAT_DIRTY") {
        // 在聊天页时聊天 WS 实时报文已处理增量, 门铃只补「没进聊天页」的红点与 toast
        if (isChatWebSocketOpen()) return;
        void this.onChatDoorbell();
        return;
      }
      if (type === "TRANSCODE_DONE" || type === "TRANSCODE_FAILED") {
        void useDynamicStore().onTranscodeSignal(type, payload);
      }
    },
    /** 站内通知有更新：未读数涨了才弹 toast（内容从拉到的最新一条取） */
    async onNotifyDirty() {
      const prev = notifyUnreadPollBaseline;
      await this.refreshNotifyUnreadCount();
      if (prev >= 0 && this.notifyUnreadCount > prev) {
        const item = await this.fetchNewestNotification();
        if (item) {
          if (this.notifyPanelOpen) {
            void this.loadNotifyListPage(true);
          }
          this.pushNotificationLiveToast(item);
          return;
        }
      }
      if (this.notifyPanelOpen) {
        void this.loadNotifyListPage(true);
      }
    },
    async fetchNewestNotification() {
      try {
        const { list } = await fetchNotificationPage({ pageNo: 1, pageSize: 1 });
        return list?.[0] || null;
      } catch {
        return null;
      }
    },
    /** 与原 WS 通知 toast 同款：毛玻璃卡片，可点进视频 / 会话 */
    pushNotificationLiveToast(item) {
      const toastStore = useToastStore();
      const typeUpper = String(item.type || "").toUpperCase();
      const label = NOTIFICATION_TYPE_LABELS[typeUpper] || item.typeLabel || typeUpper || "新通知";
      const nick = item.senderNickname || "";
      const title = nick ? `${nick} · ${label}` : label;
      const content = String(item.content || "").trim() || label;
      const isChatNotice = typeUpper === "CHAT_NOTICE";
      if (isChatNotice && item.conversationId && String(chatActiveConversationId.value || "") === String(item.conversationId)) {
        chatActiveConversationId.value = null;
        void refreshConversationList(true);
      }
      toastStore.pushNotifyLiveToast({
        avatarUrl: item.senderAvatarUrl || "",
        title,
        content,
        videoId: item.videoId != null ? parseDigitSnowflakeId(item.videoId) : null,
        notificationId: item.id != null ? String(item.id) : null,
        conversationId: isChatNotice ? null : item.conversationId || null,
        notifyKind: isChatNotice ? "CHAT_NOTICE" : null,
      });
    },
    /** 未进聊天页时收到新消息：刷会话列表，对比未读数找出「谁来了消息」弹 toast */
    async onChatDoorbell() {
      const rowsBefore = chatConversations.value;
      const hadList = rowsBefore.length > 0;
      const prevUnread = new Map(
        rowsBefore.map((r) => [String(r.conversationId), Number(r.unreadCount) || 0])
      );
      await refreshConversationList(true);
      if (!hadList) return;
      const toastStore = useToastStore();
      for (const row of chatConversations.value) {
        const before = prevUnread.get(String(row.conversationId));
        if (before == null) continue;
        if ((Number(row.unreadCount) || 0) > before) {
          const title = `${row.lastMessageSenderNickname || row.conversationName || "新消息"} · 新消息`;
          toastStore.pushNotifyLiveToast({
            avatarUrl: row.conversationAvatar || "",
            title,
            content: String(row.lastMessageContent || "").slice(0, 80) || "[消息]",
            videoId: null,
            notificationId: null,
            conversationId: String(row.conversationId || "") || null,
          });
        }
      }
    },
    /** 他人发来新消息且当前未打开该会话时，与评论通知同款的实时毛玻璃 Toast */
    maybePushChatIncomingLiveToast(parsed) {
      const toastStore = useToastStore();
      // chatStore 的 self 依赖 setChatUserContext；其 watch 晚于 notify WS 时 getChatSelfUserId 会为空，用 authStore.userId 兜底
      const selfId = String(getChatSelfUserId() ?? useAuthStore().userId ?? "").trim();
      const sender = String(parsed?.senderId ?? "").trim();
      if (!selfId || !sender || sender === selfId) return;
      const conv = String(parsed?.conversationId || "").trim();
      if (!conv) return;
      if (chatActiveConversationId.value === conv) return;
      const nick = pickNotifyWsNickname(parsed);
      const title = nick ? `${nick} · 新消息` : "新消息";
      const content = previewChatIncomingWsBody(parsed);
      const avatarUrl = typeof parsed?.avatarUrl === "string" ? parsed.avatarUrl.trim() : "";
      toastStore.pushNotifyLiveToast({
        avatarUrl,
        title,
        content,
        videoId: null,
        notificationId: null,
        conversationId: conv,
      });
    },
    /** 聊天页 WS 报文分拣：只承载聊天事件（新消息/撤回/已读/公告/禁言），非聊天报文直接忽略 */
    onChatSocketMessage(data) {
      let parsed;
      try {
        parsed = JSON.parse(typeof data === "string" ? data : String(data));
      } catch {
        return;
      }
      if (!parsed || typeof parsed !== "object") return;
      if (tryIngestChatWebSocket(parsed) === "send") {
        this.maybePushChatIncomingLiveToast(parsed);
      }
    },
    /** 登录后接通知链路：未读数 → SSE 门铃 → 轮询兜底 */
    async startSession() {
      await this.refreshNotifyUnreadCount();
      startNotifySse({ onSignal: (type, payload) => this.onSseSignal(type, payload) });
      this.startNotifyUnreadPoll();
    },
    /** 登出 / 过期：断链路并清空面板 */
    async stopSession() {
      this.stopNotifyUnreadPoll();
      stopNotifySse();
      disconnectChatWebSocket();
      this.closeNotifyPanel();
      this.notifyUnreadCount = 0;
      this.notifyList = [];
      this.notifyPages = 0;
      this.notifyTotal = 0;
      this.notifyErrorMsg = "";
    },
  },
});
