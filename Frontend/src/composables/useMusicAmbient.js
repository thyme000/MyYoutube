import { ref, computed, watch } from "vue";
import { extractCoverPalette } from "../utils/coverPalette.js";
import { displayCover, playing } from "../stores/musicPlayerStore.js";

function cssColorToRgb(css) {
  if (typeof css !== "string" || !css.trim()) return null;
  if (typeof document === "undefined") return null;
  const el = document.createElement("div");
  el.style.color = css;
  document.body.appendChild(el);
  const rgb = getComputedStyle(el).color;
  document.body.removeChild(el);
  const m = rgb.match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i);
  if (!m) return null;
  return { r: Math.round(Number(m[1])), g: Math.round(Number(m[2])), b: Math.round(Number(m[3])) };
}

function mixRgb(a, b, t) {
  return {
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t),
  };
}

/**
 * 封面色盘偏灰、三色接近时 Silk 容易糊成一片；拉高高光/暗部对比，褶皱更明显。
 */
function enrichSilkTriple(c1, c2, c3) {
  const hi = { r: 255, g: 255, b: 255 };
  const lo = { r: 6, g: 5, b: 14 };
  return {
    color1: mixRgb(c1, hi, 0.1),
    color2: mixRgb(c2, hi, 0.32),
    color3: mixRgb(c3, lo, 0.38),
  };
}

/** 音乐页氛围层：封面取色 → mesh 背景 / GradFlow Silk 三色 / 毛玻璃档位 */
export function useMusicAmbient() {
  const coverPalette = ref(null);
  const blurMode = ref("soft");
  const frostedOn = computed(() => blurMode.value === "frosted");
  const blurAmount = computed(() => (blurMode.value === "deep" || blurMode.value === "frosted" ? 72 : 40));

  /** 无封面时：偏 Apple Music 的雾面蓝 + 暖棕 + 米白极光感 */
  const auroraFallbackStyle = computed(() => ({
    background: `
      radial-gradient(ellipse 100% 80% at 78% 18%, rgba(130, 165, 220, 0.45) 0%, transparent 52%),
      radial-gradient(ellipse 90% 70% at 12% 72%, rgba(180, 120, 85, 0.38) 0%, transparent 55%),
      radial-gradient(ellipse 70% 55% at 48% 48%, rgba(245, 238, 225, 0.12) 0%, transparent 50%),
      linear-gradient(168deg, #0a0a12 0%, #12101c 45%, #0c0a10 100%)
    `,
    opacity: 1,
  }));

  const meshBackgroundStyle = computed(() => {
    const p = coverPalette.value;
    /** 播放中略压低 mesh，避免盖住 Silk WebGL 层次 */
    const meshOpacity = playing.value ? 0.72 : 0.96;
    if (!p) {
      return {
        ...auroraFallbackStyle.value,
        opacity: playing.value ? 0.78 : auroraFallbackStyle.value.opacity,
      };
    }
    return {
      background: `
        radial-gradient(ellipse 130% 90% at 12% 88%, ${p.c3} 0%, transparent 54%),
        radial-gradient(ellipse 100% 75% at 6% 8%, ${p.c1} 0%, transparent 50%),
        radial-gradient(ellipse 115% 90% at 94% 32%, ${p.c4} 0%, transparent 54%),
        radial-gradient(ellipse 95% 65% at 52% 58%, ${p.c2} 0%, transparent 48%),
        linear-gradient(165deg, #060508 0%, #0e0c12 100%)
      `,
      opacity: meshOpacity,
    };
  });

  /** gradflow-vue：Silk 模式 + 随封面变化的三色；播放中加快流动并略提噪声，抵消 UI 与 mesh 抢视线 */
  const gradFlowSilkConfig = computed(() => {
    const p = coverPalette.value;
    const fallback = {
      color1: { r: 130, g: 165, b: 220 },
      color2: { r: 255, g: 255, b: 255 },
      color3: { r: 18, g: 16, b: 32 },
    };
    const on = playing.value;
    const silk = {
      type: "silk",
      speed: on ? 0.74 : 0.56,
      scale: on ? 1.68 : 1.58,
      noise: on ? 0.2 : 0.15,
    };
    if (!p) {
      return {
        ...silk,
        ...fallback,
      };
    }
    const c1 = cssColorToRgb(p.c1) || fallback.color1;
    const c2 = cssColorToRgb(p.c2) || fallback.color2;
    const c3 = cssColorToRgb(p.c3) || fallback.color3;
    const enriched = enrichSilkTriple(c1, c2, c3);
    return {
      ...silk,
      color1: enriched.color1,
      color2: enriched.color2,
      color3: enriched.color3,
    };
  });

  const coverBgStyle = computed(() => {
    if (!displayCover.value) return {};
    return {
      backgroundImage: `url(${displayCover.value})`,
      backgroundSize: "cover",
      backgroundPosition: "center",
    };
  });

  async function refreshPalette() {
    const url = displayCover.value;
    if (!url) {
      coverPalette.value = null;
      return;
    }
    coverPalette.value = await extractCoverPalette(url);
  }

  watch(displayCover, () => void refreshPalette(), { immediate: true });

  return {
    coverPalette,
    blurMode,
    frostedOn,
    blurAmount,
    meshBackgroundStyle,
    gradFlowSilkConfig,
    coverBgStyle,
  };
}
