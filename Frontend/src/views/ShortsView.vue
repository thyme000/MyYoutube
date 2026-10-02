<script setup>
/**
 * 短视频域：沉浸 feed（短视频/关注/全屏队列）、共享评论面板、搜索全屏放大层。
 * 状态与动作全部直接取自各域 store；本组件只保留 DOM ref 与本地交互处理。
 */
import { ref, watch } from "vue";
import { storeToRefs } from "pinia";
import DynamicPlayer from "../components/DynamicPlayer.vue";
import { registerShortsFeedPlayerRefs, useShortsStore } from "../stores/shortsStore";
import { registerSearchExpandRefs, useSearchStore } from "../stores/searchStore";
import { useAuthStore } from "../stores/authStore";
import { useCommentPanelStore } from "../stores/commentPanelStore";
import { useHomeStore } from "../stores/homeStore";
import { useNavigationStore } from "../stores/navigationStore";
import { useProfileStore } from "../stores/profileStore";
import { useShareStore } from "../stores/shareStore";
import ChatEmojiPicker from "../components/chat/ChatEmojiPicker.vue";
import {
  formatCommentTime as formatDetailCommentTime,
  insertGlyphIntoCommentDraft,
  isBlockedComment,
} from "../utils/comment.js";
import {
  applyAvatarFallback,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
} from "../utils/userProfile";
import {
  cardAuthorAvatar,
  formatSearchRelTime,
  formatShortsTime,
  parseSearchTagsArray,
} from "../utils/videoCard.js";

const shortsStore = useShortsStore();
const searchStore = useSearchStore();
const commentPanelStore = useCommentPanelStore();
const profileStore = useProfileStore();
const authStore = useAuthStore();
const homeStore = useHomeStore();
const navigationStore = useNavigationStore();
const shareStore = useShareStore();

const {
  activeCommentVideo,
  activeCommentVideoAllowsComment,
  feedScope,
  followSubscribedFilterAuthorId,
  profileFeedOpen,
  selfVideoActionOpen,
  shortsDurationReady,
  shortsFeedAuthorAvatar,
  shortsFeedAuthorName,
  shortsFeedCurrent,
  shortsFeedIsDynamic,
  shortsFeedCurrentTime,
  shortsFeedDescText,
  shortsFeedDuration,
  shortsFeedIsPortrait,
  shortsFeedLikedDisplay,
  shortsFeedMetaTimeText,
  shortsFeedMuted,
  shortsFeedPaused,
  shortsFeedTitleText,
  shortsFeedVideoBuffering,
  shortsFeedVideoSrc,
  shortsFeedVolume,
  shortsIsFollowedCurrent,
  shortsIsSelfCurrent,
  shortsPanelCommentDraft,
  shortsPanelCommentSubmitting,
  shortsPanelCommentsLoading,
  shortsPanelExpandedRoots,
  shortsPanelReplyTarget,
  shortsPanelThreadByRootId,
  shortsPanelTopComments,
  shortsScrubDisplayValue,
  shortsScrubMax,
} = storeToRefs(shortsStore);

const {
  searchActive,
  searchExpandCard,
  searchExpandCurrentTime,
  searchExpandDuration,
  searchExpandDurationReady,
  searchExpandIndex,
  searchExpandIsFollowed,
  searchExpandIsPortrait,
  searchExpandIsSelf,
  searchExpandLikedDisplay,
  searchExpandMuted,
  searchExpandOpen,
  searchExpandPaused,
  searchExpandScrubDisplayValue,
  searchExpandScrubMax,
  searchExpandVolume,
} = storeToRefs(searchStore);

const { shortsCommentPanelOpen, searchListCommentOpen, searchExpandCommentOpen } =
  storeToRefs(commentPanelStore);
const {
  open: showProfileCenter,
  followingRows,
  followingLoading,
  followingHasMore,
} = storeToRefs(profileStore);
const {
  userId: currentUserId,
  isAuthed,
  selfAvatarUrl: topbarAvatarSrc,
} = storeToRefs(authStore);
const { loading } = storeToRefs(homeStore);
const { activeMenuKey } = storeToRefs(navigationStore);

/** 连播动态条目的详情(时间轴), 随当前行切换按需拉取 */
const dynamicDetail = ref(null);
watch(
  () => [shortsFeedIsDynamic.value, shortsFeedCurrent.value?.id],
  async ([isDynamic, id]) => {
    const wanted = String(id ?? "");
    if (!isDynamic || !wanted) {
      dynamicDetail.value = null;
      return;
    }
    // 换条先清空: 否则上一张的详情会挂在新的 :key 上播一帧
    if (String(dynamicDetail.value?.id ?? "") !== wanted) {
      dynamicDetail.value = null;
    }
    const detail = await ensureDynamicDetail(shortsFeedCurrent.value);
    // 拉取期间又切条了则丢弃，避免把旧详情写回去
    if (String(shortsFeedCurrent.value?.id ?? "") !== wanted) {
      return;
    }
    dynamicDetail.value = detail;
  },
  { immediate: true }
);

function onImmersiveDynamicReady(payload) {
  shortsStore.onShortsFeedDynamicReady(payload?.durationSec, payload?.canvasType);
}

function onImmersiveDynamicTimeUpdate(sec) {
  shortsStore.onShortsFeedDynamicTimeUpdate(sec);
}

// store action 与 store 绑定，可直接解构；带括号调用的函数型接口（如 searchExpandVideoUrl）
// 在 store 里就是 action，不能写成 getter
const {
  closeProfileFeed,
  closeShortsCommentPanel,
  commentDisplayNickname,
  isCommentLikeBusy,
  loadMoreShortsPanelComments,
  onDeleteShortsPanelComment,
  onShortsDislikeClick,
  onShortsFeedFgCanPlay,
  onShortsFeedFgDurationChange,
  onShortsFeedFgEnded,
  ensureDynamicDetail,
  onShortsFeedFgLoadStart,
  onShortsFeedFgLoadedMetadata,
  onShortsFeedFgMediaError,
  onShortsFeedFgPause,
  onShortsFeedFgPlay,
  onShortsFeedFgSeeked,
  onShortsFeedFgTimeUpdate,
  onShortsFeedTouchEnd,
  onShortsFeedTouchStart,
  onShortsFeedWheel,
  onShortsForwardClick,
  onShortsLikeClick,
  onShortsScrubInput,
  onShortsVolumeInput,
  requestDeleteCurrentSelfVideo,
  selectFollowSidebarAll,
  selectFollowSidebarAuthor,
  setShortsPanelReplyTarget,
  shortsFeedNext,
  shortsFeedPrev,
  shortsDownloadCurrent,
  shortsPanelCommentHasMore,
  shortsRequestFullscreen,
  shortsRequestPip,
  shortsToggleMute,
  shortsTogglePlay,
  submitCommentLike,
  submitShortsPanelComment,
  toggleCurrentSelfVideoAllowComment,
  toggleCurrentSelfVideoVisibility,
  toggleSelfVideoActionMenu,
  toggleShortsCommentPanel,
  toggleShortsFollow,
  toggleShortsPanelThread,
} = shortsStore;

