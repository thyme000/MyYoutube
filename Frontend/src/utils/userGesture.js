// @ts-check
/**
 * 全局用户手势状态(浏览器 autoplay 策略适配)。
 * 页面上任意一次 pointerdown/keydown 之后, 带声媒体才允许自动播放;
 * 与抖音 web 同款策略: 静音自动轮播常开, 交互解锁后新滚到的动态自动带声。
 */
let hasGesture = false;

if (typeof window !== "undefined") {
  const mark = () => {
    hasGesture = true;
  };
  window.addEventListener("pointerdown", mark, { once: true, capture: true });
  window.addEventListener("keydown", mark, { once: true, capture: true });
}

export function hasUserGesture() {
  return hasGesture;
}
