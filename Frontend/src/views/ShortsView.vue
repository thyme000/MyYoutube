<script setup>
/**
 * 短视频域：沉浸 feed(短视频/关注/全屏队列)、共享评论面板、搜索全屏放大层。
 * 过渡期：全部状态/函数/DOM ref 经 App.vue provide 的 shortsBridge 注入
 * (ref 对象直传保持响应式)；后续逐步迁入 shortsStore 与本组件，最终撤桥。
 */
import { inject } from "vue";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile";

const {
  activeCommentVideo,
  activeCommentVideoAllowsComment,
  activeMenuKey,
  cardAuthorAvatar,
  closeProfileFeed,
  closeSearchExpand,
  closeShortsCommentPanel,
  commentDisplayNickname,
  currentUserId,
  feedScope,
  followSubscribedFilterAuthorId,
  followingHasMore,
  followingLoading,
  followingRows,
  formatDetailCommentTime,
  formatSearchRelTime,
  formatShortsTime,
  isAuthed,
  isBlockedComment,
  isCommentLikeBusy,
  loadFollowingNextPage,
  loadMoreShortsPanelComments,
  loading,
  onAuthorAvatarClick,
  onDeleteShortsPanelComment,
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
  onSearchTagNavigate,
  onShortsDislikeClick,
  onShortsFeedFgCanPlay,
  onShortsFeedFgDurationChange,
  onShortsFeedFgEnded,
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
  onShortsPanelCommentEmojiPick,
  onShortsPanelCommentUserClick,
  onShortsScrubInput,
  onShortsVolumeInput,
  onTopbarAvatarError,
  openCommentReportModal,
  parseSearchTagsArray,
  profileFeedOpen,
  requestDeleteCurrentSelfVideo,
  searchActive,
  searchExpandBlurRef,
  searchExpandCard,
  searchExpandCommentOpen,
  searchExpandCurrentTime,
  searchExpandDuration,
  searchExpandDurationReady,
  searchExpandIndex,
  searchExpandIsFollowed,
  searchExpandIsPortrait,
  searchExpandIsSelf,
  searchExpandLikedDisplay,
  searchExpandMuted,
  searchExpandNext,
  searchExpandOpen,
  searchExpandPaused,
  searchExpandPlayerRef,
  searchExpandPrev,
  searchExpandRequestFullscreen,
  searchExpandRequestPip,
  searchExpandScrubDisplayValue,
  searchExpandScrubMax,
  searchExpandStageRef,
  searchExpandToggleMute,
  searchExpandVideoUrl,
  searchExpandVolume,
  searchListCommentOpen,
  selectFollowSidebarAll,
  selectFollowSidebarAuthor,
  selfVideoActionOpen,
  setShortsPanelReplyTarget,
  shortsCommentPanelOpen,
  shortsDurationReady,
  shortsFeedAuthorAvatar,
  shortsFeedAuthorName,
  shortsFeedBgRef,
  shortsFeedCenterRef,
  shortsFeedCurrent,
  shortsFeedCurrentTime,
  shortsFeedDescText,
  shortsFeedDuration,
  shortsFeedFgRef,
  shortsFeedIsPortrait,
  shortsFeedLikedDisplay,
  shortsFeedMetaTimeText,
  shortsFeedMuted,
  shortsFeedNext,
  shortsFeedPaused,
  shortsFeedPlayToken,
  shortsFeedPrev,
  shortsFeedTitleText,
  shortsFeedVideoBuffering,
  shortsFeedVideoSrc,
  shortsFeedVolume,
  shortsIsFollowedCurrent,
  shortsIsSelfCurrent,
  shortsPanelCommentDraft,
  shortsPanelCommentHasMore,
  shortsPanelCommentInputRef,
  shortsPanelCommentSubmitting,
  shortsPanelCommentsLoading,
  shortsPanelExpandedRoots,
  shortsPanelReplyTarget,
  shortsPanelThreadByRootId,
  shortsPanelTopComments,
  shortsRequestFullscreen,
  shortsRequestPip,
  shortsScrubDisplayValue,
  shortsScrubMax,
  shortsToggleMute,
  shortsTogglePlay,
  showProfileCenter,
  submitCommentLike,
  submitShortsPanelComment,
  toggleCurrentSelfVideoAllowComment,
  toggleCurrentSelfVideoVisibility,
  toggleSearchExpandCommentPanel,
  toggleSearchExpandFollow,
  toggleSearchExpandPlay,
  toggleSelfVideoActionMenu,
  toggleShortsCommentPanel,
  toggleShortsFollow,
  toggleShortsPanelThread,
  topbarAvatarSrc,
} = inject("shortsBridge");
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
                      {{ searchExpandCard.description || "暂无描述" }}
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
                  <p v-if="!shortsPanelCommentsLoading && !shortsPanelTopComments.length" class="video-detail-comments-empty">
                    暂无评论，来抢沙发吧
                  </p>
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
          v-if="profileFeedOpen || (!showProfileCenter && (activeMenuKey === 'shorts' || activeMenuKey === 'subscribed'))"
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
                <p v-else-if="!followingRows.length" class="follow-sidebar-hint">暂无关注，去发现页关注作者吧</p>
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
            {{ activeMenuKey === "subscribed" ? "正在加载关注动态…" : "正在加载短视频…" }}
          </div>
          <template v-else-if="shortsFeedVideoSrc">
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
                    <video
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
                        {{ shortsFeedDescText || "暂无描述" }}
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
          <div v-else class="shorts-feed-empty">
            <div class="module-empty-icon"><i class="ri-video-line"></i></div>
            <h5>{{ activeMenuKey === "subscribed" ? "暂无关注动态" : "暂无短视频" }}</h5>
            <p>
              {{
                activeMenuKey === "subscribed"
                  ? "关注作者后将在此展示推荐短视频；也可在左侧点选某位作者"
                  : "当前模块暂无可播放内容"
              }}
            </p>
          </div>
        </section>
        </div>

</template>
