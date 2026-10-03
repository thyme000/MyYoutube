<script setup>
/**
 * 首页：分类栏 + 长短视频混合流。状态直连 homeStore；
 * 残余桥仅含 App 级依赖（短视频条滚动加载/进短视频流/卡片进详情/进他人主页）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { storeToRefs } from "pinia";
import { useRoute } from "vue-router";
import { useHomeStore, homeBridgeHooks, homeSortOptions, HOME_LONG_HEAD_COUNT_DESKTOP, HOME_LONG_HEAD_COUNT_MOBILE } from "../stores/homeStore";
import { VIDEO_CATEGORY_CHIPS } from "../constants/videoCategories.js";
import { useHoverPreviewStore, cardPreviewKey } from "../stores/hoverPreviewStore";
import { useProfileStore } from "../stores/profileStore";
import {
  videoCardPlayable,
  cardAuthorAvatar,
  cardCoverSrc,
  cardAuthorLabel,
  formatCompactStat,
} from "../utils/videoCard.js";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile.js";

const homeStore = useHomeStore();
const profile = useProfileStore();
const hb = homeBridgeHooks;
const {
  loading,
  cards,
  immersiveRows,
  immersiveLoadingMore,
  homeLongLoadingMore,
  homeCategoryKey,
  homeSortKey,
} = storeToRefs(homeStore);

const route = useRoute();
const activeMenuKey = computed(() => route.meta?.menuKey ?? "home");

/** 视口宽度 → 首屏长视频张数（与原 App 逻辑一致） */
const viewportWidth = ref(typeof window !== "undefined" ? window.innerWidth : 1440);
function onViewportResize() {
  viewportWidth.value = window.innerWidth || 1440;
}
onMounted(() => window.addEventListener("resize", onViewportResize));
onBeforeUnmount(() => window.removeEventListener("resize", onViewportResize));
const homeLongHeadCount = computed(() =>
  viewportWidth.value >= 1320 ? HOME_LONG_HEAD_COUNT_DESKTOP : HOME_LONG_HEAD_COUNT_MOBILE
);

const homeLongHeadCards = computed(() => {
  if (activeMenuKey.value !== "home") return [];
  return cards.value.slice(0, homeLongHeadCount.value);
});

const homeLongTailCards = computed(() => {
  if (activeMenuKey.value !== "home") return [];
  return cards.value.slice(homeLongHeadCount.value);
});

/** 统一内容池: 动态卡显示轮播角标, 不参与视频 hover 预览 */
function isDynamicCard(card) {
  return String(card?.type ?? "") === "dynamic";
}

const hoverPreview = useHoverPreviewStore();
const { videoUrl: activePreviewVideoUrl } = storeToRefs(hoverPreview);
</script>