const {
  closeSearchExpand,
  onSearchExpandDislikeClick,
  onSearchExpandFgPause,
  onSearchExpandFgPlay,
  onSearchExpandFgSeeked,
  onSearchExpandForwardClick,
  onSearchExpandLikeClick,
  onSearchExpandScrubInput,
  onSearchExpandShareClick,
  onSearchExpandVideoLoadedMetadata,
  onSearchExpandVideoTimeUpdate,
  onSearchExpandVolumeInput,
  searchExpandNext,
  searchExpandPrev,
  searchExpandRequestFullscreen,
  searchExpandRequestPip,
  searchExpandToggleMute,
  searchExpandVideoUrl,
  toggleSearchExpandCommentPanel,
  toggleSearchExpandFollow,
  toggleSearchExpandPlay,
} = searchStore;

/** 模板沿用的名字与 store 成员名不一致，这里保留原名薄封装 */
const loadFollowingNextPage = () => profileStore.loadRelationNextPage("following");
const onSearchTagNavigate = (tag) => navigationStore.navigateToTag(tag);
const openCommentReportModal = (comment) => shareStore.openCommentReport(comment);
const onTopbarAvatarError = (evt) => applyAvatarFallback(evt, profileAvatar);

/**
 * 模板 ref= 绑定的元素对象必须留在本组件；播放器与放大层要命令式控制，
 * 故把 ref 对象注册给 store（store 侧只经 playerRefs 读写，不持有元素本身）。
 */
const shortsFeedFgRef = ref(null);
const shortsFeedBgRef = ref(null);
const shortsFeedCenterRef = ref(null);
/** 动态条目用受控 DynamicPlayer 替代两个 <video>，命令入口同样注册给 store */
const shortsFeedDynamicRef = ref(null);
registerShortsFeedPlayerRefs({
  fg: shortsFeedFgRef,
  bg: shortsFeedBgRef,
  center: shortsFeedCenterRef,
  dynamic: shortsFeedDynamicRef,
});

const searchExpandPlayerRef = ref(null);
const searchExpandBlurRef = ref(null);
const searchExpandStageRef = ref(null);
registerSearchExpandRefs({
  main: searchExpandPlayerRef,
  blur: searchExpandBlurRef,
  stage: searchExpandStageRef,
});

const shortsPanelCommentInputRef = ref(null);

function onAuthorAvatarClick(event, authorId) {
  event?.stopPropagation?.();
  profileStore.openByAuthorId(authorId);
}

function onShortsPanelCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(shortsPanelCommentDraft, shortsPanelCommentInputRef, glyph);
}

function onShortsPanelCommentUserClick(ev, userId) {
  if (userId == null || userId === "") {
    return;
  }
  onAuthorAvatarClick(ev, userId);
}
</script>

