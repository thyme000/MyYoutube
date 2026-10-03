<script setup>
import { ref, watch, nextTick, computed, onMounted, onUnmounted } from "vue";
import { sanitizeImageUrl } from "../../utils/userProfile.js";
import { STORAGE_BIZ_CHAT } from "../../api/otherApi.js";
import { uploadFileWithPresign } from "../../utils/presignUpload.js";
import {
  retryFailedChatMessage,
  sendChatMediaMessage,
  scheduleAfterChatMediaLoadError,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import ChatEmojiPicker from "./ChatEmojiPicker.vue";
import BaseModal from "../ui/BaseModal.vue";
import { annotateChatMessagesWithTime } from "../../utils/chatTime.js";
import { useChatMessageDisplay } from "../../composables/chat/useChatMessageDisplay.js";
import { useChatScroll } from "../../composables/chat/useChatScroll.js";
import { useChatOwnerRecall } from "../../composables/chat/useChatOwnerRecall.js";
import { useChatMediaPreview } from "../../composables/chat/useChatMediaPreview.js";

const CHAT_UPLOAD_MAX_BYTES = 1024 * 1024 * 1024;
const props = defineProps({
  conversationId: { type: String, default: null },
  conversationType: { type: String, default: "" },
  conversationName: { type: String, default: "" },
  conversationAvatar: { type: String, default: "" },
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

const emit = defineEmits(["send", "load-history", "withdraw", "open-info", "open-video", "open-dynamic"]);

const {
  isSelf,
  isSendFailed,
  isSending,
  isWithdrawn,
  messageMediaKind,
  isChatGenericFile,
  fileAttachmentDisplayName,
  fileAttachmentSizeLabel,
  withdrawNoticeText,
  bubbleText,
  avatarOf,
  headAvatarSrc,
  onHeadAvatarError,
  onMsgRowAvatarError,
  seqAttr,
} = useChatMessageDisplay(props);

const inputText = ref("");
const inputTextRef = ref(null);
const listRef = ref(null);
const sending = ref(false);
const fileInputRef = ref(null);
const fileUploading = ref(false);

const { mediaPreview, openMediaPreview, closeMediaPreview } = useChatMediaPreview();
const {
  isJumpSuppressingAutoScroll,
  scheduleScrollToBottom,
  isNearListBottom,
  onChatMediaLayout,
  runJumpToSeq,
} = useChatScroll(props, listRef);

const isSingleChat = computed(() => String(props.conversationType || "").toUpperCase() === "SINGLE");

const isGroupChat = computed(() => String(props.conversationType || "").toUpperCase() === "GROUP");

const {
  ownerRecallMenuKey,
  showOwnerRecallAction,
  onBubbleContextMenu,
  onOwnerWithdrawClick,
} = useChatOwnerRecall(
  props,
  { isGroupChat, isWithdrawn, isSending, isSendFailed },
  { onWithdraw: (m) => emit("withdraw", m) }
);

const downloadConfirmOpen = ref(false);
const pendingDownload = ref(null);

/** @type {import('vue').Ref<Record<string, boolean>>} */
const retrySendBusy = ref({});

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

function onOpenInfo() {
  if (!props.conversationId) return;
  emit("open-info");
}

function onForwardVideoClick(m) {
  const c = m?.content || {};
  const vid = c.forwardVideoId ?? c.forwardVideoID ?? c.videoId;
  if (vid == null || vid === "") return;
  emit("open-video", { videoId: String(vid) });
}

function onForwardDynamicClick(m) {
  const c = m?.content || {};
  const did = c.forwardDynamicId;
  if (did == null || did === "") return;
  emit("open-dynamic", { dynamicId: String(did) });
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

/** 定时递增，使撤回按钮在超过 2 分钟后随渲染隐藏（须在依赖它的 watch 之前声明） */
const uiClock = ref(0);
let withdrawUiTimer = null;

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

onMounted(() => {
  withdrawUiTimer = window.setInterval(() => {
    uiClock.value++;
  }, 10000);
  scheduleScrollToBottom();
});

onUnmounted(() => {
  if (withdrawUiTimer != null) {
    window.clearInterval(withdrawUiTimer);
    withdrawUiTimer = null;
  }
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
                  <button
                    v-else-if="String(m.contentType || '').toUpperCase() === 'FORWARD_DYNAMIC'"
                    type="button"
                    class="chat-msg-fwd-card"
                    :class="{ 'chat-msg-fwd-card--self': isSelf(m), 'chat-msg-fwd-card--no-cover': !m.content?.forwardDynamicCoverObjectKey }"
                    @click="onForwardDynamicClick(m)"
                  >
                    <div class="chat-msg-fwd-card__media">
                      <img
                        v-if="m.content?.forwardDynamicCoverObjectKey"
                        class="chat-msg-fwd-card__cover"
                        :src="sanitizeImageUrl(m.content.forwardDynamicCoverObjectKey)"
                        alt=""
                        draggable="false"
                        @error="onBubbleImageError"
                        @load="onChatMediaLayout"
                      />
                      <span class="chat-msg-fwd-card__play" aria-hidden="true">
                        <i class="ri-gallery-view-2-line"></i>
                      </span>
                      <span class="chat-msg-fwd-card__tag" aria-hidden="true">动态</span>
                      <div v-if="m.content?.forwardDynamicTitle" class="chat-msg-fwd-card__info">
                        <span class="chat-msg-fwd-card__title" :title="String(m.content.forwardDynamicTitle || '')">{{
                          String(m.content.forwardDynamicTitle || '')
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

    <BaseModal
      :open="downloadConfirmOpen"
      variant="light"
      overlay-class="chat-file-dl-overlay"
      card-class="modal-card chat-file-dl-modal"
      aria-labelledby="chat-file-dl-title"
      @close="closeFileDownloadConfirm"
    >
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
    </BaseModal>
  </div>
</template>

<style scoped>
.chat-msg-panel__head {
  flex-shrink: 0;
  padding: var(--space-12) var(--space-14);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  display: flex;
  align-items: center;
  gap: var(--space-12);
}

.chat-msg-panel__ann-hint {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-8);
  width: 100%;
  margin: 0;
  padding: var(--space-9) var(--space-14);
  border: none;
  border-bottom: 1px solid var(--orange-200);
  background: linear-gradient(90deg, var(--orange-50), var(--orange-100));
  color: var(--orange-800);
  font-size: var(--font-size-13);
  cursor: pointer;
  text-align: left;
  box-sizing: border-box;
}

.chat-msg-panel__ann-hint:hover {
  background: var(--orange-100);
}

.chat-msg-panel__ann-hint-txt {
  flex: 1;
  min-width: 0;
  font-weight: 500;
}

.chat-msg-panel__ann-hint-chev {
  flex-shrink: 0;
  opacity: 0.75;
}

.chat-msg-panel__head-av {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  border-radius: var(--radius-xl);
  overflow: hidden;
  cursor: pointer;
  background: var(--slate-200);
  box-shadow: var(--shadow-xs);
}

.chat-msg-panel__head-av:hover {
  opacity: 0.92;
}

.chat-msg-panel__head-av img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.chat-msg-panel__title {
  margin: 0;
  font-size: var(--font-size-17);
  font-weight: 600;
  color: var(--color-text-strong);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-msg-panel__title-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-10);
}

.chat-msg-panel__ban-badge {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
  padding: var(--space-4) var(--space-10);
  border-radius: var(--radius-full);
  background: rgb(var(--red-500-rgb) / 0.08);
  color: var(--red-500);
  font-size: var(--font-size-12);
  line-height: 1;
  font-weight: 600;
}

.chat-msg-panel__ban-badge i {
  font-size: var(--font-size-14);
}

.chat-msg-panel__err {
  margin: 0;
  padding: var(--space-8) var(--space-14);
  color: var(--red-700);
  font-size: var(--font-size-13);
  background: var(--red-50);
}

.chat-msg-time {
  margin: var(--space-10) 0;
  text-align: center;
  color: var(--color-text-faint);
  font-size: var(--font-size-12);
  line-height: 1.2;
  user-select: none;
}

.chat-msg-panel__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-anchor: none;
  padding: var(--space-12);
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
}

.chat-msg-panel__hist,
.chat-msg-panel__hint {
  text-align: center;
  font-size: var(--font-size-13);
  color: var(--color-text-faint);
}

.chat-msg-recall__text {
  font-size: var(--font-size-13);
  color: var(--color-text-faint);
  line-height: 1.55;
}

.chat-msg-row--self {
  flex-direction: row-reverse;
}

.chat-msg-av-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  flex-shrink: 0;
  max-width: 80px;
}

.chat-msg-av-col .chat-msg-meta {
  flex-direction: row;
  flex-wrap: wrap;
  justify-content: center;
  max-width: 100%;
}

.chat-msg-av-col .chat-msg-meta__nick {
  text-align: center;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-msg-row__av {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  object-fit: cover;
  background: var(--slate-200);
  flex-shrink: 0;
}

.chat-msg-row__col {
  max-width: min(72%, 420px);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
  min-width: 0;
}

.chat-msg-row--self .chat-msg-row__col {
  align-items: flex-end;
}

.chat-msg-meta {
  font-size: var(--font-size-12);
  color: var(--color-text-muted);
  display: flex;
  gap: var(--space-8);
}

.chat-msg-meta__st {
  color: var(--color-text-faint);
}

.chat-msg-bubble-line {
  display: inline-flex;
  flex-direction: row;
  align-items: flex-end;
  gap: var(--space-6);
  max-width: 100%;
}

.chat-msg-bubble-line--self {
  flex-direction: row-reverse;
  align-self: flex-end;
}

.chat-msg-fail-retry {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--red-500);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-22);
  line-height: 1;
}

.chat-msg-fail-retry:hover:not(:disabled) {
  color: var(--red-600);
}

.chat-msg-fail-retry:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.chat-msg-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
  max-width: 100%;
  min-width: 0;
}

.chat-msg-main--self {
  align-items: flex-end;
}

.chat-msg-bubble-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-4);
  max-width: 100%;
}

