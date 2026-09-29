const DEFAULT_TIMEOUT_MS = 30000;

/** /json/version 是 CDP 唯一无需鉴权的发现入口，返回的是浏览器级 WebSocket 端点 */
export async function browserEndpoint(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/version`);
  if (!res.ok) throw new Error(`CDP /json/version 返回 ${res.status}`);
  const info = await res.json();
  return info.webSocketDebuggerUrl;
}

export class Cdp {
  #ws;
  #pending = new Map();
  #listeners = [];
  #nextId = 0;

  constructor(ws) {
    this.#ws = ws;
    ws.onmessage = (ev) => this.#onMessage(ev.data);
  }

  static async connect(wsUrl) {
    const ws = new WebSocket(wsUrl);
    await new Promise((ok, fail) => {
      ws.onopen = ok;
      ws.onerror = () => fail(new Error(`无法连接 CDP: ${wsUrl}`));
    });
    return new Cdp(ws);
  }

  #onMessage(raw) {
    const msg = JSON.parse(raw);
    const slot = this.#pending.get(msg.id);
    if (!slot) {
      for (const fn of this.#listeners) fn(msg);
      return;
    }
    this.#pending.delete(msg.id);
    clearTimeout(slot.timer);
    if (msg.error) slot.reject(new Error(`${slot.method}: ${JSON.stringify(msg.error)}`));
    else slot.resolve(msg.result);
  }

  send(method, params = {}, sessionId, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const id = (this.#nextId += 1);
    const payload = sessionId ? { id, method, params, sessionId } : { id, method, params };
    this.#ws.send(JSON.stringify(payload));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(new Error(`CDP 超时: ${method}`));
      }, timeoutMs);
      this.#pending.set(id, { resolve, reject, timer, method });
    });
  }

  on(fn) {
    this.#listeners.push(fn);
  }

  close() {
    this.#ws.close();
  }
}

export async function evaluate(cdp, sessionId, expression, timeoutMs = 15000) {
  const res = await cdp.send(
    "Runtime.evaluate",
    { expression, returnByValue: true, awaitPromise: true },
    sessionId,
    timeoutMs
  );
  const details = res.exceptionDetails;
  if (details) throw new Error(details.exception?.description || details.text || "evaluate 抛错");
  return res.result?.value;
}
