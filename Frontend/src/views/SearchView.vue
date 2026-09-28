<script setup>
/**
 * 搜索结果页（tabs/排序/相关用户/竖滑视频流/用户列表）。
 * 过渡期：状态取自 searchStore，交互函数经 App.vue provide 的 searchBridge 注入；
 * 后续将函数逐一迁入 store / 本组件，最终撤掉桥接。
 */
import { computed, inject } from "vue";
import { storeToRefs } from "pinia";
import { useSearchStore, SEARCH_UI } from "../stores/searchStore";
import { useAuthStore } from "../stores/authStore";
import { useShortsStore } from "../stores/shortsStore";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile";
import {
  resolveVideoUrl,
  cardAuthorAvatar,
  cardAuthorLabel,
  formatHomeShortStat,
  formatShortsTime,
  parseSearchTagsArray,
  formatSearchRelTime,
  readVideoDurationFromVideoEl,
} from "../utils/videoCard.js";

/** 搜索列表进度条：节流 timeupdate，避免每帧整表重绘卡死 */
const searchFeedProgressThrottleTs = new Map();

defineProps({
  /** App.vue 的 searchFeedScrollRef，滚动容器 DOM 需回传给宿主的观察器 */
  scrollRef: { type: Object, default: null },
});

const bridge = inject("searchBridge");
const {
  searchTab,
  searchVideoSort,
  searchResultError,
  searchAllUserStrip,
  searchVideoRows,
  searchTagRows,
  searchUserRows,
  searchUserLoading,
  searchUserLoadingMore,
  searchVideoLoading,
  searchVideoLoadingMore,
  searchTagLoading,
  searchTagLoadingMore,
} = storeToRefs(useSearchStore());
const { isAuthed } = storeToRefs(useAuthStore());

const searchStore = useSearchStore();
const {
  searchFeedMainVideoById,
  searchFeedMediaWrapById,
  searchFeedProgressMap,
  searchFeedListPrefs,
  searchFeedPausedMap,
  searchListCommentOpen,
  searchListCommentCard,
  searchExpandCommentOpen,
} = storeToRefs(searchStore);

const searchFeedVideoList = computed(() => {
  if (searchTab.value === "tag") return searchTagRows.value;
  if (searchTab.value === "all" || searchTab.value === "video") return searchVideoRows.value;
  return [];
});
const searchVerticalLoading = computed(() => {
  if (searchTab.value === "tag") return searchTagLoading.value;
  return searchVideoLoading.value;
});
const searchVerticalLoadingMore = computed(() => {
  if (searchTab.value === "tag") return searchTagLoadingMore.value;
  return searchVideoLoadingMore.value;
});

/** 残余桥: 标签导航/用户行操作/点赞系统/进全屏(App 级) */
const {
  onSearchTagNavigate,
  onSearchUserRowActivate,
  searchUserRowIsSelf,
  searchUserRowIsFollowed,
  toggleSearchUserFollow,
  onAuthorAvatarClick,
  onSearchFeedForwardClick,
  onSearchFeedMainVideoClick,
  onSearchFeedLikeClick,
} = bridge;

function selectSearchTab(key) {
  searchStore.selectTab(key);
}


function searchFeedBindMainVideo(cardId, el) {
  const k = String(cardId);
  const cur = searchFeedMainVideoById.value[k];
  if (el) {
    if (cur === el) return;
    searchFeedMainVideoById.value = { ...searchFeedMainVideoById.value, [k]: el };
  } else {
    if (cur === undefined) return;
    const next = { ...searchFeedMainVideoById.value };
    delete next[k];
    searchFeedMainVideoById.value = next;
  }
}

function searchFeedBindMediaWrap(cardId, el) {
  const k = String(cardId);
  const cur = searchFeedMediaWrapById.value[k];
  if (el) {
    if (cur === el) return;
    searchFeedMediaWrapById.value = { ...searchFeedMediaWrapById.value, [k]: el };
  } else {
    if (cur === undefined) return;
    const next = { ...searchFeedMediaWrapById.value };
    delete next[k];
    searchFeedMediaWrapById.value = next;
  }
}

function searchFeedGetMainVideo(id) {
  return searchFeedMainVideoById.value[String(id)] ?? null;
}

function searchFeedListMuted(card) {
  return searchFeedListPrefs.value[String(card.id)]?.muted !== false;
}

function searchFeedListVolValue(card) {
  return searchFeedListPrefs.value[String(card.id)]?.volume ?? 0.85;
}

