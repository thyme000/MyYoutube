/** 由 App.vue 在 setup 中调用 registerGlobalToast，子组件可 import { toast } */

let impl = (message, type) => {
  const m = String(message || "").trim();
  if (!m) return;
  if (type === "error") console.error(m);
  else console.info(`[toast:${type}]`, m);
};

export function registerGlobalToast(fn) {
  impl = typeof fn === "function" ? fn : impl;
}

export function toast(message, type = "info") {
  impl(String(message || "").trim(), type);
}
