<script setup>
/**
 * 个人主页：头像/背景（含裁剪预览）、作品列表、关注关系与粉丝/关注弹窗。
 * 状态与业务动作在 profileStore，本组件只保留 DOM 交互（文件选择、裁剪拖拽、尺寸测量）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useAuthStore } from "../stores/authStore";
import { useProfileStore } from "../stores/profileStore";
import { useHoverPreviewStore, cardPreviewKey } from "../stores/hoverPreviewStore";
import {
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
  sanitizeImageUrl,
} from "../utils/userProfile";
import {
  formatHomeShortStat,
  formatWorkLikeText,
  resolveVideoUrl,
} from "../utils/videoCard.js";

const authStore = useAuthStore();
const { isAuthed } = storeToRefs(authStore);
const store = useProfileStore();
const {
  open: showProfileCenter,
  loading: profileLoading,
  msg: profileMsg,
  user: profileUser,
  worksRows: profileWorksRows,
  worksLoading: profileWorksLoading,
  targetFollowed: profileTargetFollowed,
  followBusy: profileFollowBusy,
  chatBusy: profileChatBusy,
  uploadBusy: profileUploadBusy,
  displayAvatar: profileDisplayAvatar,
  displayBg: profileDisplayBg,
  displayNickname,
  isViewingSelf,
  introDisplayText,
  avatarImgStyle,
  avatarImgUsePrecise,
  heroImgStyle,
  heroImgUsePrecise,
  cropBoxStyle,
  mediaPreviewSrc,
  pendingCropFile,
  editForm: profileEditForm,
  editSaving: profileEditSaving,
  relationUsers: relationTableUsers,
  relationTitle: relationTableTitle,
  relationKind: relationTableKind,
  relationFollowerMenuId,
} = storeToRefs(store);

const hoverPreview = useHoverPreviewStore();
const { videoUrl: activePreviewVideoUrl } = storeToRefs(hoverPreview);

const avatarFileInput = ref(null);
const bgFileInput = ref(null);
const heroCropContainerRef = ref(null);
let heroResizeObserver = null;

/** 作品过滤为视图私有状态，打开主页时复位 */
const profileWorksFilter = ref("ALL");
watch(showProfileCenter, (v) => {
  if (v) profileWorksFilter.value = "ALL";
});

const profileWorksDisplay = computed(() => {
  const base = profileWorksRows.value.map((item) => ({
    ...item,
    id: item.id,
    title: item.title || "未命名视频",
    image: item.coverUrl || item.avatarUrl || profileBanner,
    likes: formatWorkLikeText(item),
    playCount: item.playCount,
    videoUrl: resolveVideoUrl(item),
    authorNickname: item.authorNickname || item.author || "",
    description: item.description || "",
    tags: item.tags || "",
    authorAvatarUrl:
      sanitizeImageUrl(item.authorAvatarUrl || item.authorAvatar || "") ||
      profileDisplayAvatar.value,
  }));
  if (profileWorksFilter.value === "PRIVATE") {
    return base.filter((item) => String(item.visibility || "").toUpperCase() === "PRIVATE");
  }
  if (profileWorksFilter.value === "PUBLIC") {
    return base.filter((item) => String(item.visibility || "").toUpperCase() !== "PRIVATE");
  }
  return base;
});

/** 图片换了要重试加载，否则上一次的失败态会把新图顶成默认图 */
watch(
  () => store.user?.avatarUrl,
  () => {
    store.avatarImgError = false;
  },
);
watch(
  () => store.user?.bgImage,
  () => {
    store.bgImgError = false;
  },
);
watch(
  () => [
    store.isViewingSelf,
    authStore.userId,
    store.user?.id,
    store.user?.bgImage,
    store.user?.avatarUrl,
  ],
  () => store.applyCropsFromStorage(),
);
watch(
  () => [store.displayBg, store.displayAvatar],
  () => store.syncNaturalSizes(),
  { immediate: true },
);

onMounted(async () => {
  await nextTick();
  const el = heroCropContainerRef.value;
  if (!el) return;
  store.measureHero(el);
  heroResizeObserver = new ResizeObserver(() => store.measureHero(el));
  heroResizeObserver.observe(el);
});

onBeforeUnmount(() => {
  heroResizeObserver?.disconnect();
  heroResizeObserver = null;
});

function onAvatarFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  store.beginCropUpload("avatar", file);
  store.msg = "请先裁剪头像后再上传";
}

function onBgFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  store.beginCropUpload("bg", file);
  store.msg = "请先裁剪背景图后再上传";
}