<template>
        <Teleport to="body">
          <div
            v-if="searchActive && searchExpandOpen && searchExpandCard"
            class="search-expand-overlay search-expand-overlay--fullscreen"
            role="dialog"
            aria-modal="true"
            aria-label="全屏播放"
          >
            <div class="search-expand-shorts-shell">
              <div ref="searchExpandStageRef" class="shorts-feed-center search-expand-shorts-center">
                <button type="button" class="search-expand-close" aria-label="关闭" @click="closeSearchExpand">
                  <i class="ri-close-line"></i>
                </button>
                <div class="shorts-feed-stage-wrap">
                  <div
                    class="short-video-stage short-video-stage--feed"
                    :class="{
                      'short-video-stage--portrait': searchExpandIsPortrait,
                      'short-video-stage--landscape': !searchExpandIsPortrait,
                    }"
                  >
                    <video
                      ref="searchExpandBlurRef"
                      class="short-video-bg"
                      muted
                      loop
                      playsinline
                      preload="metadata"
                      :src="searchExpandVideoUrl()"
                    />
                    <video
                      ref="searchExpandPlayerRef"
                      class="short-video-fg"
                      playsinline
                      preload="metadata"
                      :poster="searchExpandCard?.coverUrl || searchExpandCard?.avatarUrl"
                      :src="searchExpandVideoUrl()"
                      @click="toggleSearchExpandPlay"
                      @timeupdate="onSearchExpandVideoTimeUpdate"
                      @loadedmetadata="onSearchExpandVideoLoadedMetadata"
                      @seeked="onSearchExpandFgSeeked"
                      @play="onSearchExpandFgPlay"
                      @pause="onSearchExpandFgPause"
                    />
                    <div
                      v-show="searchExpandPaused"
                      class="shorts-feed-pause-overlay"
                      @click.stop="toggleSearchExpandPlay"
                    >
                      <span class="shorts-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="shorts-feed-nav-side" aria-label="切换上一条下一条">
                    <button type="button" class="shorts-feed-nav-line" title="上一条" @click.stop="searchExpandPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="shorts-feed-nav-line" title="下一条" @click.stop="searchExpandNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="shorts-feed-ui">
                  <div class="shorts-feed-caption">
                    <p class="shorts-feed-user">
                      <button
                        v-if="searchExpandCard?.authorId != null && searchExpandCard?.authorId !== ''"
                        type="button"
                        class="shorts-feed-user-link"
                        title="查看作者主页"
                        @click.stop="onAuthorAvatarClick($event, searchExpandCard.authorId)"
                      >
                        @{{ searchExpandCard.authorNickname || searchExpandCard.author }}
                      </button>
                      <template v-else>@{{ searchExpandCard.authorNickname || searchExpandCard.author }}</template>
                      <span v-if="formatSearchRelTime(searchExpandCard)" class="shorts-feed-time-dot"> · {{ formatSearchRelTime(searchExpandCard) }}</span>
                    </p>
                    <p class="shorts-feed-topic">{{ searchExpandCard.title }}</p>
                    <p class="shorts-feed-desc">
                      {{ searchExpandCard.description }}
                      <span v-if="parseSearchTagsArray(searchExpandCard).length" class="search-feed-tag-row">
                        <button
                          v-for="tg in parseSearchTagsArray(searchExpandCard)"
                          :key="'extg-' + searchExpandCard.id + '-' + tg"
                          type="button"
                          class="search-feed-tag-chip"
                          @click.stop="onSearchTagNavigate(tg)"
                        >
                          {{ tg.startsWith("#") ? tg : "#" + tg }}
                        </button>
                      </span>
                    </p>
                  </div>
                  <div class="shorts-feed-action-col">
                    <div class="shorts-feed-author-block">
                      <div class="shorts-feed-author-avatar-wrap">
                        <img
                          class="shorts-feed-author-avatar"
                          :src="cardAuthorAvatar(searchExpandCard, searchExpandIndex)"
                          alt=""
                          role="button"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, searchExpandCard.authorId)"
                          @error="(e) => (e.target.src = profileAvatar)"
                        />
                        <button
                          v-if="!searchExpandIsSelf && !searchExpandIsFollowed"
                          type="button"
                          class="shorts-feed-follow-btn"
                          title="关注"
                          aria-label="关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-add-line"></i>
                        </button>
                        <button
                          v-else-if="!searchExpandIsSelf"
                          type="button"
                          class="shorts-feed-follow-btn shorts-feed-follow-btn--followed"
                          title="已关注"
                          aria-label="已关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-check-line"></i>
                        </button>
                      </div>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        :class="{ 'shorts-feed-action-btn--active': searchExpandLikedDisplay }"
                        title="点赞 / 取消赞"
                        aria-label="点赞"
                        @click.stop="onSearchExpandLikeClick"
                      >
                        <i :class="searchExpandLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.likeCount ?? 0 }}</span>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        title="评论"
                        aria-label="评论"
                        @click.stop="toggleSearchExpandCommentPanel"
                      >
                        <i class="ri-chat-3-line"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.commentCount ?? 0 }}</span>
                    </div>
                    <div v-if="!searchExpandIsSelf" class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        title="不感兴趣"
                        aria-label="不感兴趣"
                        @click.stop="onSearchExpandDislikeClick"
                      >
                        <i class="ri-thumb-down-line"></i>
                      </button>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button type="button" class="shorts-feed-action-btn" title="分享" aria-label="分享" @click.stop="onSearchExpandShareClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.shareCount ?? 0 }}</span>
                    </div>
                  </div>
                </div>
                <div class="shorts-feed-bottom-bar shorts-feed-bottom-bar--search-expand">
                  <input
                    type="range"
                    class="shorts-feed-scrub"
                    min="0"
                    :max="searchExpandScrubMax"
                    step="0.1"
                    :disabled="!searchExpandDurationReady"
                    :value="searchExpandScrubDisplayValue"
                    @input="onSearchExpandScrubInput"
                  />
                  <div class="shorts-feed-bottom-bar__row">
                    <span class="shorts-feed-time">
                      {{ formatShortsTime(searchExpandCurrentTime) }} / {{ formatShortsTime(searchExpandDuration) }}
                    </span>
                    <div class="shorts-feed-tools">
                      <button type="button" class="shorts-feed-tool-btn" title="小窗播放（画中画）" @click="searchExpandRequestPip">
                        <i class="ri-picture-in-picture-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-tool-btn" title="静音" @click="searchExpandToggleMute">
                        <i :class="searchExpandMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                      </button>
                      <input
                        type="range"
                        class="shorts-feed-vol-range"
                        min="0"
                        max="1"
                        step="0.05"
                        :value="searchExpandVolume"
                        title="音量"
                        aria-label="音量"
                        @input="onSearchExpandVolumeInput"
                      />
                      <button type="button" class="shorts-feed-tool-btn" title="全屏" @click="searchExpandRequestFullscreen">
                        <i class="ri-fullscreen-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-tool-btn" title="转发" aria-label="转发" @click="onSearchExpandForwardClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <button
                        type="button"
                        class="shorts-feed-tool-btn shorts-feed-tool-btn--playpause"
                        :title="searchExpandPaused ? '播放' : '暂停'"
                        @click="toggleSearchExpandPlay"
                      >
                        <i :class="searchExpandPaused ? 'ri-play-fill' : 'ri-pause-fill'"></i>
                      </button>
                    </div>
                  </div>
                </div>
                </div>
              </div>
            </div>
          </div>
        </Teleport>
        <Teleport to="body">
              <aside
                v-if="shortsCommentPanelOpen || searchExpandCommentOpen || searchListCommentOpen"
                class="shorts-feed-comments-panel shorts-feed-comments-panel--floating"
                aria-label="评论"
                @click.stop
              >
                <div class="shorts-feed-comments-panel-head">
                  <h3>评论 {{ activeCommentVideo?.commentCount != null ? `（${activeCommentVideo.commentCount}）` : "" }}</h3>
                  <button
                    type="button"
                    class="shorts-feed-comments-panel-close"
                    aria-label="关闭评论"
                    @click="closeShortsCommentPanel"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                </div>
                <span v-if="!activeCommentVideoAllowsComment" class="video-detail-comments-note">作者已关闭评论</span>
                <p v-if="shortsPanelReplyTarget" class="video-detail-comment-reply-hint">
                  回复 @{{ commentDisplayNickname(shortsPanelReplyTarget.userId, shortsPanelReplyTarget.nickname) }}
                  <button type="button" class="video-detail-comment-reply-cancel" @click="setShortsPanelReplyTarget(null)">
                    取消
                  </button>
                </p>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input
                    ref="shortsPanelCommentInputRef"
                    v-model="shortsPanelCommentDraft"
                    type="text"
                    class="video-detail-comment-field"
                    :disabled="!isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting"
                    :placeholder="
                      !isAuthed
                        ? '登录后参与评论…'
                        : !activeCommentVideoAllowsComment
                          ? '评论已关闭'
                          : shortsPanelReplyTarget
                            ? `回复 @${commentDisplayNickname(shortsPanelReplyTarget.userId, shortsPanelReplyTarget.nickname)}…`
                            : '添加评论…'
                    "
                    maxlength="500"
                    @keyup.enter.prevent="submitShortsPanelComment"
                  />
                  <div class="video-detail-comment-input-actions">
                    <ChatEmojiPicker
                      :disabled="!isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting"
                      @pick="onShortsPanelCommentEmojiPick"
                    />
                    <button
                      type="button"
                      class="video-detail-comment-send"
                      :disabled="
                        !isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting || !shortsPanelCommentDraft.trim()
                      "
                      @click="submitShortsPanelComment"
                    >
                      {{ shortsPanelCommentSubmitting ? "发送中…" : "发送" }}
                    </button>
                  </div>
                </div>
                <div class="shorts-feed-comments-panel-scroll">
                  <p v-if="shortsPanelCommentsLoading && !shortsPanelTopComments.length" class="video-detail-comments-empty">
                    加载评论中…
                  </p>
                  <ul v-else-if="shortsPanelTopComments.length" class="video-detail-comments-mock">
                    <template v-for="c in shortsPanelTopComments" :key="`sp-top-${c.id}`">
                      <li class="video-detail-comment-item">
                        <img
                          :src="c.avatarUrl || profileAvatar"
                          alt=""
                          class="video-detail-comment-avatar video-detail-comment-avatar--clickable"
                          role="button"
                          tabindex="0"
                          title="查看用户主页"
                          @click.stop="onShortsPanelCommentUserClick($event, c.userId)"
                          @keyup.enter.stop="onShortsPanelCommentUserClick($event, c.userId)"
                          @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                        />
                        <div class="video-detail-comment-body">
                          <div class="video-detail-comment-meta">
                            <button
                              type="button"
                              class="video-detail-comment-user video-detail-comment-user--link"
                              @click.stop="onShortsPanelCommentUserClick($event, c.userId)"
                            >
                              {{ commentDisplayNickname(c.userId, c.nickname) }}
                            </button>
                            <span class="video-detail-comment-time">{{ formatDetailCommentTime(c.createTime) }}</span>
                          </div>
                          <p class="video-detail-comment-text">{{ c.content }}</p>
                          <div class="video-detail-comment-actions">
                            <button
                              type="button"
                              class="video-detail-comment-like-btn"
                              :class="{ 'video-detail-comment-like-btn--active': c.isLike }"
                              :disabled="isCommentLikeBusy(c.id)"
                              :aria-pressed="c.isLike ? 'true' : 'false'"
                              :aria-label="c.isLike ? '取消点赞' : '点赞'"
                              @click="submitCommentLike(c)"
                            >
                              <i
                                :class="c.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                aria-hidden="true"
                              ></i>
                              {{ c.likeCount ?? 0 }}
                            </button>
                            <button
                              v-if="isAuthed && activeCommentVideoAllowsComment"
                              type="button"
                              class="video-detail-comment-action-btn"
                              @click="setShortsPanelReplyTarget(c)"
                            >
                              回复
                            </button>
                            <button
                              v-if="isAuthed && currentUserId != null && String(c.userId) === String(currentUserId)"
                              type="button"
                              class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                              @click="onDeleteShortsPanelComment(c)"
                            >
                              删除
                            </button>
                          <span
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            class="video-detail-comment-actions-spacer"
                          ></span>
                          <button
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            type="button"
                            class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                            title="举报"
                            @click="openCommentReportModal(c)"
                          >
                            <i class="ri-flag-line" aria-hidden="true"></i>举报
                          </button>
                          </div>
                          <button
                            v-if="c.replyCount > 0"
                            type="button"
                            class="video-detail-thread-toggle"
                            @click="toggleShortsPanelThread(c.id)"
                          >
                            {{
                              shortsPanelExpandedRoots[String(c.id)]
                                ? "收起回复"
                                : `展开 ${c.replyCount} 条回复`
                            }}
                          </button>
                          <ul v-if="shortsPanelExpandedRoots[String(c.id)]" class="video-detail-comment-replies">
                            <li
                              v-for="r in shortsPanelThreadByRootId[String(c.id)]?.list || []"
                              :key="`sp-r-${r.id}`"
                              class="video-detail-comment-item video-detail-comment-item--reply"
                            >
                              <img
                                :src="r.avatarUrl || profileAvatar"
                                alt=""
                                class="video-detail-comment-avatar video-detail-comment-avatar--sm video-detail-comment-avatar--clickable"
                                role="button"
                                tabindex="0"
                                title="查看用户主页"
                                @click.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                @keyup.enter.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                              />
                              <div class="video-detail-comment-body">
                                <div class="video-detail-comment-meta">
                                  <button
                                    type="button"
                                    class="video-detail-comment-user video-detail-comment-user--link"
                                    @click.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                  >
                                    {{ commentDisplayNickname(r.userId, r.nickname) }}
                                  </button>
                                  <span
                                    v-if="r.replyNickname"
                                    class="video-detail-comment-arrow"
                                    aria-hidden="true"
                                    title="回复"
                                  >▶</span>
                                  <button
                                    v-if="r.replyNickname && r.replyUserId != null && r.replyUserId !== ''"
                                    type="button"
                                    class="video-detail-comment-at video-detail-comment-at--link"
                                    @click.stop="onShortsPanelCommentUserClick($event, r.replyUserId)"
                                  >
                                    @{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}
                                  </button>
                                  <span v-else-if="r.replyNickname" class="video-detail-comment-at"
                                    >@{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}</span>
                                  <span class="video-detail-comment-time">{{ formatDetailCommentTime(r.createTime) }}</span>
                                </div>
                                <p class="video-detail-comment-text">{{ r.content }}</p>
                                <div class="video-detail-comment-actions">
                                  <button
                                    type="button"
                                    class="video-detail-comment-like-btn"
                                    :class="{ 'video-detail-comment-like-btn--active': r.isLike }"
                                    :disabled="isCommentLikeBusy(r.id)"
                                    :aria-pressed="r.isLike ? 'true' : 'false'"
                                    :aria-label="r.isLike ? '取消点赞' : '点赞'"
                                    @click="submitCommentLike(r)"
                                  >
                                    <i
                                      :class="r.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                      aria-hidden="true"
                                    ></i>
                                    {{ r.likeCount ?? 0 }}
                                  </button>
                                  <button
                                    v-if="isAuthed && activeCommentVideoAllowsComment"
                                    type="button"
                                    class="video-detail-comment-action-btn"
                                    @click="setShortsPanelReplyTarget(r)"
                                  >
                                    回复
                                  </button>
                                  <button
                                    v-if="isAuthed && currentUserId != null && String(r.userId) === String(currentUserId)"
                                    type="button"
                                    class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                                    @click="onDeleteShortsPanelComment(r)"
                                  >
                                    删除
                                  </button>
                                  <span
                                    v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                    class="video-detail-comment-actions-spacer"
                                  ></span>
                                  <button
                                    v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                    type="button"
                                    class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                                    title="举报"
                                    @click="openCommentReportModal(r)"
                                  >
                                    <i class="ri-flag-line" aria-hidden="true"></i>举报
                                  </button>
                                </div>
                              </div>
                            </li>
                            <li
                              v-if="shortsPanelThreadByRootId[String(c.id)]?.loading"
                              class="video-detail-comment-item video-detail-comment-item--reply"
                            >
                              <span class="video-detail-comments-empty">加载回复中…</span>
                            </li>
                          </ul>
                        </div>
                      </li>
                    </template>
                  </ul>
                  <div v-if="shortsPanelCommentHasMore()" class="video-detail-comments-more">
                    <button
                      type="button"
                      class="history-layout-btn"
                      :disabled="shortsPanelCommentsLoading"
                      @click="loadMoreShortsPanelComments"
                    >
                      {{ shortsPanelCommentsLoading ? "加载中…" : "加载更多评论" }}
                    </button>
                  </div>
                </div>
              </aside>
        </Teleport>
        <div
          v-if="profileFeedOpen || (!showProfileCenter && (activeMenuKey === 'immersive' || activeMenuKey === 'subscribed'))"
          class="shorts-feed-shell"
          :class="{ 'shorts-feed-shell--with-sidebar': activeMenuKey === 'subscribed' && !profileFeedOpen }"
        >
          <aside
            v-if="activeMenuKey === 'subscribed' && !profileFeedOpen"
            class="follow-sidebar"
            aria-label="我的关注"
          >
            <div class="follow-sidebar-head">关注的人</div>
            <button
              type="button"
              class="follow-sidebar-row follow-sidebar-row--all"
              :class="{
                'follow-sidebar-row--active': followSubscribedFilterAuthorId == null || followSubscribedFilterAuthorId === '',
              }"
              @click="selectFollowSidebarAll"
            >
              <span class="follow-sidebar-all-icon" aria-hidden="true"><i class="ri-play-list-2-line"></i></span>
              <span class="follow-sidebar-name">全部动态</span>
            </button>
            <div class="follow-sidebar-list">
              <p v-if="!isAuthed" class="follow-sidebar-hint">请先登录</p>
              <template v-else>
                <p v-if="followingLoading && !followingRows.length" class="follow-sidebar-hint">加载中…</p>
                <div
                  v-for="(u, uIdx) in followingRows"
                  :key="`follow-side-${uIdx}-${String(u.id)}`"
                  role="button"
                  tabindex="0"
                  class="follow-sidebar-row"
                  :class="{ 'follow-sidebar-row--active': String(followSubscribedFilterAuthorId) === String(u.id) }"
                  @click="selectFollowSidebarAuthor(u)"
                  @keydown.enter.prevent="selectFollowSidebarAuthor(u)"
                  @keydown.space.prevent="selectFollowSidebarAuthor(u)"
                >
                  <img
                    class="follow-sidebar-avatar"
                    :src="u.avatarDisplaySrc"
                    alt=""
                    loading="lazy"
                    decoding="async"
                    @error="(e) => { const t = e.target; if (!t || t.dataset.fallbackApplied === '1') return; t.dataset.fallbackApplied = '1'; t.src = profileAvatar; }"
                  />
                  <span class="follow-sidebar-name">{{ u.nickName || "用户" }}</span>
                </div>
                <button
                  v-if="followingHasMore"
                  type="button"
                  class="follow-sidebar-more"
                  :disabled="followingLoading"
                  @click="loadFollowingNextPage"
                >
                  {{ followingLoading ? "加载中…" : "加载更多" }}
                </button>
              </template>
            </div>
          </aside>
          <section
            :class="[
              profileFeedOpen ? 'profile-feed-overlay' : 'shorts-feed',
              { 'shorts-feed--follow-main': activeMenuKey === 'subscribed' && !profileFeedOpen },
            ]"
          >
          <button
            v-if="profileFeedOpen"
            type="button"
            class="profile-feed-close"
            :class="{ 'profile-feed-close--comments-open': shortsCommentPanelOpen }"
            :aria-label="feedScope === 'liked' ? '关闭赞过视频连播' : '关闭作品连播'"
            @click="closeProfileFeed"
          >
            <i class="ri-close-line"></i>
          </button>
          <div v-if="loading && !profileFeedOpen" class="shorts-feed-loading">
            {{ activeMenuKey === "subscribed" ? "正在加载关注动态…" : "正在加载沉浸内容…" }}
          </div>
          <template v-else-if="shortsFeedVideoSrc || shortsFeedIsDynamic">
            <div class="shorts-feed-body" :class="{ 'shorts-feed-body--comments-open': shortsCommentPanelOpen }">
              <div class="shorts-feed-player-column">
              <div
                ref="shortsFeedCenterRef"
                class="shorts-feed-center"
                @wheel.prevent="onShortsFeedWheel"
                @touchstart="onShortsFeedTouchStart"
                @touchend="onShortsFeedTouchEnd"
              >
                <div class="shorts-feed-stage-wrap">
                  <div
                    class="short-video-stage short-video-stage--feed"
                    :class="{
                      'short-video-stage--portrait': shortsFeedIsPortrait,
                      'short-video-stage--landscape': !shortsFeedIsPortrait,
                    }"
                  >
                    <DynamicPlayer
                      v-if="shortsFeedIsDynamic && dynamicDetail"
                      ref="shortsFeedDynamicRef"
                      :key="dynamicDetail.id"
                      class="shorts-feed-dynamic"
                      :dynamic="dynamicDetail"
                      :loop="false"
                      controlled
                      :paused="shortsFeedPaused"
                      :muted="shortsFeedMuted"
                      :volume="shortsFeedVolume"
                      @ended="onShortsFeedFgEnded"
                      @durationchange="onImmersiveDynamicReady"
                      @timeupdate="onImmersiveDynamicTimeUpdate"
                      @request-toggle-play="shortsTogglePlay"
                      @request-toggle-mute="shortsToggleMute"
                    />
                    <video
                      v-if="!shortsFeedIsDynamic"
                      :key="shortsFeedCurrent?.id"
                      ref="shortsFeedBgRef"
                      class="short-video-bg"
                      :data-play-token="shortsFeedPlayToken"
                      muted
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                    ></video>
                    <video
                      v-if="!shortsFeedIsDynamic"
                      :key="shortsFeedCurrent?.id"
                      ref="shortsFeedFgRef"
                      class="short-video-fg"
                      :data-play-token="shortsFeedPlayToken"
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                      @click="shortsTogglePlay"
                      @loadstart="onShortsFeedFgLoadStart"
                      @canplay="onShortsFeedFgCanPlay"
                      @error="onShortsFeedFgMediaError"
                      @loadedmetadata="onShortsFeedFgLoadedMetadata"
                      @durationchange="onShortsFeedFgDurationChange"
                      @timeupdate="onShortsFeedFgTimeUpdate"
                      @play="onShortsFeedFgPlay"
                      @pause="onShortsFeedFgPause"
                      @seeked="onShortsFeedFgSeeked"
                      @ended="onShortsFeedFgEnded"
                    ></video>
                    <div
                      v-show="shortsFeedVideoBuffering && shortsFeedVideoSrc"
                      class="video-player-loading video-player-loading--shorts"
                      aria-live="polite"
                      aria-busy="true"
                    >
                      <div class="video-player-loading__shimmer video-player-loading__shimmer--shorts" aria-hidden="true" />
                      <div class="video-player-loading__inner">
                        <span class="video-player-loading__ring video-player-loading__ring--shorts" />
                        <span class="video-player-loading__text video-player-loading__text--shorts">正在加载</span>
                      </div>
                    </div>
                    <div
                      v-show="shortsFeedPaused"
                      class="shorts-feed-pause-overlay"
                      @click.stop="shortsTogglePlay"
                    >
                      <span class="shorts-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="shorts-feed-nav-side" aria-label="切换视频">
                    <button type="button" class="shorts-feed-nav-line" title="上一条" @click.stop="shortsFeedPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="shorts-feed-nav-line" title="下一条" @click.stop="shortsFeedNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="shorts-feed-ui">
                    <div class="shorts-feed-caption">
                      <p class="shorts-feed-user">
                        <button
                          v-if="shortsFeedCurrent?.authorId != null && shortsFeedCurrent?.authorId !== ''"
                          type="button"
                          class="shorts-feed-user-link"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, shortsFeedCurrent.authorId)"
                        >
                          @{{ shortsFeedAuthorName }}
                        </button>
                        <template v-else>@{{ shortsFeedAuthorName }}</template>
                        <span v-if="shortsFeedMetaTimeText" class="shorts-feed-time-dot"> · {{ shortsFeedMetaTimeText }}</span>
                      </p>
                      <p class="shorts-feed-topic">{{ shortsFeedTitleText }}</p>
                      <p class="shorts-feed-desc">
                        {{ shortsFeedDescText }}
                        <span v-if="parseSearchTagsArray(shortsFeedCurrent).length" class="search-feed-tag-row search-feed-tag-row--shorts-overlay">
                          <button
                            v-for="tg in parseSearchTagsArray(shortsFeedCurrent)"
                            :key="'shtg-' + String(shortsFeedCurrent?.id ?? '') + '-' + tg"
                            type="button"
                            class="search-feed-tag-chip search-feed-tag-chip--shorts-overlay"
                            @click.stop="onSearchTagNavigate(tg)"
                          >
                            {{ tg.startsWith("#") ? tg : "#" + tg }}
                          </button>
                        </span>
                      </p>
                    </div>
                    <div class="shorts-feed-action-col">
                      <div class="shorts-feed-author-block">
                        <div class="shorts-feed-author-avatar-wrap">
                          <img
                            class="shorts-feed-author-avatar"
                            :src="shortsFeedAuthorAvatar"
                            alt=""
                            role="button"
                            title="查看作者主页"
                            @click.stop="onAuthorAvatarClick($event, shortsFeedCurrent?.authorId)"
                            @error="(e) => { e.target.src = profileAvatar; }"
                          />
                          <button
                            v-if="!shortsIsSelfCurrent && !shortsIsFollowedCurrent"
                            type="button"
                            class="shorts-feed-follow-btn"
                            title="关注"
                            aria-label="关注"
                            @click.stop="toggleShortsFollow"
                          >
                            <i class="ri-add-line"></i>
                          </button>
                          <button
                            v-else-if="!shortsIsSelfCurrent"
                            type="button"
                            class="shorts-feed-follow-btn shorts-feed-follow-btn--followed"
                            title="已关注"
                            aria-label="已关注"
                            @click.stop="toggleShortsFollow"
                          >
                            <i class="ri-check-line"></i>
                          </button>
                        </div>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          :class="{ 'shorts-feed-action-btn--active': shortsFeedLikedDisplay }"
                          title="点赞 / 取消赞"
                          aria-label="点赞"
                          @click="onShortsLikeClick"
                        >
                          <i :class="shortsFeedLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.likeCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="评论"
                          aria-label="评论"
                          @click.stop="toggleShortsCommentPanel"
                        >
                          <i class="ri-chat-3-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.commentCount ?? 0 }}</span>
                      </div>
                      <div v-if="!shortsIsSelfCurrent" class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="不感兴趣"
                          aria-label="不感兴趣"
                          @click="onShortsDislikeClick"
                        >
                          <i class="ri-thumb-down-line"></i>
                        </button>
                      </div>
                      <div v-else-if="shortsIsSelfCurrent" class="shorts-feed-action-item shorts-feed-action-item--menu">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="更多操作"
                          aria-label="更多操作"
                          @click.stop="toggleSelfVideoActionMenu"
                        >
                          <i class="ri-more-fill"></i>
                        </button>
                        <div v-if="selfVideoActionOpen" class="shorts-self-action-menu" @click.stop>
                          <button type="button" class="shorts-self-action-btn" @click="toggleCurrentSelfVideoVisibility">
                            {{ String(shortsFeedCurrent?.visibility || '').toUpperCase() === 'PRIVATE' ? '设为公开' : '设为私密' }}
                          </button>
                          <button type="button" class="shorts-self-action-btn" @click="toggleCurrentSelfVideoAllowComment">
                            {{ Number(shortsFeedCurrent?.allowComment ?? 1) === 1 ? '关闭评论' : '开启评论' }}
                          </button>
                          <button
                            v-if="shortsIsSelfCurrent"
                            type="button"
                            class="shorts-self-action-btn shorts-self-action-btn--danger"
                            @click="requestDeleteCurrentSelfVideo"
                          >
                            删除
                          </button>
                        </div>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button type="button" class="shorts-feed-action-btn" title="转发" aria-label="转发" @click.stop="onShortsForwardClick">
                          <i class="ri-share-forward-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.shareCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          :title="shortsFeedIsDynamic ? '下载成片' : '下载视频'"
                          aria-label="下载"
                          @click.stop="shortsDownloadCurrent"
                        >
                          <i class="ri-download-2-line"></i>
                        </button>
                      </div>
                    </div>
                  </div>
                  <div class="shorts-feed-bottom-bar">
                    <input
                      type="range"
                      class="shorts-feed-scrub"
                      min="0"
                      :max="shortsScrubMax"
                      step="0.1"
                      :disabled="!shortsDurationReady"
                      :value="shortsScrubDisplayValue"
                      @input="onShortsScrubInput"
                    />
                    <div class="shorts-feed-bottom-bar__row">
                      <span class="shorts-feed-time">
                        {{ formatShortsTime(shortsFeedCurrentTime) }} / {{ formatShortsTime(shortsFeedDuration) }}
                      </span>
                      <div class="shorts-feed-tools">
                        <button type="button" class="shorts-feed-tool-btn" title="小窗播放（画中画）" @click="shortsRequestPip">
                          <i class="ri-picture-in-picture-line"></i>
                        </button>
                        <button type="button" class="shorts-feed-tool-btn" title="静音" @click="shortsToggleMute">
                          <i :class="shortsFeedMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                        </button>
                        <input
                          type="range"
                          class="shorts-feed-vol-range"
                          min="0"
                          max="1"
                          step="0.05"
                          :value="shortsFeedVolume"
                          title="音量"
                          aria-label="音量"
                          @input="onShortsVolumeInput"
                        />
                        <button type="button" class="shorts-feed-tool-btn" title="全屏" @click="shortsRequestFullscreen">
                          <i class="ri-fullscreen-line"></i>
                        </button>
                        <button
                          type="button"
                          class="shorts-feed-tool-btn shorts-feed-tool-btn--playpause"
                          :title="shortsFeedPaused ? '播放' : '暂停'"
                          @click="shortsTogglePlay"
                        >
                          <i :class="shortsFeedPaused ? 'ri-play-fill' : 'ri-pause-fill'"></i>
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
              </div>
            </div>
          </template>
        </section>
        </div>

