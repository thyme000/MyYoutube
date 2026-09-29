<script setup>
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useNavigationStore } from "../stores/navigationStore";
import { useStudioStore } from "../stores/studioStore";
import { useProfileStore } from "../stores/profileStore";
import { useToastStore } from "../stores/toastStore";
import { publishVideo, mergePicturesToVideo, getMergePictureJobStatus } from "../api/videoApi";
import { STORAGE_BIZ_WORK } from "../api/otherApi";
import { uploadFileWithPresign } from "../utils/presignUpload.js";
import { menuPathFor } from "../router";
import SlideshowMusicPicker from "./SlideshowMusicPicker.vue";

const router = useRouter();
const navigationStore = useNavigationStore();
const studioStore = useStudioStore();
const profileStore = useProfileStore();
const { pushToast } = useToastStore();

const uploadMode = ref("video");
const MAX_SLIDESHOW_IMAGES = 50;
const MERGE_POLL_INTERVAL_MS = 2000;
const MERGE_POLL_MAX_ATTEMPTS = 200;
const uploadSlideshowImages = ref([]);
const uploadSlideshowAudioFile = ref(null);
const uploadFileInputRef = ref(null);
const uploadSlideshowImagesInputRef = ref(null);
const uploadSlideshowAudioInputRef = ref(null);
const uploadMusicPickerOpen = ref(false);
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

/** 幻灯片成片总时长（秒），与后端规则一致：每图约 2s，不足 6s 时按 6s 均摊 */
const slideshowBgmTargetSec = computed(() => {
  const n = uploadSlideshowImages.value.length;
  if (n <= 0) return 0;
  let perImage = 2;
  if (n * perImage < 6) perImage = 6 / n;
  return Math.round(perImage * n);
});

watch(slideshowBgmTargetSec, (sec, oldSec) => {
  if (oldSec === undefined || oldSec <= 0) return;
  if (sec === oldSec) return;
  if (uploadSlideshowAudioFile.value) {
    uploadSlideshowAudioFile.value = null;
    pushToast("图片张数已变更，请重新选择背景音乐", "info");
  }
});

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
  uploadSlideshowImages.value = [];
  uploadSlideshowAudioFile.value = null;
  uploadMusicPickerOpen.value = false;
  leaveUploadRoute();
}

function setUploadMode(mode) {
  if (uploadSubmitting.value) return;
  const m = mode === "slideshow" ? "slideshow" : "video";
  uploadMode.value = m;
  uploadMsg.value = "";
  uploadMusicPickerOpen.value = false;
  if (m === "video") {
    uploadSlideshowImages.value = [];
    uploadSlideshowAudioFile.value = null;
  } else {
    uploadPickedFile.value = null;
    uploadPickedDuration.value = 0;
  }
}

function triggerSlideshowImagesPick() {
  uploadSlideshowImagesInputRef.value?.click();
}

function triggerSlideshowAudioPick() {
  uploadSlideshowAudioInputRef.value?.click();
}

function openSlideshowMusicPicker() {
  if (uploadSubmitting.value) return;
  if (!uploadSlideshowImages.value.length) {
    pushToast("请先添加图片，再选择背景音乐", "info");
    return;
  }
  uploadMusicPickerOpen.value = true;
}

function onSlideshowCloudMusicPicked(file) {
  uploadSlideshowAudioFile.value = file;
  uploadMusicPickerOpen.value = false;
  uploadMsg.value = `已选背景音乐：${file.name}`;
}

function onSlideshowImagesInputChange(e) {
  const raw = Array.from(e?.target?.files || []).filter((f) => String(f?.type || "").startsWith("image/"));
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!raw.length) return;
  const merged = [...uploadSlideshowImages.value, ...raw].slice(0, MAX_SLIDESHOW_IMAGES);
  uploadSlideshowImages.value = merged;
  uploadMsg.value = `已选 ${merged.length} 张图片（最多 ${MAX_SLIDESHOW_IMAGES} 张）`;
}

function onSlideshowAudioInputChange(e) {
  const f = e?.target?.files?.[0];
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!f) return;
  if (!String(f.type || "").startsWith("audio/") && !/\.(mp3|m4a|aac|wav|flac|ogg)$/i.test(f.name || "")) {
    uploadMsg.value = "请选择音频文件";
    return;
  }
  uploadSlideshowAudioFile.value = f;
  uploadMsg.value = `已选背景音乐：${f.name}`;
}

function removeSlideshowImage(idx) {
  uploadSlideshowImages.value = uploadSlideshowImages.value.filter((_, i) => i !== idx);
}

function clearSlideshowImages() {
  uploadSlideshowImages.value = [];
  uploadMsg.value = "";
}

function clearSlideshowAudio() {
  uploadSlideshowAudioFile.value = null;
}

function onUploadModalDrop(e) {
  if (uploadSubmitting.value) return;
  if (uploadMode.value === "slideshow") {
    const files = Array.from(e?.dataTransfer?.files || []).filter((f) => String(f?.type || "").startsWith("image/"));
    if (!files.length) return;
    const merged = [...uploadSlideshowImages.value, ...files].slice(0, MAX_SLIDESHOW_IMAGES);
    uploadSlideshowImages.value = merged;
    uploadMsg.value = `已添加 ${files.length} 张图片，共 ${merged.length} 张`;
    return;
  }
  onUploadInputOrDrop(e);
}

