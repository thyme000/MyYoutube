<script setup>
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import BaseModal from "./ui/BaseModal.vue";
import { useNavigationStore } from "../stores/navigationStore";
import { useStudioStore } from "../stores/studioStore";
import { useProfileStore } from "../stores/profileStore";
import { useToastStore } from "../stores/toastStore";
import { publishVideo } from "../api/videoApi";
import { STORAGE_BIZ_WORK } from "../api/otherApi";
import { uploadFileWithPresign } from "../utils/presignUpload.js";
import { menuPathFor } from "../router";

const router = useRouter();
const navigationStore = useNavigationStore();
const studioStore = useStudioStore();
const profileStore = useProfileStore();
const { pushToast } = useToastStore();

const uploadFileInputRef = ref(null);
const uploadForm = ref({
  title: "",
  description: "",
  category: "LIFE",
  visibility: "PUBLIC",
  tags: "",
  allowComment: 1,
});
const uploadTagEditorOpen = ref(false);
const uploadTagDraft = ref("");
const uploadPickedFile = ref(null);
const uploadPickedDuration = ref(0);
const uploadSubmitting = ref(false);
const uploadMsg = ref("");
const uploadProgress = ref(0);
const uploadCategoryOptions = [
  { value: "GAME", label: "游戏" },
  { value: "MOVIE", label: "影视" },
  { value: "MUSIC", label: "音乐" },
  { value: "KNOWLEDGE", label: "知识" },
  { value: "LIFE", label: "生活" },
  { value: "TECH", label: "科技" },
  { value: "ENTERTAINMENT", label: "娱乐" },
  { value: "OTHER", label: "其他" },
];
const uploadVisibilityOptions = [
  { value: "PUBLIC", label: "公开" },
  { value: "PRIVATE", label: "私有" },
  { value: "UNLISTED", label: "不公开" },
];

const uploadTagList = computed(() =>
  String(uploadForm.value.tags || "")
    .split(",")
    .map((x) => x.trim())
    .filter((x) => x)
);

/** 离开 /upload：有来路则回退（创作中心/菜单页原状保留），深链直入则回当前菜单页 */
function leaveUploadRoute() {
  if (window.history.state?.back) {
    router.back();
    return;
  }
  void router.push(menuPathFor(navigationStore.activeMenuKey));
}

function closeUploadModal() {
  if (uploadSubmitting.value) {
    return;
  }
  uploadTagEditorOpen.value = false;
  uploadTagDraft.value = "";
  leaveUploadRoute();
}

function onUploadModalDrop(e) {
  if (uploadSubmitting.value) return;
  onUploadInputOrDrop(e);
}

function triggerUploadPick() {
  uploadFileInputRef.value?.click();
}

function pickUploadFile(e) {
  if (e?.dataTransfer?.files?.length) {
    return e.dataTransfer.files[0];
  }
  if (e?.target?.files?.length) {
    return e.target.files[0];
  }
  return null;
}

function openUploadTagEditor() {
  uploadTagEditorOpen.value = true;
  uploadTagDraft.value = "";
}

function appendUploadTag() {
  const tag = String(uploadTagDraft.value || "")
    .replace(/[，,\s]+/g, " ")
    .trim();
  if (!tag) return;
  const normalized = tag.replace(/\s+/g, "");
  const existing = uploadTagList.value.map((x) => x.toLowerCase());
  if (!existing.includes(normalized.toLowerCase())) {
    uploadForm.value.tags = [...uploadTagList.value, normalized].join(",");
  }
  uploadTagDraft.value = "";
  uploadTagEditorOpen.value = false;
}

function removeUploadTag(tag) {
  uploadForm.value.tags = uploadTagList.value.filter((x) => x !== tag).join(",");
}

function onUploadInputOrDrop(e) {
  const f = pickUploadFile(e);
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!f) {
    return;
  }
  uploadPickedFile.value = f;
  uploadMsg.value = `已选择：${f.name}`;
  void readVideoDuration(f);
}

function readVideoDuration(file) {
  return new Promise((resolve) => {
    try {
      const el = document.createElement("video");
      const objectUrl = URL.createObjectURL(file);
      el.preload = "metadata";
      el.src = objectUrl;
      el.onloadedmetadata = () => {
        const sec = Number.isFinite(el.duration) ? Math.max(1, Math.ceil(el.duration)) : 0;
        uploadPickedDuration.value = sec;
        URL.revokeObjectURL(objectUrl);
        resolve(sec);
      };
      el.onerror = () => {
        uploadPickedDuration.value = 0;
        URL.revokeObjectURL(objectUrl);
        resolve(0);
      };
    } catch {
      uploadPickedDuration.value = 0;
      resolve(0);
    }
  });
}

