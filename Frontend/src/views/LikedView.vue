<script setup>
/**
 * 「赞过的视频」页。
 * 过渡期：状态取自 likedStore，交互函数经 App.vue provide 的 cardsBridge 注入。
 */
import { computed, inject } from "vue";
import { storeToRefs } from "pinia";
import { useLikedStore } from "../stores/likedStore";
import { useAuthStore } from "../stores/authStore";
import {
  sanitizeImageUrl,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
} from "../utils/userProfile";

const bridge = inject("cardsBridge");
const { likedRows, likedTotal, likedLoading, likedLoadingMore, likedPageNo, likedPages } = storeToRefs(useLikedStore());
const { isAuthed } = storeToRefs(useAuthStore());

const likedDisplay = computed(() => {
  return likedRows.value.map((item) => ({
    id: item.id,
    title: item.title || "未命名视频",
    image: item.coverUrl || item.avatarUrl || profileBanner,
    likes: bridge.formatWorkLikeText(item),
    playCount: item.playCount,
    videoUrl: bridge.resolveVideoUrl(item),
    authorNickname: item.authorNickname || item.author || "",
    description: item.description || "",
    tags: item.tags || "",
    authorAvatarUrl: sanitizeImageUrl(item.authorAvatarUrl || item.authorAvatar || "") || profileAvatar,
    videoType: item.videoType,
    ...item,
  }));
});
const likedHasMore = computed(() => likedPageNo.value < (likedPages.value || 0));

const {
  onLikedWorkClick,
  loadLikedPage,
  onAuthorAvatarClick,
  onCardHoverEnter,
  onCardHoverLeave,
  isCardPreviewPlaying,
  activePreviewVideoUrl,
  resolveVideoUrl,
  formatHomeShortStat,
} = bridge;
</script>

<template>
          <section class="liked-videos-page profile-works-section">
            <div class="profile-works-title">
              <h4><i class="ri-thumb-up-line"></i> 赞过的视频</h4>
              <div class="profile-works-toolbar">
                <span v-if="isAuthed">共 {{ likedTotal }} 条</span>
                <span v-else class="liked-login-hint">登录后可查看</span>
              </div>
            </div>
            <p v-if="!isAuthed" class="profile-api-hint">请先登录后查看「赞过的视频」。</p>
            <div v-else-if="likedLoading" class="liked-loading-block">
              <p class="profile-api-hint profile-api-hint--loading">正在加载…</p>
              <div class="works-grid works-grid-profile works-grid-profile--skeleton">
                <article v-for="n in 6" :key="`liked-skeleton-${n}`" class="work-card work-card--skeleton">
                  <div class="work-thumb"></div>
                  <div class="work-meta">
                    <p class="work-title"></p>
                  </div>
                </article>
              </div>
            </div>
            <div v-else class="works-grid works-grid-profile">
              <article
                v-for="item in likedDisplay"
                :key="`liked-${item.id}`"
                class="work-card"
                :class="{ 'work-card--playable': resolveVideoUrl(item) }"
                @click="onLikedWorkClick(item)"
                @mouseenter="onCardHoverEnter(item, 'liked-work')"
                @mouseleave="onCardHoverLeave(item, 'liked-work')"
              >
                <div class="work-thumb">
                  <video
                    v-if="isCardPreviewPlaying(item, 'liked-work')"
                    class="work-preview-video"
                    :src="activePreviewVideoUrl"
                    muted
                    autoplay
                    loop
                    playsinline
                    preload="metadata"
                  ></video>
                  <img v-else :src="item.image" :alt="item.title" />
                  <span class="work-like work-like--bottom-left work-like--liked-list">
                    <i class="ri-heart-fill" aria-hidden="true"></i> {{ item.likes }}
                  </span>
                </div>
                <div class="work-meta">
                  <img
                    class="work-author-avatar"
                    :src="item.authorAvatarUrl || profileAvatar"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="onAuthorAvatarClick($event, item.authorId)"
                  />
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
            <div v-if="isAuthed && !likedLoading && likedDisplay.length && likedHasMore" class="liked-load-more-wrap">
              <button
                type="button"
                class="history-layout-btn"
                :disabled="likedLoadingMore"
                @click="loadLikedPage({ reset: false })"
              >
                {{ likedLoadingMore ? "加载中…" : "加载更多" }}
              </button>
            </div>
            <div v-if="isAuthed && !likedLoading && !likedDisplay.length" class="profile-works-empty">
              <div class="profile-works-empty-icon"><i class="ri-thumb-up-line"></i></div>
              <h5>暂无赞过的视频</h5>
              <p>去短视频或首页给喜欢的内容点个赞吧～</p>
            </div>
          </section>
</template>