</template>

<style scoped>
.search-expand-overlay {
  position: fixed;
  inset: 0;
  z-index: 4000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-24) var(--space-16);
  box-sizing: border-box;
  background: rgb(var(--slate-900-rgb) / 0.55);
  backdrop-filter: blur(6px);
}

.search-expand-overlay--fullscreen {
  align-items: stretch;
  justify-content: stretch;
  padding: 0;
  background: var(--black);
  backdrop-filter: none;
}

/* 搜索全屏：与短视频 feed 同一套 stage（short-video-bg + short-video-fg），非独立卡片 */
.search-expand-shorts-shell {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  background: var(--black);
}

.search-expand-overlay--fullscreen .shorts-feed-center.search-expand-shorts-center {
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  border: none;
}

.shorts-feed-bottom-bar--search-expand {
  position: relative;
  z-index: 10;
  flex-shrink: 0;
  padding: 0 var(--space-12) max(var(--space-12), env(safe-area-inset-bottom, 0px));
  margin-top: auto;
}

.shorts-feed-comments-panel--floating {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 4500;
  height: 100vh;
  height: 100dvh;
  max-height: none;
  box-shadow: -16px 0 48px rgb(var(--black-rgb) / 0.35);
}

.search-expand-close {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 20;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--radius-full);
  background: rgb(var(--slate-900-rgb) / 0.45);
  color: var(--color-text-inverse);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-22);
  line-height: 1;
}

