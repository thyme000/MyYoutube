// @ts-check
/**
 * 通知域：铃铛未读数、通知列表面板与 WebSocket 报文分拣。
 * Toast 卡片渲染在 toastStore / components/ToastStack.vue；WebSocket 连接在 utils/notifyWebSocket.js。
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
import {
  reconnectNotifyWebSocketIfNeeded,
  startNotifyWebSocket,
  stopNotifyWebSocket,
} from "../utils/notifyWebSocket";
import {
  pickNotifyWsAvatarUrl,
  pickNotifyWsContent,
  pickNotifyWsConversationId,
  pickNotifyWsNickname,
  pickNotifyWsNotificationId,
  pickNotifyWsTypeCode,
  pickNotifyWsVideoId,
  previewChatIncomingWsBody,
} from "../utils/notifyWs";
import { parseDigitSnowflakeId } from "../utils/snowflakeId";
import { useAuthStore } from "./authStore";
import { useNavigationStore } from "./navigationStore";
import { useDetailStore } from "./detailStore";
import { useToastStore } from "./toastStore";
import {
  tryIngestChatWebSocket,
  chatActiveConversationId,
  getChatSelfUserId,
  refreshConversationList,
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
          reconnectNotifyWebSocketIfNeeded();
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
    onNotifySocketMessage(data) {
      const toastStore = useToastStore();
      const raw = typeof data === "string" ? data : String(data);
      let parsed = null;
      try {
        parsed = JSON.parse(raw);
      } catch {
        /* 非 JSON：走下方 Toast */
      }
      if (parsed && typeof parsed === "object") {
        const chatKind = tryIngestChatWebSocket(parsed);
        if (chatKind) {
          if (chatKind === "send") {
            this.maybePushChatIncomingLiveToast(parsed);
          }
          return;
        }
      }
      void this.refreshNotifyUnreadCount();
      if (this.notifyPanelOpen) {
        void this.loadNotifyListPage(true);
      }
      try {
        const o = parsed && typeof parsed === "object" ? parsed : JSON.parse(raw);
        const nick = pickNotifyWsNickname(o);
        const typeCode = pickNotifyWsTypeCode(o);
        const label = NOTIFICATION_TYPE_LABELS[typeCode] || typeCode || "新通知";
        const title = nick ? `${nick} · ${label}` : label;
        let content = pickNotifyWsContent(o);
        if (!String(content || "").trim()) {
          content = NOTIFICATION_TYPE_LABELS[typeCode] || "";
        }
        const avatarUrl = pickNotifyWsAvatarUrl(o);
        const videoId = pickNotifyWsVideoId(raw, o);
        const notificationId = pickNotifyWsNotificationId(raw, o);
        const typeUpper = String(typeCode || "").toUpperCase();
        const isChatNotice = typeUpper === "CHAT_NOTICE";
        const conversationId = isChatNotice ? null : pickNotifyWsConversationId(raw, o);
        toastStore.pushNotifyLiveToast({
          avatarUrl,
          title,
          content,
          videoId,
          notificationId,
          conversationId,
          notifyKind: isChatNotice ? "CHAT_NOTICE" : null,
        });
        if (isChatNotice) {
          const dissolvedConvId = pickNotifyWsConversationId(raw, o);
          if (dissolvedConvId && String(chatActiveConversationId.value || "") === String(dissolvedConvId)) {
            chatActiveConversationId.value = null;
          }
          void refreshConversationList(true);
        }
      } catch {
        toastStore.pushToast(raw.slice(0, 120), "info");
      }
    },
    /** 登录后接通知链路：未读数 → WS → 轮询 */
    async startSession() {
      await this.refreshNotifyUnreadCount();
      startNotifyWebSocket({
        onMessage: (data) => this.onNotifySocketMessage(data),
      });
      this.startNotifyUnreadPoll();
    },
    /** 登出 / 过期：断链路并清空面板 */
    stopSession() {
      this.stopNotifyUnreadPoll();
      stopNotifyWebSocket();
      this.closeNotifyPanel();
      this.notifyUnreadCount = 0;
      this.notifyList = [];
      this.notifyPages = 0;
      this.notifyTotal = 0;
      this.notifyErrorMsg = "";
    },
  },
});
