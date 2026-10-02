<script setup>
/**
 * 短视频域：沉浸 feed（短视频/关注/全屏队列）、共享评论面板、搜索全屏放大层。
 * 状态与动作全部直接取自各域 store；本组件只保留 DOM ref 与本地交互处理。
 */
import { ref, watch } from "vue";
import { storeToRefs } from "pinia";
import DynamicPlayer from "../components/DynamicPlayer.vue";
import { registerImmersiveFeedPlayerRefs, useImmersiveStore } from "../stores/immersiveStore";
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
  formatPlaybackTime,
  parseSearchTagsArray,
} from "../utils/videoCard.js";

const immersiveStore = useImmersiveStore();
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
  immersiveDurationReady,
  immersiveFeedAuthorAvatar,
  immersiveFeedAuthorName,
  immersiveFeedCurrent,
  immersiveFeedIsDynamic,
  immersiveFeedCurrentTime,
  immersiveFeedDescText,
  immersiveFeedDuration,
  immersiveFeedIsPortrait,
  immersiveFeedLikedDisplay,
  immersiveFeedMetaTimeText,
  immersiveFeedMuted,
  immersiveFeedPaused,
  immersiveFeedTitleText,
  immersiveFeedVideoBuffering,
  immersiveFeedVideoSrc,
  immersiveFeedVolume,
  immersiveIsFollowedCurrent,
  immersiveIsSelfCurrent,
  immersivePanelCommentDraft,
  immersivePanelCommentSubmitting,
  immersivePanelCommentsLoading,
  immersivePanelExpandedRoots,
  immersivePanelReplyTarget,
  immersivePanelThreadByRootId,
  immersivePanelTopComments,
  immersiveScrubDisplayValue,
  immersiveScrubMax,
} = storeToRefs(immersiveStore);

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

const { immersiveCommentPanelOpen, searchListCommentOpen, searchExpandCommentOpen } =
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
  () => [immersiveFeedIsDynamic.value, immersiveFeedCurrent.value?.id],
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
    const detail = await ensureDynamicDetail(immersiveFeedCurrent.value);
    // 拉取期间又切条了则丢弃，避免把旧详情写回去
    if (String(immersiveFeedCurrent.value?.id ?? "") !== wanted) {
      return;
    }
    dynamicDetail.value = detail;
  },
  { immediate: true }
);

function onImmersiveDynamicReady(payload) {
  immersiveStore.onImmersiveFeedDynamicReady(payload?.durationSec, payload?.canvasType);
}

function onImmersiveDynamicTimeUpdate(sec) {
  immersiveStore.onImmersiveFeedDynamicTimeUpdate(sec);
}

// store action 与 store 绑定，可直接解构；带括号调用的函数型接口（如 searchExpandVideoUrl）
// 在 store 里就是 action，不能写成 getter
const {
  closeProfileFeed,
  closeImmersiveCommentPanel,
  commentDisplayNickname,
  isCommentLikeBusy,
  loadMoreImmersivePanelComments,
  onDeleteImmersivePanelComment,
  onImmersiveDislikeClick,
  onImmersiveFeedFgCanPlay,
  onImmersiveFeedFgDurationChange,
  onImmersiveFeedFgEnded,
  ensureDynamicDetail,
  onImmersiveFeedFgLoadStart,
  onImmersiveFeedFgLoadedMetadata,
  onImmersiveFeedFgMediaError,
  onImmersiveFeedFgPause,
  onImmersiveFeedFgPlay,
  onImmersiveFeedFgSeeked,
  onImmersiveFeedFgTimeUpdate,
  onImmersiveFeedTouchEnd,
  onImmersiveFeedTouchStart,
  onImmersiveFeedWheel,
  onImmersiveForwardClick,
  onImmersiveLikeClick,
  onImmersiveScrubInput,
  onImmersiveVolumeInput,
  requestDeleteCurrentSelfVideo,
  selectFollowSidebarAll,
  selectFollowSidebarAuthor,
  setImmersivePanelReplyTarget,
  immersiveFeedNext,
  immersiveFeedPrev,
  immersiveDownloadCurrent,
  immersivePanelCommentHasMore,
  immersiveRequestFullscreen,
  immersiveRequestPip,
  immersiveToggleMute,
  immersiveTogglePlay,
  submitCommentLike,
  submitImmersivePanelComment,
  toggleCurrentSelfVideoAllowComment,
  toggleCurrentSelfVideoVisibility,
  toggleSelfVideoActionMenu,
  toggleImmersiveCommentPanel,
  toggleImmersiveFollow,
  toggleImmersivePanelThread,
} = immersiveStore;

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
const immersiveFeedFgRef = ref(null);
const immersiveFeedBgRef = ref(null);
const immersiveFeedCenterRef = ref(null);
/** 动态条目用受控 DynamicPlayer 替代两个 <video>，命令入口同样注册给 store */
const immersiveFeedDynamicRef = ref(null);
registerImmersiveFeedPlayerRefs({
  fg: immersiveFeedFgRef,
  bg: immersiveFeedBgRef,
  center: immersiveFeedCenterRef,
  dynamic: immersiveFeedDynamicRef,
});