.search-expand-close:hover {
  background: rgb(var(--slate-900-rgb) / 0.65);
}

.search-expand-overlay--fullscreen .shorts-feed-ui {
  padding-bottom: max(var(--space-96), calc(env(safe-area-inset-bottom, 0px) + var(--space-72)));
}

.video-player-loading--shorts {
  z-index: 3;
  background: radial-gradient(ellipse 80% 70% at 50% 45%, rgba(30, 35, 45, 0.88), rgba(6, 8, 12, 0.94));
}

.video-player-loading__shimmer--shorts {
  opacity: 0.35;
  background: linear-gradient(
    105deg,
    transparent 0%,
    rgb(var(--white-rgb) / 0.08) 45%,
    transparent 90%
  );
}

.video-player-loading__ring--shorts {
  width: 40px;
  height: 40px;
  border: 3px solid rgb(var(--white-rgb) / 0.14);
  border-top-color: var(--blue-300);
}

.video-player-loading__text--shorts {
  color: rgb(var(--white-rgb) / 0.78);
}

.shorts-feed-shell--with-sidebar .shorts-feed--follow-main {
  min-width: 0;
}

.follow-sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--line);
  background: var(--panel);
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 64px);
  max-height: calc(100vh - 64px);
  overflow: hidden;
}

