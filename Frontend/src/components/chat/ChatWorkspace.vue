<script setup>
import { ref, computed, watch, onMounted, nextTick } from "vue";
import ChatConversationList from "./ChatConversationList.vue";
import ChatMessagePanel from "./ChatMessagePanel.vue";
import ChatSessionDetailPanel from "./ChatSessionDetailPanel.vue";
import {
  chatConversations,
  chatConversationsLoading,
  chatConversationsError,
  chatActiveConversationId,
  chatActiveThread,
  openConversation,
  loadOlderHistory,
  sendTextMessage,
  withdrawMyMessage,
  loadMoreConversations,
  toggleMuteRow,
  togglePinRow,
  initChatWhenAuthed,
  isGroupAnnouncementUnread,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";

const props = defineProps({
  selfUserId: { type: [String, Number], default: null },
});

const emit = defineEmits(["open-video", "open-user"]);

const activeRow = computed(() =>
  chatConversations.value.find((r) => String(r.conversationId) === String(chatActiveConversationId.value)),
);

/** 与后端群 createdBy 一致，用于群主撤回等 */
const isActiveGroupOwner = computed(() => {
  const r = activeRow.value;
  if (!r || String(r.conversationType || "").toUpperCase() !== "GROUP") return false;
  if (props.selfUserId == null || r.createdBy == null) return false;
  return String(r.createdBy) === String(props.selfUserId);
});

const activeAnnouncementUnread = computed(() => isGroupAnnouncementUnread(activeRow.value));

const thread = computed(() => chatActiveThread.value);

const sessionDetailOpen = ref(false);

/** 搜索结果点击后滚动到对应 seq（nonce 递增触发子组件 watch） */
const jumpTargetSeq = ref(0);
const jumpTargetNonce = ref(0);

watch(chatActiveConversationId, () => {
  sessionDetailOpen.value = false;
});

/** 列表已有数据且当前未选中会话时，默认打开排序后的第一条（与 chatStore 排序一致：置顶优先，再按最后活跃） */
function openFirstConversationIfNeeded() {
  const list = chatConversations.value;
  if (!list.length || chatActiveConversationId.value) return;
  const id = list[0]?.conversationId;
  if (id) void openConversation(String(id));
}

watch(
  () => props.selfUserId,
  async (uid) => {
    if (uid == null) return;
    await initChatWhenAuthed();
    openFirstConversationIfNeeded();
  },
);

watch(chatConversations, () => {
  openFirstConversationIfNeeded();
});

onMounted(async () => {
  if (props.selfUserId == null) return;
  await initChatWhenAuthed();
  openFirstConversationIfNeeded();
});

async function onSelect(id) {
  await openConversation(id);
}

async function onSend(text) {
  const id = chatActiveConversationId.value;
  if (!id) return;
  try {
    await sendTextMessage(id, text);
  } catch (e) {
    toast(e?.message || "发送失败", "error");
  }
}

async function onWithdraw(m) {
  const id = chatActiveConversationId.value;
  if (!id || !m?.clientMsgId) return;
  try {
    await withdrawMyMessage(id, m.clientMsgId, m.senderId);
  } catch (e) {
    toast(e?.message || "撤回失败", "error");
  }
}

function onMute(row, mute) {
  void toggleMuteRow(row, mute).catch((e) => {
    toast(e?.message || "操作失败", "error");
  });
}

function onPin(row, pin) {
  void togglePinRow(row, pin).catch((e) => {
    toast(e?.message || "操作失败", "error");
  });
}

function onOpenSessionInfo() {
  if (!chatActiveConversationId.value || !activeRow.value) return;
  sessionDetailOpen.value = true;
}

function onCloseSessionDetail() {
  sessionDetailOpen.value = false;
}

async function onJumpToSeqFromFind({ seq }) {
  sessionDetailOpen.value = false;
  await nextTick();
  jumpTargetSeq.value = Number(seq) || 0;
  jumpTargetNonce.value += 1;
}

function onOpenVideo(e) {
  emit("open-video", e);
}

function onOpenUser(e) {
  sessionDetailOpen.value = false;
  emit("open-user", e);
}
</script>

<template>
  <div class="chat-workspace">
    <p v-if="chatConversationsError" class="chat-workspace__err">{{ chatConversationsError }}</p>
    <div class="chat-workspace__main">
      <div class="chat-workspace__thread">
        <ChatMessagePanel
          v-if="chatActiveConversationId"
          :conversation-id="chatActiveConversationId"
          :conversation-name="activeRow?.conversationName || ''"
          :conversation-avatar="activeRow?.conversationAvatar || ''"
          :conversation-type="String(activeRow?.conversationType || '')"
          :conversation-banned="!!(activeRow?.conversationBanned ?? activeRow?.conversatinoBanned)"
          :self-banned="!!activeRow?.banned"
          :items="thread.items"
          :loading="thread.loadingInitial"
          :loading-history="thread.loadingHistory"
          :has-more-history="thread.hasMoreHistory"
          :error="thread.initialError"
          :self-user-id="selfUserId"
          :is-group-owner="isActiveGroupOwner"
          :peer-read-seq="thread.peerReadSeq || 0"
          :jump-target-seq="jumpTargetSeq"
          :jump-target-nonce="jumpTargetNonce"
          :announcement-unread="activeAnnouncementUnread"
          @send="onSend"
          @load-history="loadOlderHistory(chatActiveConversationId)"
          @withdraw="onWithdraw"
          @open-info="onOpenSessionInfo"
          @open-video="onOpenVideo"
        />
        <div v-else class="chat-workspace__placeholder">
          <p>选择右侧会话开始聊天</p>
        </div>
      </div>
      <aside class="chat-workspace__rail">
        <ChatSessionDetailPanel
          v-if="sessionDetailOpen && activeRow"
          :row="activeRow"
          :self-user-id="selfUserId"
          @close="onCloseSessionDetail"
          @jump-to-seq="onJumpToSeqFromFind"
          @open-user="onOpenUser"
        />
        <ChatConversationList
          v-show="!sessionDetailOpen"
          :items="chatConversations"
          :active-id="chatActiveConversationId"
          :loading="chatConversationsLoading"
          :compact="false"
          @select="onSelect"
          @mute="onMute"
          @pin="onPin"
          @load-more="loadMoreConversations"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.chat-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  padding: var(--space-8) var(--space-12) var(--space-12);
  box-sizing: border-box;
}

