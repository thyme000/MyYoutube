import { ref, onMounted, onUnmounted } from "vue";
import { sanitizeImageUrl } from "../../utils/userProfile.js";

/** 图片 / 视频全屏预览：ESC 关闭 */
export function useChatMediaPreview() {
  const mediaPreview = ref(null);

  function openMediaPreview(m, kind) {
    const url = sanitizeImageUrl(m?.content?.objectKey);
    if (!url) return;
    mediaPreview.value = { url, kind: kind === "video" ? "video" : "image" };
  }

  function closeMediaPreview() {
    mediaPreview.value = null;
  }

  function onGlobalKeydownForPreview(ev) {
    if (ev.key === "Escape" && mediaPreview.value) {
      closeMediaPreview();
    }
  }

  onMounted(() => {
    window.addEventListener("keydown", onGlobalKeydownForPreview);
  });

  onUnmounted(() => {
    window.removeEventListener("keydown", onGlobalKeydownForPreview);
  });

  return { mediaPreview, openMediaPreview, closeMediaPreview };
}