.follow-sidebar-head {
  padding: var(--space-14) var(--space-16) var(--space-8);
  font-size: var(--font-size-12);
  font-weight: 700;
  color: var(--muted);
  letter-spacing: 0.06em;
}

.follow-sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--space-10) var(--space-16);
}

.follow-sidebar-hint {
  margin: var(--space-12) var(--space-8);
  font-size: var(--font-size-13);
  color: var(--muted);
  line-height: 1.5;
}

.follow-sidebar-row {
  display: flex;
  align-items: center;
  gap: var(--space-10);
  width: 100%;
  padding: var(--space-10) var(--space-12);
  margin-bottom: var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius-xl);
  background: transparent;
  color: var(--text);
  font-size: var(--font-size-14);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.follow-sidebar-row:hover {
  background: rgb(var(--blue-500-rgb) / 0.08);
}

.follow-sidebar-row:focus-visible {
  outline: 2px solid var(--accent, var(--blue-500));
  outline-offset: 2px;
}

.follow-sidebar-row--active {
  background: rgb(var(--blue-500-rgb) / 0.14);
  border-color: rgb(var(--blue-500-rgb) / 0.35);
}

.follow-sidebar-row--all .follow-sidebar-all-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgb(var(--blue-500-rgb) / 0.15);
  color: var(--accent, var(--blue-500));
  font-size: var(--font-size-20);
}

