<script setup>
import { ref, watch, nextTick, computed, onMounted, onUnmounted } from "vue";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { STORAGE_BIZ_CHAT } from "../../api/otherApi.js";
import { uploadFileWithPresign } from "../../utils/presignUpload.js";
import {
  sendChatMediaMessage,
  retryFailedChatMessage,
  scheduleAfterChatMediaLoadError,
  ensureMessageSeqLoaded,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import ChatEmojiPicker from "./ChatEmojiPicker.vue";
import { annotateChatMessagesWithTime } from "../../utils/chatTime.js";

/** 聊天附件单文件上限（1 GiB，与常见「1G」表述一致） */
const CHAT_UPLOAD_MAX_BYTES = 1024 * 1024 * 1024;

const props = defineProps({
  conversationId: { type: String, default: null },
  conversationName: { type: String, default: "" },
  /** 会话头像（侧栏与消息头展示，对象存储签名 URL） */
  conversationAvatar: { type: String, default: "" },
  conversationType: { type: String, default: "" },
  /** 群聊：是否开启全员禁言（由群主在会话信息面板切换） */
  conversationBanned: { type: Boolean, default: false },
  /** 群聊：当前用户是否被单独禁言（来自会话行 banned 字段） */
  selfBanned: { type: Boolean, default: false },
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingHistory: { type: Boolean, default: false },
  hasMoreHistory: { type: Boolean, default: false },
  error: { type: String, default: "" },
  selfUserId: { type: [String, Number], default: null },
  /** 当前用户是否为群聊群主（与后端会话 createdBy 一致） */
  isGroupOwner: { type: Boolean, default: false },
  peerReadSeq: { type: Number, default: 0 },
  /** 与 jumpTargetNonce 配合：要滚动定位的消息 seq */
  jumpTargetSeq: { type: Number, default: 0 },
  /** 递增以触发滚动（同 seq 再次点击也会 +1） */
  jumpTargetNonce: { type: Number, default: 0 },
  /** 群聊：服务端公告版本高于本用户已读 */
  announcementUnread: { type: Boolean, default: false },
});

const emit = defineEmits(["send", "load-history", "withdraw", "open-info", "open-video"]);

function headAvatarSrc() {
  const u = sanitizeImageUrl(props.conversationAvatar);
  return u || DEFAULT_PROFILE_AVATAR_URL;
}

function onHeadAvatarError(ev) {
  const t = ev?.target;
  if (t instanceof HTMLImageElement) {
    t.onerror = null;
    t.src = DEFAULT_PROFILE_AVATAR_URL;
  }
}

function onOpenInfo() {
  if (!props.conversationId) return;
  emit("open-info");
}

const inputText = ref("");
const inputTextRef = ref(null);
const listRef = ref(null);
const sending = ref(false);
const fileInputRef = ref(null);
const fileUploading = ref(false);

const downloadConfirmOpen = ref(false);
const pendingDownload = ref(null);

/** @type {import('vue').Ref<Record<string, boolean>>} */
const retrySendBusy = ref({});

function isSendFailed(m) {
  return String(m?.status || "").toUpperCase() === "FAILED";
}

function isSending(m) {
  return String(m?.status || "").toUpperCase() === "SENDING";
}

async function onRetrySend(m) {
  if (!props.conversationId || !isSelf(m) || !isSendFailed(m) || !m.clientMsgId) return;
  const key = String(m.clientMsgId);
  if (retrySendBusy.value[key]) return;
  retrySendBusy.value = { ...retrySendBusy.value, [key]: true };
  try {
    await retryFailedChatMessage(props.conversationId, key);
    await nextTick();
    scheduleScrollToBottom();
  } catch (e) {
    toast(e?.message || "重发失败", "error");
  } finally {
    const rest = { ...retrySendBusy.value };
    delete rest[key];
    retrySendBusy.value = rest;
  }
}

function openFileDownloadConfirm(m) {
  const url = sanitizeImageUrl(m?.content?.objectKey);
  if (!url) {
    toast("无法下载：缺少文件地址", "error");
    return;
  }
  pendingDownload.value = {
    url,
    fileName: fileAttachmentDisplayName(m),
  };
  downloadConfirmOpen.value = true;
}

function closeFileDownloadConfirm() {
  downloadConfirmOpen.value = false;
  pendingDownload.value = null;
}

function confirmFileDownload() {
  const p = pendingDownload.value;
  closeFileDownloadConfirm();
  if (!p?.url) return;
  const a = document.createElement("a");
  a.href = p.url;
  a.download = p.fileName || "download";
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/** 图片 / 视频全屏预览 */
const mediaPreview = ref(null);

function openMediaPreview(m, kind) {
  const url = sanitizeImageUrl(m?.content?.objectKey);
  if (!url) return;
  mediaPreview.value = { url, kind: kind === "video" ? "video" : "image" };
}

function closeMediaPreview() {
  mediaPreview.value = null;
}

function onGlobalKeydownForPreview(ev) {
  if (ev.key === "Escape" && mediaPreview.value) {
    closeMediaPreview();
  }
}

const isSelf = (m) => String(m.senderId) === String(props.selfUserId);

function seqAttr(m) {
  const s = Number(m?.seq);
  return Number.isFinite(s) && s > 0 ? String(s) : undefined;
}

function scrollJumpTargetIntoView(seq, behavior, withFlash = true) {
  const wrap = listRef.value;
  if (!wrap) return null;
  const el = wrap.querySelector(`[data-chat-seq="${seq}"]`);
  if (!(el instanceof HTMLElement)) return null;
  const beh = behavior === "smooth" ? "smooth" : "auto";
  el.scrollIntoView({ block: "center", behavior: beh });
  if (withFlash) {
    el.classList.add("chat-msg-jump-flash");
    window.setTimeout(() => el.classList.remove("chat-msg-jump-flash"), 1400);
  }
  return el;
}

async function runJumpToSeq(seq) {
  const id = String(props.conversationId || "");
  if (!id) return;
  const t = Number(seq);
  if (!Number.isFinite(t) || t <= 0) return;
  activateJumpScrollAutoScrollSuppress();
  try {
    await ensureMessageSeqLoaded(id, t);
    await nextTick();
    await nextTick();
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const first = scrollJumpTargetIntoView(t, "smooth");
    if (!first) {
      toast("未在已加载消息中找到该条，请稍后再试", "info");
      return;
    }
    // 滚底定时器（最长约 600ms）可能在首次定位之后执行，再在窗口外补一次瞬时对齐
    window.setTimeout(() => {
      if (!isJumpSuppressingAutoScroll()) return;
      scrollJumpTargetIntoView(t, "auto", false);
    }, 680);
  } catch (e) {
    toast(e?.message || "跳转失败", "error");
  }
}

/** FILE 中的视频：后端暂无 VIDEO 枚举，上传走 FILE + video mime */
function messageMediaKind(m) {
  const ct = String(m?.contentType || "").toUpperCase();
  const c = m?.content || {};
  const key = String(c.objectKey || "").trim();
  if (!key) return null;
  if (ct === "IMAGE") return "image";
  if (ct === "FILE" || ct === "VIDEO") {
    const mime = String(c.mimeType || "").toLowerCase();
    if (mime.startsWith("video/")) return "video";
    const name = String(c.fileName || c.objectKey || "").toLowerCase();
    if (/\.(mp4|webm|ogg|ogv|mov|m4v|mkv|3gp)(\?|#|$)/i.test(name)) return "video";
    if (ct === "VIDEO") return "video";
  }
  return null;
}

/** 非视频的 FILE（走附件卡片 + 下载确认） */
function isChatGenericFile(m) {
  if (String(m?.contentType || "").toUpperCase() !== "FILE") return false;
  if (!String(m?.content?.objectKey || "").trim()) return false;
  return messageMediaKind(m) !== "video";
}

function fileAttachmentDisplayName(m) {
  const c = m?.content || {};
  const fn = String(c.fileName || "").trim();
  if (fn) return fn;
  const key = String(c.objectKey || "");
  const base = key.split("?")[0].split("#")[0].split(/[/\\]/).pop() || "";
  return base || "[文件]";
}

function fileAttachmentSizeLabel(m) {
  const n = Number(m?.content?.size);
  if (!Number.isFinite(n) || n <= 0) return "";
  const units = ["B", "K", "M", "G"];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  if (i === 0) return `${Math.round(v)}${units[i]}`;
  const decimals = v >= 10 ? 0 : 1;
  const rounded = decimals === 0 ? Math.round(v) : Math.round(v * 10) / 10;
  let s = String(rounded);
  if (s.endsWith(".0")) s = s.slice(0, -2);
  return `${s}${units[i]}`;
}

function isWithdrawn(m) {
  const st = String(m?.status || "").toUpperCase();
  return st === "WITHDRAWN" || st === "WITHDRAW";
}

/** 后端 RecallTypeEnum：JSON 可能为 "ADMIN" / { name: "ADMIN" } */
function normalizeRecallType(v) {
  if (v == null || v === "") return "";
  if (typeof v === "string") return String(v).toUpperCase();
  if (typeof v === "object") {
    const n = v?.name ?? v?.code;
    if (n != null) return String(n).toUpperCase();
  }
  return String(v).toUpperCase();
}

/** 居中系统提示：本人撤回 vs 群主撤回成员消息 */
function withdrawNoticeText(m) {
  const rt = normalizeRecallType(m?.recallType);
  const nick = (isSelf(m) ? "我" : String(m?.senderNickName || "").trim() || "对方").trim();
  // 群主撤自己的消息应为 SENDER；若历史数据误为 ADMIN 且展示为「我」，仍按本人撤回展示
  if (rt === "ADMIN" && !isSelf(m)) {
    return `群主撤回了「${nick}」的一条消息`;
  }
  return `\u201c${nick}\u201d 撤回了一条消息`;
}

function bubbleText(m) {
  const st = String(m.status || "").toUpperCase();
  if (st === "WITHDRAWN" || st === "WITHDRAW") return "已撤回";
  const c = m.content || {};
  const ct = String(m.contentType || "").toUpperCase();
  if (ct === "TEXT") return c.text || "";
  if (ct === "SYSTEM") return c.systemText || c.text || "[系统]";
  if (ct === "FORWARD_VIDEO") return "[转发视频]";
  if (ct === "IMAGE") return "";
  if (messageMediaKind(m) === "video") return "";
  if (isChatGenericFile(m)) return "";
  if (ct === "FILE") return c.fileName || c.objectKey || "[文件]";
  return c.text || "[消息]";
}

function onForwardVideoClick(m) {
  const c = m?.content || {};
  const vid = c.forwardVideoId ?? c.forwardVideoID ?? c.videoId;
  if (vid == null || vid === "") return;
  emit("open-video", { videoId: String(vid) });
}

function avatarOf(m) {
  const s = sanitizeImageUrl(m.senderAvatarUrl);
  return s || DEFAULT_PROFILE_AVATAR_URL;
}

function onMsgRowAvatarError(ev) {
  const el = ev?.target;
  if (el instanceof HTMLImageElement) {
    el.onerror = null;
    el.src = DEFAULT_PROFILE_AVATAR_URL;
    el.style.opacity = "1";
  }
  scheduleAfterChatMediaLoadError(props.conversationId);
}

function onBubbleImageError() {
  scheduleAfterChatMediaLoadError(props.conversationId);
}

function onBubbleVideoError() {
  scheduleAfterChatMediaLoadError(props.conversationId);
}

function onPreviewMediaError() {
  closeMediaPreview();
  scheduleAfterChatMediaLoadError(props.conversationId);
}

function scrollToBottomNow() {
  const el = listRef.value;
  if (!el) return;
  el.scrollTop = el.scrollHeight;
}

/** 搜索结果跳转定位期间，禁止自动滚底（避免 items 变长 + isNearListBottom 触发把条拉到底） */
let jumpSuppressAutoScrollUntil = 0;

function isJumpSuppressingAutoScroll() {
  return Date.now() < jumpSuppressAutoScrollUntil;
}

function activateJumpScrollAutoScrollSuppress() {
  jumpSuppressAutoScrollUntil = Date.now() + 2000;
}

/** 布局与图片/视频加载后多次对齐到底部，避免刷新后滚动条停在中间 */
function scheduleScrollToBottom() {
  if (isJumpSuppressingAutoScroll()) return;
  nextTick(() => {
    scrollToBottomNow();
    requestAnimationFrame(() => {
      scrollToBottomNow();
      requestAnimationFrame(scrollToBottomNow);
    });
    window.setTimeout(scrollToBottomNow, 60);
    window.setTimeout(scrollToBottomNow, 240);
    window.setTimeout(scrollToBottomNow, 600);
  });
}

const STICK_BOTTOM_PX = 120;

function isNearListBottom() {
  const el = listRef.value;
  if (!el) return true;
  const { scrollTop, scrollHeight, clientHeight } = el;
  if (scrollHeight <= clientHeight + 2) return true;
  return scrollHeight - scrollTop - clientHeight < STICK_BOTTOM_PX;
}

let mediaLayoutTimer = null;
function onChatMediaLayout() {
  if (mediaLayoutTimer) window.clearTimeout(mediaLayoutTimer);
  mediaLayoutTimer = window.setTimeout(() => {
    mediaLayoutTimer = null;
    if (isJumpSuppressingAutoScroll()) return;
    if (isNearListBottom()) {
      scheduleScrollToBottom();
    }
  }, 48);
}

/** 定时递增，使撤回按钮在超过 2 分钟后随渲染隐藏（须在依赖它的 watch 之前声明） */
const uiClock = ref(0);
let withdrawUiTimer = null;

/** 群主右击气泡后展示的「撤回」：`senderId::clientMsgId` */
const ownerRecallMenuKey = ref(/** @type {string|null} */ (null));

watch(
  () => props.conversationId,
  () => {
    ownerRecallMenuKey.value = null;
    uiClock.value++;
    scheduleScrollToBottom();
  },
);

watch(
  () => props.loading,
  (v, prev) => {
    if (v === false && prev === true) {
      if (!isJumpSuppressingAutoScroll()) scheduleScrollToBottom();
    }
  },
);

watch(
  () => props.items?.length,
  (len, oldLen) => {
    if (props.loading || props.loadingHistory) return;
    if (isJumpSuppressingAutoScroll()) return;
    if (len > (oldLen ?? 0) && isNearListBottom()) {
      scheduleScrollToBottom();
    }
  },
);

watch(
  () => props.isGroupOwner,
  () => {
    ownerRecallMenuKey.value = null;
  },
);

watch(
  () => props.jumpTargetNonce,
  (nonce, prevNonce) => {
    if (!nonce || nonce === prevNonce) return;
    const seq = Number(props.jumpTargetSeq);
    if (!Number.isFinite(seq) || seq <= 0) return;
    if (!props.conversationId) return;
    void runJumpToSeq(seq);
  }
);

const isSingleChat = computed(() => String(props.conversationType || "").toUpperCase() === "SINGLE");

const isGroupChat = computed(() => String(props.conversationType || "").toUpperCase() === "GROUP");

const sendDisabledByBan = computed(
  () =>
    isGroupChat.value &&
    (props.selfBanned || (props.conversationBanned && !props.isGroupOwner))
);

const canSend = computed(
  () => inputText.value.trim().length > 0 && !sending.value && !sendDisabledByBan.value
);

const banPlaceholder = computed(() => {
  if (!isGroupChat.value) return "";
  if (props.selfBanned) return "你已被群主禁言";
  if (props.conversationBanned && !props.isGroupOwner) return "本群已开启全员禁言";
  return "";
});

/** 私聊下对方已读游标内，己方消息里最大的 seq（仅一条展示「已读」） */
const latestReadSelfSeq = computed(() => {
  const pr = Number(props.peerReadSeq) || 0;
  if (!pr) return 0;
  let max = 0;
  for (const x of props.items || []) {
    if (String(x.senderId) !== String(props.selfUserId)) continue;
    const st = String(x?.status || "").toUpperCase();
    if (st === "WITHDRAWN" || st === "WITHDRAW") continue;
    const s = Number(x.seq);
    if (!Number.isFinite(s) || s <= 0 || pr < s) continue;
    if (s > max) max = s;
  }
  return max;
});

const timeAnnotatedItems = computed(() => annotateChatMessagesWithTime(props.items, Date.now()));

function showReadForMessage(m) {
  if (!isSingleChat.value || !isSelf(m)) return false;
  const pr = Number(props.peerReadSeq) || 0;
  const s = Number(m.seq);
  if (!Number.isFinite(s) || s <= 0 || pr < s) return false;
  if (isWithdrawn(m)) return false;
  return s === latestReadSelfSeq.value;
}

function msgRecallKey(m) {
  return `${String(m?.senderId ?? "")}::${String(m?.clientMsgId ?? "")}`;
}

function canOwnerRecallMessage(m) {
  if (!isGroupChat.value || !props.isGroupOwner) return false;
  if (!m?.clientMsgId || m._local) return false;
  if (isWithdrawn(m)) return false;
  const ct = String(m.contentType || "").toUpperCase();
  if (ct === "SYSTEM") return false;
  if (isSending(m) || isSendFailed(m)) return false;
  return true;
}

function showOwnerRecallAction(m) {
  const k = msgRecallKey(m);
  return Boolean(k && ownerRecallMenuKey.value === k);
}

function onBubbleContextMenu(m, ev) {
  if (!canOwnerRecallMessage(m)) {
    return;
  }
  ev.preventDefault();
  ownerRecallMenuKey.value = msgRecallKey(m);
}

function onDocumentMousedownOwnerRecall(ev) {
  if (!ownerRecallMenuKey.value) return;
  const t = ev.target;
  if (t instanceof Element && t.closest(".chat-msg-owner-recall-wrap")) return;
  ownerRecallMenuKey.value = null;
}

function onOwnerWithdrawClick(m) {
  if (!showOwnerRecallAction(m) || !m.clientMsgId) return;
  ownerRecallMenuKey.value = null;
  emit("withdraw", m);
}

onMounted(() => {
  withdrawUiTimer = window.setInterval(() => {
    uiClock.value++;
  }, 10000);
  window.addEventListener("keydown", onGlobalKeydownForPreview);
  document.addEventListener("mousedown", onDocumentMousedownOwnerRecall, true);
  scheduleScrollToBottom();
});

onUnmounted(() => {
  if (withdrawUiTimer != null) {
    window.clearInterval(withdrawUiTimer);
    withdrawUiTimer = null;
  }
  if (mediaLayoutTimer != null) {
    window.clearTimeout(mediaLayoutTimer);
    mediaLayoutTimer = null;
  }
  window.removeEventListener("keydown", onGlobalKeydownForPreview);
  document.removeEventListener("mousedown", onDocumentMousedownOwnerRecall, true);
});

async function onSend() {
  if (!props.conversationId) return;
  if (sendDisabledByBan.value) {
    return;
  }
  if (!canSend.value) return;
  const t = inputText.value.trim();
  sending.value = true;
  try {
    emit("send", t);
    inputText.value = "";
    await nextTick();
    scheduleScrollToBottom();
  } finally {
    sending.value = false;
  }
}

function onScroll() {
  ownerRecallMenuKey.value = null;
  const el = listRef.value;
  if (!el || !props.hasMoreHistory || props.loadingHistory) return;
  if (el.scrollTop < 48) {
    emit("load-history");
  }
}

function withinWithdrawWindow(m) {
  void uiClock.value;
  if (!isSelf(m) || m._local) return false;
  // 群内群主通过右击代撤回（含本人历史消息），不再展示 2 分钟内的常驻「撤回」
  if (isGroupChat.value && props.isGroupOwner) return false;
  const t = m.createdAt ? new Date(m.createdAt).getTime() : 0;
  if (!Number.isFinite(t)) return false;
  return Date.now() - t < 2 * 60 * 1000;
}

function onWithdraw(m) {
  if (!withinWithdrawWindow(m) || !m.clientMsgId) return;
  emit("withdraw", m);
}

function triggerFilePick() {
  if (!props.conversationId) {
    toast("请先选择会话", "info");
    return;
  }
  if (sendDisabledByBan.value) {
    return;
  }
  if (fileUploading.value) return;
  fileInputRef.value?.click();
}

function onEmojiPick(glyph) {
  const g = String(glyph || "");
  if (!g) return;
  const el = inputTextRef.value;
  if (el instanceof HTMLTextAreaElement) {
    const start = typeof el.selectionStart === "number" ? el.selectionStart : inputText.value.length;
    const end = typeof el.selectionEnd === "number" ? el.selectionEnd : start;
    const v = inputText.value;
    inputText.value = `${v.slice(0, start)}${g}${v.slice(end)}`;
    nextTick(() => {
      const pos = start + g.length;
      el.selectionStart = el.selectionEnd = pos;
      el.focus();
    });
  } else {
    inputText.value += g;
  }
}

async function onChatFileChange(ev) {
  const input = ev?.target;
  const file = input?.files?.[0];
  if (input) input.value = "";
  if (!file || !props.conversationId) return;
  if (file.size > CHAT_UPLOAD_MAX_BYTES) {
    toast("聊天附件单文件不能超过 1GB", "error");
    return;
  }
  fileUploading.value = true;
  try {
    const uploaded = await uploadFileWithPresign(file, STORAGE_BIZ_CHAT);
    const isImage = String(file.type || "").toLowerCase().startsWith("image/");
    const contentType = isImage ? "IMAGE" : "FILE";
    await sendChatMediaMessage(props.conversationId, {
      contentType,
      objectKey: uploaded.objectKey,
      mimeType: uploaded.mimeType,
      size: uploaded.size,
      fileName: uploaded.fileName,
    });
    await nextTick();
    scheduleScrollToBottom();
  } catch (e) {
    toast(e?.message || "上传或发送失败", "error");
  } finally {
    fileUploading.value = false;
  }
}
</script>

<template>
  <div class="chat-msg-panel">
    <header class="chat-msg-panel__head">
      <button
        type="button"
        class="chat-msg-panel__head-av"
        :title="isGroupChat ? '群聊信息' : '会话信息'"
        aria-label="查看会话信息"
        @click="onOpenInfo"
      >
        <img :src="headAvatarSrc()" alt="" @error="onHeadAvatarError" />
      </button>
      <div class="chat-msg-panel__title-wrap">
        <h2 class="chat-msg-panel__title">{{ conversationName || "聊天" }}</h2>
        <Transition name="chat-ban-fade">
          <span
            v-if="isGroupChat && conversationBanned"
            class="chat-msg-panel__ban-badge"
            aria-label="全员禁言中"
            title="全员禁言中"
          >
            <i class="ri-volume-mute-line" aria-hidden="true"></i>
            禁言中
          </span>
        </Transition>
      </div>
    </header>
    <button
      v-if="isGroupChat && announcementUnread"
      type="button"
      class="chat-msg-panel__ann-hint"
      @click="onOpenInfo"
    >
      <i class="ri-megaphone-line" aria-hidden="true"></i>
      <span class="chat-msg-panel__ann-hint-txt">群公告有更新，点此查看</span>
      <i class="ri-arrow-right-s-line chat-msg-panel__ann-hint-chev" aria-hidden="true"></i>
    </button>
    <p v-if="error" class="chat-msg-panel__err">{{ error }}</p>
    <div ref="listRef" class="chat-msg-panel__list" @scroll.passive="onScroll">
      <div v-if="loadingHistory" class="chat-msg-panel__hist">加载更早消息…</div>
      <template v-for="m in timeAnnotatedItems" :key="m.clientMsgId || m.messageId">
        <div v-if="m._showTime && m._timeText" class="chat-msg-time" role="note">
          {{ m._timeText }}
        </div>
        <div
          v-if="isWithdrawn(m)"
          class="chat-msg-recall"
          role="note"
          :data-chat-seq="seqAttr(m)"
        >
          <span class="chat-msg-recall__text">{{ withdrawNoticeText(m) }}</span>
        </div>
        <div
          v-else
          class="chat-msg-row"
          :class="{ 'chat-msg-row--self': isSelf(m) }"
          :data-chat-seq="seqAttr(m)"
        >
          <div class="chat-msg-av-col">
            <div class="chat-msg-meta">
              <span class="chat-msg-meta__nick">{{ isSelf(m) ? "我" : m.senderNickName || "对方" }}</span>
              <span v-if="isSending(m)" class="chat-msg-meta__st">发送中…</span>
            </div>
            <img class="chat-msg-row__av" :src="avatarOf(m)" alt="" @error="onMsgRowAvatarError" />
          </div>
          <div class="chat-msg-row__col">
            <div class="chat-msg-main" :class="{ 'chat-msg-main--self': isSelf(m) }">
              <div class="chat-msg-bubble-wrap">
                <div class="chat-msg-bubble-line" :class="{ 'chat-msg-bubble-line--self': isSelf(m) }">
                  <div
                    class="chat-msg-bubble-stack"
                    :class="{ 'chat-msg-bubble-stack--self': isSelf(m) }"
                    @contextmenu="onBubbleContextMenu(m, $event)"
                  >
                  <button
                    v-if="withinWithdrawWindow(m)"
                    type="button"
                    class="chat-msg-withdraw"
                    @click="onWithdraw(m)"
                  >
                    撤回
                  </button>
                  <span v-if="showOwnerRecallAction(m)" class="chat-msg-owner-recall-wrap">
                    <button
                      type="button"
                      class="chat-msg-withdraw chat-msg-owner-recall"
                      title="撤回该条消息"
                      @click.stop="onOwnerWithdrawClick(m)"
                    >
                      撤回
                    </button>
                  </span>
                  <div
                    v-if="String(m.contentType || '').toUpperCase() === 'IMAGE' && m.content?.objectKey"
                    class="chat-msg-bubble chat-msg-bubble--img chat-msg-bubble--media-tappable"
                    role="button"
                    tabindex="0"
                    title="点击放大"
                    @click="openMediaPreview(m, 'image')"
                    @keydown.enter.prevent="openMediaPreview(m, 'image')"
                    @keydown.space.prevent="openMediaPreview(m, 'image')"
                  >
                    <img
                      :src="sanitizeImageUrl(m.content.objectKey)"
                      alt=""
                      class="chat-msg-bubble__img"
                      draggable="false"
                      @load="onChatMediaLayout"
                      @error="onBubbleImageError"
                    />
                  </div>
                  <button
                    v-else-if="String(m.contentType || '').toUpperCase() === 'FORWARD_VIDEO'"
                    type="button"
                    class="chat-msg-fwd-card"
                    :class="{ 'chat-msg-fwd-card--self': isSelf(m), 'chat-msg-fwd-card--no-cover': !m.content?.forwardVideoCoverObjectKey }"
                    @click="onForwardVideoClick(m)"
                  >
                    <div class="chat-msg-fwd-card__media">
                      <img
                        v-if="m.content?.forwardVideoCoverObjectKey"
                        class="chat-msg-fwd-card__cover"
                        :src="sanitizeImageUrl(m.content.forwardVideoCoverObjectKey)"
                        alt=""
                        draggable="false"
                        @error="onBubbleImageError"
                        @load="onChatMediaLayout"
                      />
                      <span class="chat-msg-fwd-card__play" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                      <span class="chat-msg-fwd-card__tag" aria-hidden="true">转发</span>
                      <div v-if="m.content?.forwardVideoTitle" class="chat-msg-fwd-card__info">
                        <span class="chat-msg-fwd-card__title" :title="String(m.content.forwardVideoTitle || '')">{{
                          String(m.content.forwardVideoTitle || '')
                        }}</span>
                      </div>
                    </div>
                  </button>
                  <div
                    v-else-if="messageMediaKind(m) === 'video'"
                    class="chat-msg-bubble chat-msg-bubble--video chat-msg-bubble--media-tappable"
                    role="button"
                    tabindex="0"
                    title="点击放大观看"
                    @click="openMediaPreview(m, 'video')"
                    @keydown.enter.prevent="openMediaPreview(m, 'video')"
                    @keydown.space.prevent="openMediaPreview(m, 'video')"
                  >
                    <video
                      class="chat-msg-bubble__video chat-msg-bubble__video--thumb"
                      muted
                      playsinline
                      preload="metadata"
                      :src="sanitizeImageUrl(m.content.objectKey)"
                      @loadedmetadata="onChatMediaLayout"
                      @error="onBubbleVideoError"
                    />
                    <span class="chat-msg-media-play" aria-hidden="true">
                      <i class="ri-play-fill"></i>
                    </span>
                  </div>
                  <button
                    v-else-if="isChatGenericFile(m)"
                    type="button"
                    class="chat-msg-bubble chat-msg-bubble--file"
                    :class="{ 'chat-msg-bubble--self': isSelf(m) }"
                    @click="openFileDownloadConfirm(m)"
                  >
                    <span class="chat-msg-file-card__text">
                      <span class="chat-msg-file-card__name" :title="fileAttachmentDisplayName(m)">{{ fileAttachmentDisplayName(m) }}</span>
                      <span v-if="fileAttachmentSizeLabel(m)" class="chat-msg-file-card__size">{{ fileAttachmentSizeLabel(m) }}</span>
                    </span>
                    <span class="chat-msg-file-card__icon" aria-hidden="true">
                      <i class="ri-file-2-line"></i>
                    </span>
                  </button>
                  <div v-else class="chat-msg-bubble" :class="{ 'chat-msg-bubble--self': isSelf(m) }">
                    {{ bubbleText(m) }}
                  </div>
                  </div>
                  <button
                    v-if="isSelf(m) && isSendFailed(m)"
                    type="button"
                    class="chat-msg-fail-retry"
                    :disabled="!!retrySendBusy[String(m.clientMsgId || '')]"
                    :title="m._sendError ? `点击重发（${m._sendError}）` : '点击重发'"
                    :aria-label="m._sendError ? `发送失败，${m._sendError}，点击重发` : '发送失败，点击重发'"
                    @click="onRetrySend(m)"
                  >
                    <i class="ri-error-warning-fill" aria-hidden="true"></i>
                  </button>
                </div>
                <div v-if="showReadForMessage(m)" class="chat-msg-read chat-msg-read--under-bubble">已读</div>
              </div>
            </div>
          </div>
        </div>
      </template>
      <div v-if="loading && !items.length" class="chat-msg-panel__hint">加载消息…</div>
    </div>
    <footer class="chat-msg-panel__foot">
      <input
        ref="fileInputRef"
        type="file"
        class="chat-msg-file-input"
        :disabled="fileUploading"
        @change="onChatFileChange"
      />
      <div class="chat-msg-foot-tools">
        <button
          type="button"
          class="chat-msg-attach"
          :disabled="!conversationId || fileUploading || sendDisabledByBan"
          title="上传文件"
          aria-label="上传文件"
          @click="triggerFilePick"
        >
          <i class="ri-folder-line" aria-hidden="true"></i>
        </button>
        <ChatEmojiPicker :disabled="!conversationId || fileUploading || sendDisabledByBan" @pick="onEmojiPick" />
      </div>
      <textarea
        ref="inputTextRef"
        v-model="inputText"
        class="chat-msg-input"
        rows="2"
        :disabled="!conversationId || sendDisabledByBan"
        :placeholder="sendDisabledByBan ? banPlaceholder : '输入消息，Enter 发送（Shift+Enter 换行）'"
        @keydown.enter.exact.prevent="onSend"
      />
      <button type="button" class="chat-msg-send" :disabled="!canSend" @click="onSend">发送</button>
    </footer>

    <Teleport to="body">
      <div
        v-if="mediaPreview"
        class="chat-media-preview-overlay"
        role="presentation"
        @click.self="closeMediaPreview"
      >
        <button type="button" class="chat-media-preview__close" aria-label="关闭" @click="closeMediaPreview">
          <i class="ri-close-line" aria-hidden="true"></i>
        </button>
        <img
          v-if="mediaPreview.kind === 'image'"
          :src="mediaPreview.url"
          alt=""
          class="chat-media-preview__img"
          draggable="false"
          @click.stop
          @error="onPreviewMediaError"
        />
        <video
          v-else
          class="chat-media-preview__video"
          controls
          playsinline
          preload="auto"
          :src="mediaPreview.url"
          @click.stop
          @error="onPreviewMediaError"
        />
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="downloadConfirmOpen"
        class="modal-overlay modal-overlay--light chat-file-dl-overlay"
        role="presentation"
        @click.self="closeFileDownloadConfirm"
      >
        <div class="modal-card chat-file-dl-modal" role="dialog" aria-modal="true" aria-labelledby="chat-file-dl-title" @click.stop>
          <h3 id="chat-file-dl-title">确认下载</h3>
          <p class="chat-file-dl-modal__msg">
            是否确认下载
            <span class="chat-file-dl-modal__fname">「{{ pendingDownload?.fileName || "" }}」</span>
            ？
          </p>
          <div class="chat-file-dl-modal__actions">
            <button type="button" class="chat-file-dl-btn chat-file-dl-btn--ghost" @click="closeFileDownloadConfirm">取消</button>
            <button type="button" class="chat-file-dl-btn chat-file-dl-btn--primary" @click="confirmFileDownload">确认</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
