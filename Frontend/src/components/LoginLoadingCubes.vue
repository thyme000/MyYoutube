<!-- From Uiverse.io by dexter-st — 接入为 Vue 组件，动画名加前缀避免污染全局 -->
<template>
  <div class="login-loading-cubes" aria-hidden="true">
    <div class="wrapper-grid">
      <div
        v-for="(ch, i) in letters"
        :key="i"
        class="cube"
        @animationend="onCubeAnimationEnd($event, i)"
      >
        <div class="face face-front">{{ ch }}</div>
        <div class="face face-back" />
        <div class="face face-right" />
        <div class="face face-left" />
        <div class="face face-top" />
        <div class="face face-bottom" />
      </div>
    </div>
  </div>
</template>

<script setup>
const emit = defineEmits(["finished"]);

const letters = ["T", "H", "Y", "M", "E"];
let finishedEmitted = false;

/** Vue scoped 会给 keyframes 加哈希，event.animationName 形如 llc-translate-z-xxxxx */
function onCubeAnimationEnd(event, index) {
  if (finishedEmitted) return;
  if (index !== letters.length - 1) return;
  const name = event.animationName || "";
  if (!name.startsWith("llc-translate-z")) return;
  finishedEmitted = true;
  emit("finished");
}
</script>

<style scoped>
.login-loading-cubes {
  display: flex;
  align-items: center;
  justify-content: center;
}

.wrapper-grid {
  /* 单轮扫光：周期越长、间隔越大，观感越慢 */
  --animation-duration: 1.3s;
  --stagger-step: 0.12s;
  --cube-color: #0000;
  --highlight-color: var(--green-bright);
  --cube-width: 76px;
  --cube-height: 76px;
  --font-size: 2.35em;

  position: relative;
  inset: 0;

  display: grid;
  grid-template-columns: repeat(5, var(--cube-width));
  grid-template-rows: auto;
  column-gap: var(--space-14);

  width: calc(5 * var(--cube-width) + 4 * 14px);
  height: var(--cube-height);
  perspective: 560px;

  font-family: "Poppins", sans-serif;
  font-size: var(--font-size);
  font-weight: 800;
  color: transparent;
}

.cube {
  position: relative;
  transform-style: preserve-3d;
}

.face {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--cube-width);
  height: var(--cube-height);
  background-color: var(--cube-color);
}

.face-left,
.face-right,
.face-back,
.face-front {
  box-shadow:
    inset 0 0 2px 1px rgb(var(--black-rgb) / 0.066),
    inset 0 0 12px 1px rgb(var(--white-rgb) / 0.066);
}

.face-front {
  transform: rotateY(0deg) translateZ(calc(var(--cube-width) / 2));
}

.face-back {
  transform: rotateY(180deg) translateZ(calc(var(--cube-width) / 2));
  opacity: 0.6;
}

.face-left {
  transform: rotateY(-90deg) translateZ(calc(var(--cube-width) / 2));
  opacity: 0.6;
}

.face-right {
  transform: rotateY(90deg) translateZ(calc(var(--cube-width) / 2));
  opacity: 0.6;
}

.face-top {
  height: var(--cube-width);
  transform: rotateX(90deg) translateZ(calc(var(--cube-width) / 2));
  opacity: 0.8;
}

.face-bottom {
  height: var(--cube-width);
  transform: rotateX(-90deg) translateZ(calc(var(--cube-height) - var(--cube-width) * 0.5));
  opacity: 0.8;
}

.cube:nth-child(1) {
  z-index: 0;
  animation-delay: calc(var(--stagger-step) * 0);
}

.cube:nth-child(2) {
  z-index: 1;
  animation-delay: calc(var(--stagger-step) * 1);
}

.cube:nth-child(3) {
  z-index: 2;
  animation-delay: calc(var(--stagger-step) * 2);
}

.cube:nth-child(4) {
  z-index: 1;
  animation-delay: calc(var(--stagger-step) * 3);
}

.cube:nth-child(5) {
  z-index: 0;
  animation-delay: calc(var(--stagger-step) * 4);
}

.cube {
  animation-name: llc-translate-z;
  animation-duration: var(--animation-duration);
  animation-timing-function: ease-in-out;
  animation-iteration-count: 1;
  animation-fill-mode: both;
}

.cube .face {
  animation-name: llc-face-color, llc-edge-glow;
  animation-duration: var(--animation-duration), var(--animation-duration);
  animation-timing-function: ease-in-out, ease-in-out;
  animation-iteration-count: 1, 1;
  animation-fill-mode: both, both;
  animation-delay: inherit;
}

.cube .face.face-front {
  animation-name: llc-face-color, llc-face-glow, llc-edge-glow;
  animation-duration: var(--animation-duration), var(--animation-duration), var(--animation-duration);
  animation-timing-function: ease-in-out, ease-in-out, ease-in-out;
  animation-iteration-count: 1, 1, 1;
  animation-fill-mode: both, both, both;
  animation-delay: inherit;
}

@keyframes llc-translate-z {
  0%,
  40%,
  100% {
    transform: translateZ(-2px);
  }
  30% {
    transform: translateZ(16px) translateY(-1px);
  }
}

@keyframes llc-face-color {
  0%,
  50%,
  100% {
    background-color: var(--cube-color);
  }
  10% {
    background-color: var(--highlight-color);
  }
}

@keyframes llc-face-glow {
  0%,
  22% {
    color: rgb(var(--white-rgb) / 0.15);
    filter: none;
  }
  30% {
    color: var(--color-text-inverse);
    filter: drop-shadow(0 18px 14px var(--highlight-color));
  }
  42%,
  100% {
    color: rgb(var(--white-rgb) / 0.92);
    filter: drop-shadow(0 6px 12px rgb(var(--green-bright-rgb) / 0.45));
  }
}

@keyframes llc-edge-glow {
  0%,
  40%,
  100% {
    box-shadow:
      inset 0 0 2px 1px rgb(var(--black-rgb) / 0.066),
      inset 0 0 12px 1px rgb(var(--white-rgb) / 0.066);
  }
  30% {
    box-shadow: 0 0 2px 0px var(--highlight-color);
  }
}
</style>