<template>
        <div v-if="activeMenuKey === 'home' && !hb.isProfileCenterOpen()" class="home-feed-header">
          <nav class="home-category-bar" role="navigation" aria-label="视频分类">
            <button
              v-for="chip in VIDEO_CATEGORY_CHIPS"
              :key="chip.key"
              type="button"
              class="home-chip"
              :class="{ 'home-chip--active': homeCategoryKey === chip.key }"
              @click="homeStore.selectCategory(chip.key)"
            >
              {{ chip.label }}
            </button>
            <div class="home-sort-inline">
              <label class="home-sort-label" for="home-sort-select">排序</label>
              <select
                id="home-sort-select"
                class="home-sort-select"
                :value="homeSortKey"
                @change="homeStore.selectSort($event.target.value)"
              >
                <option v-for="opt in homeSortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
          </nav>
        </div>
          <section v-if="activeMenuKey === 'home'" class="home-mixed-feed">
        <section class="card-grid">
              <template v-if="loading">
              <article v-for="n in 8" :key="`skeleton-home-a-${n}`" class="video-card video-card--skeleton">
                <div class="video-cover video-cover-loading"></div>
            <div class="video-info">
                  <div class="video-card-author-avatar"></div>
                  <div class="video-info-text">
                    <strong></strong>
                    <span></span>
                  </div>
            </div>
          </article>
              </template>
              <template v-if="!loading">
              <article
                v-for="(card, index) in homeLongHeadCards"
                :key="`home-long-head-${card.id}`"
                class="video-card"
                :class="{ 'video-card--playable': videoCardPlayable(card) }"
                @click="hb.openVideoDetail(card)"
                @mouseenter="!isDynamicCard(card) && hoverPreview.enter(card, 'home-head')"
                @mouseleave="hoverPreview.leave(card, 'home-head')"
              >
                <span v-if="isDynamicCard(card)" class="video-card-badge">
                  <i class="ri-gallery-view-2-line"></i> 轮播图集
                </span>
                <video
                  v-if="hoverPreview.activeId === cardPreviewKey(card, 'home-head')"
                  class="video-cover video-cover--preview"
                  :src="activePreviewVideoUrl"
                  muted
                  autoplay
                  loop
                  playsinline
                  preload="metadata"
                ></video>
                <img v-else class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                <div class="video-info">
                  <img
                    class="video-card-author-avatar"
                    :src="cardAuthorAvatar(card, index)"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="profile.openByAuthorId(card.authorId)"
                    @error="(e) => (e.target.src = profileAvatar)"
                  />
                  <div class="video-info-text">
                    <strong>{{ card.title }}</strong>
                    <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
                  </div>
                </div>
              </article>
              </template>
            </section>

            <section v-if="!loading && immersiveRows.length" class="home-immersive-block">
              <div class="home-immersive-head">
                <h3><i class="ri-play-circle-line"></i> 沉浸</h3>
              </div>
              <div
                :ref="(el) => (hb.immersiveStripRef.value = el)"
                class="home-immersive-strip"
                @wheel.prevent="hb.onImmersiveStripWheel"
                @scroll="hb.onImmersiveStripScroll"
              >
                <article
                  v-for="(card, index) in immersiveRows"
                  :key="`home-immersive-${card.id}-${index}`"
                  class="home-immersive-card"
                  @click="hb.openImmersiveFeed(card, index)"
                  @mouseenter="!isDynamicCard(card) && hoverPreview.enter(card, 'home-immersive')"
                  @mouseleave="hoverPreview.leave(card, 'home-immersive')"
                >
                  <div class="home-immersive-media">
                    <span v-if="isDynamicCard(card)" class="video-card-badge">
                      <i class="ri-gallery-view-2-line"></i> 轮播图集
                    </span>
                    <video
                      v-if="hoverPreview.activeId === cardPreviewKey(card, 'home-immersive')"
                      class="home-immersive-cover home-immersive-cover--video video-cover--preview"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else class="home-immersive-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  </div>
                  <div class="home-immersive-card-footer">
                    <div class="home-immersive-card-main">
                      <p class="home-immersive-title">{{ card.title }}</p>
                      <span class="home-immersive-author">{{ cardAuthorLabel(card) }}</span>
                    </div>
                    <div class="home-immersive-stats" aria-label="播放与点赞">
                      <span class="home-immersive-stat" title="播放量">
                        <i class="ri-play-line" aria-hidden="true"></i>{{ formatCompactStat(card.playCount) }}
                      </span>
                      <span class="home-immersive-stat" title="点赞">
                        <i class="ri-heart-line" aria-hidden="true"></i>{{ formatCompactStat(card.likeCount) }}
                      </span>
                    </div>
                  </div>
                </article>
                <div v-if="immersiveLoadingMore" class="home-immersive-loading-more">加载中…</div>
              </div>
            </section>

            <section class="card-grid">
              <template v-if="!loading">
              <article
                v-for="(card, index) in homeLongTailCards"
                :key="`home-long-tail-${card.id}`"
                class="video-card"
                :class="{ 'video-card--playable': videoCardPlayable(card) }"
                @click="hb.openVideoDetail(card)"
                @mouseenter="!isDynamicCard(card) && hoverPreview.enter(card, 'home-tail')"
                @mouseleave="hoverPreview.leave(card, 'home-tail')"
              >
                <span v-if="isDynamicCard(card)" class="video-card-badge">
                  <i class="ri-gallery-view-2-line"></i> 轮播图集
                </span>
                <video
                  v-if="hoverPreview.activeId === cardPreviewKey(card, 'home-tail')"
                  class="video-cover video-cover--preview"
                  :src="activePreviewVideoUrl"
                  muted
                  autoplay
                  loop
                  playsinline
                  preload="metadata"
                ></video>
                <img v-else class="video-cover" :src="cardCoverSrc(card, index + homeLongHeadCards.length)" :alt="card.title" />
            <div class="video-info">
                  <img
                    class="video-card-author-avatar"
                    :src="cardAuthorAvatar(card, index + homeLongHeadCards.length)"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="profile.openByAuthorId(card.authorId)"
                    @error="(e) => (e.target.src = profileAvatar)"
                  />
                  <div class="video-info-text">
              <strong>{{ card.title }}</strong>
                    <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
                  </div>
            </div>
          </article>
              </template>
        </section>
            <section v-if="homeLongLoadingMore" class="card-grid card-grid--loading-more">
              <article v-for="n in 5" :key="`skeleton-home-more-${n}`" class="video-card video-card--skeleton">
                <div class="video-cover video-cover-loading"></div>
                <div class="video-info">
                  <div class="video-card-author-avatar"></div>
                  <div class="video-info-text">
                    <strong></strong>
                    <span></span>
                  </div>
                </div>
              </article>
            </section>
          </section>

          <section v-else-if="activeMenuKey !== 'subscribed' && activeMenuKey !== 'chat'" class="card-grid">
            <template v-if="loading">
            <article v-for="n in 8" :key="`skeleton-${n}`" class="video-card video-card--skeleton">
              <div class="video-cover video-cover-loading"></div>
              <div class="video-info">
                <div class="video-card-author-avatar"></div>
                <div class="video-info-text">
                  <strong></strong>
                  <span></span>
                </div>
              </div>
            </article>
            </template>

            <template v-if="!loading">
            <article
              v-for="(card, index) in cards"
              :key="card.id"
              class="video-card"
              :class="{ 'video-card--playable': videoCardPlayable(card) }"
              @click="hb.openVideoDetail(card)"
            >
              <img class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
              <div class="video-info">
                <img
                  class="video-card-author-avatar"
                  :src="cardAuthorAvatar(card, index)"
                  alt=""
                  role="button"
                  title="查看作者主页"
                  @click.stop="profile.openByAuthorId(card.authorId)"
                  @error="(e) => (e.target.src = profileAvatar)"
                />
                <div class="video-info-text">
                  <strong>{{ card.title }}</strong>
                  <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
                </div>
              </div>
            </article>
            </template>
          </section>
