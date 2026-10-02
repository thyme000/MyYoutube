import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAuthStore } from "../authStore";
import { useDetailStore } from "../detailStore";

const RESUME_URL = "/videos/playback/resume";
const TICK_MIN_INTERVAL_MS = 8_000;

let nowMs;
let fetchMock;

/** 造一个「正在播放的长视频详情」，并把播放器元素换成可断言的假对象 */
function playingLongVideo(store, { currentTime = 12.5, paused = false } = {}) {
  const auth = useAuthStore();
  auth.signIn("t");
  auth.userId = 9001;
  store.video = { id: "1001" };
  store.playerRef = { paused, currentTime };
  return store;
}

function resumeCalls() {
  return fetchMock.mock.calls.filter(([url]) => url === RESUME_URL);
}

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  nowMs = 1_000_000;
  vi.spyOn(Date, "now").mockImplementation(() => nowMs);
  fetchMock = vi.fn().mockResolvedValue({ ok: true });
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("detailStore 长视频续播上报的守卫", () => {
  it("拿不到播放器元素时不上报", async () => {
    const store = playingLongVideo(useDetailStore());
    store.playerRef = null;
    await store.flushPlaybackResume("pause");
    expect(resumeCalls()).toHaveLength(0);
  });

  it("未登录（payload 拼不出来）时不上报", async () => {
    const store = playingLongVideo(useDetailStore());
    useAuthStore().userId = null;
    await store.flushPlaybackResume("pause");
    expect(resumeCalls()).toHaveLength(0);
  });

  it("播放器已暂停时，非 pause 触发点不上报", async () => {
    const store = playingLongVideo(useDetailStore(), { paused: true });
    await store.flushPlaybackResume("tick");
    await store.flushPlaybackResume("switch");
    expect(resumeCalls()).toHaveLength(0);
  });

  it("tick 触发时进度为 0 不上报，pause 触发时允许 0", async () => {
    const store = playingLongVideo(useDetailStore(), { currentTime: 0, paused: true });
    await store.flushPlaybackResume("tick");
    expect(resumeCalls()).toHaveLength(0);

    await store.flushPlaybackResume("pause");
    expect(resumeCalls()).toHaveLength(1);
  });
});

describe("detailStore 续播上报的节流", () => {
  it("tick 在 8 秒内重复触发被节流", async () => {
    const store = playingLongVideo(useDetailStore());
    await store.flushPlaybackResume("tick");
    expect(resumeCalls()).toHaveLength(1);

    nowMs += TICK_MIN_INTERVAL_MS - 1;
    await store.flushPlaybackResume("tick");
    expect(resumeCalls()).toHaveLength(1);
  });

  it("tick 间隔达到 8 秒后重新上报", async () => {
    const store = playingLongVideo(useDetailStore());
    await store.flushPlaybackResume("tick");
    nowMs += TICK_MIN_INTERVAL_MS;
    await store.flushPlaybackResume("tick");
    expect(resumeCalls()).toHaveLength(2);
  });

  it("pause / switch / leave 不受 8 秒节流限制", async () => {
    const store = playingLongVideo(useDetailStore());
    await store.flushPlaybackResume("tick");
    await store.flushPlaybackResume("pause");
    await store.flushPlaybackResume("switch");
    await store.flushPlaybackResume("leave");
    expect(resumeCalls()).toHaveLength(4);
  });

  it("上一次请求未回来时不再发新的", async () => {
    const store = playingLongVideo(useDetailStore());
    let resolveFetch;
    fetchMock.mockReturnValueOnce(new Promise((r) => (resolveFetch = r)));

    const first = store.flushPlaybackResume("pause");
    await store.flushPlaybackResume("pause");
    expect(resumeCalls()).toHaveLength(1);

    resolveFetch({ ok: true });
    await first;
    await store.flushPlaybackResume("pause");
    expect(resumeCalls()).toHaveLength(2);
  });
});

describe("detailStore 续播上报的请求体", () => {
  it("按后端约定带 videoId / playTime(ms) / userId", async () => {
    const store = playingLongVideo(useDetailStore(), { currentTime: 12.5 });
    await store.flushPlaybackResume("pause");

    const [url, init] = resumeCalls()[0];
    expect(url).toBe(RESUME_URL);
    expect(init.method).toBe("PUT");
    expect(init.headers.Authorization).toBe("t");
    expect(JSON.parse(init.body)).toEqual({ videoId: "1001", playTime: 12500, userId: 9001 });
  });

  it("leave / unload 走 keepalive，其余触发点不走", async () => {
    const store = playingLongVideo(useDetailStore());
    await store.flushPlaybackResume("tick");
    await store.flushPlaybackResume("leave");
    await store.flushPlaybackResume("unload");

    expect(resumeCalls()[0][1].keepalive).toBe(false);
    expect(resumeCalls()[1][1].keepalive).toBe(true);
    expect(resumeCalls()[2][1].keepalive).toBe(true);
  });

  it("请求失败被静默吞掉，不影响播放", async () => {
    const store = playingLongVideo(useDetailStore());
    fetchMock.mockRejectedValueOnce(new Error("offline"));
    await expect(store.flushPlaybackResume("pause")).resolves.toBeUndefined();
  });
});