function pickMediaFromPreview() {
  const type = store.mediaPreviewType;
  store.closeMediaPreview();
  nextTick(() => {
    (type === "avatar" ? avatarFileInput : bgFileInput).value?.click();
  });
}

async function downloadMediaPreview() {
  const url = mediaPreviewSrc.value;
  if (!url) return;
  const base = store.mediaPreviewType === "avatar" ? "avatar" : "background";
  try {
    const res = await fetch(url, { mode: "cors" });
    if (!res.ok) throw new Error("fetch failed");
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${base}-${Date.now()}.jpg`;
    a.click();
    URL.revokeObjectURL(a.href);
  } catch {
    const a = document.createElement("a");
    a.href = url;
    a.target = "_blank";
    a.rel = "noopener";
    a.click();
  }
}

function onCropImgLoad(event) {
  const img = event.target;
  store.setCropNaturalSize({ w: img.naturalWidth, h: img.naturalHeight });
  if (store.pendingCropFile) store.applyDefaultCropForUpload(img.naturalWidth, img.naturalHeight);
}

/** 拖动选区：按容器像素换算成归一化位移，并夹在图片范围内 */
function onCropBoxDragStart(event) {
  if (event.button !== 0 || event.target.classList.contains("crop-handle-br")) return;
  event.preventDefault();
  event.stopPropagation();
  const inner = event.currentTarget.closest(".media-preview-crop-stage-inner");
  if (!inner) return;
  const rect = inner.getBoundingClientRect();
  const start = { ...store.cropRectDraft };
  const sx = event.clientX;
  const sy = event.clientY;
  function onMove(ev) {
    const L = Math.min(Math.max(start.L + (ev.clientX - sx) / rect.width, 0), 1 - start.W);
    const T = Math.min(Math.max(start.T + (ev.clientY - sy) / rect.height, 0), 1 - start.H);
    store.setCropDraft({ ...start, L, T });
  }
  function onUp() {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  }
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

/** 拖拽右下角：保持目标宽高比缩放选区 */
function onCropResizeStart(event) {
  event.stopPropagation();
  event.preventDefault();
  const inner = event.currentTarget.closest(".media-preview-crop-stage-inner");
  if (!inner) return;
  const rect = inner.getBoundingClientRect();
  const { w: nw, h: nh } = store.cropNaturalSize;
  if (nw <= 0 || nh <= 0) return;
  const ratioWH = (store.mediaPreviewType === "bg" ? 16 / 5 : 1) * (nh / nw);
  const start = { ...store.cropRectDraft };
  const sx = event.clientX;
  function onMove(ev) {
    let W = Math.max(0.05, start.W + (ev.clientX - sx) / rect.width);
    let H = Math.max(0.05, W / ratioWH);
    if (start.L + W > 1) {
      W = 1 - start.L;
      H = W / ratioWH;
    }
    if (start.T + H > 1) {
      H = 1 - start.T;
      W = H * ratioWH;
    }
    store.setCropDraft({ ...start, W, H });
  }
  function onUp() {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  }
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}
</script>

<template>
  <section v-if="showProfileCenter" class="profile-page">
    <input
      ref="avatarFileInput"
      type="file"
      accept="image/*"
      class="visually-hidden"
      aria-hidden="true"
      @change="onAvatarFileChange"
    />
    <input
      ref="bgFileInput"
      type="file"
      accept="image/*"
      class="visually-hidden"
      aria-hidden="true"
      @change="onBgFileChange"
    />
    <p v-if="profileLoading" class="profile-api-hint">正在加载用户信息…</p>
    <p v-else-if="profileMsg" class="profile-api-hint">{{ profileMsg }}</p>

    <div class="profile-top-row">
      <aside class="profile-side-card">
        <button
          type="button"
          class="profile-avatar-wrap"
          title="查看头像"
          @click="store.openMediaPreview('avatar')"
        >
          <img
            v-if="!isViewingSelf"
            :src="profileDisplayAvatar"
            alt="用户头像"
            class="profile-avatar-sm"
            @error="store.markAvatarError"
          />
          <div
            v-else
            class="profile-avatar-sm profile-avatar-crop-inner"
            role="img"
            :aria-label="displayNickname"
          >
            <img
              :src="profileDisplayAvatar"
              :class="['profile-avatar-img-el', avatarImgUsePrecise ? 'profile-avatar-img-el--precise' : 'profile-avatar-img-el--cover']"
              :style="avatarImgStyle"
              alt=""
              draggable="false"
              @load="store.setAvatarNaturalSize({ w: $event.target.naturalWidth, h: $event.target.naturalHeight })"
              @error="store.markAvatarError"
            />
          </div>
        </button>
        <h4>个人资料</h4>
        <p>昵称：{{ displayNickname }}</p>
        <p class="profile-id-line">ID：{{ profileUser?.id ?? "—" }}</p>
        <div class="profile-stat-list">
          <button
            v-if="isViewingSelf"
            type="button"
            class="profile-stat-row profile-stat-btn"
            @click="store.openRelationTable('following')"
          >
            <span class="profile-stat-left">关注 <i class="ri-play-fill stat-triangle"></i></span>
            <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
          </button>
          <div v-else class="profile-stat-row">
            <span>关注</span>
            <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
          </div>
          <button
            v-if="isViewingSelf"
            type="button"
            class="profile-stat-row profile-stat-btn"
            @click="store.openRelationTable('followers')"
          >
            <span class="profile-stat-left">粉丝 <i class="ri-play-fill stat-triangle"></i></span>
            <strong>{{ profileUser?.fans ?? "—" }}</strong>
          </button>
          <div v-else class="profile-stat-row">
            <span>粉丝</span>
            <strong>{{ profileUser?.fans ?? "—" }}</strong>
          </div>
          <div class="profile-stat-row">
            <span>获赞</span>
            <strong>{{ profileUser?.likeCount ?? "—" }}</strong>
          </div>
          <div class="profile-stat-row">
            <span>作品数</span>
            <strong>{{ profileUser?.videoCount ?? profileWorksDisplay.length }}</strong>
          </div>
        </div>
        <div
          class="profile-intro"
          :class="{ 'profile-intro--editable': isViewingSelf }"
          :role="isViewingSelf ? 'button' : undefined"
          :tabindex="isViewingSelf ? 0 : undefined"
          @click="isViewingSelf && store.openEdit()"
          @keydown.enter.prevent="isViewingSelf && store.openEdit()"
        >
          <h5>个人简介</h5>
          <p>{{ introDisplayText }}</p>
          <span v-if="isViewingSelf" class="profile-intro-edit-hint">点击编辑资料</span>
        </div>
      </aside>

      <section class="profile-hero-col">
        <button
          type="button"
          class="profile-hero profile-hero-clickable profile-hero--stretch"
          title="查看背景图"
          @click="store.openMediaPreview('bg')"
        >
          <div ref="heroCropContainerRef" class="profile-hero-img" role="img" aria-hidden="true">
            <img
              :src="profileDisplayBg"
              :class="['profile-hero-img-el', heroImgUsePrecise ? 'profile-hero-img-el--precise' : 'profile-hero-img-el--cover']"
              :style="heroImgStyle"
              alt=""
              draggable="false"
              @load="store.setBgNaturalSize({ w: $event.target.naturalWidth, h: $event.target.naturalHeight })"
              @error="store.markBgError"
            />
          </div>
          <div class="profile-hero-mask"></div>
        </button>
      </section>
    </div>

    <section class="profile-works-section">
      <div class="profile-works-title">
        <h4>作品列表</h4>
        <div class="profile-works-toolbar">
          <button
            v-if="!isViewingSelf && isAuthed"
            type="button"
            class="profile-page-follow-btn"
            :disabled="profileFollowBusy"
            @click="store.toggleFollow"
          >
            {{ profileTargetFollowed ? "已关注" : "+ 关注" }}
          </button>
          <button
            v-if="!isViewingSelf && isAuthed"
            type="button"
            class="profile-works-filter-btn"
            :disabled="profileChatBusy"
            title="与对方发起私聊"
            @click="store.startChat"
          >
            {{ profileChatBusy ? "创建中…" : "聊一聊" }}
          </button>
          <div v-if="isViewingSelf" class="profile-works-filters">
            <button
              v-for="f in [
                { key: 'ALL', label: '全部' },
                { key: 'PUBLIC', label: '公开' },
                { key: 'PRIVATE', label: '私密' },
              ]"
              :key="f.key"
              type="button"
              class="profile-works-filter-btn"
              :class="{ 'profile-works-filter-btn--active': profileWorksFilter === f.key }"
              @click="profileWorksFilter = f.key"
            >
              {{ f.label }}
            </button>
          </div>
          <span>共 {{ profileWorksDisplay.length }} 条</span>
        </div>
      </div>
      <p v-if="profileWorksLoading" class="profile-api-hint profile-api-hint--loading">正在加载作品列表…</p>
      <div v-if="profileWorksLoading" class="works-grid works-grid-profile works-grid-profile--skeleton">
        <article v-for="n in 6" :key="`work-skeleton-${n}`" class="work-card work-card--skeleton">
          <div class="work-thumb"></div>
          <div class="work-meta">
            <p class="work-title"></p>
          </div>
        </article>
      </div>
      <div class="works-grid works-grid-profile">
        <article
          v-for="item in profileWorksDisplay"
          :key="item.id"
          class="work-card"
          :class="{ 'work-card--playable': resolveVideoUrl(item) }"
          @click="store.onWorkCardClick(item)"
          @mouseenter="hoverPreview.enter(item, 'profile-work')"
          @mouseleave="hoverPreview.leave(item, 'profile-work')"
        >
          <div class="work-thumb">
            <video
              v-if="hoverPreview.activeId === cardPreviewKey(item, 'profile-work')"
              class="work-preview-video"
              :src="activePreviewVideoUrl"
              muted
              autoplay
              loop
              playsinline
              preload="metadata"
            ></video>
            <img v-else :src="item.image" :alt="item.title" />
            <span class="work-like work-like--bottom-left"><i class="ri-heart-line"></i> {{ item.likes }}</span>
          </div>
          <div class="work-meta">
            <img class="work-author-avatar" :src="item.authorAvatarUrl || profileAvatar" alt="" />
            <div class="work-meta-main">
              <div class="work-title-row">
                <p class="work-title">{{ item.title }}</p>
                <span class="work-play-stat" title="播放量">
                  <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(item.playCount) }}
                </span>
              </div>
            </div>
          </div>
        </article>
      </div>
      <div v-if="!profileWorksLoading && !profileWorksDisplay.length" class="profile-works-empty">
        <div class="profile-works-empty-icon"><i class="ri-movie-2-line"></i></div>
        <h5>暂无内容</h5>
        <p>该账号还未发布过作品哦～</p>
      </div>
    </section>

    <!-- 头像 / 背景 预览与裁剪 -->
    <div
      v-if="store.mediaPreviewOpen"
      class="modal-overlay modal-overlay--light"
      @click.self="store.closeMediaPreview()"
    >
      <div class="modal-card media-preview-card media-preview-card--light" role="dialog" aria-modal="true" @click.stop>
        <h3 class="media-preview-title media-preview-title--light">
          {{ store.mediaPreviewType === "avatar" ? "头像" : "背景图" }}
        </h3>
        <div class="media-preview-body media-preview-body--stack">
          <div class="media-preview-crop-stage">
            <div class="media-preview-crop-stage-inner">
              <img
                class="media-preview-full-img"
                :src="mediaPreviewSrc"
                :alt="store.mediaPreviewType === 'avatar' ? '头像预览' : '背景预览'"
                draggable="false"
                @load="onCropImgLoad"
                @error="store.markMediaPreviewError"
              />
              <div
                v-if="isViewingSelf && pendingCropFile"
                class="crop-box"
                :style="cropBoxStyle"
                @pointerdown="onCropBoxDragStart"
              >
                <div class="crop-handle-br" title="调整选区大小" @pointerdown.stop="onCropResizeStart" />
              </div>
            </div>
          </div>
          <div class="media-preview-side media-preview-side--light">
            <button
              v-if="isViewingSelf"
              type="button"
              class="profile-action-btn primary"
              :disabled="profileUploadBusy"
              @click="pendingCropFile ? store.submitCropUpload() : pickMediaFromPreview()"
            >
              {{ profileUploadBusy ? "处理中…" : pendingCropFile ? "裁剪并上传" : "更换图片" }}
            </button>
            <button v-else type="button" class="profile-action-btn primary" @click="downloadMediaPreview">
              下载图片
            </button>
            <button type="button" class="profile-action-btn ghost" @click="store.closeMediaPreview()">关闭</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 关注 / 粉丝列表 -->
    <div
      v-if="store.relationOpen"
      class="modal-overlay modal-overlay--light relation-modal-overlay"
      @click.self="store.closeRelationTable()"
    >
      <div class="modal-card relation-table-card relation-table-card--white" @click="relationFollowerMenuId = null">
        <h3 class="relation-table-heading">{{ relationTableTitle }}</h3>
        <div class="relation-table-wrap relation-table-wrap--white">
          <table class="relation-table relation-table--white">
            <thead>
              <tr>
                <th>ID</th>
                <th>头像</th>
                <th class="relation-th-nick">昵称</th>
                <th class="relation-th-intro">简介</th>
                <th class="relation-th-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(user, relIdx) in relationTableUsers" :key="`rel-${relIdx}-${String(user.id)}`">
                <td>{{ user.id }}</td>
                <td>
                  <img
                    class="relation-avatar"
                    :src="user.avatarDisplaySrc || profileAvatar"
                    alt=""
                    role="button"
                    title="查看用户主页"
                    @click.stop="store.openByAuthorId(user.id)"
                    @error="(e) => { const t = e.target; if (!t || t.dataset.fallbackApplied === '1') return; t.dataset.fallbackApplied = '1'; t.src = profileAvatar; }"
                  />
                </td>
                <td class="relation-nick-cell">
                  <div
                    class="relation-name"
                    role="button"
                    tabindex="0"
                    title="查看用户主页"
                    @click.stop="store.openByAuthorId(user.id)"
                    @keyup.enter.stop="store.openByAuthorId(user.id)"
                  >
                    {{ user.nickName }}
                  </div>
                </td>
                <td class="relation-intro-cell">
                  <span class="relation-intro-text">{{ user.introduction?.trim() || "—" }}</span>
                </td>
                <td class="relation-action-cell">
                  <template v-if="relationTableKind === 'followers'">
                    <button
                      type="button"
                      class="relation-more-btn"
                      aria-label="更多操作"
                      @click.stop="store.toggleFollowerMenu(user.id)"
                    >
                      <i class="ri-more-2-fill"></i>
                    </button>
                    <div v-if="relationFollowerMenuId === user.id" class="relation-flyout" @click.stop>
                      <button type="button" class="relation-flyout-btn" @click="store.removeFollower(user)">
                        移除
                      </button>
                      <button
                        type="button"
                        class="relation-flyout-btn relation-flyout-btn--primary"
                        @click="store.followBack(user)"
                      >
                        回关
                      </button>
                    </div>
                  </template>
                  <template v-else>
                    <button type="button" class="relation-unfollow-btn" @click="store.unfollowFollowing(user)">
                      取关
                    </button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="relation-table-more">
          <button
            v-if="relationTableKind === 'following' && store.followingHasMore"
            type="button"
            class="history-layout-btn"
            :disabled="store.followingLoading"
            @click="store.loadRelationNextPage('following')"
          >
            {{ store.followingLoading ? "加载中…" : "加载更多" }}
          </button>
          <button
            v-if="relationTableKind === 'followers' && store.followerHasMore"
            type="button"
            class="history-layout-btn"
            :disabled="store.followerLoading"
            @click="store.loadRelationNextPage('followers')"
          >
            {{ store.followerLoading ? "加载中…" : "加载更多" }}
          </button>
        </div>
        <button type="button" class="relation-modal-close" @click="store.closeRelationTable()">关闭</button>
      </div>
    </div>

    <!-- 编辑资料 -->
    <div
      v-if="store.editOpen"
      class="modal-overlay modal-overlay--light profile-edit-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="profile-edit-title"
      @click.self="store.closeEdit()"
    >
      <div class="modal-card profile-edit-modal" @click.stop>
        <h3 id="profile-edit-title" class="profile-edit-title">编辑资料</h3>
        <div class="profile-edit-form">
          <label class="profile-edit-label">
            <span>昵称</span>
            <input
              v-model="profileEditForm.nickName"
              type="text"
              maxlength="64"
              autocomplete="nickname"
              placeholder="请输入昵称"
            />
          </label>
          <label class="profile-edit-label">
            <span>个人简介</span>
            <textarea
              v-model="profileEditForm.introduction"
              rows="4"
              maxlength="500"
              placeholder="介绍一下自己…"
            ></textarea>
          </label>
        </div>
        <div class="profile-edit-actions">
          <button type="button" class="profile-edit-btn profile-edit-btn--ghost" @click="store.closeEdit()">
            取消
          </button>
          <button
            type="button"
            class="profile-edit-btn profile-edit-btn--primary"
            :disabled="profileEditSaving"
            @click="store.saveEdit"
          >
            {{ profileEditSaving ? "保存中…" : "保存" }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.profile-page {
  display: grid;
  gap: var(--space-12);
}

.profile-hero-mask {
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(180deg, rgba(15, 15, 15, 0.12), rgba(15, 15, 15, 0.55));
  pointer-events: none;
}

.profile-hero-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.profile-hero-img-el {
  display: block;
}

.profile-hero-img-el--cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
}

.profile-hero-img-el--precise {
  /* 宽高与 left/top 由内联样式按原图与选区精确计算 */
}

.profile-avatar-wrap {
  display: block;
  border: none;
  padding: 0;
  margin: 0 0 var(--space-14);
  background: transparent;
  cursor: pointer;
  border-radius: 50%;
  line-height: 0;
  overflow: hidden;
  width: fit-content;
}

.profile-avatar-wrap:focus-visible {
  outline: 2px solid rgb(var(--white-rgb) / 0.4);
  outline-offset: 3px;
}

.profile-avatar-wrap .profile-avatar-sm {
  margin-bottom: 0;
}

.profile-avatar-crop-inner {
  position: relative;
  flex-shrink: 0;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  overflow: hidden;
  background-color: var(--gray-100);
}

.profile-avatar-img-el {
  display: block;
}

.profile-avatar-img-el--cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
}

.profile-avatar-img-el--precise {
  /* 尺寸与定位由内联样式计算 */
}

.media-preview-card {
  max-width: 640px;
  width: min(92vw, 640px);
}

.media-preview-card--light {
  max-width: min(920px, 96vw);
  width: min(94vw, 920px);
  background: var(--color-bg-surface);
  color: var(--ink-980);
  border: 1px solid var(--gray-200);
  box-shadow: 0 12px 40px rgb(var(--black-rgb) / 0.12);
}

.media-preview-title {
  margin: 0 0 var(--space-16);
  font-size: var(--font-size-18);
}

.media-preview-title--light {
  color: var(--ink-980);
}

.media-preview-body {
  display: flex;
  flex-direction: row;
  gap: var(--space-20);
  align-items: center;
  flex-wrap: wrap;
}

.media-preview-body--stack {
  flex-direction: column;
  align-items: stretch;
}

.media-preview-crop-stage {
  width: 100%;
  display: flex;
  justify-content: center;
}

.media-preview-crop-stage-inner {
  position: relative;
  display: inline-block;
  max-width: 100%;
  vertical-align: top;
}

.media-preview-full-img {
  display: block;
  max-width: 100%;
  max-height: min(70vh, 680px);
  width: auto;
  height: auto;
  object-fit: contain;
  vertical-align: top;
}

.crop-box {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid var(--white);
  outline: 2px solid rgb(var(--red-brand-rgb) / 0.95);
  box-shadow: 0 0 0 9999px rgb(var(--black-rgb) / 0.38);
  cursor: move;
  z-index: 2;
  touch-action: none;
}

.crop-handle-br {
  position: absolute;
  right: -7px;
  bottom: -7px;
  width: 18px;
  height: 18px;
  background: var(--color-bg-surface);
  border: 2px solid var(--accent);
  border-radius: var(--radius-sm);
  cursor: nwse-resize;
  z-index: 3;
  box-shadow: 0 1px 4px rgb(var(--black-rgb) / 0.2);
}

.media-preview-side--light .profile-action-btn {
  border-color: var(--gray-300);
  color: var(--ink-950);
  background: var(--gray-100);
}

.media-preview-side--light .profile-action-btn.primary {
  background: var(--accent);
  color: var(--color-text-inverse);
  border-color: transparent;
}

.media-preview-side--light .profile-action-btn.ghost {
  background: var(--color-bg-surface);
  border: 1px solid var(--ink-300);
}

.media-preview-side {
  display: flex;
  flex-direction: column;
  gap: var(--space-10);
  justify-content: center;
  min-width: 140px;
  flex: 1;
}

.profile-action-btn {
  padding: var(--space-10) var(--space-16);
  border-radius: var(--radius-xl);
  border: 1px solid rgb(var(--white-rgb) / 0.12);
  background: rgb(var(--white-rgb) / 0.08);
  color: var(--text);
  cursor: pointer;
  font-size: var(--font-size-14);
}

.profile-action-btn.primary {
  background: var(--accent);
  border-color: transparent;
  color: var(--color-text-inverse);
}

.profile-action-btn.primary:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.profile-action-btn.ghost {
  background: transparent;
}

@media (max-width: 600px) {
  .media-preview-body {
    flex-direction: column;
    align-items: stretch;
  }

  .media-preview-body--stack {
    flex-direction: column;
  }


  .media-preview-side {
    width: 100%;
  }

  .media-preview-side--light {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .media-preview-body {
    flex-direction: column;
    align-items: stretch;
  }

  .media-preview-body--stack {
    flex-direction: column;
  }


  .media-preview-side {
    width: 100%;
  }

  .media-preview-side--light {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .media-preview-body {
    flex-direction: column;
    align-items: stretch;
  }

  .media-preview-body--stack {
    flex-direction: column;
  }


  .media-preview-side {
    width: 100%;
  }

  .media-preview-side--light {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .media-preview-body {
    flex-direction: column;
    align-items: stretch;
  }

  .media-preview-body--stack {
    flex-direction: column;
  }


  .media-preview-side {
    width: 100%;
  }

  .media-preview-side--light {
    width: 100%;
  }
}

.profile-avatar-sm {
  width: 110px;
  height: 110px;
  border-radius: 50%;
  object-fit: cover;
  display: block;
  border: 2px solid var(--white);
  margin-bottom: var(--space-14);
}

.profile-stat-list {
  margin-top: var(--space-14);
  padding-top: var(--space-10);
  display: grid;
  gap: var(--space-8);
}

.profile-stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--font-size-14);
}

.profile-stat-btn {
  width: 100%;
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-align: left;
}

.profile-stat-btn:hover {
  opacity: 0.82;
}

.profile-stat-row span {
  color: var(--muted);
}

.profile-stat-left {
  display: inline-flex;
  align-items: center;
  gap: var(--space-4);
}

.stat-triangle {
  font-size: var(--font-size-11);
  color: var(--color-text-faint);
  transform: rotate(90deg);
}

.profile-stat-row strong {
  color: var(--text);
  font-weight: 700;
}

.profile-intro {
  margin-top: var(--space-14);
  padding-top: var(--space-12);
}

.profile-intro h5 {
  margin: 0 0 var(--space-8);
  font-size: var(--font-size-15);
  color: #33415f;
}

.profile-intro p {
  margin: 0;
  font-size: var(--font-size-13);
  line-height: 1.6;
  color: var(--color-text-muted);
}

.profile-id-line {
  margin: var(--space-6) 0 0;
  font-size: var(--font-size-13);
  color: var(--color-text-muted);
}

.profile-intro--editable {
  cursor: pointer;
  border-radius: var(--radius-xl);
  padding: var(--space-10) var(--space-10) var(--space-8);
  margin: var(--space-10) -10px 0;
  border: 1px solid transparent;
  transition: background 0.15s, border-color 0.15s;
}

.profile-intro--editable:hover {
  background: rgb(var(--indigo-500-rgb) / 0.06);
  border-color: rgb(var(--indigo-500-rgb) / 0.2);
}

.profile-intro-edit-hint {
  display: block;
  margin-top: var(--space-6);
  font-size: var(--font-size-12);
  color: var(--color-text-faint);
}

.profile-edit-overlay {
  z-index: var(--z-modal-raised);
}

.profile-edit-modal {
  width: min(440px, 92vw);
  background: var(--color-bg-surface) !important;
  border: 1px solid var(--color-border-subtle) !important;
  box-shadow: var(--shadow-lg);
  color: var(--color-text-strong);
}

.profile-edit-title {
  margin: 0 0 var(--space-16);
  font-size: var(--font-size-18);
  font-weight: 600;
  color: var(--color-text-strong);
}

.profile-edit-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-14);
}

.profile-edit-label {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  font-size: var(--font-size-13);
  color: var(--color-text-secondary);
}

.profile-edit-label input,
.profile-edit-label textarea {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-10) var(--space-12);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-14);
  color: var(--color-text-strong);
  background: var(--color-bg-sunken);
}

.profile-edit-label textarea {
  resize: vertical;
  min-height: 96px;
  line-height: 1.5;
}

.profile-edit-label input:focus,
.profile-edit-label textarea:focus {
  outline: none;
  border-color: var(--indigo-500);
  background: var(--color-bg-surface);
}

.profile-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-10);
  margin-top: var(--space-18);
}

.profile-edit-btn {
  min-width: 88px;
  height: 38px;
  padding: 0 var(--space-16);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-14);
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
}

.profile-edit-btn--ghost {
  background: var(--color-bg-sunken);
  border-color: var(--color-border);
  color: var(--color-text-secondary);
}

.profile-edit-btn--ghost:hover {
  background: var(--color-bg-hover);
}

.profile-edit-btn--primary {
  background: var(--indigo-600);
  color: var(--color-text-inverse);
}

.profile-edit-btn--primary:hover:not(:disabled) {
  background: var(--indigo-700);
}

.profile-edit-btn--primary:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.profile-page-follow-btn {
  flex-shrink: 0;
  padding: var(--space-6) var(--space-14);
  border-radius: var(--radius-full);
  border: 1px solid var(--accent, var(--blue-500));
  background: var(--accent, var(--blue-500));
  color: var(--color-text-inverse);
  font-size: var(--font-size-13);
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s ease, transform 0.12s ease;
}

.profile-page-follow-btn:hover:not(:disabled) {
  opacity: 0.92;
}

.profile-page-follow-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.profile-works-filters {
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
}

.profile-works-filter-btn {
  border: 1px solid var(--color-border-strong);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  border-radius: var(--radius-full);
  height: 30px;
  padding: 0 var(--space-12);
  font-size: var(--font-size-12);
  cursor: pointer;
}

.profile-works-filter-btn:hover {
  background: var(--slate-50);
}

.profile-works-filter-btn--active {
  background: var(--blue-700);
  border-color: var(--blue-700);
  color: var(--color-text-inverse);
}

.profile-works-filter-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.relation-modal-overlay {
  z-index: 1000;
}

.relation-table-card {
  width: min(900px, 95vw);
}

.relation-table-card--white {
  background: var(--color-bg-surface) !important;
  border: 1px solid var(--color-border-subtle) !important;
  box-shadow: var(--shadow-lg);
  color: var(--color-text-strong);
}

.relation-table-heading {
  color: var(--color-text-strong);
  font-weight: 600;
}

.relation-table-wrap {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
}

.relation-table-wrap--white {
  border-color: var(--color-border-subtle);
  background: var(--color-bg-sunken);
}

.relation-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--color-bg-surface);
}

.relation-table--white {
  background: var(--color-bg-surface);
}

.relation-table th,
.relation-table td {
  padding: var(--space-10) var(--space-12);
  border-bottom: 1px solid var(--line);
  text-align: left;
  font-size: var(--font-size-14);
}

.relation-table--white th,
.relation-table--white td {
  border-bottom-color: var(--color-border-subtle);
}

.relation-table thead th {
  background: var(--color-bg-subtle);
  position: sticky;
  top: 0;
}

.relation-table--white thead th {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-weight: 600;
}

.relation-th-action {
  width: 120px;
  text-align: right !important;
}

.relation-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--color-border-subtle);
  background: var(--color-bg-hover);
  display: block;
}

.relation-th-nick {
  min-width: 100px;
}

.relation-th-intro {
  min-width: 140px;
}

.relation-nick-cell {
  vertical-align: middle;
}

.relation-intro-cell {
  max-width: min(320px, 40vw);
  vertical-align: middle;
  color: var(--color-text-muted);
  font-size: var(--font-size-13);
  line-height: 1.4;
}

.relation-name {
  display: inline-block;
  font-weight: 600;
  color: var(--color-text-strong);
  cursor: pointer;
  text-align: left;
}

.relation-name:hover {
  color: var(--blue-700);
}

.relation-intro-text {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.relation-action-cell {
  position: relative;
  text-align: right;
  vertical-align: middle;
}

.relation-more-btn {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.relation-more-btn:hover {
  background: var(--color-bg-sunken);
  border-color: var(--color-border-strong);
  color: var(--color-text-strong);
}

.relation-flyout {
  position: absolute;
  right: 100%;
  top: 50%;
  transform: translateY(-50%);
  margin-right: var(--space-8);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-2xl);
  box-shadow: 0 10px 40px rgb(var(--slate-900-rgb) / 0.12);
  padding: var(--space-8);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  min-width: 132px;
  z-index: 1001;
}

.relation-flyout-btn {
  padding: var(--space-10) var(--space-12);
  border: none;
  border-radius: var(--radius-lg);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-14);
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
}

.relation-flyout-btn:hover {
  background: var(--slate-200);
}

.relation-flyout-btn--primary {
  background: var(--blue-50);
  color: var(--blue-700);
  font-weight: 500;
}

.relation-flyout-btn--primary:hover {
  background: var(--blue-100);
}

.relation-unfollow-btn {
  padding: var(--space-8) var(--space-16);
  border-radius: var(--radius-xl);
  border: 1px solid var(--red-200);
  background: var(--color-bg-surface);
  color: var(--orange-700);
  font-size: var(--font-size-14);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.relation-unfollow-btn:hover {
  background: var(--orange-50);
  border-color: var(--orange-500);
}

.relation-modal-close {
  margin-top: var(--space-14);
  width: 100%;
  height: 40px;
  border: 1px solid var(--color-border);
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
  border-radius: var(--radius-xl);
  cursor: pointer;
  font-size: var(--font-size-14);
  font-weight: 500;
}

.relation-modal-close:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border-strong);
}

@media (max-width: 640px) {
  .relation-flyout {
    right: auto;
    left: 0;
    top: calc(100% + 6px);
    transform: none;
    margin-right: 0;
  }
}
</style>