const searchExpandPlayerRef = ref(null);
const searchExpandBlurRef = ref(null);
const searchExpandStageRef = ref(null);
registerSearchExpandRefs({
  main: searchExpandPlayerRef,
  blur: searchExpandBlurRef,
  stage: searchExpandStageRef,
});

const immersivePanelCommentInputRef = ref(null);

function onAuthorAvatarClick(event, authorId) {
  event?.stopPropagation?.();
  profileStore.openByAuthorId(authorId);
}

function onImmersivePanelCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(immersivePanelCommentDraft, immersivePanelCommentInputRef, glyph);
}

function onImmersivePanelCommentUserClick(ev, userId) {
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
            <div class="search-expand-immersive-shell">
              <div ref="searchExpandStageRef" class="immersive-feed-center search-expand-immersive-center">
                <button type="button" class="search-expand-close" aria-label="关闭" @click="closeSearchExpand">
                  <i class="ri-close-line"></i>
                </button>
                <div class="immersive-feed-stage-wrap">
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
                      class="immersive-feed-pause-overlay"
                      @click.stop="toggleSearchExpandPlay"
                    >
                      <span class="immersive-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="immersive-feed-nav-side" aria-label="切换上一条下一条">
                    <button type="button" class="immersive-feed-nav-line" title="上一条" @click.stop="searchExpandPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="immersive-feed-nav-line" title="下一条" @click.stop="searchExpandNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="immersive-feed-ui">
                  <div class="immersive-feed-caption">
                    <p class="immersive-feed-user">
                      <button
                        v-if="searchExpandCard?.authorId != null && searchExpandCard?.authorId !== ''"
                        type="button"
                        class="immersive-feed-user-link"
                        title="查看作者主页"
                        @click.stop="onAuthorAvatarClick($event, searchExpandCard.authorId)"
                      >
                        @{{ searchExpandCard.authorNickname || searchExpandCard.author }}
                      </button>
                      <template v-else>@{{ searchExpandCard.authorNickname || searchExpandCard.author }}</template>
                      <span v-if="formatSearchRelTime(searchExpandCard)" class="immersive-feed-time-dot"> · {{ formatSearchRelTime(searchExpandCard) }}</span>
                    </p>
                    <p class="immersive-feed-topic">{{ searchExpandCard.title }}</p>
                    <p class="immersive-feed-desc">
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
                  <div class="immersive-feed-action-col">
                    <div class="immersive-feed-author-block">
                      <div class="immersive-feed-author-avatar-wrap">
                        <img
                          class="immersive-feed-author-avatar"
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
                          class="immersive-feed-follow-btn"
                          title="关注"
                          aria-label="关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-add-line"></i>
                        </button>
                        <button
                          v-else-if="!searchExpandIsSelf"
                          type="button"
                          class="immersive-feed-follow-btn immersive-feed-follow-btn--followed"
                          title="已关注"
                          aria-label="已关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-check-line"></i>
                        </button>
                      </div>
                    </div>
                    <div class="immersive-feed-action-item">
                      <button
                        type="button"
                        class="immersive-feed-action-btn"
                        :class="{ 'immersive-feed-action-btn--active': searchExpandLikedDisplay }"
                        title="点赞 / 取消赞"
                        aria-label="点赞"
                        @click.stop="onSearchExpandLikeClick"
                      >
                        <i :class="searchExpandLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                      </button>
                      <span class="immersive-feed-action-count">{{ searchExpandCard?.likeCount ?? 0 }}</span>
                    </div>
                    <div class="immersive-feed-action-item">
                      <button
                        type="button"
                        class="immersive-feed-action-btn"
                        title="评论"
                        aria-label="评论"
                        @click.stop="toggleSearchExpandCommentPanel"
                      >
                        <i class="ri-chat-3-line"></i>
                      </button>
                      <span class="immersive-feed-action-count">{{ searchExpandCard?.commentCount ?? 0 }}</span>
                    </div>
                    <div v-if="!searchExpandIsSelf" class="immersive-feed-action-item">
                      <button
                        type="button"
                        class="immersive-feed-action-btn"
                        title="不感兴趣"
                        aria-label="不感兴趣"
                        @click.stop="onSearchExpandDislikeClick"
                      >
                        <i class="ri-thumb-down-line"></i>
                      </button>
                    </div>
                    <div class="immersive-feed-action-item">
                      <button type="button" class="immersive-feed-action-btn" title="分享" aria-label="分享" @click.stop="onSearchExpandShareClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <span class="immersive-feed-action-count">{{ searchExpandCard?.shareCount ?? 0 }}</span>
                    </div>
                  </div>
                </div>
                <div class="immersive-feed-bottom-bar immersive-feed-bottom-bar--search-expand">
                  <input
                    type="range"
                    class="immersive-feed-scrub"
                    min="0"
                    :max="searchExpandScrubMax"
                    step="0.1"
                    :disabled="!searchExpandDurationReady"
                    :value="searchExpandScrubDisplayValue"
                    @input="onSearchExpandScrubInput"
                  />
                  <div class="immersive-feed-bottom-bar__row">
                    <span class="immersive-feed-time">
                      {{ formatPlaybackTime(searchExpandCurrentTime) }} / {{ formatPlaybackTime(searchExpandDuration) }}
                    </span>
                    <div class="immersive-feed-tools">
                      <button type="button" class="immersive-feed-tool-btn" title="小窗播放（画中画）" @click="searchExpandRequestPip">
                        <i class="ri-picture-in-picture-line"></i>
                      </button>
                      <button type="button" class="immersive-feed-tool-btn" title="静音" @click="searchExpandToggleMute">
                        <i :class="searchExpandMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                      </button>
                      <input
                        type="range"
                        class="immersive-feed-vol-range"
                        min="0"
                        max="1"
                        step="0.05"
                        :value="searchExpandVolume"
                        title="音量"
                        aria-label="音量"
                        @input="onSearchExpandVolumeInput"
                      />
                      <button type="button" class="immersive-feed-tool-btn" title="全屏" @click="searchExpandRequestFullscreen">
                        <i class="ri-fullscreen-line"></i>
                      </button>
                      <button type="button" class="immersive-feed-tool-btn" title="转发" aria-label="转发" @click="onSearchExpandForwardClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <button
                        type="button"
                        class="immersive-feed-tool-btn immersive-feed-tool-btn--playpause"
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
                v-if="immersiveCommentPanelOpen || searchExpandCommentOpen || searchListCommentOpen"
                class="immersive-feed-comments-panel immersive-feed-comments-panel--floating"
                aria-label="评论"
                @click.stop
              >
                <div class="immersive-feed-comments-panel-head">
                  <h3>评论 {{ activeCommentVideo?.commentCount != null ? `（${activeCommentVideo.commentCount}）` : "" }}</h3>
                  <button
                    type="button"
                    class="immersive-feed-comments-panel-close"
                    aria-label="关闭评论"
                    @click="closeImmersiveCommentPanel"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                </div>
                <span v-if="!activeCommentVideoAllowsComment" class="video-detail-comments-note">作者已关闭评论</span>
                <p v-if="immersivePanelReplyTarget" class="video-detail-comment-reply-hint">
                  回复 @{{ commentDisplayNickname(immersivePanelReplyTarget.userId, immersivePanelReplyTarget.nickname) }}
                  <button type="button" class="video-detail-comment-reply-cancel" @click="setImmersivePanelReplyTarget(null)">
                    取消
                  </button>
                </p>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input
                    ref="immersivePanelCommentInputRef"
                    v-model="immersivePanelCommentDraft"
                    type="text"
                    class="video-detail-comment-field"
                    :disabled="!isAuthed || !activeCommentVideoAllowsComment || immersivePanelCommentSubmitting"
                    :placeholder="
                      !isAuthed
                        ? '登录后参与评论…'
                        : !activeCommentVideoAllowsComment
                          ? '评论已关闭'
                          : immersivePanelReplyTarget
                            ? `回复 @${commentDisplayNickname(immersivePanelReplyTarget.userId, immersivePanelReplyTarget.nickname)}…`
                            : '添加评论…'
                    "
                    maxlength="500"
                    @keyup.enter.prevent="submitImmersivePanelComment"
                  />
                  <div class="video-detail-comment-input-actions">
                    <ChatEmojiPicker
                      :disabled="!isAuthed || !activeCommentVideoAllowsComment || immersivePanelCommentSubmitting"
                      @pick="onImmersivePanelCommentEmojiPick"
                    />
                    <button
                      type="button"
                      class="video-detail-comment-send"
                      :disabled="
                        !isAuthed || !activeCommentVideoAllowsComment || immersivePanelCommentSubmitting || !immersivePanelCommentDraft.trim()
                      "
                      @click="submitImmersivePanelComment"
                    >
                      {{ immersivePanelCommentSubmitting ? "发送中…" : "发送" }}
                    </button>
                  </div>
                </div>
                <div class="immersive-feed-comments-panel-scroll">
                  <p v-if="immersivePanelCommentsLoading && !immersivePanelTopComments.length" class="video-detail-comments-empty">
                    加载评论中…
                  </p>
                  <ul v-else-if="immersivePanelTopComments.length" class="video-detail-comments-mock">
                    <template v-for="c in immersivePanelTopComments" :key="`sp-top-${c.id}`">
                      <li class="video-detail-comment-item">
                        <img
                          :src="c.avatarUrl || profileAvatar"
                          alt=""
                          class="video-detail-comment-avatar video-detail-comment-avatar--clickable"
                          role="button"
                          tabindex="0"
                          title="查看用户主页"
                          @click.stop="onImmersivePanelCommentUserClick($event, c.userId)"
                          @keyup.enter.stop="onImmersivePanelCommentUserClick($event, c.userId)"
                          @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                        />
                        <div class="video-detail-comment-body">
                          <div class="video-detail-comment-meta">
                            <button
                              type="button"
                              class="video-detail-comment-user video-detail-comment-user--link"
                              @click.stop="onImmersivePanelCommentUserClick($event, c.userId)"
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
                              @click="setImmersivePanelReplyTarget(c)"
                            >
                              回复
                            </button>
                            <button
                              v-if="isAuthed && currentUserId != null && String(c.userId) === String(currentUserId)"
                              type="button"
                              class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                              @click="onDeleteImmersivePanelComment(c)"
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
                            @click="toggleImmersivePanelThread(c.id)"
                          >
                            {{
                              immersivePanelExpandedRoots[String(c.id)]
                                ? "收起回复"
                                : `展开 ${c.replyCount} 条回复`
                            }}
                          </button>
                          <ul v-if="immersivePanelExpandedRoots[String(c.id)]" class="video-detail-comment-replies">
                            <li
                              v-for="r in immersivePanelThreadByRootId[String(c.id)]?.list || []"
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
                                @click.stop="onImmersivePanelCommentUserClick($event, r.userId)"
                                @keyup.enter.stop="onImmersivePanelCommentUserClick($event, r.userId)"
                                @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                              />
                              <div class="video-detail-comment-body">
                                <div class="video-detail-comment-meta">
                                  <button
                                    type="button"
                                    class="video-detail-comment-user video-detail-comment-user--link"
                                    @click.stop="onImmersivePanelCommentUserClick($event, r.userId)"
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
                                    @click.stop="onImmersivePanelCommentUserClick($event, r.replyUserId)"
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
                                    @click="setImmersivePanelReplyTarget(r)"
                                  >
                                    回复
                                  </button>
                                  <button
                                    v-if="isAuthed && currentUserId != null && String(r.userId) === String(currentUserId)"
                                    type="button"
                                    class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                                    @click="onDeleteImmersivePanelComment(r)"
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
                              v-if="immersivePanelThreadByRootId[String(c.id)]?.loading"
                              class="video-detail-comment-item video-detail-comment-item--reply"
                            >
                              <span class="video-detail-comments-empty">加载回复中…</span>
                            </li>
                          </ul>
                        </div>
                      </li>
                    </template>
                  </ul>
                  <div v-if="immersivePanelCommentHasMore()" class="video-detail-comments-more">
                    <button
                      type="button"
                      class="history-layout-btn"
                      :disabled="immersivePanelCommentsLoading"
                      @click="loadMoreImmersivePanelComments"
                    >
                      {{ immersivePanelCommentsLoading ? "加载中…" : "加载更多评论" }}
                    </button>
                  </div>
                </div>
              </aside>
        </Teleport>
        <div
          v-if="profileFeedOpen || (!showProfileCenter && (activeMenuKey === 'immersive' || activeMenuKey === 'subscribed'))"
          class="immersive-feed-shell"
          :class="{ 'immersive-feed-shell--with-sidebar': activeMenuKey === 'subscribed' && !profileFeedOpen }"
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
              profileFeedOpen ? 'profile-feed-overlay' : 'immersive-feed',
              { 'immersive-feed--follow-main': activeMenuKey === 'subscribed' && !profileFeedOpen },
            ]"
          >
          <button
            v-if="profileFeedOpen"
            type="button"
            class="profile-feed-close"
            :class="{ 'profile-feed-close--comments-open': immersiveCommentPanelOpen }"
            :aria-label="feedScope === 'liked' ? '关闭赞过视频连播' : '关闭作品连播'"
            @click="closeProfileFeed"
          >
            <i class="ri-close-line"></i>
          </button>
          <div v-if="loading && !profileFeedOpen" class="immersive-feed-loading">
            {{ activeMenuKey === "subscribed" ? "正在加载关注动态…" : "正在加载沉浸内容…" }}
          </div>
          <template v-else-if="immersiveFeedVideoSrc || immersiveFeedIsDynamic">
            <div class="immersive-feed-body" :class="{ 'immersive-feed-body--comments-open': immersiveCommentPanelOpen }">
              <div class="immersive-feed-player-column">
              <div
                ref="immersiveFeedCenterRef"
                class="immersive-feed-center"
                @wheel.prevent="onImmersiveFeedWheel"
                @touchstart="onImmersiveFeedTouchStart"
                @touchend="onImmersiveFeedTouchEnd"
              >
                <div class="immersive-feed-stage-wrap">
                  <div
                    class="short-video-stage short-video-stage--feed"
                    :class="{
                      'short-video-stage--portrait': immersiveFeedIsPortrait,
                      'short-video-stage--landscape': !immersiveFeedIsPortrait,
                    }"
                  >
                    <DynamicPlayer
                      v-if="immersiveFeedIsDynamic && dynamicDetail"
                      ref="immersiveFeedDynamicRef"
                      :key="dynamicDetail.id"
                      class="immersive-feed-dynamic"
                      :dynamic="dynamicDetail"
                      :loop="false"
                      controlled
                      :paused="immersiveFeedPaused"
                      :muted="immersiveFeedMuted"
                      :volume="immersiveFeedVolume"
                      @ended="onImmersiveFeedFgEnded"
                      @durationchange="onImmersiveDynamicReady"
                      @timeupdate="onImmersiveDynamicTimeUpdate"
                      @request-toggle-play="immersiveTogglePlay"
                      @request-toggle-mute="immersiveToggleMute"
                    />
                    <video
                      v-if="!immersiveFeedIsDynamic"
                      :key="immersiveFeedCurrent?.id"
                      ref="immersiveFeedBgRef"
                      class="short-video-bg"
                      :data-play-token="immersiveFeedPlayToken"
                      muted
                      playsinline
                      preload="metadata"
                      :src="immersiveFeedVideoSrc"
                    ></video>
                    <video
                      v-if="!immersiveFeedIsDynamic"
                      :key="immersiveFeedCurrent?.id"
                      ref="immersiveFeedFgRef"
                      class="short-video-fg"
                      :data-play-token="immersiveFeedPlayToken"
                      playsinline
                      preload="metadata"
                      :src="immersiveFeedVideoSrc"
                      @click="immersiveTogglePlay"
                      @loadstart="onImmersiveFeedFgLoadStart"
                      @canplay="onImmersiveFeedFgCanPlay"
                      @error="onImmersiveFeedFgMediaError"
                      @loadedmetadata="onImmersiveFeedFgLoadedMetadata"
                      @durationchange="onImmersiveFeedFgDurationChange"
                      @timeupdate="onImmersiveFeedFgTimeUpdate"
                      @play="onImmersiveFeedFgPlay"
                      @pause="onImmersiveFeedFgPause"
                      @seeked="onImmersiveFeedFgSeeked"
                      @ended="onImmersiveFeedFgEnded"
                    ></video>
                    <div
                      v-show="immersiveFeedVideoBuffering && immersiveFeedVideoSrc"
                      class="video-player-loading video-player-loading--immersive"
                      aria-live="polite"
                      aria-busy="true"
                    >
                      <div class="video-player-loading__shimmer video-player-loading__shimmer--immersive" aria-hidden="true" />
                      <div class="video-player-loading__inner">
                        <span class="video-player-loading__ring video-player-loading__ring--immersive" />
                        <span class="video-player-loading__text video-player-loading__text--immersive">正在加载</span>
                      </div>
                    </div>
                    <div
                      v-show="immersiveFeedPaused"
                      class="immersive-feed-pause-overlay"
                      @click.stop="immersiveTogglePlay"
                    >
                      <span class="immersive-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="immersive-feed-nav-side" aria-label="切换视频">
                    <button type="button" class="immersive-feed-nav-line" title="上一条" @click.stop="immersiveFeedPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="immersive-feed-nav-line" title="下一条" @click.stop="immersiveFeedNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="immersive-feed-ui">
                    <div class="immersive-feed-caption">
                      <p class="immersive-feed-user">
                        <button
                          v-if="immersiveFeedCurrent?.authorId != null && immersiveFeedCurrent?.authorId !== ''"
                          type="button"
                          class="immersive-feed-user-link"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, immersiveFeedCurrent.authorId)"
                        >
                          @{{ immersiveFeedAuthorName }}
                        </button>
                        <template v-else>@{{ immersiveFeedAuthorName }}</template>
                        <span v-if="immersiveFeedMetaTimeText" class="immersive-feed-time-dot"> · {{ immersiveFeedMetaTimeText }}</span>
                      </p>
                      <p class="immersive-feed-topic">{{ immersiveFeedTitleText }}</p>
                      <p class="immersive-feed-desc">
                        {{ immersiveFeedDescText }}
                        <span v-if="parseSearchTagsArray(immersiveFeedCurrent).length" class="search-feed-tag-row search-feed-tag-row--immersive-overlay">
                          <button
                            v-for="tg in parseSearchTagsArray(immersiveFeedCurrent)"
                            :key="'shtg-' + String(immersiveFeedCurrent?.id ?? '') + '-' + tg"
                            type="button"
                            class="search-feed-tag-chip search-feed-tag-chip--immersive-overlay"
                            @click.stop="onSearchTagNavigate(tg)"
                          >
                            {{ tg.startsWith("#") ? tg : "#" + tg }}
                          </button>
                        </span>
                      </p>
                    </div>
                    <div class="immersive-feed-action-col">
                      <div class="immersive-feed-author-block">
                        <div class="immersive-feed-author-avatar-wrap">
                          <img
                            class="immersive-feed-author-avatar"
                            :src="immersiveFeedAuthorAvatar"
                            alt=""
                            role="button"
                            title="查看作者主页"
                            @click.stop="onAuthorAvatarClick($event, immersiveFeedCurrent?.authorId)"
                            @error="(e) => { e.target.src = profileAvatar; }"
                          />
                          <button
                            v-if="!immersiveIsSelfCurrent && !immersiveIsFollowedCurrent"
                            type="button"
                            class="immersive-feed-follow-btn"
                            title="关注"
                            aria-label="关注"
                            @click.stop="toggleImmersiveFollow"
                          >
                            <i class="ri-add-line"></i>
                          </button>
                          <button
                            v-else-if="!immersiveIsSelfCurrent"
                            type="button"
                            class="immersive-feed-follow-btn immersive-feed-follow-btn--followed"
                            title="已关注"
                            aria-label="已关注"
                            @click.stop="toggleImmersiveFollow"
                          >
                            <i class="ri-check-line"></i>
                          </button>
                        </div>
                      </div>
                      <div class="immersive-feed-action-item">
                        <button
                          type="button"
                          class="immersive-feed-action-btn"
                          :class="{ 'immersive-feed-action-btn--active': immersiveFeedLikedDisplay }"
                          title="点赞 / 取消赞"
                          aria-label="点赞"
                          @click="onImmersiveLikeClick"
                        >
                          <i :class="immersiveFeedLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                        </button>
                        <span class="immersive-feed-action-count">{{ immersiveFeedCurrent?.likeCount ?? 0 }}</span>
                      </div>
                      <div class="immersive-feed-action-item">
                        <button
                          type="button"
                          class="immersive-feed-action-btn"
                          title="评论"
                          aria-label="评论"
                          @click.stop="toggleImmersiveCommentPanel"
                        >
                          <i class="ri-chat-3-line"></i>
                        </button>
                        <span class="immersive-feed-action-count">{{ immersiveFeedCurrent?.commentCount ?? 0 }}</span>
                      </div>
                      <div v-if="!immersiveIsSelfCurrent" class="immersive-feed-action-item">
                        <button
                          type="button"
                          class="immersive-feed-action-btn"
                          title="不感兴趣"
                          aria-label="不感兴趣"
                          @click="onImmersiveDislikeClick"
                        >
                          <i class="ri-thumb-down-line"></i>
                        </button>
                      </div>
                      <div v-else-if="immersiveIsSelfCurrent" class="immersive-feed-action-item immersive-feed-action-item--menu">
                        <button
                          type="button"
                          class="immersive-feed-action-btn"
                          title="更多操作"
                          aria-label="更多操作"
                          @click.stop="toggleSelfVideoActionMenu"
                        >
                          <i class="ri-more-fill"></i>
                        </button>
                        <div v-if="selfVideoActionOpen" class="immersive-self-action-menu" @click.stop>
                          <button type="button" class="immersive-self-action-btn" @click="toggleCurrentSelfVideoVisibility">
                            {{ String(immersiveFeedCurrent?.visibility || '').toUpperCase() === 'PRIVATE' ? '设为公开' : '设为私密' }}
                          </button>
                          <button type="button" class="immersive-self-action-btn" @click="toggleCurrentSelfVideoAllowComment">
                            {{ Number(immersiveFeedCurrent?.allowComment ?? 1) === 1 ? '关闭评论' : '开启评论' }}
                          </button>
                          <button
                            v-if="immersiveIsSelfCurrent"
                            type="button"
                            class="immersive-self-action-btn immersive-self-action-btn--danger"
                            @click="requestDeleteCurrentSelfVideo"
                          >
                            删除
                          </button>
                        </div>
                      </div>
                      <div class="immersive-feed-action-item">
                        <button type="button" class="immersive-feed-action-btn" title="转发" aria-label="转发" @click.stop="onImmersiveForwardClick">
                          <i class="ri-share-forward-line"></i>
                        </button>
                        <span class="immersive-feed-action-count">{{ immersiveFeedCurrent?.shareCount ?? 0 }}</span>
                      </div>
                      <div class="immersive-feed-action-item">
                        <button
                          type="button"
                          class="immersive-feed-action-btn"
                          :title="immersiveFeedIsDynamic ? '下载成片' : '下载视频'"
                          aria-label="下载"
                          @click.stop="immersiveDownloadCurrent"
                        >
                          <i class="ri-download-2-line"></i>
                        </button>
                      </div>
                    </div>
                  </div>
                  <div class="immersive-feed-bottom-bar">
                    <input
                      type="range"
                      class="immersive-feed-scrub"
                      min="0"
                      :max="immersiveScrubMax"
                      step="0.1"
                      :disabled="!immersiveDurationReady"
                      :value="immersiveScrubDisplayValue"
                      @input="onImmersiveScrubInput"
                    />
                    <div class="immersive-feed-bottom-bar__row">
                      <span class="immersive-feed-time">
                        {{ formatPlaybackTime(immersiveFeedCurrentTime) }} / {{ formatPlaybackTime(immersiveFeedDuration) }}
                      </span>
                      <div class="immersive-feed-tools">
                        <button type="button" class="immersive-feed-tool-btn" title="小窗播放（画中画）" @click="immersiveRequestPip">
                          <i class="ri-picture-in-picture-line"></i>
                        </button>
                        <button type="button" class="immersive-feed-tool-btn" title="静音" @click="immersiveToggleMute">
                          <i :class="immersiveFeedMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                        </button>
                        <input
                          type="range"
                          class="immersive-feed-vol-range"
                          min="0"
                          max="1"
                          step="0.05"
                          :value="immersiveFeedVolume"
                          title="音量"
                          aria-label="音量"
                          @input="onImmersiveVolumeInput"
                        />
                        <button type="button" class="immersive-feed-tool-btn" title="全屏" @click="immersiveRequestFullscreen">
                          <i class="ri-fullscreen-line"></i>
                        </button>
                        <button
                          type="button"
                          class="immersive-feed-tool-btn immersive-feed-tool-btn--playpause"
                          :title="immersiveFeedPaused ? '播放' : '暂停'"
                          @click="immersiveTogglePlay"
                        >
                          <i :class="immersiveFeedPaused ? 'ri-play-fill' : 'ri-pause-fill'"></i>
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
.search-expand-immersive-shell {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  background: var(--black);
}

