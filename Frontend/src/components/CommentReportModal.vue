<script setup>
import { computed, ref, watch } from "vue";
import { reportComment } from "../api/commentApi.js";
import { toast } from "../utils/toast.js";
import BaseModal from "./ui/BaseModal.vue";

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  comment: { type: Object, default: null },
  selfUserId: { type: [String, Number], default: null },
});

const emit = defineEmits(["update:modelValue", "reported"]);

const reason = ref("");
const remark = ref("");
const busy = ref(false);

const isBlockedComment = computed(() => {
  const c = props.comment;
  const stRaw = c?.status ?? c?.commentStatus ?? c?.comment_status;
  const st = String(stRaw ?? "").trim().toUpperCase();
  if (st === "BLOCKED" || st === "BANNED") return true;
  if (stRaw === 2 || stRaw === "2") return true;
  const text = String(c?.content || "");
  return text.includes("评论违规") || text.includes("已被处理");
});

const isSelfComment = computed(() => {
  if (!props.comment || props.selfUserId == null) return false;
  return String(props.comment.userId) === String(props.selfUserId);
});

const commentTitle = computed(() => {
  const n = String(props.comment?.nickname || "").trim();
  return n ? `举报「${n}」的评论` : "举报评论";
});

function close() {
  if (busy.value) return;
  emit("update:modelValue", false);
}

async function submit() {
  if (busy.value) return;
  if (isSelfComment.value) {
    toast("不能举报自己的评论", "info");
    return;
  }
  if (isBlockedComment.value) {
    toast("该评论已被处理，无法举报", "info");
    return;
  }
  const id = props.comment?.id;
  if (id == null || id === "") {
    toast("缺少评论信息", "error");
    return;
  }
  const r = reason.value.trim();
  const m = remark.value.trim();
  if (!r) {
    toast("请选择举报原因", "info");
    return;
  }
  if (!m) {
    toast("请填写补充说明", "info");
    return;
  }
  busy.value = true;
  try {
    await reportComment({ commentId: id, reportType: r, remark: m });
    emit("reported", { commentId: String(id), reportType: r });
    emit("update:modelValue", false);
  } catch (e) {
    toast(e?.message || "提交失败", "error");
  } finally {
    busy.value = false;
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reason.value = "";
      remark.value = "";
    }
  }
);
</script>

<template>
  <BaseModal :open="modelValue" variant="light" overlay-class="comment-report-overlay" card-class="comment-report" @close="close">
        <header class="comment-report__head">
          <h2 class="comment-report__title">{{ commentTitle }}</h2>
          <div class="comment-report__win-btns">
            <button type="button" class="comment-report__win-btn" aria-label="关闭" :disabled="busy" @click="close">
              <i class="ri-close-line" aria-hidden="true"></i>
            </button>
          </div>
        </header>

        <div v-if="comment?.content" class="comment-report__quote">
          <p class="comment-report__quote-text">{{ String(comment.content || "") }}</p>
        </div>

        <div class="comment-report__body">
          <div class="comment-report__field">
            <label class="comment-report__label">举报原因</label>
            <select v-model="reason" class="comment-report__select" :disabled="busy || isSelfComment || isBlockedComment">
              <option value="">请选择</option>
              <option value="ABUSE">辱骂</option>
              <option value="PORNOGRAPHY">涉黄</option>
              <option value="ADVERTISING">广告</option>
              <option value="TROLLING">引战</option>
              <option value="OTHER">其他</option>
            </select>
          </div>
          <div class="comment-report__field">
            <label class="comment-report__label">补充说明</label>
            <textarea
              v-model="remark"
              class="comment-report__textarea"
              rows="4"
              maxlength="200"
              placeholder="请描述具体原因（必填）"
              :disabled="busy || isSelfComment || isBlockedComment"
            ></textarea>
            <div class="comment-report__counter">{{ remark.length }}/200</div>
          </div>
          <p v-if="isSelfComment" class="comment-report__hint">不能举报自己的评论</p>
          <p v-else-if="isBlockedComment" class="comment-report__hint">该评论已被处理，无法举报</p>
        </div>

        <footer class="comment-report__foot">
          <button type="button" class="comment-report__btn comment-report__btn--ghost" :disabled="busy" @click="close">
            取消
          </button>
          <button
            type="button"
            class="comment-report__btn comment-report__btn--primary"
            :disabled="busy || isSelfComment || isBlockedComment || !reason.trim() || !remark.trim()"
            @click="submit"
          >
            {{ busy ? "提交中…" : "提交举报" }}
          </button>
        </footer>
  </BaseModal>
</template>


<style scoped>
/* —— 评论举报 —— */
.comment-report-overlay {
  z-index: 12060;
  align-items: flex-start;
  padding-top: 8vh;
}

.comment-report {
  width: min(420px, 94vw);
  max-height: min(88vh, 720px);
  margin: 0 auto;
  background: var(--slate-100);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-modal);
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.comment-report__head {
  position: relative;
  padding: var(--space-16) var(--space-44) var(--space-12);
  background: var(--slate-100);
  border-bottom: 1px solid var(--slate-200);
}

.comment-report__title {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 600;
  color: var(--color-text-strong);
  text-align: center;
  line-height: 1.35;
}

.comment-report__win-btns {
  position: absolute;
  top: 10px;
  right: 8px;
  display: flex;
  gap: var(--space-2);
}

.comment-report__win-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--radius-lg);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-22);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.comment-report__win-btn:hover:not(:disabled) {
  background: rgb(var(--slate-900-rgb) / 0.06);
  color: var(--color-text-strong);
}

.comment-report__win-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.comment-report__quote {
  margin: var(--space-12) var(--space-16) 0;
  padding: var(--space-10) var(--space-12);
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
}

.comment-report__quote-text {
  margin: 0;
  font-size: var(--font-size-13);
  line-height: 1.5;
  color: var(--color-text-strong);
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.comment-report__body {
  padding: var(--space-12) var(--space-16) var(--space-10);
  overflow: auto;
  flex: 1;
}

.comment-report__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  margin-bottom: var(--space-12);
}

.comment-report__label {
  font-size: var(--font-size-12);
  color: var(--color-text-muted);
}

.comment-report__select,
.comment-report__textarea {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-bg-surface);
  color: var(--color-text-strong);
  font-size: var(--font-size-14);
  outline: none;
}

.comment-report__select {
  height: 38px;
  padding: 0 var(--space-10);
}

.comment-report__textarea {
  padding: var(--space-10);
  resize: none;
}

.comment-report__select:focus,
.comment-report__textarea:focus {
  border-color: rgb(var(--sky-500-rgb) / 0.55);
  box-shadow: 0 0 0 3px rgb(var(--sky-500-rgb) / 0.16);
}

.comment-report__counter {
  text-align: right;
  font-size: var(--font-size-12);
  color: var(--color-text-faint);
}

.comment-report__hint {
  margin: var(--space-8) 0 0;
  font-size: var(--font-size-13);
  color: var(--red-500);
  font-weight: 600;
}

.comment-report__foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-10);
  padding: var(--space-12) var(--space-12) var(--space-14);
  border-top: 1px solid var(--slate-200);
  background: var(--slate-100);
}

.comment-report__btn {
  height: 38px;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  cursor: pointer;
  font-size: var(--font-size-14);
}

.comment-report__btn--ghost {
  background: var(--color-bg-surface);
  color: var(--color-text-strong);
}

.comment-report__btn--primary {
  background: var(--red-500);
  color: var(--color-text-inverse);
  border-color: var(--red-500);
}

.comment-report__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