async function submitUploadVideo() {
  if (uploadSubmitting.value) {
    return;
  }
  const title = String(uploadForm.value.title || "").trim();
  if (!title) {
    uploadMsg.value = "请填写视频标题";
    pushToast("请填写视频标题", "info");
    return;
  }
  if (!uploadPickedFile.value) {
    uploadMsg.value = "请先选择视频文件";
    pushToast("请先选择视频文件", "info");
    return;
  }

  uploadSubmitting.value = true;
  uploadMsg.value = "";
  uploadProgress.value = 0;
  try {
    const file = uploadPickedFile.value;
    const { objectKey } = await uploadFileWithPresign(file, STORAGE_BIZ_WORK, (p) => {
      uploadProgress.value = Math.max(0, Math.min(100, Math.round((p || 0) * 100)));
    });
    uploadProgress.value = 100;
    const duration = uploadPickedDuration.value || (await readVideoDuration(file));
    uploadMsg.value = "正在发布视频…";
    await publishVideo({
      title,
      description: String(uploadForm.value.description || "").trim(),
      category: uploadForm.value.category,
      tags: uploadTagList.value.join(","),
      visibility: uploadForm.value.visibility,
      allowComment: Number(uploadForm.value.allowComment ? 1 : 0),
      videoUrl: objectKey,
      duration: Number(duration || 1),
    });

    uploadMsg.value = "上传成功，等待审核…";
    pushToast("上传成功，等待审核…", "success");
    navigationStore.pageHint = `已发布：${title}`;
    leaveUploadRoute();
    if (studioStore.showStudio) {
      void studioStore.load();
    }
    if (profileStore.open) {
      void profileStore.loadWorks();
    }
  } catch (e) {
    uploadMsg.value = `发布失败：${e.message || e}`;
    pushToast(`发布失败：${e.message || e}`, "error");
  } finally {
    uploadSubmitting.value = false;
    uploadProgress.value = 0;
  }
}
</script>

<template>
  <BaseModal
    :open="true"
    overlay-class="upload-modal-overlay"
    :close-on-esc="false"
    :close-on-backdrop="false"
    aria-label="上传视频"
  >
      <form
        class="upload-modal"
        @submit.prevent="submitUploadVideo"
        @click.stop
      >
        <div class="upload-modal-head">
          <h3>上传视频</h3>
          <button
            type="button"
            class="upload-modal-close"
            aria-label="关闭"
            :disabled="uploadSubmitting"
            @click="closeUploadModal"
          >
            <i class="ri-close-line"></i>
          </button>
        </div>
        <div class="upload-modal-main">
          <div
            class="upload-modal-drop"
            @dragover.prevent
            @drop.prevent="onUploadModalDrop"
          >
            <div class="upload-modal-icon">
              <i class="ri-upload-cloud-2-line"></i>
            </div>
            <p class="upload-modal-main-text">将要上传的视频文件拖放到此处</p>
            <button type="button" class="upload-modal-select" :disabled="uploadSubmitting" @click="triggerUploadPick">
              选择文件
            </button>
            <p v-if="uploadPickedFile" class="upload-picked-file">已选：{{ uploadPickedFile.name }}</p>
            <p v-if="uploadPickedDuration" class="upload-progress-text">时长约 {{ uploadPickedDuration }} 秒</p>
            <p v-if="uploadSubmitting" class="upload-progress-text">
              上传进度：{{ uploadProgress }}%
            </p>
          </div>

          <div class="upload-meta-grid">
            <label class="upload-field">
              <span>标题</span>
              <input v-model="uploadForm.title" type="text" maxlength="120" required placeholder="请输入视频标题" />
            </label>
            <label class="upload-field">
              <span>分类</span>
              <select v-model="uploadForm.category">
                <option v-for="c in uploadCategoryOptions" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
            </label>
            <label class="upload-field upload-field--full">
              <span>简介</span>
              <textarea v-model="uploadForm.description" rows="3" maxlength="500" placeholder="请输入视频简介（可选）"></textarea>
            </label>
            <label class="upload-field">
              <span>可见性</span>
              <select v-model="uploadForm.visibility">
                <option v-for="v in uploadVisibilityOptions" :key="v.value" :value="v.value">{{ v.label }}</option>
              </select>
            </label>
            <label class="upload-field">
              <span>标签</span>
              <div class="upload-tags-box">
                <div v-if="uploadTagList.length" class="upload-tags-list">
                  <button
                    v-for="tag in uploadTagList"
                    :key="tag"
                    type="button"
                    class="upload-tag-chip"
                    :disabled="uploadSubmitting"
                    @click="removeUploadTag(tag)"
                  >
                    {{ tag }} <i class="ri-close-line"></i>
                  </button>
                </div>
                <div v-if="uploadTagEditorOpen" class="upload-tag-editor">
                  <input
                    v-model="uploadTagDraft"
                    type="text"
                    maxlength="20"
                    placeholder="输入一个标签后确认"
                    :disabled="uploadSubmitting"
                    @keyup.enter.prevent="appendUploadTag"
                  />
                  <button type="button" class="upload-tag-btn" :disabled="uploadSubmitting" @click="appendUploadTag">确认</button>
                </div>
                <button
                  v-else
                  type="button"
                  class="upload-tag-btn"
                  :disabled="uploadSubmitting"
                  @click="openUploadTagEditor"
                >
                  + 添加标签
                </button>
              </div>
            </label>
            <label class="upload-field upload-field--full upload-switch">
              <input v-model="uploadForm.allowComment" type="checkbox" :true-value="1" :false-value="0" />
              <span>允许评论</span>
            </label>
          </div>
        </div>

        <p v-if="uploadMsg" class="upload-modal-msg">{{ uploadMsg }}</p>
        <p class="upload-modal-foot">
          上传即表示你同意平台服务条款与社区准则。请勿侵犯他人版权或隐私。
        </p>
        <div class="upload-modal-actions">
          <button type="button" class="upload-action upload-action--ghost" :disabled="uploadSubmitting" @click="closeUploadModal">
            取消
          </button>
          <button type="submit" class="upload-action upload-action--primary" :disabled="uploadSubmitting">
            {{ uploadSubmitting ? "发布中…" : "上传并发布" }}
          </button>
        </div>
      </form>
  </BaseModal>
  <input
    ref="uploadFileInputRef"
    type="file"
    accept="video/*"
    class="visually-hidden"
    aria-hidden="true"
    @change="onUploadInputOrDrop"
  />
</template>