.chat-msg-bubble-stack {
  display: inline-flex;
  flex-direction: row;
  align-items: flex-end;
  gap: var(--space-8);
  max-width: 100%;
}

.chat-msg-bubble-stack--self {
  align-self: flex-end;
}

.chat-msg-bubble {
  padding: var(--space-10) var(--space-12);
  border-radius: var(--radius-xl);
  background: var(--color-bg-surface);
  color: var(--color-text-strong);
  font-size: var(--font-size-15);
  line-height: 1.45;
  word-break: break-word;
  box-shadow: 0 1px 0 rgb(var(--slate-900-rgb) / 0.06);
}

.chat-msg-bubble--self {
  background: var(--wechat-bubble);
  box-shadow: none;
}

.chat-msg-bubble--img,
.chat-msg-bubble--video {
  padding: 0;
  background: transparent;
  box-shadow: none;
}

.chat-msg-bubble__img,
.chat-msg-bubble__video {
  max-width: 100%;
  max-height: 220px;
  border-radius: var(--radius-xl);
  display: block;
  vertical-align: top;
}

.chat-msg-bubble--media-tappable {
  cursor: pointer;
  border-radius: var(--radius-xl);
  position: relative;
}

.chat-msg-bubble--media-tappable:focus-visible {
  outline: 2px solid var(--sky-500);
  outline-offset: 2px;
}

