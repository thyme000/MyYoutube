<script setup>
// @ts-check
/**
 * 发布图集配乐动态弹窗: 选图 + 选画布/转场 + 配乐(音频库选择/本地上传入库/曲库选段入库) + 焦点微调。
 * 音频一律先入库(audioId), 发布时后端校验可见性并以快照落库。
 */
import { computed, ref } from "vue";
import BaseModal from "./ui/BaseModal.vue";
import AudioPicker from "./AudioPicker.vue";
import MyAudioModal from "./MyAudioModal.vue";
import SlideshowMusicPicker from "./SlideshowMusicPicker.vue";
import { publishDynamic } from "../api/dynamicApi.js";
import { uploadFileWithPresign } from "../utils/presignUpload.js";
import { uploadAudioToLibrary } from "../utils/audioLibrary.js";
import { useToastStore } from "../stores/toastStore";
import { useStudioStore } from "../stores/studioStore";
import { useProfileStore } from "../stores/profileStore";

const MAX_IMAGES = 18;
const props = defineProps({
  open: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "published"]);
const toastStore = useToastStore();
const studioStore = useStudioStore();
const profileStore = useProfileStore();

const title = ref("");
const images = ref([]);
const selectedIdx = ref(0);
const canvasType = ref("vertical");
const transition = ref("fade");
const allowComment = ref(1);
/** 选中的音频库条目 { id, title, durationMs } */
const audio = ref(null);
const submitting = ref(false);
const progressMsg = ref("");
const audioPickerOpen = ref(false);
const myAudioOpen = ref(false);
const musicPickerOpen = ref(false);
const imagesInputRef = ref(null);
const audioInputRef = ref(null);

const selectedItem = computed(() => images.value[selectedIdx.value] || null);
const canSubmit = computed(() => images.value.length > 0 && audio.value && !submitting.value);

function triggerImagesPick() {
  imagesInputRef.value?.click();
}
function triggerAudioPick() {
  audioInputRef.value?.click();
}

function onImagesInputChange(e) {
  const raw = Array.from(e?.target?.files || []).filter((f) => String(f?.type || "").startsWith("image/"));
  if (e?.target && "value" in e.target) e.target.value = "";
  if (!raw.length) return;
  const merged = [...images.value, ...raw].slice(0, MAX_IMAGES);
  images.value = merged.map((f) => ({ file: f, url: URL.createObjectURL(f), focusX: 0.5, focusY: 0.5 }));
  selectedIdx.value = Math.min(selectedIdx.value, Math.max(0, merged.length - 1));
}

function onAudioInputChange(e) {
  const f = e?.target?.files?.[0];
  if (e?.target && "value" in e.target) e.target.value = "";
  if (!f) return;
  ingestAudioFile(f);
}

/** 本地音频/曲库选段 → 上传 → 自动入库(公开) */
async function ingestAudioFile(file) {
  if (!String(file.type || "").startsWith("audio/") && !/\.(mp3|m4a|aac|wav|flac|ogg)$/i.test(file.name || "")) {
    toastStore.pushToast("请选择音频文件", "error");
    return;
  }
  try {
    progressMsg.value = "音频入库中…";
    const r = await uploadAudioToLibrary(file);
    audio.value = { id: r.audioId, title: r.title, durationMs: r.durationMs };
    progressMsg.value = "";
    toastStore.pushToast(`音频已入库：${r.title}`, "success");
  } catch (e) {
    progressMsg.value = "";
    toastStore.pushToast(e?.message || "音频入库失败", "error");
  }
}

function onAudioPicked(a) {
  audio.value = a;
  audioPickerOpen.value = false;
}

function onCloudMusicPicked(file) {
  musicPickerOpen.value = false;
  if (file) void ingestAudioFile(file);
}

function removeImage(idx) {
  images.value = images.value.filter((_, i) => i !== idx);
  if (selectedIdx.value >= images.value.length) {
    selectedIdx.value = Math.max(0, images.value.length - 1);
  }
}

/** 在预览图上点击设置该图的裁剪焦点(0~1), 与成片 ffmpeg crop 对齐 */
function setFocusOnPreview(e) {
  if (!selectedItem.value) return;
  const rect = e.currentTarget.getBoundingClientRect();
  selectedItem.value.focusX = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
  selectedItem.value.focusY = Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height));
}

function close() {
  if (submitting.value) return;
  emit("close");
}