.search-expand-overlay--fullscreen .immersive-feed-center.search-expand-immersive-center {
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  border: none;
}

.immersive-feed-bottom-bar--search-expand {
  position: relative;
  z-index: 10;
  flex-shrink: 0;
  padding: 0 var(--space-12) max(var(--space-12), env(safe-area-inset-bottom, 0px));
  margin-top: auto;
}

.immersive-feed-comments-panel--floating {
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

.search-expand-overlay--fullscreen .immersive-feed-ui {
  padding-bottom: max(var(--space-96), calc(env(safe-area-inset-bottom, 0px) + var(--space-72)));
}

.video-player-loading--immersive {
  z-index: 3;
  background: radial-gradient(ellipse 80% 70% at 50% 45%, rgba(30, 35, 45, 0.88), rgba(6, 8, 12, 0.94));
}

.video-player-loading__shimmer--immersive {
  opacity: 0.35;
  background: linear-gradient(
    105deg,
    transparent 0%,
    rgb(var(--white-rgb) / 0.08) 45%,
    transparent 90%
  );
}

.video-player-loading__ring--immersive {
  width: 40px;
  height: 40px;
  border: 3px solid rgb(var(--white-rgb) / 0.14);
  border-top-color: var(--blue-300);
}

.video-player-loading__text--immersive {
  color: rgb(var(--white-rgb) / 0.78);
}

.immersive-feed-shell--with-sidebar .immersive-feed--follow-main {
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
  .immersive-feed-shell--with-sidebar {
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
  .immersive-feed-shell--with-sidebar {
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

.immersive-feed-center {
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

.profile-feed-overlay .immersive-feed-center {
  width: 100vw;
  height: 100vh;
  border-radius: 0;
  border: none;
}

.immersive-feed-stage-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.profile-feed-overlay .immersive-feed-stage-wrap {
  height: 100%;
}

/* 左侧细线型上下切换，叠在视频上层 */
.immersive-feed-nav-side {
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

.immersive-feed-nav-line {
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

.immersive-feed-nav-line:hover {
  color: rgb(var(--white-rgb) / 0.78);
}

.immersive-feed-nav-line:active {
  color: var(--color-text-inverse);
}

.immersive-feed-pause-overlay {
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

.immersive-feed-pause-overlay__icon {
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

.immersive-feed-ui {
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

.immersive-feed-caption {
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

.immersive-feed-action-col {
  pointer-events: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
  align-items: center;
  padding-bottom: var(--space-4);
}

.immersive-feed-author-block {
  margin-bottom: var(--space-2);
}

.immersive-feed-author-avatar-wrap {
  position: relative;
  width: 48px;
  height: 48px;
}

.immersive-feed-follow-btn {
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

.immersive-feed-follow-btn:hover {
  filter: brightness(1.08);
}

.immersive-feed-follow-btn--followed {
  background: rgb(var(--white-rgb) / 0.92);
  color: var(--ink-980);
}

.immersive-feed-action-btn {
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

.immersive-feed-action-btn:hover {
  opacity: 0.9;
  transform: scale(1.05);
}

.immersive-feed-action-btn--active {
  color: var(--red-hot);
  filter: drop-shadow(0 0 8px rgb(var(--red-hot-rgb) / 0.65));
}

.immersive-feed-action-btn--active i {
  color: var(--red-hot);
  -webkit-text-fill-color: var(--red-hot);
}

.immersive-feed-action-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-6);
  min-height: 58px;
  position: relative;
}

.immersive-feed-action-item--menu {
  overflow: visible;
}

/* 观看自己的短视频：三点菜单（浅色浮层，与创作中心行内菜单一致） */
.immersive-self-action-menu {
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

.immersive-self-action-btn {
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

.immersive-self-action-btn:hover {
  background: var(--color-bg-hover);
  border-color: var(--slate-400);
}

.immersive-self-action-btn--danger {
  color: var(--red-700);
  border-color: rgb(var(--red-700-rgb) / 0.4);
  background: var(--red-50);
}

.immersive-self-action-btn--danger:hover {
  background: var(--red-100);
  border-color: rgb(var(--red-700-rgb) / 0.55);
}

.immersive-feed-action-count {
  font-size: var(--font-size-12);
  line-height: 1;
  color: var(--color-text-inverse);
  text-shadow: 0 1px 4px rgb(var(--black-rgb) / 0.45);
  white-space: nowrap;
}

.immersive-feed-bottom-bar {
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

.immersive-feed-player-column {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.immersive-feed-comments-panel-head {
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

.immersive-feed-comments-panel-head h3 {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text);
}

.immersive-feed-comments-panel-close {
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

.immersive-feed-comments-panel-close:hover {
  background: linear-gradient(180deg, var(--slate-50) 0%, var(--slate-200) 100%);
  color: var(--color-text-strong);
}

.immersive-feed-comments-panel-scroll {
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