.chat-msg-bubble__video--thumb {
  pointer-events: none;
}

.chat-msg-media-play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgb(var(--white-rgb) / 0.92);
  font-size: 44px;
  pointer-events: none;
  text-shadow: 0 10px 24px rgb(var(--black-rgb) / 0.45);
}

/* 通用附件：左文案 + 右文件图标（非图/非视频），高度约两条文字消息 */
.chat-msg-bubble--file {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-22);
  min-width: min(360px, 100%);
  min-height: 108px;
  max-width: 100%;
  padding: var(--space-20) var(--space-24);
  text-align: left;
  cursor: pointer;
  border: none;
  font: inherit;
  box-sizing: border-box;
  background: var(--slate-200);
  color: var(--color-text-strong);
  border-radius: var(--radius-3xl);
  box-shadow: 0 2px 10px rgb(var(--slate-900-rgb) / 0.08);
  -webkit-tap-highlight-color: transparent;
}

.chat-msg-bubble--file:hover {
  filter: brightness(0.99);
  box-shadow: 0 3px 14px rgb(var(--slate-900-rgb) / 0.1);
}

.chat-msg-bubble--file.chat-msg-bubble--self {
  background: var(--slate-200);
  color: var(--color-text-strong);
  box-shadow: 0 2px 10px rgb(var(--slate-900-rgb) / 0.08);
}