.follow-sidebar-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  background: var(--line);
}

.follow-sidebar-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}

.follow-sidebar-more {
  display: block;
  width: calc(100% - 16px);
  margin: var(--space-8) var(--space-8) 0;
  padding: var(--space-8);
  border-radius: var(--radius-lg);
  border: 1px dashed var(--line);
  background: transparent;
  color: var(--muted);
  font-size: var(--font-size-13);
  cursor: pointer;
}

.follow-sidebar-more:hover:not(:disabled) {
  border-color: var(--accent, var(--blue-500));
  color: var(--accent, var(--blue-500));
}

.follow-sidebar-more:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .shorts-feed-shell--with-sidebar {
    flex-direction: column;
  }

  .follow-sidebar {
    width: 100%;
    max-height: 200px;
    min-height: auto;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
}

@media (max-width: 900px) {
  .shorts-feed-shell--with-sidebar {
    flex-direction: column;
  }

  .follow-sidebar {
    width: 100%;
    max-height: 200px;
    min-height: auto;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
}

.shorts-feed-center {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--black);
  border-radius: var(--radius-2xl);
  overflow: hidden;
  border: 1px solid #1e2430;
  touch-action: pan-y;
}

.profile-feed-overlay .shorts-feed-center {
  width: 100vw;
  height: 100vh;
  border-radius: 0;
  border: none;
}

.shorts-feed-stage-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.profile-feed-overlay .shorts-feed-stage-wrap {
  height: 100%;
}

/* 左侧细线型上下切换，叠在视频上层 */
.shorts-feed-nav-side {
  position: absolute;
  left: 8px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 7;
  display: flex;
  flex-direction: column;
  gap: var(--space-18);
  pointer-events: auto;
}

.shorts-feed-nav-line {
  width: auto;
  height: auto;
  padding: var(--space-4);
  border: none;
  background: transparent;
  color: rgb(var(--white-rgb) / 0.38);
  cursor: pointer;
  font-size: var(--font-size-22);
  line-height: 1;
  font-weight: 300;
  transition: color 0.2s ease, opacity 0.2s ease;
  filter: drop-shadow(0 1px 2px rgb(var(--black-rgb) / 0.65));
}