async function submit() {
  if (!images.value.length) {
    toastStore.pushToast("请至少添加一张图片", "error");
    return;
  }
  if (!audio.value) {
    toastStore.pushToast("请选择背景音乐", "error");
    return;
  }
  submitting.value = true;
  progressMsg.value = "";
  try {
    const total = images.value.length + 1;
    const items = [];
    for (let i = 0; i < images.value.length; i++) {
      progressMsg.value = `正在上传图片 ${i + 1}/${images.value.length}…`;
      const { objectKey } = await uploadFileWithPresign(images.value[i].file, "work", (p) => {
        const base = (i + (p || 0)) / total;
        progressMsg.value = `正在上传图片 ${i + 1}/${images.value.length}… ${Math.round(base * 100)}%`;
      });
      items.push({
        imageObjectKey: objectKey,
        focusX: images.value[i].focusX,
        focusY: images.value[i].focusY,
      });
    }
    progressMsg.value = "正在发布…";
    await publishDynamic({
      title: title.value.trim(),
      canvasType: canvasType.value,
      transition: transition.value,
      audioId: audio.value.id,
      allowComment: allowComment.value,
      items,
    });
    toastStore.pushToast("动态已发布", "success");
    emit("published");
    // 就地刷新当前所在列表（与 UploadModal 发布视频后一致）；推荐流排除自己，刷新沉浸队列没有意义
    if (studioStore.showStudio) {
      void studioStore.load();
    }
    if (profileStore.open) {
      void profileStore.loadWorks();
    }
    reset();
    emit("close");
  } catch (e) {
    toastStore.pushToast(e?.message || "发布失败", "error");
    progressMsg.value = "";
  } finally {
    submitting.value = false;
  }
}

function reset() {
  title.value = "";
  images.value = [];
  selectedIdx.value = 0;
  canvasType.value = "vertical";
  transition.value = "fade";
  allowComment.value = 1;
  audio.value = null;
  progressMsg.value = "";
}
</script>

<template>
  <BaseModal :open="props.open" card-class="dynpub-card" :close-on-esc="!submitting" :close-on-backdrop="!submitting" aria-label="发布动态">
    <div class="dynpub">
      <header class="dynpub-head">
        <h2>发布动态</h2>
        <p class="dynpub-sub">图片轮播 + 背景音乐，预览即成片</p>
      </header>

      <div class="dynpub-body">
        <section class="dynpub-left">
          <button type="button" class="dynpub-btn dynpub-btn--ghost" :disabled="submitting" @click="triggerImagesPick">
            <i class="ri-image-add-line"></i> 添加图片（{{ images.length }}/{{ MAX_IMAGES }}）
          </button>
          <div v-if="images.length" class="dynpub-thumbs">
            <div
              v-for="(img, i) in images"
              :key="i"
              class="dynpub-thumb"
              :class="{ 'dynpub-thumb--active': i === selectedIdx }"
              @click="selectedIdx = i"
            >
              <img :src="img.url" alt="" />
              <button type="button" class="dynpub-thumb-x" :disabled="submitting" @click.stop="removeImage(i)">×</button>
            </div>
          </div>

          <div class="dynpub-field-row">
            <label class="dynpub-label">画布</label>
            <label class="dynpub-radio"><input v-model="canvasType" type="radio" value="vertical" :disabled="submitting" /> 竖版 9:16</label>
            <label class="dynpub-radio"><input v-model="canvasType" type="radio" value="horizontal" :disabled="submitting" /> 横版 16:9</label>
          </div>
          <div class="dynpub-field-row">
            <label class="dynpub-label">切换</label>
            <label class="dynpub-radio"><input v-model="transition" type="radio" value="fade" :disabled="submitting" /> 叠化</label>
            <label class="dynpub-radio"><input v-model="transition" type="radio" value="cut" :disabled="submitting" /> 硬切</label>
          </div>
          <div class="dynpub-field-row">
            <label class="dynpub-label">评论</label>
            <label class="dynpub-radio"><input v-model.number="allowComment" type="radio" :value="1" :disabled="submitting" /> 允许评论</label>
            <label class="dynpub-radio"><input v-model.number="allowComment" type="radio" :value="0" :disabled="submitting" /> 关闭评论</label>
          </div>

          <div class="dynpub-audio">
            <button type="button" class="dynpub-btn dynpub-btn--ghost" :disabled="submitting" @click="audioPickerOpen = true">
              <i class="ri-music-2-line"></i> {{ audio ? "换音频" : "音频库选择" }}
            </button>
            <button type="button" class="dynpub-btn dynpub-btn--ghost" :disabled="submitting" @click="triggerAudioPick">
              <i class="ri-upload-2-line"></i> 本地上传
            </button>
            <button type="button" class="dynpub-btn dynpub-btn--ghost" :disabled="submitting" @click="musicPickerOpen = true">
              <i class="ri-disc-line"></i> 曲库选段
            </button>
          </div>
          <div class="dynpub-audio-line">
            <span v-if="audio" class="dynpub-audio-name">
              <i class="ri-music-fill"></i> {{ audio.title }} · {{ (audio.durationMs / 1000).toFixed(1) }}s
            </span>
            <span v-else class="dynpub-audio-hint">未选择音频（本地上传会自动入库，默认公开）</span>
            <button type="button" class="dynpub-manage" @click="myAudioOpen = true">管理我的音频</button>
          </div>

          <input v-model="title" class="dynpub-title" type="text" maxlength="100" placeholder="标题（可选）" :disabled="submitting" />
          <p v-if="progressMsg" class="dynpub-msg">{{ progressMsg }}</p>
        </section>

        <section class="dynpub-right">
          <p class="dynpub-hint">点击画面设置当前图的焦点（保住人脸/主体，成片按同口径裁剪）</p>
          <div
            class="dynpub-preview"
            :class="canvasType === 'horizontal' ? 'dynpub-preview--horizontal' : 'dynpub-preview--vertical'"
            @click="setFocusOnPreview"
          >
            <img
              v-if="selectedItem"
              :src="selectedItem.url"
              :style="{ objectPosition: `${selectedItem.focusX * 100}% ${selectedItem.focusY * 100}%` }"
              alt=""
            />
            <span v-if="selectedItem" class="dynpub-focus-dot"
              :style="{ left: `${selectedItem.focusX * 100}%`, top: `${selectedItem.focusY * 100}%` }"></span>
            <span v-if="!selectedItem" class="dynpub-preview-empty">先添加图片</span>
          </div>
        </section>
      </div>

      <footer class="dynpub-actions">
        <button type="button" class="dynpub-btn dynpub-btn--ghost" :disabled="submitting" @click="close">取消</button>
        <button type="button" class="dynpub-btn dynpub-btn--primary" :disabled="!canSubmit" @click="submit">
          {{ submitting ? "发布中…" : "发布" }}
        </button>
      </footer>
    </div>

    <AudioPicker :open="audioPickerOpen" @close="audioPickerOpen = false" @picked="onAudioPicked" />
    <MyAudioModal :open="myAudioOpen" @close="myAudioOpen = false" />
    <SlideshowMusicPicker :open="musicPickerOpen" :target-segment-sec="0" @close="musicPickerOpen = false" @picked="onCloudMusicPicked" />
    <input ref="imagesInputRef" type="file" accept="image/*" multiple class="visually-hidden" @change="onImagesInputChange" />
    <input ref="audioInputRef" type="file" accept="audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg" class="visually-hidden" @change="onAudioInputChange" />
  </BaseModal>