.chat-msg-fwd-card {
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  max-width: 100%;
  -webkit-tap-highlight-color: transparent;
}

.chat-msg-fwd-card__media {
  position: relative;
  width: min(320px, 78vw);
  height: 204px;
  border-radius: var(--radius-3xl);
  overflow: hidden;
  background: #0b1220;
  box-shadow: 0 10px 28px rgb(var(--slate-900-rgb) / 0.22);
}

.chat-msg-fwd-card--self .chat-msg-fwd-card__media {
  box-shadow: var(--shadow-xl);
}

.chat-msg-fwd-card__cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.chat-msg-fwd-card__play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgb(var(--white-rgb) / 0.92);
  font-size: 56px;
  pointer-events: none;
  text-shadow: 0 10px 24px rgb(var(--black-rgb) / 0.45);
}

.chat-msg-fwd-card__tag {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: var(--space-4) var(--space-8);
  border-radius: var(--radius-lg);
  background: rgb(var(--slate-400-rgb) / 0.75);
  color: var(--color-text-strong);
  font-size: var(--font-size-12);
  font-weight: 600;
  line-height: 1;
  pointer-events: none;
}

.chat-msg-fwd-card__info {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: var(--space-10) var(--space-12);
  background: linear-gradient(180deg, rgb(var(--black-rgb) / 0) 0%, rgb(var(--black-rgb) / 0.68) 100%);
}

.chat-msg-fwd-card__title {
  display: block;
  color: rgb(var(--white-rgb) / 0.95);
  font-size: var(--font-size-13);
  font-weight: 600;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.chat-msg-fwd-card--no-cover .chat-msg-fwd-card__media {
  background: var(--gray-900);
}

.chat-msg-file-card__text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: var(--space-10);
  min-width: 0;
  flex: 1;
}

.chat-msg-file-card__name {
  display: block;
  width: 100%;
  max-width: 100%;
  font-size: var(--font-size-18);
  font-weight: 600;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-msg-file-card__size {
  font-size: var(--font-size-15);
  color: var(--color-text-muted);
  line-height: 1.2;
}

.chat-msg-file-card__icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 80px;
  line-height: 1;
  color: var(--color-text-secondary);
}

.chat-media-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 2600;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-24) var(--space-16);
  box-sizing: border-box;
  background: rgb(var(--slate-900-rgb) / 0.88);
  backdrop-filter: blur(4px);
}

.chat-media-preview__close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: var(--radius-full);
  background: rgb(var(--white-rgb) / 0.12);
  color: var(--slate-50);
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
}

