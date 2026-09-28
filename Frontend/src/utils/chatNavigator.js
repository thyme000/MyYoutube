// @ts-check
/** 从悬浮会话条跳转到全屏聊天并选中会话（由 App.vue 注册实现） */
let impl = null;

export function registerChatNavigator(fn) {
  impl = typeof fn === "function" ? fn : null;
}

/** @param {string} conversationId */
export function navigateToChat(conversationId) {
  if (impl) impl(String(conversationId || ""));
}
