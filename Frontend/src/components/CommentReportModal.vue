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

