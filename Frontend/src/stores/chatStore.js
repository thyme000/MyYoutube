// @ts-check
/**
 * 聊天模块状态：会话列表、当前会话消息、未读、WebSocket 增量合并。
 */
import { computed } from "vue";
import { defineStore } from "pinia";
import * as chatApi from "../api/chatApi.js";
import { safeSnowflakeId, parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { toObjectKeyOrPath } from "../utils/chatMediaKey.js";
import { navigateToChat } from "../utils/chatNavigator.js";
import { playSendSound } from "../utils/uiSounds.js";

/** @typedef {{ userId: string|number, nickName?: string, avatarUrl?: string }} ChatUserCtx */

let selfCtx = /** @type {ChatUserCtx | null} */ (null);

export function setChatUserContext(ctx) {
  if (!ctx?.userId) {
    selfCtx = null;
    return;
  }
  selfCtx = {
    userId: ctx.userId,
    nickName: ctx.nickName || "",
    avatarUrl: ctx.avatarUrl || "",
  };
}

export function getChatSelfUserId() {
  return selfCtx?.userId ?? null;
}

export const chatConversations = computed({
  get: () => st().chatConversations,
  set: (v) => { st().chatConversations = v; },
});
export const chatConversationsLoading = computed({
  get: () => st().chatConversationsLoading,
  set: (v) => { st().chatConversationsLoading = v; },
});
export const chatConversationsError = computed({
  get: () => st().chatConversationsError,
  set: (v) => { st().chatConversationsError = v; },
});
export const chatConversationsPage = computed({
  get: () => st().chatConversationsPage,
  set: (v) => { st().chatConversationsPage = v; },
});
export const chatConversationsPages = computed({
  get: () => st().chatConversationsPages,
  set: (v) => { st().chatConversationsPages = v; },
});
export const chatTotalUnread = computed({
  get: () => st().chatTotalUnread,
  set: (v) => { st().chatTotalUnread = v; },
});

export const chatActiveConversationId = computed({
  get: () => st().chatActiveConversationId,
  set: (v) => { st().chatActiveConversationId = v; },
});

/** @type {import('vue').Ref<Record<string, any>>} */
const threadsRef = computed({
  get: () => st().threadsRef,
  set: (v) => { st().threadsRef = v; },
});

const THREAD_DEFAULT = () => ({
  items: [],
  hasMoreHistory: true,
  loadingHistory: false,
  loadingInitial: false,
  initialError: "",
  /** 对端已读游标（私聊 UI） */
  peerReadSeq: 0,
});

function threadKey(id) {
  return String(id || "");
}

function getThread(id) {
  const k = threadKey(id);
  const cur = threadsRef.value;
  if (!cur[k]) {
    threadsRef.value = { ...cur, [k]: { ...THREAD_DEFAULT() } };
  }
  return threadsRef.value[k];
}


/**
 * pinia 状态容器：聊天会话/消息线程/未读。
 * 导出同名 ref 为可写 computed 门面（.value 读写直通 store），消费方零改动。
 */
const useChatState = defineStore("chat", {
  state: () => ({
    chatConversations: ([]),
    chatConversationsLoading: false,
    chatConversationsError: "",
    chatConversationsPage: 1,
    chatConversationsPages: 1,
    chatTotalUnread: 0,
    chatActiveConversationId: (null),
    threadsRef: {},
  }),
});

let _state = null;
function st() {
  if (!_state) _state = useChatState();
  return _state;
}

export const chatActiveThread = computed(() => {
  const id = chatActiveConversationId.value;
  if (!id) return THREAD_DEFAULT();
  return getThread(id);
});

function sortConversationRows(rows) {
  return [...(rows || [])].sort((a, b) => {
    const pa = a.pin ? 1 : 0;
    const pb = b.pin ? 1 : 0;
    if (pa !== pb) return pb - pa;
    const ta = new Date(a.lastMessageTime || 0).getTime();
    const tb = new Date(b.lastMessageTime || 0).getTime();
    return tb - ta;
  });
}

let initChatInFlight = null;
let lastInitChatAtMs = 0;
let refreshConvInFlight = null;
let refreshConvPendingReset = false;

export async function initChatWhenAuthed() {
  if (!selfCtx?.userId) return;
  const now = Date.now();
  if (initChatInFlight) return initChatInFlight;
  if (now - lastInitChatAtMs < 800) return;
  lastInitChatAtMs = now;
  initChatInFlight = Promise.all([refreshConversationList(true), refreshTotalUnread()]).finally(() => {
    initChatInFlight = null;
  });
  return initChatInFlight;
}

export function resetChatWhenLogout() {
  chatConversations.value = [];
  chatConversationsError.value = "";
  chatConversationsPage.value = 1;
  chatConversationsPages.value = 1;
  chatTotalUnread.value = 0;
  chatActiveConversationId.value = null;
  threadsRef.value = {};
  selfCtx = null;
}

export async function refreshTotalUnread() {
  if (!selfCtx?.userId) return;
  try {
    const n = await chatApi.getTotalUnread();
    chatTotalUnread.value = Number(n) || 0;
  } catch {
    /* ignore */
  }
}

export async function refreshConversationList(reset = false) {
  if (!selfCtx?.userId) return;
  if (refreshConvInFlight) {
    if (reset) refreshConvPendingReset = true;
    return refreshConvInFlight;
  }
  if (reset) refreshConvPendingReset = false;
  if (reset) {
    chatConversationsPage.value = 1;
  }
  refreshConvInFlight = (async () => {
    chatConversationsLoading.value = true;
    chatConversationsError.value = "";
    try {
      const page = chatConversationsPage.value;
      const data = await chatApi.listUserConversations(page, 30);
      const rawList = data?.list ?? [];
      const list = (rawList || []).map((r) => {
        if (!r || typeof r !== "object") return r;
        const convBan = r.conversationBanned ?? r.conversatinoBanned;
        if (convBan == null) return r;
        return { ...r, conversationBanned: Boolean(convBan) };
      });
      const pages = Number(data?.pages) || 1;
      chatConversationsPages.value = pages;
      if (reset || page <= 1) {
        chatConversations.value = sortConversationRows(list);
      } else {
        chatConversations.value = sortConversationRows([...chatConversations.value, ...list]);
      }
      await refreshTotalUnread();
    } catch (e) {
      chatConversationsError.value = e?.message || "加载会话失败";
    } finally {
      chatConversationsLoading.value = false;
    }
  })();

  try {
    await refreshConvInFlight;
  } finally {
    refreshConvInFlight = null;
    if (refreshConvPendingReset) {
      refreshConvPendingReset = false;
      await refreshConversationList(true);
    }
  }
}

export async function loadMoreConversations() {
  if (chatConversationsLoading.value) return;
  if (chatConversationsPage.value >= chatConversationsPages.value) return;
  chatConversationsPage.value += 1;
  await refreshConversationList(false);
}

export function upsertConversationRow(patch) {
  const id = patch?.conversationId;
  if (!id) return;
  const list = chatConversations.value;
  const idx = list.findIndex((r) => String(r.conversationId) === String(id));
  if (idx >= 0) {
    const next = { ...list[idx], ...patch };
    const copy = [...list];
    copy[idx] = next;
    chatConversations.value = sortConversationRows(copy);
  } else {
    void refreshConversationList(true);
  }
}

/** 群聊且服务端公告版本高于本用户已读版本 */
export function isGroupAnnouncementUnread(row) {
  if (!row || String(row.conversationType || "").toUpperCase() !== "GROUP") return false;
  const cur = Number(row.lastAnnouncementVersion) || 0;
  if (cur <= 0) return false;
  const seen = Number(row.lastSeenAnnouncementVersion) || 0;
  return cur > seen;
}

function previewFromSendWs(o) {
  let ct = o.contentType;
  if (ct && typeof ct === "object") {
    ct = ct.name ?? ct.code ?? ct;
  }
  ct = String(ct || "").toUpperCase();
  if (ct === "TEXT") return String(o.content || "").slice(0, 80);
  if (ct === "IMAGE") return "[图片]";
  if (ct === "FILE") return "[文件]";
  if (ct === "SYSTEM") return "[系统消息]";
  if (ct === "FORWARD_VIDEO") return "[转发视频]";
  if (ct === "FORWARD_DYNAMIC") return "[转发动态]";
  return "[消息]";
}

/** WebSocket / Gson 可能把 id 打成数字，统一成非空字符串 */
function normalizeWsConversationId(v) {
  if (v == null) return "";
  const s = typeof v === "string" ? v.trim() : String(v).trim();
  return s;
}

/**
 * @param {Record<string, unknown>} o
 * @returns {{ kind: 'send'|'withdraw'|'read'|'announcement'|'ban', payload: any }|null}
 */
export function classifyChatWebSocketPayload(o) {
  if (!o || typeof o !== "object") return null;
  if ("type" in o && o.type != null && !("contentType" in o)) {
    const t = o.type;
    if (typeof t === "number" || (typeof t === "string" && String(t).length < 6)) {
      return null;
    }
  }
  if (o.videoId != null || o.notificationId != null || o.refId != null) return null;
  const conv = normalizeWsConversationId(o.conversationId);
  if (!conv) return null;

  const payload = { ...o, conversationId: conv };

  // 会话禁言更新：{ conversationId, banned, type: 'conversation' | 'member' }
  if ("banned" in o && o.banned != null && !("messageId" in o) && !("lastReadSequence" in o)) {
    return { kind: "ban", payload };
  }

  if (String(o.type || "") === "ANNOUNCEMENT_UPDATED") {
    return { kind: "announcement", payload };
  }

  if (o.lastReadSequence != null && conv) {
    return { kind: "read", payload };
  }
  if (o.messageId && o.contentType != null) {
    return { kind: "send", payload };
  }
  // 撤回 VO 无 contentType；避免把缺 contentType 的异常报文误判为撤回
  if (o.messageId && o.senderId != null && o.contentType == null) {
    return { kind: "withdraw", payload };
  }
  return null;
}

/**
 * @returns {false | 'read' | 'withdraw' | 'send' | 'announcement' | 'ban'} 非 false 表示已消费，站点通知逻辑应跳过
 */
export function tryIngestChatWebSocket(o) {
  const hit = classifyChatWebSocketPayload(o);
  if (!hit) return false;
  const selfId = selfCtx?.userId != null ? String(selfCtx.userId) : "";
  if (hit.kind === "ban") {
    const conv = String(hit.payload.conversationId || "");
    const banned = Boolean(hit.payload.banned);
    const t = String(hit.payload.type || "").toLowerCase();
    if (conv && (t === "conversation" || t === "member")) {
      if (t === "conversation") {
        upsertConversationRow({ conversationId: conv, conversationBanned: banned });
      } else {
        // 后端按「被禁言的 userId」推送给目标用户，因此本地会话行 banned 可直接更新
        upsertConversationRow({ conversationId: conv, banned });
      }
    } else {
      void refreshConversationList(true);
    }
    return "ban";
  }
  if (hit.kind === "announcement") {
    const conv = String(hit.payload.conversationId || "");
    const ver = Number(hit.payload.version);
    if (conv && Number.isFinite(ver) && ver > 0) {
      upsertConversationRow({
        conversationId: conv,
        lastAnnouncementVersion: ver,
      });
    }
    return "announcement";
  }
  if (hit.kind === "read") {
    const conv = String(hit.payload.conversationId || "");
    const seq = Number(hit.payload.lastReadSequence) || 0;
    if (conv && chatActiveConversationId.value === conv) {
      const st = getThread(conv);
      st.peerReadSeq = Math.max(st.peerReadSeq || 0, seq);
    }
    return "read";
  }
  if (hit.kind === "withdraw") {
    const conv = String(hit.payload.conversationId || "");
    const mid = String(hit.payload.messageId || "");
    if (conv && mid) {
      const st = getThread(conv);
      const rtRaw = hit.payload.recallType;
      let recallTypeNorm = "";
      if (rtRaw != null && rtRaw !== "") {
        if (typeof rtRaw === "string") recallTypeNorm = String(rtRaw).toUpperCase();
        else if (typeof rtRaw === "object") {
          const n = rtRaw?.name ?? rtRaw?.code;
          if (n != null) recallTypeNorm = String(n).toUpperCase();
        } else recallTypeNorm = String(rtRaw).toUpperCase();
      }
      st.items = (st.items || []).map((m) =>
        String(m.messageId) === mid
          ? {
              ...m,
              status: "WITHDRAWN",
              content: { ...(m.content || {}), text: "已撤回" },
              ...(recallTypeNorm ? { recallType: recallTypeNorm } : {}),
            }
          : m
      );
      upsertConversationRow({
        conversationId: conv,
        isLastMessageRecall: true,
        lastMessageContent: "撤回了一条消息",
      });
    }
    return "withdraw";
  }
  if (hit.kind === "send") {
    const conv = String(hit.payload.conversationId || "");
    const sender = String(hit.payload.senderId ?? "");
    const active = chatActiveConversationId.value === conv;
    const preview = previewFromSendWs(hit.payload);
    upsertConversationRow({
      conversationId: conv,
      lastMessageContent: preview,
      lastMessageSenderNickname: hit.payload.senderNickname,
      lastMessageTime: new Date().toISOString(),
      isLastMessageRecall: false,
    });
    if (active) {
      void pullNewerForConversation(conv);
      void postConversationReadAfterView(conv);
    } else if (sender && selfId && sender !== selfId) {
      const row = chatConversations.value.find((r) => String(r.conversationId) === conv);
      const uc = (row?.unreadCount ?? 0) + 1;
      upsertConversationRow({ conversationId: conv, unreadCount: uc });
      void refreshTotalUnread();
    }
    return "send";
  }
  return false;
}

function sortAsc(items) {
  return [...(items || [])].sort((a, b) => (Number(a.seq) || 0) - (Number(b.seq) || 0));
}

/** 合并同一条消息：列表/同步里 objectKey 为签名 URL，发送 ack 只有裸 key，需保留可展示的地址 */
function mergeDupMessage(prev, m) {
  const seq = Number(m.seq) || Number(prev.seq) || 0;
  const mid = m?.messageId && !String(m.messageId).startsWith("local-") ? String(m.messageId) : "";
  const messageId = mid || prev.messageId || m.messageId;
  const clientMsgId = m.clientMsgId || prev.clientMsgId;
  const status = m.status || prev.status;
  const ct = String(m?.contentType || prev?.contentType || "").toUpperCase();
  let content = m.content !== undefined ? m.content : prev.content;
  if ((ct === "IMAGE" || ct === "FILE") && prev?.content && m?.content && typeof prev.content === "object" && typeof m.content === "object") {
    const pk = String(prev.content.objectKey || "");
    const nk = String(m.content.objectKey || "");
    const pSigned = /^https?:\/\//i.test(pk);
    const nSigned = /^https?:\/\//i.test(nk);
    const objectKey = nSigned ? nk : pSigned ? pk : nk || pk;
    content = { ...prev.content, ...m.content, objectKey };
  }
  return {
    ...prev,
    ...m,
    seq,
    messageId,
    clientMsgId,
    status,
    content,
  };
}

function dedupeMessages(items) {
  const arr = sortAsc(items || []);
  const map = new Map();
  for (const m of arr) {
    const mid = m?.messageId && !String(m.messageId).startsWith("local-") ? String(m.messageId) : "";
    const key = mid ? `id:${mid}` : m?.clientMsgId ? `cid:${m.clientMsgId}` : `row:${m.seq}:${m.createdAt}`;
    const prev = map.get(key);
    if (!prev) {
      map.set(key, m);
      continue;
    }
    map.set(key, mergeDupMessage(prev, m));
  }
  return sortAsc(Array.from(map.values()));
}

export async function openConversation(conversationId) {
  const id = String(conversationId || "");
  if (!id) return;
  chatActiveConversationId.value = id;
  const st = getThread(id);
  if (!st.items.length && !st.loadingInitial) {
    await loadInitialMessages(id);
  } else {
    await pullNewerForConversation(id);
  }
  const rowBefore = chatConversations.value.find((r) => String(r.conversationId) === id);
  const prevUnread = rowBefore ? Math.max(0, Number(rowBefore.unreadCount) || 0) : 0;
  if (rowBefore) {
    upsertConversationRow({ conversationId: id, unreadCount: 0 });
  }
  if (prevUnread > 0) {
    chatTotalUnread.value = Math.max(0, Number(chatTotalUnread.value || 0) - prevUnread);
  }
  // 须先上报已读再拉总未读，否则服务端聚合仍含本会话，侧栏角标不下降
  await postConversationReadAfterView(id);
  await refreshTotalUnread();
}

export async function loadInitialMessages(conversationId) {
  const id = String(conversationId || "");
  const st = getThread(id);
  st.loadingInitial = true;
  st.initialError = "";
  try {
    const res = await chatApi.listMessages(id, { size: 40 });
    const msgs = res?.messages ?? [];
    st.items = sortAsc(msgs);
    st.hasMoreHistory = Boolean(res?.hasMore);
  } catch (e) {
    st.initialError = e?.message || "加载消息失败";
  } finally {
    st.loadingInitial = false;
  }
}

export async function loadOlderHistory(conversationId) {
  const id = String(conversationId || "");
  const st = getThread(id);
  if (!st.hasMoreHistory || st.loadingHistory || !st.items.length) return;
  const minSeq = Math.min(...st.items.map((m) => Number(m.seq) || 0));
  st.loadingHistory = true;
  try {
    const res = await chatApi.listMessages(id, { seq: minSeq, size: 30 });
    const older = res?.messages ?? [];
    st.items = sortAsc([...older, ...st.items]);
    st.hasMoreHistory = Boolean(res?.hasMore);
  } catch {
    /* ignore */
  } finally {
    st.loadingHistory = false;
  }
}

function threadSeqRange(st) {
  const seqs = (st.items || []).map((m) => Number(m.seq) || 0).filter((x) => x > 0);
  if (!seqs.length) return { min: 0, max: 0 };
  return { min: Math.min(...seqs), max: Math.max(...seqs) };
}

/**
 * 为跳转到指定 seq 预拉取消息（更早 / 更新），直到该 seq 落在已加载区间或无法再拉取。
 * @param {string} conversationId
 * @param {number} targetSeq
 */
export async function ensureMessageSeqLoaded(conversationId, targetSeq) {
  const id = String(conversationId || "");
  const t = Number(targetSeq);
  if (!id || !Number.isFinite(t) || t <= 0) return;

  const st = getThread(id);
  if (!st.items.length && !st.loadingInitial) {
    await loadInitialMessages(id);
  }

  for (let i = 0; i < 60; i++) {
    if (st.loadingInitial && !st.items.length) {
      await new Promise((r) => setTimeout(r, 40));
      continue;
    }
    const { min, max } = threadSeqRange(st);
    if (!st.items.length) break;
    if (t >= min && t <= max) return;

    if (t < min) {
      if (!st.hasMoreHistory || st.loadingHistory) break;
      await loadOlderHistory(id);
      continue;
    }

    const lenBefore = st.items.length;
    await pullNewerForConversation(id);
    if (st.items.length === lenBefore) break;
  }
}

export async function pullNewerForConversation(conversationId) {
  const id = String(conversationId || "");
  const st = getThread(id);
  const maxSeq = st.items.length ? Math.max(...st.items.map((m) => Number(m.seq) || 0)) : 0;
  try {
    const res = await chatApi.syncMessages(id, { seq: maxSeq, size: 50 });
    const newer = res?.messages ?? [];
    if (!newer.length) return;
    st.items = dedupeMessages([...st.items, ...newer]);
  } catch {
    /* ignore */
  }
}

let chatSignedUrlRefreshTimer = null;
let lastChatSignedUrlRefreshAtMs = 0;
const CHAT_SIGNED_URL_REFRESH_COOLDOWN_MS = 30000;

/**
 * 预签名过期后图片/头像会加载失败；节流刷新会话列表，并对当前会话拉最新一页合并 objectKey/头像（不整表替换，避免丢掉已加载更早历史）。
 * @param {string|null|undefined} conversationId 当前聊天会话 id；仅刷新侧栏时可不传
 */
export function scheduleAfterChatMediaLoadError(conversationId) {
  const now = Date.now();
  if (now - lastChatSignedUrlRefreshAtMs < CHAT_SIGNED_URL_REFRESH_COOLDOWN_MS) return;
  if (chatSignedUrlRefreshTimer != null) return;
  chatSignedUrlRefreshTimer = window.setTimeout(async () => {
    chatSignedUrlRefreshTimer = null;
    lastChatSignedUrlRefreshAtMs = Date.now();
    try {
      await refreshConversationList(true);
    } catch {
      /* ignore */
    }
    const id = conversationId != null ? String(conversationId).trim() : "";
    if (id && selfCtx?.userId) {
      try {
        await refreshFrontPageMessageSigning(id);
      } catch {
        /* ignore */
      }
    }
  }, 450);
}

async function refreshFrontPageMessageSigning(conversationId) {
  const id = String(conversationId || "");
  if (!id) return;
  const st = getThread(id);
  const res = await chatApi.listMessages(id, { size: 40 });
  const freshList = sortAsc(res?.messages ?? []);
  if (!freshList.length) return;
  const patchByMid = new Map(freshList.map((x) => [String(x.messageId), x]));
  st.items = st.items.map((m) => {
    const mid = String(m.messageId || "");
    if (!mid || mid.startsWith("local-")) return m;
    const f = patchByMid.get(mid);
    if (!f) return m;
    const next = { ...m, senderAvatarUrl: f.senderAvatarUrl || m.senderAvatarUrl };
    if (f.content != null && typeof f.content === "object") {
      next.content = { ...(m.content || {}), ...f.content };
    }
    return next;
  });
}

/**
 * 上报已读游标到后端（私聊与群聊均支持；后端 ChatMessageService.read 会更新 unreadCount / lastReadSeq）。
 * 此前误写成仅私聊调用，导致群聊未读一直累加。
 */
async function postConversationReadAfterView(conversationId) {
  const id = String(conversationId || "");
  const st = getThread(id);
  const maxSeq = st.items.length ? Math.max(...st.items.map((m) => Number(m.seq) || 0)) : 0;
  if (maxSeq <= 0) return;
  try {
    await chatApi.readConversation(id, maxSeq);
  } catch {
    /* ignore */
  }
}

export function newClientMsgId() {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return `cm-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * @param {string} conversationId
 * @param {string} text
 * @returns {Promise<void>}
 */
export async function sendTextMessage(conversationId, text) {
  const id = String(conversationId || "");
  const uid = selfCtx?.userId;
  if (!id || uid == null) throw new Error("未登录");
  const trimmed = String(text || "").trim();
  if (!trimmed) return;

  const clientMsgId = newClientMsgId();
  const optimistic = {
    messageId: `local-${clientMsgId}`,
    clientMsgId,
    conversationId: id,
    senderId: safeSnowflakeId(uid),
    senderNickName: selfCtx.nickName || "我",
    senderAvatarUrl: selfCtx.avatarUrl || "",
    contentType: "TEXT",
    content: { text: trimmed },
    seq: 0,
    status: "SENDING",
    createdAt: new Date().toISOString(),
    _local: true,
  };

  const st = getThread(id);
  st.items = [...st.items, optimistic];
  playSendSound();

  try {
    const ack = await chatApi.sendMessage({
      conversationId: id,
      senderId: safeSnowflakeId(uid),
      contentType: "TEXT",
      content: { text: trimmed },
      clientMsgId,
    });
    st.items = dedupeMessages(
      st.items.map((m) =>
        m.clientMsgId === clientMsgId
          ? {
              ...m,
              messageId: ack.messageId,
              seq: ack.seq,
              status: ack.status || "SENT",
              _local: false,
            }
          : m
      )
    );
    upsertConversationRow({
      conversationId: id,
      lastMessageContent: trimmed.slice(0, 80),
      lastMessageSenderNickname: selfCtx.nickName,
      lastMessageTime: new Date().toISOString(),
      isLastMessageRecall: false,
    });
    void postConversationReadAfterView(id);
  } catch (e) {
    st.items = st.items.map((m) =>
      m.clientMsgId === clientMsgId
        ? { ...m, status: "FAILED", _sendError: String(e?.message || "发送失败") }
        : m
    );
    throw e;
  }
}

function previewForListRowFromMessage(m) {
  const ct = String(m?.contentType || "").toUpperCase();
  if (ct === "TEXT") return String(m?.content?.text || "").slice(0, 80);
  if (ct === "IMAGE") return "[图片]";
  if (ct === "FORWARD_VIDEO") return "[转发视频]";
  const mimeLower = String(m?.content?.mimeType || "").toLowerCase();
  const fnLower = String(m?.content?.fileName || "").toLowerCase();
  const isVideoFile =
    ct === "FILE" &&
    (mimeLower.startsWith("video/") || /\.(mp4|webm|ogg|ogv|mov|m4v|mkv|3gp)(\?|#|$)/i.test(fnLower));
  if (ct === "FILE") return isVideoFile ? "[视频]" : String(m?.content?.fileName || "[文件]").slice(0, 80);
  return "[消息]";
}

/**
 * 失败气泡点击重发（沿用原 clientMsgId，与后端幂等一致）
 * @param {string} conversationId
 * @param {string} clientMsgId
 */
export async function retryFailedChatMessage(conversationId, clientMsgId) {
  const id = String(conversationId || "");
  const uid = selfCtx?.userId;
  if (!id || uid == null) throw new Error("未登录");
  const cid = String(clientMsgId || "").trim();
  if (!cid) throw new Error("无效消息");
  const st = getThread(id);
  const m = st.items.find((x) => String(x.clientMsgId) === cid);
  if (!m) throw new Error("消息不存在");
  const stt = String(m.status || "").toUpperCase();
  if (stt !== "FAILED") throw new Error("当前状态不可重发");
  const ct = String(m.contentType || "").toUpperCase();
  if (ct !== "TEXT" && ct !== "IMAGE" && ct !== "FILE") throw new Error("不支持重发该类型");

  st.items = st.items.map((x) => (String(x.clientMsgId) === cid ? { ...x, status: "SENDING", _sendError: undefined } : x));
  playSendSound();

  try {
    const ack = await chatApi.sendMessage({
      conversationId: id,
      senderId: m.senderId || safeSnowflakeId(uid),
      contentType: ct,
      content: m.content,
      clientMsgId: cid,
    });
    st.items = dedupeMessages(
      st.items.map((x) =>
        String(x.clientMsgId) === cid
          ? {
              ...x,
              messageId: ack.messageId,
              seq: ack.seq,
              status: ack.status || "SENT",
              _local: false,
            }
          : x
      )
    );
    if (ct === "IMAGE" || ct === "FILE") {
      try {
        const anchor = Math.max(0, Number(ack.seq) - 1);
        const res = await chatApi.syncMessages(id, { seq: anchor, size: 50 });
        const newer = res?.messages ?? [];
        if (newer.length) {
          st.items = dedupeMessages([...st.items, ...newer]);
        }
      } catch {
        /* ignore */
      }
    }
    upsertConversationRow({
      conversationId: id,
      lastMessageContent: previewForListRowFromMessage(m),
      lastMessageSenderNickname: selfCtx.nickName,
      lastMessageTime: new Date().toISOString(),
      isLastMessageRecall: false,
    });
    void postConversationReadAfterView(id);
  } catch (e) {
    st.items = st.items.map((x) =>
      String(x.clientMsgId) === cid ? { ...x, status: "FAILED", _sendError: String(e?.message || "发送失败") } : x
    );
    throw e;
  }
}

/**
 * 发送图片或文件消息（content 需含 objectKey、mimeType、size；fileName 建议带上便于展示）
 * @param {string} conversationId
 * @param {{ contentType: 'IMAGE'|'FILE', objectKey: string, mimeType: string, size: number, fileName?: string }} media
 */
export async function sendChatMediaMessage(conversationId, media) {
  const id = String(conversationId || "");
  const uid = selfCtx?.userId;
  if (!id || uid == null) throw new Error("未登录");
  const ct = String(media?.contentType || "").toUpperCase();
  if (ct !== "IMAGE" && ct !== "FILE") throw new Error("不支持的媒体类型");
  const objectKey = String(media?.objectKey || "").trim();
  const mimeType = String(media?.mimeType || "").trim() || "application/octet-stream";
  const size = Number(media?.size) || 0;
  if (!objectKey || !mimeType || size <= 0) throw new Error("媒体字段不完整");

  const clientMsgId = newClientMsgId();
  const content = {
    objectKey,
    mimeType,
    size,
    ...(media?.fileName ? { fileName: String(media.fileName) } : {}),
  };
  const optimistic = {
    messageId: `local-${clientMsgId}`,
    clientMsgId,
    conversationId: id,
    senderId: safeSnowflakeId(uid),
    senderNickName: selfCtx.nickName || "我",
    senderAvatarUrl: selfCtx.avatarUrl || "",
    contentType: ct,
    content,
    seq: 0,
    status: "SENDING",
    createdAt: new Date().toISOString(),
    _local: true,
  };

  const st = getThread(id);
  st.items = [...st.items, optimistic];
  playSendSound();

  const mimeLower = String(media?.mimeType || content.mimeType || "").toLowerCase();
  const fnLower = String(media?.fileName || content.fileName || "").toLowerCase();
  const isVideoFile =
    ct === "FILE" &&
    (mimeLower.startsWith("video/") || /\.(mp4|webm|ogg|ogv|mov|m4v|mkv|3gp)(\?|#|$)/i.test(fnLower));
  const preview = ct === "IMAGE" ? "[图片]" : isVideoFile ? "[视频]" : String(media?.fileName || "[文件]").slice(0, 80);

  try {
    const ack = await chatApi.sendMessage({
      conversationId: id,
      senderId: safeSnowflakeId(uid),
      contentType: ct,
      content,
      clientMsgId,
    });
    st.items = dedupeMessages(
      st.items.map((m) =>
        m.clientMsgId === clientMsgId
          ? {
              ...m,
              messageId: ack.messageId,
              seq: ack.seq,
              status: ack.status || "SENT",
              _local: false,
            }
          : m
      )
    );
    /** ack 不含 content；列表接口会把 objectKey 换成签名 URL，拉一条增量以显示图片 */
    try {
      const anchor = Math.max(0, Number(ack.seq) - 1);
      const res = await chatApi.syncMessages(id, { seq: anchor, size: 50 });
      const newer = res?.messages ?? [];
      if (newer.length) {
        st.items = dedupeMessages([...st.items, ...newer]);
      }
    } catch {
      /* ignore */
    }
    upsertConversationRow({
      conversationId: id,
      lastMessageContent: preview,
      lastMessageSenderNickname: selfCtx.nickName,
      lastMessageTime: new Date().toISOString(),
      isLastMessageRecall: false,
    });
    void postConversationReadAfterView(id);
  } catch (e) {
    st.items = st.items.map((m) =>
      m.clientMsgId === clientMsgId
        ? { ...m, status: "FAILED", _sendError: String(e?.message || "发送失败") }
        : m
    );
    throw e;
  }
}

/** @param {string|number|null|undefined} messageSenderId 该条消息的发送人 id（群主撤回他人消息时与当前用户不同） */
export async function withdrawMyMessage(conversationId, clientMsgId, messageSenderId) {
  const id = String(conversationId || "");
  const sid = messageSenderId ?? selfCtx?.userId;
  await chatApi.withdrawMessage({
    conversationId: id,
    senderId: safeSnowflakeId(sid),
    clientMsgId: String(clientMsgId),
  });
  const st = getThread(id);
  const selfStr = selfCtx?.userId != null ? String(selfCtx.userId) : "";
  const senderStr = sid != null ? String(sid) : "";
  const recallType = selfStr && senderStr && selfStr === senderStr ? "SENDER" : "ADMIN";
  st.items = st.items.map((m) =>
    m.clientMsgId === clientMsgId
      ? { ...m, status: "WITHDRAWN", content: { ...(m.content || {}), text: "已撤回" }, recallType }
      : m
  );
}

export async function toggleMuteRow(row, mute) {
  const id = String(row.conversationId);
  await chatApi.setConversationMute(id, mute);
  upsertConversationRow({ conversationId: id, mute });
}

export async function toggleConversationBanned(row, banned) {
  const id = String(row.conversationId);
  await chatApi.banConversation(id, banned);
  upsertConversationRow({ conversationId: id, conversationBanned: banned });
}

export async function togglePinRow(row, pin) {
  const id = String(row.conversationId);
  await chatApi.setConversationPin(id, pin);
  upsertConversationRow({ conversationId: id, pin });
}

export async function removeConversationLocal(row) {
  const id = String(row.conversationId);
  await chatApi.deleteConversation(id);
  chatConversations.value = chatConversations.value.filter((r) => String(r.conversationId) !== id);
  if (chatActiveConversationId.value === id) {
    chatActiveConversationId.value = null;
  }
  const nextThreads = { ...threadsRef.value };
  delete nextThreads[threadKey(id)];
  threadsRef.value = nextThreads;
  await refreshTotalUnread();
}

/**
 * 发起私聊（占位名称/头像可后续用 getUserInfoById 优化）
 * @param {string|number} peerUserId
 * @param {{ name?: string, avatar?: string }} [hint]
 */
export async function startSingleChat(peerUserId, hint = {}) {
  const uid = selfCtx?.userId;
  if (uid == null) throw new Error("未登录");
  const peer =
    parseDigitSnowflakeId(peerUserId) ??
    (typeof peerUserId === "string" && /^\d+$/.test(peerUserId.trim()) ? peerUserId.trim() : null) ??
    safeSnowflakeId(peerUserId);
  if (!peer || !/^\d+$/.test(peer)) {
    throw new Error("无效的用户 ID（请勿使用已丢失精度的数字，请传字符串雪花 ID）");
  }
  const name = hint.name || `用户 ${peer}`;
  const rawAvatar = hint.avatar || "static/cat.jpg";
  const avatar = toObjectKeyOrPath(rawAvatar) || rawAvatar;
  const vo = await chatApi.createConversation({
    type: "SINGLE",
    memberIds: [peer],
    name,
    avatar,
  });
  await refreshConversationList(true);
  if (vo?.id) {
    await openConversation(vo.id);
    navigateToChat(vo.id);
  }
  return vo;
}

/**
 * 发起群聊（成员须互关；memberUserIds 不含自己）
 * @param {(string|number)[]} memberUserIds
 * @param {{ name?: string, avatar?: string }} [hint]
 */
export async function startGroupChat(memberUserIds, hint = {}) {
  const uid = selfCtx?.userId;
  if (uid == null) throw new Error("未登录");
  const ids = [];
  for (const raw of memberUserIds || []) {
    const peer =
      parseDigitSnowflakeId(raw) ??
      (typeof raw === "string" && /^\d+$/.test(String(raw).trim()) ? String(raw).trim() : null) ??
      safeSnowflakeId(raw);
    if (peer && /^\d+$/.test(peer)) ids.push(peer);
  }
  if (!ids.length) {
    throw new Error("请至少选择一名互关好友");
  }
  const name = (hint.name && String(hint.name).trim()) || "群聊";
  const rawAvatar = hint.avatar || "static/cat.jpg";
  const avatar = toObjectKeyOrPath(rawAvatar) || rawAvatar;
  const vo = await chatApi.createConversation({
    type: "GROUP",
    memberIds: ids,
    name,
    avatar,
  });
  await refreshConversationList(true);
  if (vo?.id) {
    await openConversation(vo.id);
    navigateToChat(vo.id);
  }
  return vo;
}
