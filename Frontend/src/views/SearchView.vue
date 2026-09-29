<script setup>
/**
 * 搜索结果页（tabs/排序/相关用户/竖滑视频流/用户列表）。
 * 过渡期：状态取自 searchStore，交互函数经 App.vue provide 的 searchBridge 注入；
 * 后续将函数逐一迁入 store / 本组件，最终撤掉桥接。
 */
import { computed } from "vue";
import { storeToRefs } from "pinia";
import { useSearchStore, searchBridgeHooks, SEARCH_UI } from "../stores/searchStore";
import { useAuthStore } from "../stores/authStore";
import { useCommentPanelStore } from "../stores/commentPanelStore";
import { useNavigationStore } from "../stores/navigationStore";
import { useProfileStore } from "../stores/profileStore";
import { useShareStore } from "../stores/shareStore";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile";
import {
  resolveVideoUrl,
  cardAuthorAvatar,
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

/** 标签导航/用户行操作/点赞/进全屏改为直连 store 或 searchBridgeHooks（App 注册），不再经 inject */
const navigation = useNavigationStore();
const profile = useProfileStore();
const share = useShareStore();
const sb = searchBridgeHooks;

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
  useCommentPanelStore().shortsCommentPanelOpen = false;
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
                <button type="button" class="search-user-card-wide__main" @click="profile.openByAuthorId(u.id)">
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
                  v-if="isAuthed && !sb.searchUserRowIsSelf(u)"
                  type="button"
                  class="search-user-follow-pill"
                  :class="{ 'search-user-follow-pill--followed': sb.searchUserRowIsFollowed(u) }"
                  @click.stop="sb.toggleSearchUserFollow(u)"
                >
                  {{ sb.searchUserRowIsFollowed(u) ? "已关注" : "关注" }}
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
                      @click.stop="profile.openByAuthorId(card.authorId)"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                    <div class="search-feed-slide-meta-text">
                      <p class="shorts-feed-user shorts-feed-user--search-list">
                        <button
                          v-if="card.authorId != null && card.authorId !== ''"
                          type="button"
                          class="shorts-feed-user-link"
                          title="查看作者主页"
                          @click.stop="profile.openByAuthorId(card.authorId)"
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
                            @click.stop="navigation.navigateToTag(tg)"
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
                        @click="searchStore.openSearchExpand(card, index)"
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
                        @click.stop="sb.onSearchFeedLikeClick(card, $event)"
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
                          <button type="button" class="shorts-feed-tool-btn" title="转发" aria-label="转发" @click="share.openForwardVideo(card)">
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
                @click="sb.onSearchUserRowActivate(u, $event)"
                @keydown.enter.prevent="sb.onSearchUserRowActivate(u, $event)"
                @keydown.space.prevent="sb.onSearchUserRowActivate(u, $event)"
              >
                <img :src="u.avatarUrl || profileAvatar" alt="" @error="(e) => (e.target.src = profileAvatar)" />
                <div class="search-user-row-main">
                  <strong>{{ u.nickname }}</strong>
                  <span class="search-user-row-sub">{{ u.introduction }}</span>
                  <span class="search-user-row-meta">粉丝 {{ formatHomeShortStat(u.fans) }} · 获赞 {{ formatHomeShortStat(u.likeCount ?? 0) }}</span>
                </div>
                <button
                  v-if="isAuthed && !sb.searchUserRowIsSelf(u)"
                  type="button"
                  class="search-user-follow-pill"
                  :class="{ 'search-user-follow-pill--followed': sb.searchUserRowIsFollowed(u) }"
                  @click.stop="sb.toggleSearchUserFollow(u)"
                >
                  {{ sb.searchUserRowIsFollowed(u) ? "已关注" : "关注" }}
                </button>
              </li>
            </ul>
            <p v-if="searchUserLoadingMore" class="search-feed-more">加载更多…</p>
          </div>
        </section>
</template>

<style scoped>
.search-session {
  width: min(1200px, 75vw);
  margin-left: 0;
  margin-right: auto;
  align-self: flex-start;
  padding: var(--space-12) var(--space-16) var(--space-48);
  min-height: 100%;
  box-sizing: border-box;
}

@media (max-width: 900px) {
  .search-session {
    width: min(96vw, 100%);
    margin-left: 0;
    margin-right: auto;
  }
}

.search-session-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-12);
  margin-bottom: var(--space-12);
}

