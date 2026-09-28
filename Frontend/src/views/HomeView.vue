<script setup>
/**
 * 首页：分类栏 + 长短视频混合流。
 * 过渡期：状态/函数/DOM ref 经 App.vue provide 的 homeBridge 注入。
 */
import { inject } from "vue";
import { VIDEO_CATEGORY_CHIPS } from "../constants/videoCategories.js";

const {
  activeMenuKey,
  activePreviewVideoUrl,
  cardAuthorAvatar,
  cardAuthorLabel,
  cardCoverSrc,
  cards,
  formatHomeShortStat,
  homeCategoryKey,
  homeLongHeadCards,
  homeLongLoadingMore,
  homeLongTailCards,
  homeShortsLoadingMore,
  homeShortsRows,
  homeShortsStripRef,
  homeSortKey,
  homeSortOptions,
  isCardPreviewPlaying,
  loading,
  moduleEmptyState,
  onAuthorAvatarClick,
  onCardHoverEnter,
  onCardHoverLeave,
  onHomeShortCardClick,
  onHomeShortsStripScroll,
  onHomeShortsStripWheel,
  onVideoCardClick,
  selectHomeCategory,
  selectHomeSort,
  showProfileCenter,
  videoCardPlayable,
} = inject("homeBridge");
</script>

<template>
        <div v-if="activeMenuKey === 'home' && !showProfileCenter" class="home-feed-header">
          <nav class="home-category-bar" role="navigation" aria-label="视频分类">
            <button
              v-for="chip in VIDEO_CATEGORY_CHIPS"
              :key="chip.key"
              type="button"
              class="home-chip"
              :class="{ 'home-chip--active': homeCategoryKey === chip.key }"
              @click="selectHomeCategory(chip)"
            >
              {{ chip.label }}
            </button>
            <div class="home-sort-inline">
              <label class="home-sort-label" for="home-sort-select">排序</label>
              <select
                id="home-sort-select"
                class="home-sort-select"
                :value="homeSortKey"
                @change="selectHomeSort($event.target.value)"
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
                @click="onVideoCardClick(card)"
                @mouseenter="onCardHoverEnter(card, 'home-head')"
                @mouseleave="onCardHoverLeave(card, 'home-head')"
              >
                <video
                  v-if="isCardPreviewPlaying(card, 'home-head')"
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
                    @click.stop="onAuthorAvatarClick($event, card.authorId)"
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
                ref="homeShortsStripRef"
                class="home-shorts-strip"
                @wheel.prevent="onHomeShortsStripWheel"
                @scroll="onHomeShortsStripScroll"
              >
                <article
                  v-for="(card, index) in homeShortsRows"
                  :key="`home-short-${card.id}-${index}`"
                  class="home-short-card"
                  @click="onHomeShortCardClick(card, index)"
                  @mouseenter="onCardHoverEnter(card, 'home-shorts')"
                  @mouseleave="onCardHoverLeave(card, 'home-shorts')"
                >
                  <div class="home-short-media">
                    <video
                      v-if="isCardPreviewPlaying(card, 'home-shorts')"
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
                @click="onVideoCardClick(card)"
                @mouseenter="onCardHoverEnter(card, 'home-tail')"
                @mouseleave="onCardHoverLeave(card, 'home-tail')"
              >
                <video
                  v-if="isCardPreviewPlaying(card, 'home-tail')"
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
                    @click.stop="onAuthorAvatarClick($event, card.authorId)"
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
              @click="onVideoCardClick(card)"
            >
              <img class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
              <div class="video-info">
                <img
                  class="video-card-author-avatar"
                  :src="cardAuthorAvatar(card, index)"
                  alt=""
                  role="button"
                  title="查看作者主页"
                  @click.stop="onAuthorAvatarClick($event, card.authorId)"
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
