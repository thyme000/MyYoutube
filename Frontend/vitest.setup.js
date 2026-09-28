/**
 * store 单测只额外需要 localStorage（authStore → api/http 读写 token、profileStore 读裁剪缓存），
 * 为此启动 jsdom 不划算 —— 本机 jsdom 环境初始化要 50 秒以上。
 */
if (!globalThis.localStorage) {
  const store = new Map();
  globalThis.localStorage = {
    getItem: (key) => (store.has(String(key)) ? store.get(String(key)) : null),
    setItem: (key, value) => store.set(String(key), String(value)),
    removeItem: (key) => store.delete(String(key)),
    clear: () => store.clear(),
    key: (index) => [...store.keys()][index] ?? null,
    get length() {
      return store.size;
    },
  };
}