.search-session-tabs {
  display: flex;
  gap: var(--space-6);
  flex-wrap: wrap;
}

.search-session-tab {
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--text);
  border-radius: var(--radius-full);
  padding: var(--space-6) var(--space-14);
  font-size: var(--font-size-13);
  cursor: pointer;
  box-shadow: var(--shadow-xs);
}

.search-session-tab.is-active {
  background: var(--accent);
  color: var(--color-text-inverse);
  border-color: var(--accent);
}

.search-session-sort {
  display: flex;
  align-items: center;
  gap: var(--space-8);
  font-size: var(--font-size-13);
  color: var(--muted);
}

.search-session-sort select {
  border-radius: var(--radius-lg);
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--text);
  padding: var(--space-4) var(--space-8);
}

.search-session-msg {
  font-size: var(--font-size-13);
  margin: 0 0 var(--space-12);
}

.search-session-msg--err {
  color: var(--red-600);
}

.search-user-strip {
  margin-bottom: var(--space-16);
}

.search-user-strip-title {
  font-size: var(--font-size-12);
  color: var(--muted);
  margin-bottom: var(--space-8);
}

.search-user-strip-scroll {
  display: flex;
  gap: var(--space-10);
  overflow-x: auto;
  padding-bottom: var(--space-4);
}

/* 综合：相关用户 — 约半宽、紧凑横排信息卡，可横向滑动 */
.search-user-strip-scroll--wide-cards {
  align-items: stretch;
  max-width: min(1348px, 100%);
}

.search-user-card-wide {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-10);
  flex: 0 0 calc(50% - 6px);
  width: calc(50% - 6px);
  max-width: min(420px, calc(50% - 6px));
  min-width: 0;
  min-height: 0;
  padding: var(--space-12) var(--space-14);
  border-radius: var(--radius-3xl);
  border: 1px solid var(--line);
  background: var(--panel);
  box-shadow: 0 2px 12px rgb(var(--slate-900-rgb) / 0.06);
  flex-shrink: 0;
  box-sizing: border-box;
}

.search-user-card-wide:hover {
  border-color: var(--color-border-strong);
  box-shadow: 0 6px 20px rgb(var(--slate-900-rgb) / 0.08);
}

.search-user-card-wide__main {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: flex-start;
  gap: var(--space-12);
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--text);
}

.search-user-card-wide__main:hover .search-user-card-wide__name {
  color: var(--blue-600);
}

.search-user-card-wide__avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--line);
  flex-shrink: 0;
}

.search-user-card-wide__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.search-user-card-wide__name {
  font-size: var(--font-size-15);
  font-weight: 600;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-user-card-wide__intro {
  margin: 0;
  font-size: var(--font-size-13);
  color: var(--muted);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.search-user-card-wide__intro--muted {
  color: var(--color-text-faint);
  font-style: italic;
}

.search-user-card-wide__meta {
  font-size: var(--font-size-12);
  color: var(--muted);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-4);
}

.search-user-card-wide__meta-dot {
  opacity: 0.65;
  user-select: none;
}

.search-feed-loading,
.search-feed-empty,
.search-feed-more {
  text-align: center;
  color: var(--muted);
  font-size: var(--font-size-14);
  padding: var(--space-24) var(--space-12);
}

.search-feed-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-20);
}

.search-feed-slide {
  border-radius: var(--radius-4xl);
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  box-shadow: 0 8px 28px rgb(var(--slate-900-rgb) / 0.08);
  scroll-snap-align: start;
}

.search-feed-slide-top {
  padding: var(--space-12) var(--space-14) var(--space-10);
  flex-shrink: 0;
}

.search-feed-slide-meta-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-12);
}

.search-feed-slide-meta-text {
  flex: 1;
  min-width: 0;
}