.chat-media-preview__close:hover {
  background: rgb(var(--white-rgb) / 0.2);
}

.chat-media-preview__img {
  max-width: min(96vw, 1200px);
  max-height: min(88vh, 900px);
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: var(--radius-lg);
  box-shadow: 0 12px 48px rgb(var(--black-rgb) / 0.45);
}

.chat-media-preview__video {
  max-width: min(96vw, 1100px);
  max-height: min(82vh, 800px);
  width: 100%;
  border-radius: var(--radius-lg);
  box-shadow: 0 12px 48px rgb(var(--black-rgb) / 0.45);
}

.chat-file-dl-overlay {
  z-index: 2400;
}

.chat-file-dl-modal__msg {
  margin: 0;
  font-size: var(--font-size-14);
  line-height: 1.55;
  color: var(--color-text-secondary);
}

.chat-file-dl-modal__fname {
  color: var(--color-text-strong);
  font-weight: 600;
  word-break: break-all;
}

.chat-file-dl-modal__actions {
  margin-top: var(--space-20);
  display: flex;
  justify-content: flex-end;
  gap: var(--space-10);
  flex-wrap: wrap;
}

.chat-file-dl-btn {
  height: 38px;
  padding: 0 var(--space-18);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-14);
  font-weight: 600;
  cursor: pointer;
  border: none;
}

.chat-file-dl-btn--ghost {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.chat-file-dl-btn--ghost:hover {
  background: var(--slate-200);
}

.chat-file-dl-btn--primary {
  background: var(--sky-500);
  color: var(--color-text-inverse);
}

.chat-file-dl-btn--primary:hover {
  background: var(--sky-600);
}

.chat-msg-read {
  font-size: var(--font-size-11);
  color: var(--color-text-faint);
  line-height: 1.2;
  padding: 0 var(--space-2);
  flex-shrink: 0;
}

/* 已读贴在气泡块左下角，宽度随气泡，不撑开整列 */
.chat-msg-read--under-bubble {
  align-self: flex-start;
  margin-top: 0;
}

.chat-msg-withdraw {
  flex-shrink: 0;
  align-self: center;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-12);
  cursor: pointer;
  padding: var(--space-2) 0;
  line-height: 1.2;
  white-space: nowrap;
}

.chat-msg-withdraw:hover {
  color: var(--color-text-strong);
}

.chat-msg-owner-recall-wrap {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
}

.chat-msg-owner-recall {
  padding: var(--space-4) var(--space-10);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  box-shadow: var(--shadow-sm);
  font-weight: 600;
}

.chat-msg-owner-recall:hover {
  border-color: var(--color-border-strong);
  color: var(--color-text-strong);
}

.chat-msg-panel__foot {
  position: relative;
  flex-shrink: 0;
  display: flex;
  gap: var(--space-10);
  padding: var(--space-10) var(--space-12);
  border-top: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  align-items: flex-end;
}

.chat-msg-input {
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
}

.chat-msg-input:disabled {
  background: var(--color-bg-sunken);
  color: var(--color-text-faint);
  cursor: not-allowed;
}

.chat-msg-file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.chat-msg-foot-tools {
  display: flex;
  flex-direction: row;
  gap: var(--space-8);
  align-items: flex-end;
  flex-shrink: 0;
}

.chat-msg-input {
  flex: 1;
  min-height: 44px;
  max-height: 120px;
  resize: vertical;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  padding: var(--space-10) var(--space-12);
  font-size: var(--font-size-15);
  font-family: inherit;
  box-sizing: border-box;
}

.chat-msg-send {
  padding: var(--space-10) var(--space-18);
  border-radius: var(--radius-xl);
  border: none;
  background: var(--wechat);
  color: var(--color-text-inverse);
  font-size: var(--font-size-15);
  cursor: pointer;
}

.chat-msg-send:disabled {
  background: var(--slate-300);
  cursor: not-allowed;
}
</style>
