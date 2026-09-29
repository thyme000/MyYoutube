<script setup>
import { computed, watch, onBeforeUnmount } from "vue";

const props = defineProps({
  open: { type: Boolean, default: false },
  /** dark | light | none：映射全局 .modal-overlay / .modal-overlay--light；none 时样式完全由 overlayClass 决定 */
  variant: { type: String, default: "dark" },
  overlayClass: { type: String, default: "" },
  cardClass: { type: String, default: "" },
  width: { type: String, default: "" },
  lockScroll: { type: Boolean, default: true },
  /** 默认接管 ESC；已被 App 叠层仲裁管理的弹窗（upload/profile 系）传 false 保持单一路径 */
  closeOnEsc: { type: Boolean, default: true },
  closeOnBackdrop: { type: Boolean, default: true },
  stopCardClick: { type: Boolean, default: true },
  ariaLabel: { type: String, default: "" },
  ariaLabelledby: { type: String, default: "" },
});

const emit = defineEmits(["close", "cardClick"]);

let lockCount = 0;
function lockBody() {
  if (++lockCount === 1) document.body.style.overflow = "hidden";
}
function unlockBody() {
  if (lockCount > 0 && --lockCount === 0) document.body.style.overflow = "";
}

function onKeydown(ev) {
  if (ev.key === "Escape") {
    ev.preventDefault();
    emit("close");
  }
}

function onCardClick(ev) {
  if (props.stopCardClick) ev.stopPropagation();
  emit("cardClick", ev);
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      if (props.lockScroll) lockBody();
      if (props.closeOnEsc) window.addEventListener("keydown", onKeydown);
    } else {
      if (props.lockScroll) unlockBody();
      if (props.closeOnEsc) window.removeEventListener("keydown", onKeydown);
    }
  }
);

onBeforeUnmount(() => {
  if (props.open) {
    if (props.lockScroll) unlockBody();
    if (props.closeOnEsc) window.removeEventListener("keydown", onKeydown);
  }
});

const overlayCls = computed(() => [
  props.variant === "none" ? "" : "modal-overlay",
  props.variant === "light" ? "modal-overlay--light" : "",
  props.overlayClass,
]);
const cardStyle = computed(() => (props.width ? { width: props.width } : null));
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      :class="overlayCls"
      role="presentation"
      @click.self="closeOnBackdrop && emit('close')"
    >
      <div
        :class="cardClass"
        role="dialog"
        aria-modal="true"
        :aria-label="ariaLabel || undefined"
        :aria-labelledby="ariaLabelledby || undefined"
        :style="cardStyle"
        @click="onCardClick"
      >
        <slot />
      </div>
    </div>
  </Teleport>
</template>