async function pollMergePictureJob(jobId) {
  const id = String(jobId || "").trim();
  if (!id) throw new Error("缺少合成任务 ID");
  for (let i = 0; i < MERGE_POLL_MAX_ATTEMPTS; i++) {
    const st = await getMergePictureJobStatus(id);
    if (!st || typeof st !== "object") {
      throw new Error("任务状态异常");
    }
    if (st.status === "DONE") {
      if (!st.videoObjectKey) {
        throw new Error("合成成功但未返回视频地址");
      }
      return st;
    }
    if (st.status === "FAIL") {
      throw new Error(st.errorMessage || "幻灯片合成失败");
    }
    uploadProgress.value = Math.min(92, 72 + Math.floor(i / 4));
    await new Promise((r) => setTimeout(r, MERGE_POLL_INTERVAL_MS));
  }
  throw new Error("合成超时，请稍后重试或联系管理员");
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
  const isSlideshow = uploadMode.value === "slideshow";
  if (isSlideshow) {
    if (!uploadSlideshowImages.value.length) {
      uploadMsg.value = "请至少选择一张图片";
      pushToast("请至少选择一张图片", "info");
      return;
    }
  } else if (!uploadPickedFile.value) {
    uploadMsg.value = "请先选择视频文件";
    pushToast("请先选择视频文件", "info");
    return;
  }

  uploadSubmitting.value = true;
  uploadMsg.value = "";
  uploadProgress.value = 0;
  try {
    const publishBodyBase = {
      title,
      description: String(uploadForm.value.description || "").trim(),
      category: uploadForm.value.category,
      tags: uploadTagList.value.join(","),
      visibility: uploadForm.value.visibility,
      allowComment: Number(uploadForm.value.allowComment ? 1 : 0),
    };

    if (isSlideshow) {
      const imgs = uploadSlideshowImages.value;
      const audio = uploadSlideshowAudioFile.value;
      const totalParts = imgs.length + (audio ? 1 : 0);
      const objectKeys = [];
      for (let i = 0; i < imgs.length; i++) {
        uploadMsg.value = `正在上传图片 ${i + 1}/${imgs.length}…`;
        const { objectKey } = await uploadFileWithPresign(imgs[i], STORAGE_BIZ_WORK, (p) => {
          const base = (i + (p || 0)) / totalParts;
          uploadProgress.value = Math.max(0, Math.min(68, Math.round(base * 68)));
        });
        objectKeys.push(objectKey);
      }
      let audioObjectKey;
      if (audio) {
        uploadMsg.value = "正在上传音频…";
        const r = await uploadFileWithPresign(audio, STORAGE_BIZ_WORK, (p) => {
          const base = (imgs.length + (p || 0)) / totalParts;
          uploadProgress.value = Math.max(0, Math.min(68, Math.round(base * 68)));
        });
        audioObjectKey = r.objectKey;
      }
      uploadProgress.value = 70;
      uploadMsg.value = "已提交合成任务，请稍候…";
      const jobId = await mergePicturesToVideo({
        objectKeys,
        ...(audioObjectKey ? { audioObjectKey } : {}),
      });
      const merged = await pollMergePictureJob(jobId);
      uploadProgress.value = 94;
      uploadMsg.value = "正在发布视频…";
      await publishVideo({
        ...publishBodyBase,
        videoUrl: merged.videoObjectKey,
        duration: Number(merged.durationSeconds || 1),
      });
    } else {
      const file = uploadPickedFile.value;
      const { objectKey } = await uploadFileWithPresign(file, STORAGE_BIZ_WORK, (p) => {
        uploadProgress.value = Math.max(0, Math.min(100, Math.round((p || 0) * 100)));
      });
      uploadProgress.value = 100;
      const duration = uploadPickedDuration.value || (await readVideoDuration(file));
      uploadMsg.value = "正在发布视频…";
      await publishVideo({
        ...publishBodyBase,
        videoUrl: objectKey,
        duration: Number(duration || 1),
      });
    }

    uploadProgress.value = 100;
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
  <div
    class="modal-overlay upload-modal-overlay"
    role="dialog"
    aria-modal="true"
    aria-label="上传视频"
  >
      <form
        class="upload-modal"
        :class="{ 'upload-modal--slideshow-wide': uploadMode === 'slideshow' }"
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
        <div class="upload-mode-tabs" role="tablist" aria-label="上传方式">
          <button
            type="button"
            role="tab"
            class="upload-mode-tab"
            :class="{ 'upload-mode-tab--active': uploadMode === 'video' }"
            :disabled="uploadSubmitting"
            :aria-selected="uploadMode === 'video'"
            @click="setUploadMode('video')"
          >
            视频文件
          </button>
          <button
            type="button"
            role="tab"
            class="upload-mode-tab"
            :class="{ 'upload-mode-tab--active': uploadMode === 'slideshow' }"
            :disabled="uploadSubmitting"
            :aria-selected="uploadMode === 'slideshow'"
            @click="setUploadMode('slideshow')"
          >
            图片幻灯片
          </button>
        </div>
        <div
          class="upload-modal-main"
          :class="{ 'upload-modal-main--split': uploadMode === 'slideshow' }"
        >
          <div
            class="upload-modal-drop"
            :class="{ 'upload-modal-drop--slide-col': uploadMode === 'slideshow' }"
            @dragover.prevent
            @drop.prevent="onUploadModalDrop"
          >
            <template v-if="uploadMode === 'video'">
              <div class="upload-modal-icon">
                <i class="ri-upload-cloud-2-line"></i>
              </div>
              <p class="upload-modal-main-text">将要上传的视频文件拖放到此处</p>
              <button type="button" class="upload-modal-select" :disabled="uploadSubmitting" @click="triggerUploadPick">
                选择文件
              </button>
              <p v-if="uploadPickedFile" class="upload-picked-file">已选：{{ uploadPickedFile.name }}</p>
              <p v-if="uploadPickedDuration" class="upload-progress-text">时长约 {{ uploadPickedDuration }} 秒</p>
            </template>
            <template v-else>
              <div class="upload-modal-icon">
                <i class="ri-image-2-line"></i>
              </div>
              <p class="upload-modal-main-text">先按顺序添加图片（可拖入）；再选背景音乐，BGM 时长会与幻灯片总长对齐</p>
              <div class="upload-slideshow-actions">
                <button
                  type="button"
                  class="upload-modal-select"
                  :disabled="uploadSubmitting"
                  title="在文件选择框中可一次多选：Windows 用 Ctrl/Shift；Mac 用 Command/Shift"
                  @click="triggerSlideshowImagesPick"
                >
                  添加图片（可多选）
                </button>
                <template v-if="uploadSlideshowImages.length">
                  <button
                    type="button"
                    class="upload-modal-select upload-modal-select--ghost"
                    :disabled="uploadSubmitting"
                    @click="openSlideshowMusicPicker"
                  >
                    云端音乐（截取片段）
                  </button>
                  <button
                    type="button"
                    class="upload-modal-select upload-modal-select--ghost"
                    :disabled="uploadSubmitting"
                    @click="triggerSlideshowAudioPick"
                  >
                    本地上传音频
                  </button>
                </template>
                <button
                  v-if="uploadSlideshowImages.length"
                  type="button"
                  class="upload-modal-select upload-modal-select--ghost"
                  :disabled="uploadSubmitting"
                  @click="clearSlideshowImages"
                >
                  清空图片
                </button>
                <button
                  v-if="uploadSlideshowAudioFile"
                  type="button"
                  class="upload-modal-select upload-modal-select--ghost"
                  :disabled="uploadSubmitting"
                  @click="clearSlideshowAudio"
                >
                  移除音频
                </button>
              </div>
              <ul v-if="uploadSlideshowImages.length" class="upload-slideshow-list">
                <li v-for="(f, idx) in uploadSlideshowImages" :key="`${idx}-${f.name}-${f.size}`" class="upload-slideshow-item">
                  <span class="upload-slideshow-item-name">{{ idx + 1 }}. {{ f.name }}</span>
                  <button type="button" class="upload-slideshow-remove" :disabled="uploadSubmitting" @click="removeSlideshowImage(idx)">
                    移除
                  </button>
                </li>
              </ul>
              <p v-if="uploadSlideshowAudioFile" class="upload-slideshow-audio-line">音频：{{ uploadSlideshowAudioFile.name }}</p>
            </template>
            <p v-if="uploadSubmitting" class="upload-progress-text">
              {{ uploadMode === "slideshow" ? "进度" : "上传进度" }}：{{ uploadProgress }}%
            </p>
          </div>

          <div
            class="upload-meta-grid"
            :class="{ 'upload-meta-grid--slide-col': uploadMode === 'slideshow' }"
          >
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
            {{
              uploadSubmitting
                ? uploadMode === "slideshow"
                  ? "处理中…"
                  : "发布中…"
                : uploadMode === "slideshow"
                  ? "生成并发布"
                  : "上传并发布"
            }}
          </button>
        </div>
      </form>
    </div>
  <SlideshowMusicPicker
    :open="uploadMusicPickerOpen"
    :target-segment-sec="slideshowBgmTargetSec"
    @close="uploadMusicPickerOpen = false"
    @picked="onSlideshowCloudMusicPicked"
  />
  <input
    ref="uploadFileInputRef"
    type="file"
    accept="video/*"
    class="visually-hidden"
    aria-hidden="true"
    @change="onUploadInputOrDrop"
  />
  <input
    ref="uploadSlideshowImagesInputRef"
    type="file"
    accept="image/*"
    :multiple="true"
    class="visually-hidden"
    aria-hidden="true"
    @change="onSlideshowImagesInputChange"
  />
  <input
    ref="uploadSlideshowAudioInputRef"
    type="file"
    accept="audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg"
    class="visually-hidden"
    aria-hidden="true"
    @change="onSlideshowAudioInputChange"
  />
</template>
