import { computed } from "vue";

/** 会话详情的基础判定：会话类型 / 禁言态 / 自己的 ID / 会话名 */
export function useChatSessionMeta(props) {
  const isGroup = computed(
    () => String(props.row?.conversationType || "").toUpperCase() === "GROUP"
  );

  const isSingle = computed(() => !isGroup.value);

  const conversationBanned = computed(
    () => Boolean(props.row?.conversationBanned ?? props.row?.conversatinoBanned)
  );

  const selfStr = computed(() =>
    props.selfUserId != null ? String(props.selfUserId) : ""
  );

  const convName = computed(() => String(props.row?.conversationName || "").trim() || "未命名会话");

  return { isGroup, isSingle, conversationBanned, selfStr, convName };
}
