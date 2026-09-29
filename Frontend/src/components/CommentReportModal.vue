<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { reportComment } from "../api/commentApi.js";
import { toast } from "../utils/toast.js";

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

function onGlobalKeyDown(ev) {
  if (!props.modelValue) return;
  if (ev?.key === "Escape") close();
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reason.value = "";
      remark.value = "";
      window.addEventListener("keydown", onGlobalKeyDown);
    } else {
      window.removeEventListener("keydown", onGlobalKeyDown);
    }
  }
);

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onGlobalKeyDown);
});
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="modal-overlay modal-overlay--light comment-report-overlay" @click.self="close">
      <div class="comment-report" role="dialog" aria-modal="true" @click.stop>
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
      </div>
    </div>
  </Teleport>
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
  background: #f4f6f8;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.18);
  border: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.comment-report__head {
  position: relative;
  padding: 16px 44px 12px;
  background: #eef1f4;
  border-bottom: 1px solid #e5e9ef;
}

.comment-report__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  text-align: center;
  line-height: 1.35;
}

.comment-report__win-btns {
  position: absolute;
  top: 10px;
  right: 8px;
  display: flex;
  gap: 2px;
}

.comment-report__win-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  font-size: 22px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.comment-report__win-btn:hover:not(:disabled) {
  background: rgba(15, 23, 42, 0.06);
  color: #0f172a;
}

.comment-report__win-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.comment-report__quote {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #fff;
}

.comment-report__quote-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: #0f172a;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.comment-report__body {
  padding: 12px 16px 10px;
  overflow: auto;
  flex: 1;
}

.comment-report__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}

.comment-report__label {
  font-size: 12px;
  color: #64748b;
}

.comment-report__select,
.comment-report__textarea {
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  color: #0f172a;
  font-size: 14px;
  outline: none;
}

.comment-report__select {
  height: 38px;
  padding: 0 10px;
}

.comment-report__textarea {
  padding: 10px;
  resize: none;
}

.comment-report__select:focus,
.comment-report__textarea:focus {
  border-color: rgba(14, 165, 233, 0.55);
  box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.16);
}

.comment-report__counter {
  text-align: right;
  font-size: 12px;
  color: #94a3b8;
}

.comment-report__hint {
  margin: 8px 0 0;
  font-size: 13px;
  color: #ef4444;
  font-weight: 600;
}

.comment-report__foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 12px 12px 14px;
  border-top: 1px solid #e5e9ef;
  background: #eef1f4;
}

.comment-report__btn {
  height: 38px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  font-size: 14px;
}

.comment-report__btn--ghost {
  background: #fff;
  color: #0f172a;
}

.comment-report__btn--primary {
  background: #ef4444;
  color: #fff;
  border-color: #ef4444;
}

.comment-report__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
