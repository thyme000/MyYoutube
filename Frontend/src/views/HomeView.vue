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
  formatHomeShortStat,
} from "../utils/videoCard.js";

const homeStore = useHomeStore();
const profile = useProfileStore();
const hb = homeBridgeHooks;
const {
  loading,
  cards,
  homeShortsRows,
  homeShortsLoadingMore,
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

const moduleEmptyState = computed(() => {
  if (activeMenuKey.value === "shorts") {
    return {
      icon: "ri-video-line",
      title: "暂无短视频",
      desc: "当前模块暂无可展示内容",
    };
  }
  if (activeMenuKey.value === "home") {
    return {
      icon: "ri-film-line",
      title: "暂无推荐内容",
      desc: "稍后刷新或切换分类再试",
    };
  }
  return {
    icon: "ri-inbox-line",
    title: "暂无数据",
    desc: "当前模块暂无可展示内容",
  };
});

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
                @mouseenter="hoverPreview.enter(card, 'home-head')"
                @mouseleave="hoverPreview.leave(card, 'home-head')"
              >
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

            <section v-if="!loading && homeShortsRows.length" class="home-shorts-block">
              <div class="home-shorts-head">
                <h3><i class="ri-video-line"></i> Shorts</h3>
              </div>
              <div
                :ref="(el) => (hb.shortsStripRef.value = el)"
                class="home-shorts-strip"
                @wheel.prevent="hb.onHomeShortsStripWheel"
                @scroll="hb.onHomeShortsStripScroll"
              >
                <article
                  v-for="(card, index) in homeShortsRows"
                  :key="`home-short-${card.id}-${index}`"
                  class="home-short-card"
                  @click="hb.openShortsFeed(card, index)"
                  @mouseenter="hoverPreview.enter(card, 'home-shorts')"
                  @mouseleave="hoverPreview.leave(card, 'home-shorts')"
                >
                  <div class="home-short-media">
                    <video
                      v-if="hoverPreview.activeId === cardPreviewKey(card, 'home-shorts')"
                      class="home-short-cover home-short-cover--video video-cover--preview"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else class="home-short-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  </div>
                  <div class="home-short-card-footer">
                    <div class="home-short-card-main">
                      <p class="home-short-title">{{ card.title }}</p>
                      <span class="home-short-author">{{ cardAuthorLabel(card) }}</span>
                    </div>
                    <div class="home-short-stats" aria-label="播放与点赞">
                      <span class="home-short-stat" title="播放量">
                        <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.playCount) }}
                      </span>
                      <span class="home-short-stat" title="点赞">
                        <i class="ri-heart-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.likeCount) }}
                      </span>
                    </div>
                  </div>
                </article>
                <div v-if="homeShortsLoadingMore" class="home-shorts-loading-more">加载中…</div>
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
                @mouseenter="hoverPreview.enter(card, 'home-tail')"
                @mouseleave="hoverPreview.leave(card, 'home-tail')"
              >
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
            <section
              v-if="!loading && !homeLongHeadCards.length && !homeLongTailCards.length && !homeShortsRows.length"
              class="module-empty"
            >
              <div class="module-empty-icon"><i :class="moduleEmptyState.icon"></i></div>
              <h5>{{ moduleEmptyState.title }}</h5>
              <p>{{ moduleEmptyState.desc }}</p>
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
            <div v-if="!loading && !cards.length" class="module-empty module-empty--grid">
              <div class="module-empty-icon"><i :class="moduleEmptyState.icon"></i></div>
              <h5>{{ moduleEmptyState.title }}</h5>
              <p>{{ moduleEmptyState.desc }}</p>
            </div>
          </section>
</template>

<style scoped>
/* 首页分类条：独立一层，避免 flex 压缩或与卡片 transform 叠层错位 */
.home-feed-header {
  flex-shrink: 0;
  position: relative;
  z-index: 10;
  background: var(--bg);
  padding: 6px 0 14px;
  margin: 0 0 12px;
  border-bottom: 1px solid var(--line);
  isolation: isolate;
}

.home-category-bar {
  display: flex;
  flex-wrap: nowrap;
  gap: 10px;
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
  border-radius: 999px;
  padding: 9px 22px;
  min-width: 72px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--line);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.home-chip:hover {
  background: var(--hover);
}

.home-chip--active {
  background: var(--active);
  border-color: #d0d9ea;
  color: #1a2332;
}

.home-sort-inline {
  margin-left: 4px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 2px;
}

.home-sort-label {
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
}

.home-sort-select {
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fff;
  color: var(--text);
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
}

.home-mixed-feed {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.home-shorts-block {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(15, 35, 80, 0.06);
  padding: 12px;
}

.home-shorts-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.home-shorts-head h3 {
  margin: 0;
  font-size: 18px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.home-shorts-loading-more {
  flex: 0 0 100px;
  align-self: center;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
}

.card-grid--loading-more {
  opacity: 0.96;
}

.module-empty--grid {
  grid-column: 1 / -1;
}
</style>