function searchFeedListScrubMax(cardId) {
  const d = Number(searchFeedProgressMap.value[String(cardId)]?.d);
  return Number.isFinite(d) && d > 0 ? d : 1;
}

function searchFeedListScrubDisplay(cardId) {
  const p = searchFeedProgressMap.value[String(cardId)];
  const t = Number(p?.t);
  const d = Number(p?.d);
  if (!Number.isFinite(t) || t < 0) return 0;
  if (Number.isFinite(d) && d > 0) return Math.min(t, d);
  return 0;
}

function searchFeedListDurationReady(cardId) {
  const d = Number(searchFeedProgressMap.value[String(cardId)]?.d);
  return Number.isFinite(d) && d > 0;
}

function searchFeedListCurrent(cardId) {
  return Number(searchFeedProgressMap.value[String(cardId)]?.t) || 0;
}

function searchFeedListDuration(cardId) {
  return Number(searchFeedProgressMap.value[String(cardId)]?.d) || 0;
}

function searchFeedListShowPaused(card) {
  return searchFeedPausedMap.value[String(card.id)] !== false;
}

function onSearchFeedListScrubInput(e, card) {
  const v = searchFeedGetMainVideo(card.id);
  if (!v) return;
  const t = Number(e.target.value);
  if (!Number.isFinite(t)) return;
  v.currentTime = t;
  const d = readVideoDurationFromVideoEl(v);
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [String(card.id)]: { t, d: d > 0 ? d : searchFeedProgressMap.value[String(card.id)]?.d ?? 0 },
  };
}

function searchFeedListToggleMute(card, ev) {
  ev?.stopPropagation?.();
  const k = String(card.id);
  const next = !searchFeedListMuted(card);
  searchFeedListPrefs.value = { ...searchFeedListPrefs.value, [k]: { ...searchFeedListPrefs.value[k], muted: next } };
  const el = searchFeedGetMainVideo(card.id);
  if (el) el.muted = next;
}

function onSearchFeedListVolumeInput(e, card) {
  const vol = Number(e.target.value);
  const k = String(card.id);
  searchFeedListPrefs.value = {
    ...searchFeedListPrefs.value,
    [k]: { ...searchFeedListPrefs.value[k], volume: vol, muted: vol === 0 },
  };
  const v = searchFeedGetMainVideo(card.id);
  if (v) {
    v.volume = vol;
    v.muted = vol === 0;
  }
}

async function searchFeedListRequestPip(card, ev) {
  ev?.stopPropagation?.();
  const v = searchFeedGetMainVideo(card.id);
  if (!v?.requestPictureInPicture) return;
  try {
    if (document.pictureInPictureElement === v) {
      await document.exitPictureInPicture();
    } else {
      await v.requestPictureInPicture();
    }
  } catch {
    /* ignore */
  }
}

async function searchFeedListRequestFullscreen(card, ev) {
  ev?.stopPropagation?.();
  const el = searchFeedMediaWrapById.value[String(card.id)];
  if (!el) return;
  try {
    if (document.fullscreenElement === el) {
      await document.exitFullscreen();
    } else {
      await el.requestFullscreen();
    }
  } catch {
    /* ignore */
  }
}

function searchFeedListTogglePlay(card, ev) {
  ev?.stopPropagation?.();
  const v = searchFeedGetMainVideo(card.id);
  if (!v) return;
  if (v.paused) v.play().catch(() => {});
  else v.pause();
}

function onSearchFeedListTimeUpdate(e, cardId) {
  const v = e.target;
  const id = String(cardId);
  const now = Date.now();
  const last = searchFeedProgressThrottleTs.get(id) ?? 0;
  if (now - last < 180) {
    return;
  }
  searchFeedProgressThrottleTs.set(id, now);
  const d = readVideoDurationFromVideoEl(v);
  const t = Number(v.currentTime) || 0;
  const prev = searchFeedProgressMap.value[id];
  const dur = d > 0 ? d : prev?.d ?? 0;
  if (prev && Math.abs((prev.t ?? 0) - t) < 0.12 && prev.d === dur) {
    return;
  }
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [id]: { t, d: dur },
  };
  const slide = e.target.closest?.("[data-search-v-slide]");
  const blur = slide?.querySelector?.(".search-feed-video--blur");
  if (blur && v && Math.abs(blur.currentTime - v.currentTime) > 0.25) {
    blur.currentTime = v.currentTime;
  }
}