</template>

<style scoped>
/* 首页分类条：独立一层，避免 flex 压缩或与卡片 transform 叠层错位 */
.home-feed-header {
  flex-shrink: 0;
  position: relative;
  z-index: 10;
  background: var(--bg);
  padding: var(--space-6) 0 var(--space-14);
  margin: 0 0 var(--space-12);
  border-bottom: 1px solid var(--line);
  isolation: isolate;
}

.home-category-bar {
  display: flex;
  flex-wrap: nowrap;
  gap: var(--space-10);
  overflow-x: auto;
  padding: 0;
  margin: 0;
  scrollbar-width: thin;
}

.home-category-bar::-webkit-scrollbar {
  height: 6px;
}

.home-chip {
  flex: 0 0 auto;
  border: none;
  cursor: pointer;
  border-radius: var(--radius-full);
  padding: var(--space-9) var(--space-22);
  min-width: 72px;
  font-size: var(--font-size-14);
  font-weight: 600;
  color: var(--text);
  background: rgb(var(--white-rgb) / 0.92);
  border: 1px solid var(--line);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.home-chip:hover {
  background: var(--hover);
}

.home-chip--active {
  background: var(--active);
  border-color: var(--color-border-strong);
  color: var(--color-text-strong);
}

.home-sort-inline {
  margin-left: var(--space-4);
  display: inline-flex;
  align-items: center;
  gap: var(--space-8);
  padding: 0 var(--space-8) 0 var(--space-2);
}

.home-sort-label {
  font-size: var(--font-size-12);
  color: var(--muted);
  white-space: nowrap;
}

.home-sort-select {
  border: 1px solid var(--line);
  border-radius: var(--radius-full);
  background: var(--color-bg-surface);
  color: var(--text);
  padding: var(--space-8) var(--space-12);
  font-size: var(--font-size-13);
  cursor: pointer;
}

.home-mixed-feed {
  display: flex;
  flex-direction: column;
  gap: var(--space-14);
}

.home-immersive-block {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-card);
  padding: var(--space-12);
}

.home-immersive-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-10);
}

.home-immersive-head h3 {
  margin: 0;
  font-size: var(--font-size-18);
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
}

.home-immersive-loading-more {
  flex: 0 0 100px;
  align-self: center;
  text-align: center;
  color: var(--muted);
  font-size: var(--font-size-12);
}

.card-grid--loading-more {
  opacity: 0.96;
}
</style>