</template>

<style scoped>
.dynpub {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dynpub-head h2 {
  margin: 0;
  font-size: 18px;
}
.dynpub-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ink-500, #999);
}
.dynpub-body {
  display: grid;
  grid-template-columns: 1fr 220px;
  gap: 16px;
}
.dynpub-left {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dynpub-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.dynpub-thumb {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  outline: 2px solid transparent;
}
.dynpub-thumb--active {
  outline-color: var(--ink-100, #f5f5f7);
}
.dynpub-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.dynpub-thumb-x {
  position: absolute;
  top: 0;
  right: 0;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 0 0 0 6px;
  background: rgb(0 0 0 / 0.55);
  color: #fff;
  line-height: 1;
  cursor: pointer;
}
.dynpub-field-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dynpub-label {
  font-size: 13px;
  color: var(--ink-500, #999);
}
.dynpub-radio {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  cursor: pointer;
}
.dynpub-audio {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.dynpub-audio-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dynpub-audio-name {
  font-size: 12px;
  color: var(--ink-300, #ccc);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dynpub-audio-hint {
  font-size: 12px;
  color: var(--ink-500, #999);
}
.dynpub-manage {
  border: none;
  background: transparent;
  color: var(--ink-400, #aaa);
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
}
.dynpub-title {
  height: 36px;
  border-radius: 8px;
  border: 1px solid var(--line, #333);
  background: transparent;
  color: inherit;
  padding: 0 10px;
  font-size: 14px;
}
.dynpub-msg {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400, #aaa);
}
.dynpub-right {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dynpub-hint {
  margin: 0;
  font-size: 11px;
  color: var(--ink-500, #999);
  line-height: 1.5;
}
.dynpub-preview {
  position: relative;
  width: 100%;
  border-radius: 10px;
  overflow: hidden;
  background: #000;
  cursor: crosshair;
}
.dynpub-preview--vertical {
  aspect-ratio: 9 / 16;
}
.dynpub-preview--horizontal {
  aspect-ratio: 16 / 9;
}
.dynpub-preview img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.dynpub-preview-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: var(--ink-500, #999);
}
.dynpub-focus-dot {
  position: absolute;
  width: 14px;
  height: 14px;
  margin: -7px 0 0 -7px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px rgb(0 0 0 / 0.5);
  pointer-events: none;
}
.dynpub-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.dynpub-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border-radius: 8px;
  border: none;
  font-size: 13px;
  cursor: pointer;
}
.dynpub-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.dynpub-btn--ghost {
  background: transparent;
  border: 1px solid var(--line, #333);
  color: inherit;
}
.dynpub-btn--primary {
  background: var(--ink-100, #f5f5f7);
  color: #111;
  font-weight: 600;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
