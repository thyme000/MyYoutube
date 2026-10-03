import { describe, it, expect, beforeEach, vi } from "vitest";
import { ref } from "vue";
import { createPinia, setActivePinia } from "pinia";

import { registerImmersiveFeedPlayerRefs, useImmersiveStore } from "../immersiveStore";

/** 前景 <video> 的最小桩：事件处理只用到 paused / currentTime / duration */
function fakeVideo({ paused = false, currentTime = 0, duration = 30 } = {}) {
  return { paused, currentTime, duration, play: vi.fn(() => Promise.resolve()), pause: vi.fn() };
}

/** 只认 fg；其余 ref 显式置空，避免模块级 playerRefs 跨用例残留 */
function useFg(fg) {
  registerImmersiveFeedPlayerRefs({ fg: ref(fg), bg: ref(null), center: ref(null), dynamic: ref(null) });
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe("切条后旧前景 <video> 的迟到事件", () => {
  it("旧元素的 pause 不把正在播的新视频标成暂停", () => {
    const store = useImmersiveStore();
    const current = fakeVideo({ paused: false });
    const stale = fakeVideo({ paused: true });
    useFg(current);
    store.immersiveFeedPaused = false;

    store.onImmersiveFeedFgPause({ target: stale });

    expect(store.immersiveFeedPaused).toBe(false);
  });

  it("当前元素的 pause 仍然置为暂停并同步进度", () => {
    const store = useImmersiveStore();
    const current = fakeVideo({ paused: true, currentTime: 3, duration: 30 });
    useFg(current);
    store.immersiveFeedPaused = false;

    store.onImmersiveFeedFgPause({ target: current });

    expect(store.immersiveFeedPaused).toBe(true);
    expect(store.immersiveFeedCurrentTime).toBe(3);
  });

  it("旧元素的 loadedmetadata 不覆盖新条的时长与暂停态", async () => {
    const store = useImmersiveStore();
    const current = fakeVideo({ duration: 30 });
    const stale = fakeVideo({ paused: true, duration: 999 });
    useFg(current);
    store.immersiveFeedDuration = 30;
    store.immersiveFeedPaused = false;

    await store.onImmersiveFeedFgLoadedMetadata({ target: stale });

    expect(store.immersiveFeedDuration).toBe(30);
    expect(store.immersiveFeedPaused).toBe(false);
  });

  it("旧元素的 ended 不再多切一条", () => {
    const store = useImmersiveStore();
    const current = fakeVideo();
    const stale = fakeVideo({ paused: true });
    useFg(current);
    store.feedScope = "profile";
    store.profileFeedQueue = [{ id: "1" }, { id: "2" }];
    store.immersiveFeedIndex = 0;

    store.onImmersiveFeedFgEnded({ target: stale });

    expect(store.immersiveFeedIndex).toBe(0);
  });

  it("旧元素的 timeupdate 不覆盖新条的播放进度", () => {
    const store = useImmersiveStore();
    const current = fakeVideo({ currentTime: 12 });
    const stale = fakeVideo({ currentTime: 0 });
    useFg(current);
    store.profileFeedOpen = true;
    store.immersiveFeedCurrentTime = 12;

    store.onImmersiveFeedFgTimeUpdate({ target: stale });

    expect(store.immersiveFeedCurrentTime).toBe(12);
  });
});