function onSearchFeedListLoadedMetadata(e, cardId) {
  const v = e.target;
  const id = String(cardId);
  const d = readVideoDurationFromVideoEl(v);
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [id]: { t: Number(v.currentTime) || 0, d },
  };
  const p = searchFeedListPrefs.value[id];
  v.volume = p?.volume ?? 0.85;
  v.muted = p?.muted !== false;
}

function onSearchFeedListSeeked(e) {
  const v = e.target;
  const slide = e.target.closest?.("[data-search-v-slide]");
  const blur = slide?.querySelector?.(".search-feed-video--blur");
  if (blur && v) {
    blur.currentTime = v.currentTime;
  }
}

function onSearchFeedListVideoPlay(e, cardId) {
  onSearchFeedListPlay(e);
  searchFeedPausedMap.value = { ...searchFeedPausedMap.value, [String(cardId)]: false };
}

function onSearchFeedListVideoPause(e, cardId) {
  onSearchFeedListPause(e);
  searchFeedPausedMap.value = { ...searchFeedPausedMap.value, [String(cardId)]: true };
}

function openSearchListCommentPanel(card, ev) {
  ev?.stopPropagation?.();
  if (!card?.id) return;
  useShortsStore().shortsCommentPanelOpen = false;
  searchExpandCommentOpen.value = false;
  searchListCommentCard.value = card;
  searchListCommentOpen.value = true;
}


function onSearchFeedListPlay(e) {
  const blur = e.target?.closest?.("[data-search-v-slide]")?.querySelector?.(".search-feed-video--blur");
  blur?.play?.().catch(() => {});
}
function onSearchFeedListPause(e) {
  const blur = e.target?.closest?.("[data-search-v-slide]")?.querySelector?.(".search-feed-video--blur");
  blur?.pause?.();
}
</script>