.chat-workspace__err {
  margin: 0;
  color: var(--red-700);
  font-size: var(--font-size-14);
}

.chat-workspace__main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  gap: 0;
  border-radius: var(--radius-4xl);
  overflow: hidden;
  background: var(--color-bg-surface);
  border: 1px solid rgb(var(--slate-900-rgb) / 0.1);
  box-shadow:
    0 1px 2px rgb(var(--slate-900-rgb) / 0.06),
    0 8px 28px rgb(var(--slate-900-rgb) / 0.1),
    0 24px 48px rgb(var(--slate-900-rgb) / 0.08),
    inset 0 1px 0 rgb(var(--white-rgb) / 0.95);
}

.chat-workspace__rail {
  width: min(320px, 38vw);
  flex-shrink: 0;
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  min-height: 0;
  display: flex;
  flex-direction: column;
}

@media (max-width: 900px) {
  .chat-workspace__main {
    flex-direction: column;
  }

  .chat-workspace__rail {
    width: 100%;
    max-height: 40vh;
    border-left: none;
    border-top: 1px solid var(--color-border);
  }
}

@media (max-width: 900px) {
  .chat-workspace__main {
    flex-direction: column;
  }

  .chat-workspace__rail {
    width: 100%;
    max-height: 40vh;
    border-left: none;
    border-top: 1px solid var(--color-border);
  }
}
</style>
