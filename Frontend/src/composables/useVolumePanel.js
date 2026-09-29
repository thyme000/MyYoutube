import { ref, watch, nextTick, onUnmounted } from "vue";

/** 音量面板：点击外部收起（捕获阶段监听，避免面板内点击先命中开关） */
export function useVolumePanel() {
  const volumePanelOpen = ref(false);
  const volWrapRef = ref(null);

  function onDocCloseVolume(ev) {
    if (!volumePanelOpen.value) return;
    const el = volWrapRef.value;
    if (el && !el.contains(ev.target)) {
      volumePanelOpen.value = false;
    }
  }

  function toggleVolumePanel(ev) {
    ev?.stopPropagation?.();
    volumePanelOpen.value = !volumePanelOpen.value;
  }

  watch(volumePanelOpen, (open) => {
    if (open) {
      nextTick(() => {
        setTimeout(() => document.addEventListener("click", onDocCloseVolume, true), 0);
      });
    } else {
      document.removeEventListener("click", onDocCloseVolume, true);
    }
  });

  onUnmounted(() => {
    document.removeEventListener("click", onDocCloseVolume, true);
  });

  return { volumePanelOpen, volWrapRef, toggleVolumePanel };
}