.shorts-feed-nav-line:hover {
  color: rgb(var(--white-rgb) / 0.78);
}

.shorts-feed-nav-line:active {
  color: var(--color-text-inverse);
}

.shorts-feed-pause-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: auto;
  cursor: pointer;
  background: rgb(var(--black-rgb) / 0.12);
}

.shorts-feed-pause-overlay__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: rgb(var(--black-rgb) / 0.42);
  border: 1px solid rgb(var(--white-rgb) / 0.22);
  color: rgb(var(--white-rgb) / 0.95);
  font-size: 42px;
  line-height: 1;
  box-shadow: 0 8px 28px rgb(var(--black-rgb) / 0.35);
}

.shorts-feed-ui {
  position: absolute;
  inset: 0;
  z-index: 6;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  padding: var(--space-14) var(--space-12) var(--space-96);
  pointer-events: none;
  gap: var(--space-12);
}

.shorts-feed-caption {
  pointer-events: none;
  color: var(--color-text-inverse);
  max-width: min(78%, 520px);
  padding-left: var(--space-28);
  background: transparent;
  border: none;
  border-radius: 0;
  padding: 0 0 0 var(--space-10);
  backdrop-filter: none;
  text-shadow: 0 1px 4px rgb(var(--black-rgb) / 0.55);
}

.shorts-feed-action-col {
  pointer-events: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
  align-items: center;
  padding-bottom: var(--space-4);
}

.shorts-feed-author-block {
  margin-bottom: var(--space-2);
}

.shorts-feed-author-avatar-wrap {
  position: relative;
  width: 48px;
  height: 48px;
}

.shorts-feed-follow-btn {
  position: absolute;
  bottom: -2px;
  left: 50%;
  transform: translateX(-50%);
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  padding: 0;
  background: var(--red-600);
  color: var(--color-text-inverse);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-13);
  line-height: 1;
  box-shadow: 0 2px 6px rgb(var(--black-rgb) / 0.35);
}

.shorts-feed-follow-btn:hover {
  filter: brightness(1.08);
}

.shorts-feed-follow-btn--followed {
  background: rgb(var(--white-rgb) / 0.92);
  color: var(--ink-980);
}

.shorts-feed-action-btn {
  width: auto;
  height: auto;
  border-radius: 0;
  border: none;
  background: transparent;
  color: var(--color-text-inverse);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-30);
  line-height: 1;
  transition: transform 0.15s ease, opacity 0.2s ease;
  padding: 0;
}

.shorts-feed-action-btn:hover {
  opacity: 0.9;
  transform: scale(1.05);
}

.shorts-feed-action-btn--active {
  color: var(--red-hot);
  filter: drop-shadow(0 0 8px rgb(var(--red-hot-rgb) / 0.65));
}

.shorts-feed-action-btn--active i {
  color: var(--red-hot);
  -webkit-text-fill-color: var(--red-hot);
}

.shorts-feed-action-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-6);
  min-height: 58px;
  position: relative;
}

.shorts-feed-action-item--menu {
  overflow: visible;
}

/* 观看自己的短视频：三点菜单（浅色浮层，与创作中心行内菜单一致） */
.shorts-self-action-menu {
  position: absolute;
  right: 44px;
  top: 50%;
  transform: translateY(-50%);
  min-width: 132px;
  padding: var(--space-6);
  border-radius: var(--radius-2xl);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  z-index: 30;
}

.shorts-self-action-btn {
  border: 1px solid var(--color-border-strong);
  background: var(--color-bg-sunken);
  color: var(--color-text-strong);
  border-radius: var(--radius-xl);
  padding: var(--space-8) var(--space-10);
  font-size: var(--font-size-13);
  line-height: 1.2;
  cursor: pointer;
  text-align: left;
  font-weight: 500;
}

.shorts-self-action-btn:hover {
  background: var(--color-bg-hover);
  border-color: var(--slate-400);
}

.shorts-self-action-btn--danger {
  color: var(--red-700);
  border-color: rgb(var(--red-700-rgb) / 0.4);
  background: var(--red-50);
}

.shorts-self-action-btn--danger:hover {
  background: var(--red-100);
  border-color: rgb(var(--red-700-rgb) / 0.55);
}

.shorts-feed-action-count {
  font-size: var(--font-size-12);
  line-height: 1;
  color: var(--color-text-inverse);
  text-shadow: 0 1px 4px rgb(var(--black-rgb) / 0.45);
  white-space: nowrap;
}

.shorts-feed-bottom-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 6;
  padding: var(--space-10) var(--space-12) var(--space-14);
  background: linear-gradient(180deg, transparent, rgb(var(--black-rgb) / 0.78));
  pointer-events: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
}

/* 个人主页作品：全屏连播 */
.profile-feed-overlay {
  position: fixed;
  inset: 0;
  z-index: 1004;
  background: #0b0f18;
  display: flex;
  flex-direction: column;
  padding: 0;
  margin: 0;
}

.shorts-feed-player-column {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.shorts-feed-comments-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-10);
  margin-bottom: var(--space-12);
  padding-bottom: var(--space-12);
  flex-shrink: 0;
  border-bottom: 1px solid rgb(var(--slate-900-rgb) / 0.07);
  box-shadow: 0 1px 0 rgb(var(--white-rgb) / 0.75);
}

.shorts-feed-comments-panel-head h3 {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text);
}

.shorts-feed-comments-panel-close {
  width: 36px;
  height: 36px;
  border: 1px solid rgb(var(--slate-900-rgb) / 0.08);
  border-radius: 50%;
  background: linear-gradient(180deg, var(--white) 0%, var(--slate-100) 100%);
  color: var(--color-text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-20);
  flex-shrink: 0;
  box-shadow: 0 1px 2px rgb(var(--slate-900-rgb) / 0.06), inset 0 1px 0 rgb(var(--white-rgb) / 0.95);
}

.shorts-feed-comments-panel-close:hover {
  background: linear-gradient(180deg, var(--slate-50) 0%, var(--slate-200) 100%);
  color: var(--color-text-strong);
}

.shorts-feed-comments-panel-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin-top: var(--space-8);
  padding-right: var(--space-4);
}

.profile-feed-close {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 80;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  background: rgb(var(--black-rgb) / 0.58);
  color: var(--color-text-inverse);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-22);
  line-height: 1;
}

.profile-feed-close:hover {
  background: rgb(var(--black-rgb) / 0.62);
}

/* 打开评论侧栏时避免与侧栏右上角关闭钮叠在一起 */
.profile-feed-close--comments-open {
  left: 16px;
  right: auto;
}
</style>
