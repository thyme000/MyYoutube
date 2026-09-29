import { ref, watch, onUnmounted } from "vue";
import { musicLyricsFullscreen } from "../stores/musicPlayerStore.js";

/** 歌词全屏：与 store 同步、body 滚动锁、ESC 退出 */
export function useLyricsFullscreen() {
  const lyricsFullscreen = ref(false);

  function onFsKeydown(e) {
    if (e.key === "Escape") lyricsFullscreen.value = false;
  }

  watch(lyricsFullscreen, (on) => {
    musicLyricsFullscreen.value = on;
    if (typeof document === "undefined") return;
    document.body.style.overflow = on ? "hidden" : "";
    if (on) {
      document.addEventListener("keydown", onFsKeydown);
    } else {
      document.removeEventListener("keydown", onFsKeydown);
    }
  });

  onUnmounted(() => {
    document.removeEventListener("keydown", onFsKeydown);
    document.body.style.overflow = "";
    musicLyricsFullscreen.value = false;
  });

  return { lyricsFullscreen };
}