.search-feed-slide-video-wrap {
  width: 100%;
  max-width: min(1348px, 100%);
  max-height: min(797px, 72vh);
  aspect-ratio: 1348 / 797;
  margin: 0 auto 0 0;
  align-self: flex-start;
}

.search-feed-slide-media {
  position: relative;
  width: 100%;
  height: 100%;
}

.search-feed-slide-video-wrap .search-feed-overlay-actions {
  z-index: 4;
}

/* 横向 16:9；竖屏视频居中 contain，两侧由模糊层填充（短视频常见做法） */
.search-feed-player {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  max-height: min(52vh, 560px);
  background: #0f1419;
  border-radius: 0 0 var(--radius-2xl) var(--radius-2xl);
  overflow: hidden;
}

.search-feed-player__blur {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.search-feed-video--blur {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 120%;
  height: 120%;
  min-width: 100%;
  min-height: 100%;
  transform: translate(-50%, -50%) scale(1.08);
  object-fit: cover;
  filter: blur(32px) saturate(1.15);
  opacity: 0.95;
}

.search-feed-video--main {
  position: relative;
  z-index: 1;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: transparent;
}

.search-feed-overlay-actions {
  position: absolute;
  right: 10px;
  bottom: 50%;
  transform: translateY(50%);
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
  align-items: center;
  pointer-events: none;
  /* 避免子元素 button 使用 color:inherit 时吃到卡片上的深色正文色 */
  color: var(--white);
}

.search-feed-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-12);
  color: var(--color-text-inverse);
  text-shadow: 0 1px 3px rgb(var(--black-rgb) / 0.75);
}

.search-feed-stat i {
  font-size: var(--font-size-24);
  color: var(--color-text-inverse);
  -webkit-text-fill-color: var(--white);
}

/* 与主短视频右侧操作区一致：默认白、点赞实心高红 */
.search-feed-overlay-actions .search-feed-stat--btn i {
  color: var(--color-text-inverse);
  -webkit-text-fill-color: var(--white);
}

.search-feed-overlay-actions .search-feed-stat--btn.search-feed-stat--active,
.search-feed-overlay-actions .search-feed-stat--btn.search-feed-stat--active i {
  color: var(--red-hot);
  -webkit-text-fill-color: var(--red-hot);
}

.search-feed-stat--btn {
  pointer-events: auto;
  cursor: pointer;
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  color: var(--color-text-inverse);
  -webkit-text-fill-color: var(--white);
  text-align: center;
}

.search-feed-stat--btn:hover {
  opacity: 0.92;
}

.search-feed-stat--active {
  color: var(--red-hot);
}

.search-user-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-10);
}

.search-user-row {
  display: flex;
  align-items: center;
  gap: var(--space-12);
  padding: var(--space-12);
  border-radius: var(--radius-2xl);
  background: var(--panel);
  border: 1px solid var(--line);
  box-shadow: 0 2px 8px rgb(var(--slate-900-rgb) / 0.05);
  cursor: pointer;
  list-style: none;
}

.search-user-row:focus-visible {
  outline: 2px solid var(--sky-500);
  outline-offset: 2px;
}

.search-user-row-meta {
  display: block;
  margin-top: var(--space-2);
  font-size: var(--font-size-12);
  color: var(--muted);
}

.search-user-follow-pill {
  flex-shrink: 0;
  margin-left: auto;
  padding: var(--space-6) var(--space-14);
  border-radius: var(--radius-full);
  border: 1px solid var(--red-hot);
  background: rgb(var(--red-hot-rgb) / 0.08);
  color: var(--red-hot);
  font-size: var(--font-size-13);
  font-weight: 600;
  line-height: 1.2;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease,
    border-color 0.15s ease;
}

.search-user-follow-pill:hover {
  background: rgb(var(--red-hot-rgb) / 0.14);
}

.search-user-follow-pill--followed {
  border-color: var(--color-border);
  background: var(--color-bg-hover);
  color: var(--color-text-muted);
}

.search-user-follow-pill--followed:hover {
  background: var(--slate-200);
}

.search-user-row img {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  object-fit: cover;
}

.search-user-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.search-user-row-sub {
  font-size: var(--font-size-13);
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