<template>
        <section class="search-session" :ref="scrollRef">
          <div class="search-session-toolbar">
            <nav class="search-session-tabs" aria-label="搜索分类">
              <button type="button" class="search-session-tab" :class="{ 'is-active': searchTab === 'all' }" @click="selectSearchTab('all')">综合</button>
              <button type="button" class="search-session-tab" :class="{ 'is-active': searchTab === 'video' }" @click="selectSearchTab('video')">视频</button>
              <button type="button" class="search-session-tab" :class="{ 'is-active': searchTab === 'user' }" @click="selectSearchTab('user')">用户</button>
              <button type="button" class="search-session-tab" :class="{ 'is-active': searchTab === 'tag' }" @click="selectSearchTab('tag')">标签</button>
            </nav>
            <div v-if="searchTab === 'all' || searchTab === 'video'" class="search-session-sort">
              <label for="search-video-sort">排序</label>
              <select id="search-video-sort" v-model="searchVideoSort">
                <option value="relevance">相关</option>
                <option value="latest">最新</option>
              </select>
            </div>
          </div>
          <p v-if="searchResultError" class="search-session-msg search-session-msg--err">{{ searchResultError }}</p>
          <div v-if="searchTab === 'all' && searchAllUserStrip.length" class="search-user-strip">
            <div class="search-user-strip-title">相关用户</div>
            <div class="search-user-strip-scroll search-user-strip-scroll--wide-cards">
              <div
                v-for="u in searchAllUserStrip"
                :key="'su-' + u.id"
                class="search-user-card-wide"
              >
                <button type="button" class="search-user-card-wide__main" @click="onAuthorAvatarClick($event, u.id)">
                  <img class="search-user-card-wide__avatar" :src="u.avatarUrl || profileAvatar" alt="" @error="(e) => (e.target.src = profileAvatar)" />
                  <div class="search-user-card-wide__body">
                    <span class="search-user-card-wide__name">{{ u.nickname }}</span>
                    <p v-if="u.introduction" class="search-user-card-wide__intro">{{ u.introduction }}</p>
                    <p v-else class="search-user-card-wide__intro search-user-card-wide__intro--muted">暂无简介</p>
                    <div class="search-user-card-wide__meta">
                      <span>粉丝 {{ formatHomeShortStat(u.fans) }}</span>
                      <span class="search-user-card-wide__meta-dot">·</span>
                      <span>获赞 {{ formatHomeShortStat(u.likeCount ?? 0) }}</span>
                    </div>
                  </div>
                </button>
                <button
                  v-if="isAuthed && !searchUserRowIsSelf(u)"
                  type="button"
                  class="search-user-follow-pill"
                  :class="{ 'search-user-follow-pill--followed': searchUserRowIsFollowed(u) }"
                  @click.stop="toggleSearchUserFollow(u)"
                >
                  {{ searchUserRowIsFollowed(u) ? "已关注" : "关注" }}
                </button>
              </div>
            </div>
          </div>
          <div v-if="searchTab === 'all' || searchTab === 'video' || searchTab === 'tag'" class="search-feed-wrap">
            <div v-if="searchVerticalLoading" class="search-feed-loading">加载中…</div>
            <div v-else-if="!searchFeedVideoList.length && !searchResultError" class="search-feed-empty">
              <p>{{ SEARCH_UI.noResult }}</p>
            </div>
            <div v-else class="search-feed-list">
              <article
                v-for="(card, index) in searchFeedVideoList"
                :key="'sv-' + card.id + '-' + index"
                class="search-feed-slide search-feed-slide--stacked"
                data-search-v-slide
              >
                <div class="search-feed-slide-top">
                  <div class="search-feed-slide-meta-row">
                    <img
                      class="shorts-feed-author-avatar search-feed-slide-avatar"
                      :src="cardAuthorAvatar(card, index)"
                      alt=""
                      @click.stop="onAuthorAvatarClick($event, card.authorId)"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                    <div class="search-feed-slide-meta-text">
                      <p class="shorts-feed-user shorts-feed-user--search-list">
                        <button
                          v-if="card.authorId != null && card.authorId !== ''"
                          type="button"
                          class="shorts-feed-user-link"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, card.authorId)"
                        >
                          @{{ card.authorNickname || card.author }}
                        </button>
                        <template v-else>@{{ card.authorNickname || card.author }}</template>
                        <span v-if="formatSearchRelTime(card)" class="shorts-feed-time-dot"> · {{ formatSearchRelTime(card) }}</span>
                      </p>
                      <p class="shorts-feed-topic shorts-feed-topic--search-list">{{ card.title }}</p>
                      <p v-if="card.description || parseSearchTagsArray(card).length" class="shorts-feed-desc shorts-feed-desc--search-list">
                        <template v-if="card.description">{{ card.description }}</template>
                        <span v-if="parseSearchTagsArray(card).length" class="search-feed-tag-row">
                          <button
                            v-for="tg in parseSearchTagsArray(card)"
                            :key="'tg-' + card.id + '-' + tg"
                            type="button"
                            class="search-feed-tag-chip"
                            @click.stop="onSearchTagNavigate(tg)"
                          >
                            {{ tg.startsWith("#") ? tg : "#" + tg }}
                          </button>
                        </span>
                      </p>
                    </div>
                  </div>
                </div>
                <div class="search-feed-slide-video-wrap">
                  <div class="search-feed-slide-media" :ref="(el) => searchFeedBindMediaWrap(card.id, el)">
                    <div class="search-feed-player">
                      <div class="search-feed-player__blur" aria-hidden="true">
                        <video
                          :src="resolveVideoUrl(card) || undefined"
                          class="search-feed-video search-feed-video--blur"
                          muted
                          loop
                          playsinline
                          preload="metadata"
                        />
                      </div>
                      <video
                        :ref="(el) => searchFeedBindMainVideo(card.id, el)"
                        :src="resolveVideoUrl(card) || undefined"
                        class="search-feed-video search-feed-video--main"
                        :muted="searchFeedListMuted(card)"
                        loop
                        playsinline
                        preload="metadata"
                        :poster="card.coverUrl || card.avatarUrl"
                        @click="onSearchFeedMainVideoClick(card, index)"
                        @timeupdate="onSearchFeedListTimeUpdate($event, card.id)"
                        @loadedmetadata="onSearchFeedListLoadedMetadata($event, card.id)"
                        @seeked="onSearchFeedListSeeked"
                        @play="onSearchFeedListVideoPlay($event, card.id)"
                        @pause="onSearchFeedListVideoPause($event, card.id)"
                      />
                    </div>
                    <div class="search-feed-overlay-actions">
                      <button
                        type="button"
                        class="search-feed-stat search-feed-stat--btn"
                        :class="{ 'search-feed-stat--active': card.isLike }"
                        title="点赞"
                        @click.stop="onSearchFeedLikeClick(card, $event)"
                      >
                        <i :class="card.isLike ? 'ri-heart-fill' : 'ri-heart-line'" aria-hidden="true"></i>{{ formatHomeShortStat(card.likeCount) }}
                      </button>
                      <button
                        type="button"
                        class="search-feed-stat search-feed-stat--btn"
                        title="评论"
                        @click.stop="openSearchListCommentPanel(card, $event)"
                      >
                        <i class="ri-chat-3-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.commentCount) }}
                      </button>
                    </div>
                    <div class="search-feed-card-dock" @click.stop>
                      <input
                        type="range"
                        class="shorts-feed-scrub"
                        min="0"
                        :max="searchFeedListScrubMax(card.id)"
                        step="0.1"
                        :disabled="!searchFeedListDurationReady(card.id)"
                        :value="searchFeedListScrubDisplay(card.id)"
                        @input="onSearchFeedListScrubInput($event, card)"
                      />
                      <div class="shorts-feed-bottom-bar__row">
                        <span class="shorts-feed-time">
                          {{ formatShortsTime(searchFeedListCurrent(card.id)) }} / {{ formatShortsTime(searchFeedListDuration(card.id)) }}
                        </span>
                        <div class="shorts-feed-tools">
                          <button
                            type="button"
                            class="shorts-feed-tool-btn"
                            title="小窗播放（画中画）"
                            @click="searchFeedListRequestPip(card, $event)"
                          >
                            <i class="ri-picture-in-picture-line"></i>
                          </button>
                          <button type="button" class="shorts-feed-tool-btn" title="静音" @click="searchFeedListToggleMute(card, $event)">
                            <i :class="searchFeedListMuted(card) ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                          </button>
                          <input
                            type="range"
                            class="shorts-feed-vol-range"
                            min="0"
                            max="1"
                            step="0.05"
                            :value="searchFeedListVolValue(card)"
                            title="音量"
                            aria-label="音量"
                            @input="onSearchFeedListVolumeInput($event, card)"
                          />
                          <button type="button" class="shorts-feed-tool-btn" title="全屏" @click="searchFeedListRequestFullscreen(card, $event)">
                            <i class="ri-fullscreen-line"></i>
                          </button>
                          <button type="button" class="shorts-feed-tool-btn" title="转发" aria-label="转发" @click="onSearchFeedForwardClick(card, $event)">
                            <i class="ri-share-forward-line"></i>
                          </button>
                          <button
                            type="button"
                            class="shorts-feed-tool-btn shorts-feed-tool-btn--playpause"
                            :title="searchFeedListShowPaused(card) ? '播放' : '暂停'"
                            @click="searchFeedListTogglePlay(card, $event)"
                          >
                            <i :class="searchFeedListShowPaused(card) ? 'ri-play-fill' : 'ri-pause-fill'"></i>
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </article>
              <p v-if="searchVerticalLoadingMore" class="search-feed-more">加载更多…</p>
            </div>
          </div>
          <div v-if="searchTab === 'user'" class="search-user-page">
            <div v-if="searchUserLoading" class="search-feed-loading">加载中…</div>
            <div v-else-if="!searchUserRows.length && !searchResultError" class="search-feed-empty">
              <p>{{ SEARCH_UI.noResult }}</p>
            </div>
            <ul v-else class="search-user-list">
              <li
                v-for="u in searchUserRows"
                :key="'usr-' + u.id"
                class="search-user-row"
                role="button"
                tabindex="0"
                :title="'查看「' + (u.nickname || '用户') + '」的主页'"
                @click="onSearchUserRowActivate(u, $event)"
                @keydown.enter.prevent="onSearchUserRowActivate(u, $event)"
                @keydown.space.prevent="onSearchUserRowActivate(u, $event)"
              >
                <img :src="u.avatarUrl || profileAvatar" alt="" @error="(e) => (e.target.src = profileAvatar)" />
                <div class="search-user-row-main">
                  <strong>{{ u.nickname }}</strong>
                  <span class="search-user-row-sub">{{ u.introduction }}</span>
                  <span class="search-user-row-meta">粉丝 {{ formatHomeShortStat(u.fans) }} · 获赞 {{ formatHomeShortStat(u.likeCount ?? 0) }}</span>
                </div>
                <button
                  v-if="isAuthed && !searchUserRowIsSelf(u)"
                  type="button"
                  class="search-user-follow-pill"
                  :class="{ 'search-user-follow-pill--followed': searchUserRowIsFollowed(u) }"
                  @click.stop="toggleSearchUserFollow(u)"
                >
                  {{ searchUserRowIsFollowed(u) ? "已关注" : "关注" }}
                </button>
              </li>
            </ul>
            <p v-if="searchUserLoadingMore" class="search-feed-more">加载更多…</p>
          </div>
        </section>
</template>
