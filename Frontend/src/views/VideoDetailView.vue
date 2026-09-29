<script setup>
/**
 * 视频详情页（播放器/点赞关注/评论区/侧栏推荐）。
 * 业务状态与动作在 detailStore；本组件只保留 DOM 交互（输入框、滚动、表情插入）。
 */
import { ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useAuthStore } from "../stores/authStore";
import { useDetailStore, detailSidebarTabs } from "../stores/detailStore";
import { formatCommentTime, insertGlyphIntoCommentDraft, isBlockedComment } from "../utils/comment.js";
import { cardAuthorLabel, parseSearchTagsArray } from "../utils/videoCard.js";
import { applyAvatarFallback, DEFAULT_PROFILE_AVATAR_URL as profileAvatar, DEFAULT_PROFILE_BG_URL as profileBanner } from "../utils/userProfile";
import ChatEmojiPicker from "../components/chat/ChatEmojiPicker.vue";

const authStore = useAuthStore();
const { isAuthed, userId: currentUserId, selfAvatarUrl: topbarAvatarSrc } = storeToRefs(authStore);

const store = useDetailStore();
const {
  open: showVideoDetail,
  video: videoDetail,
  loading: detailLoading,
  buffering: detailVideoBuffering,
  errorMsg: detailErrorMsg,
  playerRef: detailPlayerRef,
  sidebarTab: detailSidebarTab,
  sidebarList: detailSidebarList,
  sidebarLoading: detailSidebarLoading,
  sidebarLoadingMore: detailSidebarLoadingMore,
  topComments: detailTopComments,
  commentsLoading: detailCommentsLoading,
  submitting: detailCommentSubmitting,
  draft: detailCommentDraft,
  replyTarget: detailReplyTarget,
  threadByRootId: detailThreadByRootId,
  expandedRoots: detailExpandedRoots,
  sessionLiked: detailSessionLiked,
  likeBusy: detailLikeBusy,
  followBusy: detailFollowBusy,
  authorAvatar: detailAuthorAvatar,
  fanText: detailFanText,
  subscribeFollowed: detailSubscribeFollowed,
  allowComment: detailVideoAllowComment,
  commentHasMore: detailCommentHasMore,
} = storeToRefs(store);

const detailCommentInputRef = ref(null);
const detailSidebarScrollRef = ref(null);

/** 切换视频后侧栏回到顶部 */
watch(
  () => String(store.video?.id ?? ""),
  () => {
    const el = detailSidebarScrollRef.value;
    if (el) el.scrollTop = 0;
  },
);

function onDetailCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(detailCommentDraft, detailCommentInputRef, glyph);
}

function onDetailCommentUserClick(ev, userId) {
  store.onCommentUserClick(ev, userId);
}

function onAuthorAvatarClick(ev, authorId) {
  ev?.stopPropagation?.();
  store.onCommentUserClick(ev, authorId);
}

function onTopbarAvatarError(e) {
  applyAvatarFallback(e, profileAvatar);
}
</script>

<template>
      <div v-if="showVideoDetail" class="video-detail-page video-detail-page--layer">
        <div v-if="detailLoading" class="video-detail-loading" aria-busy="true">
          <span class="video-player-loading__ring video-player-loading__ring--detail" />
          <span class="video-detail-loading__text">加载中…</span>
    </div>
        <template v-else-if="videoDetail">
          <div class="video-detail-layout">
            <div class="video-detail-main">
              <div class="video-detail-player-wrap">
                <video
                  v-if="videoDetail.videoUrl"
                  :key="String(videoDetail.id)"
                  ref="detailPlayerRef"
                  class="video-detail-player"
                  controls
                  playsinline
                  preload="auto"
                  autoplay
                  :src="videoDetail.videoUrl"
                  @loadstart="store.onVideoLoadStart"
                  @canplay="store.onVideoCanPlay"
                  @error="store.onVideoMediaError"
                  @loadeddata="() => void store.tryAutoplay()"
                  @loadedmetadata="() => { store.applyPlaybackResumeIfNeeded(); void store.tryAutoplay(); }"
                  @canplaythrough="() => void store.tryAutoplay()"
                  @pause="store.onVideoPause"
                  @play="store.onVideoPlay"
                  @timeupdate="store.onVideoTimeUpdate"
                  @seeked="store.onVideoSeeked"
                  @ended="store.onVideoEnded"
                />
                <div
                  v-if="videoDetail.videoUrl && detailVideoBuffering"
                  class="video-player-loading video-player-loading--detail"
                  aria-live="polite"
                  aria-busy="true"
                >
                  <div class="video-player-loading__shimmer" aria-hidden="true" />
                  <div class="video-player-loading__inner">
                    <span class="video-player-loading__ring video-player-loading__ring--detail" />
                    <span class="video-player-loading__text video-player-loading__text--detail">正在加载视频</span>
  </div>
                </div>
                <div v-else-if="!videoDetail.videoUrl" class="video-detail-player-empty">暂无播放地址</div>
              </div>
              <h1 class="video-detail-title">{{ videoDetail.title }}</h1>
              <div class="video-detail-meta-row">
                <div class="video-detail-channel">
                  <div class="video-detail-channel-avatar-wrap">
                    <img
                      :src="detailAuthorAvatar"
                      alt=""
                      class="video-detail-channel-avatar"
                      role="button"
                      title="查看作者主页"
                      @click.stop="onAuthorAvatarClick($event, videoDetail.authorId)"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                  </div>
                  <div class="video-detail-channel-text">
                    <strong>{{ videoDetail.authorNickname }}</strong>
                    <span>{{ detailFanText }}</span>
                  </div>
                  <button
                    v-if="videoDetail.authorId && String(videoDetail.authorId) !== String(currentUserId)"
                    type="button"
                    class="video-detail-subscribe"
                    :disabled="detailFollowBusy"
                    @click="store.onFollowClick"
                  >
                    {{ detailSubscribeFollowed ? "已关注" : "+ 关注" }}
                  </button>
                </div>
                <div class="video-detail-actions">
                  <button
                    type="button"
                    class="video-detail-action-pill"
                    :class="{ 'video-detail-action-pill--active': detailSessionLiked }"
                    :disabled="detailLikeBusy"
                    @click="store.onLikeClick"
                  >
                    <i :class="detailSessionLiked ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"></i>
                    {{ videoDetail.likeCount ?? 0 }}
                  </button>
                  <button type="button" class="video-detail-action-pill" @click="store.onDislikeClick">
                    <i class="ri-thumb-down-line"></i> 不感兴趣
                  </button>
                  <button type="button" class="video-detail-action-pill" @click="store.openForwardVideo(videoDetail)">
                    <i class="ri-share-forward-line"></i> 分享
                  </button>
                </div>
              </div>
              <p class="video-detail-views">{{ videoDetail.viewsText }}</p>
              <p v-if="detailErrorMsg" class="video-detail-api-hint">{{ detailErrorMsg }}</p>
              <p v-if="videoDetail.description" class="video-detail-desc">{{ videoDetail.description }}</p>
              <p v-if="parseSearchTagsArray(videoDetail).length" class="video-detail-tags-row">
                <span class="search-feed-tag-row">
                  <button
                    v-for="tg in parseSearchTagsArray(videoDetail)"
                    :key="'dvtg-' + String(videoDetail.id) + '-' + tg"
                    type="button"
                    class="search-feed-tag-chip"
                    @click.stop="store.onSearchTagNavigate(tg)"
                  >
                    {{ tg.startsWith("#") ? tg : "#" + tg }}
                  </button>
                </span>
              </p>

              <section class="video-detail-comments" aria-label="评论区">
                <div class="video-detail-comments-head">
                  <h3>评论 {{ videoDetail.commentCount != null ? `（${videoDetail.commentCount}）` : "" }}</h3>
                  <span v-if="!detailVideoAllowComment" class="video-detail-comments-note">作者已关闭评论</span>
                </div>
                <p v-if="detailReplyTarget" class="video-detail-comment-reply-hint">
                  回复 @{{ store.displayCommentNickname(detailReplyTarget.userId, detailReplyTarget.nickname) }}
                  <button type="button" class="video-detail-comment-reply-cancel" @click="store.setReplyTarget(null)">取消</button>
                </p>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input
                    ref="detailCommentInputRef"
                    v-model="detailCommentDraft"
                    type="text"
                    class="video-detail-comment-field"
                    :disabled="!isAuthed || !detailVideoAllowComment || detailCommentSubmitting"
                    :placeholder="
                      !isAuthed
                        ? '登录后参与评论…'
                        : !detailVideoAllowComment
                          ? '评论已关闭'
                          : detailReplyTarget
                            ? `回复 @${store.displayCommentNickname(detailReplyTarget.userId, detailReplyTarget.nickname)}…`
                            : '添加评论…'
                    "
                    maxlength="500"
                    @keyup.enter.prevent="store.submitComment"
                  />
                  <div class="video-detail-comment-input-actions">
                    <ChatEmojiPicker
                      :disabled="!isAuthed || !detailVideoAllowComment || detailCommentSubmitting"
                      @pick="onDetailCommentEmojiPick"
                    />
                    <button
                      type="button"
                      class="video-detail-comment-send"
                      :disabled="!isAuthed || !detailVideoAllowComment || detailCommentSubmitting || !detailCommentDraft.trim()"
                      @click="store.submitComment"
                    >
                      {{ detailCommentSubmitting ? "发送中…" : "发送" }}
                    </button>
                  </div>
                </div>
                <p v-if="detailCommentsLoading && !detailTopComments.length" class="video-detail-comments-empty">加载评论中…</p>
                <ul v-else class="video-detail-comments-mock">
                  <template v-for="c in detailTopComments" :key="`dc-top-${c.id}`">
                    <li class="video-detail-comment-item">
                      <img
                        :src="c.avatarUrl || profileAvatar"
                        alt=""
                        class="video-detail-comment-avatar video-detail-comment-avatar--clickable"
                        role="button"
                        tabindex="0"
                        title="查看用户主页"
                        @click.stop="onDetailCommentUserClick($event, c.userId)"
                        @keyup.enter.stop="onDetailCommentUserClick($event, c.userId)"
                        @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                      />
                      <div class="video-detail-comment-body">
                        <div class="video-detail-comment-meta">
                          <button
                            type="button"
                            class="video-detail-comment-user video-detail-comment-user--link"
                            @click.stop="onDetailCommentUserClick($event, c.userId)"
                          >
                            {{ store.displayCommentNickname(c.userId, c.nickname) }}
                          </button>
                          <span class="video-detail-comment-time">{{ formatCommentTime(c.createTime) }}</span>
                        </div>
                        <p class="video-detail-comment-text">{{ c.content }}</p>
                        <div class="video-detail-comment-actions">
                          <button
                            type="button"
                            class="video-detail-comment-like-btn"
                            :class="{ 'video-detail-comment-like-btn--active': c.isLike }"
                            :disabled="store.isCommentLikeBusy(c.id)"
                            :aria-pressed="c.isLike ? 'true' : 'false'"
                            :aria-label="c.isLike ? '取消点赞' : '点赞'"
                            @click="store.submitCommentLike(c)"
                          >
                            <i
                              :class="c.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                              aria-hidden="true"
                            ></i>
                            {{ c.likeCount ?? 0 }}
                          </button>
                          <button
                            v-if="isAuthed && detailVideoAllowComment"
                            type="button"
                            class="video-detail-comment-action-btn"
                            @click="store.setReplyTarget(c)"
                          >
                            回复
                          </button>
                          <button
                            v-if="isAuthed && currentUserId != null && String(c.userId) === String(currentUserId)"
                            type="button"
                            class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                            @click="store.deleteComment(c)"
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
                            @click="store.openCommentReport(c)"
                          >
                            <i class="ri-flag-line" aria-hidden="true"></i>举报
                          </button>
                        </div>
                        <button
                          v-if="c.replyCount > 0"
                          type="button"
                          class="video-detail-thread-toggle"
                          @click="store.toggleThread(c.id)"
                        >
                          {{
                            detailExpandedRoots[String(c.id)]
                              ? "收起回复"
                              : `展开 ${c.replyCount} 条回复`
                          }}
                        </button>
                        <ul
                          v-if="detailExpandedRoots[String(c.id)]"
                          class="video-detail-comment-replies"
                        >
                          <li
                            v-for="r in detailThreadByRootId[String(c.id)]?.list || []"
                            :key="`dc-r-${r.id}`"
                            class="video-detail-comment-item video-detail-comment-item--reply"
                          >
                            <img
                              :src="r.avatarUrl || profileAvatar"
                              alt=""
                              class="video-detail-comment-avatar video-detail-comment-avatar--sm video-detail-comment-avatar--clickable"
                              role="button"
                              tabindex="0"
                              title="查看用户主页"
                              @click.stop="onDetailCommentUserClick($event, r.userId)"
                              @keyup.enter.stop="onDetailCommentUserClick($event, r.userId)"
                              @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                            />
                            <div class="video-detail-comment-body">
                              <div class="video-detail-comment-meta">
                                <button
                                  type="button"
                                  class="video-detail-comment-user video-detail-comment-user--link"
                                  @click.stop="onDetailCommentUserClick($event, r.userId)"
                                >
                                  {{ store.displayCommentNickname(r.userId, r.nickname) }}
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
                                  @click.stop="onDetailCommentUserClick($event, r.replyUserId)"
                                >
                                  @{{ store.displayCommentNickname(r.replyUserId, r.replyNickname) }}
                                </button>
                                <span v-else-if="r.replyNickname" class="video-detail-comment-at"
                                  >@{{ store.displayCommentNickname(r.replyUserId, r.replyNickname) }}</span>
                                <span class="video-detail-comment-time">{{ formatCommentTime(r.createTime) }}</span>
                              </div>
                              <p class="video-detail-comment-text">{{ r.content }}</p>
                              <div class="video-detail-comment-actions">
                                <button
                                  type="button"
                                  class="video-detail-comment-like-btn"
                                  :class="{ 'video-detail-comment-like-btn--active': r.isLike }"
                                  :disabled="store.isCommentLikeBusy(r.id)"
                                  :aria-pressed="r.isLike ? 'true' : 'false'"
                                  :aria-label="r.isLike ? '取消点赞' : '点赞'"
                                  @click="store.submitCommentLike(r)"
                                >
                                  <i
                                    :class="r.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                    aria-hidden="true"
                                  ></i>
                                  {{ r.likeCount ?? 0 }}
                                </button>
                                <button
                                  v-if="isAuthed && detailVideoAllowComment"
                                  type="button"
                                  class="video-detail-comment-action-btn"
                                  @click="store.setReplyTarget(r)"
                                >
                                  回复
                                </button>
                                <button
                                  v-if="isAuthed && currentUserId != null && String(r.userId) === String(currentUserId)"
                                  type="button"
                                  class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                                  @click="store.deleteComment(r)"
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
                                  @click="store.openCommentReport(r)"
                                >
                                  <i class="ri-flag-line" aria-hidden="true"></i>举报
                                </button>
                              </div>
                            </div>
                          </li>
                          <li
                            v-if="detailThreadByRootId[String(c.id)]?.loading"
                            class="video-detail-comment-item video-detail-comment-item--reply"
                          >
                            <span class="video-detail-comments-empty">加载回复中…</span>
                          </li>
                        </ul>
                      </div>
                    </li>
                  </template>
                </ul>
                <p v-if="!detailCommentsLoading && !detailTopComments.length" class="video-detail-comments-empty">暂无评论，来抢沙发吧</p>
                <div v-if="detailCommentHasMore" class="video-detail-comments-more">
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="detailCommentsLoading"
                    @click="store.loadMoreComments"
                  >
                    {{ detailCommentsLoading ? "加载中…" : "加载更多评论" }}
                  </button>
                </div>
              </section>
            </div>

            <aside class="video-detail-side" aria-label="更多内容">
              <div class="video-detail-side-chips">
                <button
                  v-for="tab in detailSidebarTabs"
                  :key="tab.key"
                  type="button"
                  class="video-detail-chip"
                  :class="{ 'video-detail-chip--active': detailSidebarTab === tab.key }"
                  @click="store.setSidebarTab(tab.key)"
                >
                  {{ tab.label }}
                </button>
              </div>
              <div ref="detailSidebarScrollRef" class="video-detail-side-scroll" @scroll.passive="store.onSidebarScroll">
                <p v-if="detailSidebarLoading && !detailSidebarList.length" class="video-detail-side-hint">加载中…</p>
                <button
                  v-for="item in detailSidebarList"
                  :key="item.id"
                  type="button"
                  class="video-detail-side-item"
                  :class="{ 'video-detail-side-item--current': store.isSidebarItemNowPlaying(item) }"
                  @click="store.openFromSidebar(item)"
                >
                  <div class="video-detail-side-thumb-wrap">
                    <img :src="item.coverUrl || item.avatarUrl || profileBanner" alt="" class="video-detail-side-thumb" />
                    <div
                      v-if="store.isSidebarItemNowPlaying(item)"
                      class="video-detail-now-playing"
                      aria-hidden="true"
                    >
                      <i class="ri-play-fill video-detail-now-playing-icon" aria-hidden="true"></i>
                      <span class="video-detail-now-playing-text">正在播放</span>
                    </div>
                  </div>
                  <div class="video-detail-side-info">
                    <span class="video-detail-side-title">{{ item.title }}</span>
                    <span class="video-detail-side-sub">{{ cardAuthorLabel(item) }} · {{ item.viewsText }}</span>
                  </div>
                </button>
                <p v-if="detailSidebarLoadingMore" class="video-detail-side-hint">加载更多…</p>
                <p v-if="!detailSidebarLoading && !detailSidebarList.length" class="video-detail-side-hint">暂无推荐</p>
              </div>
            </aside>
          </div>
        </template>
      </div>
</template>

<style scoped>
/* —— 视频详情页：独立占满主区域（与首页滚动区分离），浅色主题与全站一致 —— */
.video-detail-page--layer {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg);
  color: var(--text);
  position: relative;
  z-index: 5;
}

.video-detail-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text);
}

.video-detail-loading {
  flex: 1;
  min-height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-16);
  padding: var(--space-48);
  text-align: center;
  color: var(--muted);
}

.video-detail-loading__text {
  font-size: var(--font-size-14);
  font-weight: 600;
  color: var(--color-text-muted);
}

.video-detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 400px);
  gap: var(--space-20);
  padding: var(--space-8) var(--space-18) var(--space-24);
  flex: 1;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
}

.video-detail-main {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: var(--space-8);
  padding-bottom: var(--space-8);
  -webkit-overflow-scrolling: touch;
}

.video-detail-player-wrap {
  position: relative;
  /* 视频未铺满区域用浅灰填充，避免大块纯黑 */
  background: var(--gray-200);
  border-radius: var(--radius-2xl);
  overflow: hidden;
  margin-bottom: var(--space-12);
  border: 1px solid var(--line);
}

/* 元数据未到时 <video> 无固有高度，先固定 16:9 占位，避免加载层期间被压扁再撑开 */
.video-detail-player-wrap:has(> .video-detail-player) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 100%;
  max-height: min(70vh, 720px);
  aspect-ratio: 16 / 9;
  min-height: min(200px, 42vw);
}

.video-detail-player-wrap:has(> .video-detail-player) .video-detail-player {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  vertical-align: top;
}

.video-player-loading--detail {
  border-radius: var(--radius-2xl);
  background: linear-gradient(155deg, var(--slate-200) 0%, var(--gray-200) 42%, var(--slate-300) 100%);
}

.video-player-loading__ring--detail {
  border: 3px solid rgb(var(--blue-600-rgb) / 0.18);
  border-top-color: var(--blue-600);
}

.video-player-loading__text--detail {
  color: var(--color-text-muted);
}

.video-detail-player {
  width: 100%;
  max-height: min(70vh, 720px);
  display: block;
  background: transparent;
  vertical-align: top;
}

.video-detail-player-empty {
  aspect-ratio: 16 / 9;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--muted);
  background: var(--gray-200);
}

.video-detail-title {
  margin: var(--space-8) 0 var(--space-12);
  font-size: var(--font-size-20);
  font-weight: 700;
  line-height: 1.3;
  color: var(--text);
}

.video-detail-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-12);
  margin-bottom: var(--space-8);
}

.video-detail-channel {
  display: flex;
  align-items: center;
  gap: var(--space-12);
  min-width: 0;
}

.video-detail-channel-avatar-wrap {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--line);
  border: 1px solid var(--line);
}

.video-detail-channel-text {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
}

.video-detail-channel-text strong {
  font-size: var(--font-size-15);
  color: var(--text);
}

.video-detail-channel-text span {
  font-size: var(--font-size-13);
  color: var(--muted);
}

.video-detail-subscribe {
  margin-left: var(--space-8);
  border: 1px solid var(--line);
  border-radius: var(--radius-full);
  padding: var(--space-8) var(--space-18);
  font-size: var(--font-size-14);
  font-weight: 600;
  background: var(--color-bg-surface);
  color: var(--text);
  cursor: not-allowed;
}

.video-detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-8);
}

.video-detail-action-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
  border: 1px solid var(--line);
  border-radius: var(--radius-full);
  padding: var(--space-8) var(--space-14);
  font-size: var(--font-size-13);
  background: var(--color-bg-surface);
  color: var(--text);
  cursor: not-allowed;
}

.video-detail-action-pill:not(:disabled) {
  cursor: pointer;
}

.video-detail-action-pill--active {
  border-color: var(--accent);
  color: var(--accent);
  background: rgb(var(--red-brand-rgb) / 0.08);
}

.video-detail-views {
  margin: 0 0 var(--space-12);
  font-size: var(--font-size-14);
  color: var(--muted);
}

.video-detail-api-hint {
  font-size: var(--font-size-13);
  color: var(--amber-500);
  margin: 0 0 var(--space-10);
}

.video-detail-desc {
  margin: 0 0 var(--space-20);
  font-size: var(--font-size-14);
  line-height: 1.55;
  color: var(--text);
  padding: var(--space-12) var(--space-14);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-2xl);
}

.video-detail-tags-row {
  margin: -8px 0 var(--space-20);
}

.video-detail-comments {
  margin-top: var(--space-8);
  padding-top: var(--space-16);
  border-top: 1px solid var(--line);
}

.video-detail-comments-head {
  display: flex;
  align-items: center;
  gap: var(--space-10);
  margin-bottom: var(--space-14);
  flex-wrap: wrap;
}

.video-detail-comments-head h3 {
  margin: 0;
  font-size: var(--font-size-16);
  color: var(--text);
}

.video-detail-side {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  border-left: 1px solid var(--line);
  padding-left: var(--space-16);
  background: var(--panel);
  border-radius: var(--radius-2xl);
  padding: var(--space-12) var(--space-12) var(--space-12) var(--space-16);
  border: 1px solid var(--line);
  overflow: hidden;
}

.video-detail-side-chips {
  display: flex;
  flex-wrap: nowrap;
  gap: var(--space-10);
  margin-bottom: var(--space-12);
  width: 100%;
}

.video-detail-chip {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-full);
  padding: var(--space-10) var(--space-20);
  font-size: var(--font-size-14);
  font-weight: 600;
  cursor: pointer;
  background: var(--color-bg-subtle);
  color: var(--text);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.video-detail-chip:hover {
  background: var(--hover);
}

.video-detail-chip--active {
  background: var(--active);
  border-color: var(--color-border-strong);
  color: var(--color-text-strong);
}

.video-detail-side-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: var(--space-4);
  padding-bottom: var(--space-8);
  -webkit-overflow-scrolling: touch;
}

.video-detail-side-hint {
  font-size: var(--font-size-13);
  color: var(--muted);
  padding: var(--space-12) 0;
  text-align: center;
}

.video-detail-side-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-10);
  width: 100%;
  border: none;
  background: transparent;
  padding: 0 0 var(--space-14);
  margin-bottom: var(--space-4);
  cursor: pointer;
  text-align: left;
  color: inherit;
}

.video-detail-side-item:hover .video-detail-side-title {
  color: var(--accent);
}

.video-detail-side-thumb-wrap {
  position: relative;
  flex-shrink: 0;
  width: 168px;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-xl);
  overflow: hidden;
  background: var(--line);
}

.video-detail-now-playing {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-6);
  padding: var(--space-10) var(--space-8);
  background: rgb(var(--slate-900-rgb) / 0.62);
  color: var(--color-text-inverse);
  text-shadow: 0 1px 3px rgb(var(--black-rgb) / 0.5);
  pointer-events: none;
}

.video-detail-now-playing-icon {
  font-size: var(--font-size-30);
  line-height: 1;
  filter: drop-shadow(0 1px 2px rgb(var(--black-rgb) / 0.35));
  animation: video-detail-now-pulse 1.6s ease-in-out infinite;
}

.video-detail-now-playing-text {
  font-size: var(--font-size-12);
  font-weight: 800;
  letter-spacing: 0.02em;
}

.video-detail-side-item--current .video-detail-side-thumb-wrap {
  box-shadow: 0 0 0 2px var(--blue-500);
}

.video-detail-side-item--current .video-detail-side-title {
  color: var(--blue-700);
  font-weight: 700;
}

.video-detail-side-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.video-detail-side-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-width: 0;
}

.video-detail-side-title {
  font-size: var(--font-size-14);
  font-weight: 600;
  color: var(--text);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.video-detail-side-sub {
  font-size: var(--font-size-12);
  color: var(--muted);
}

@media (max-width: 1100px) {
  .video-detail-layout {
    grid-template-columns: 1fr;
  }

  .video-detail-side {
    border-left: none;
    padding-left: var(--space-12);
    border-top: 1px solid var(--line);
    padding-top: var(--space-16);
  }

  .video-detail-side-scroll {
    max-height: min(50vh, 480px);
  }
}

@media (max-width: 1100px) {
  .video-detail-layout {
    grid-template-columns: 1fr;
  }

  .video-detail-side {
    border-left: none;
    padding-left: var(--space-12);
    border-top: 1px solid var(--line);
    padding-top: var(--space-16);
  }

  .video-detail-side-scroll {
    max-height: min(50vh, 480px);
  }
}

@media (max-width: 1100px) {
  .video-detail-layout {
    grid-template-columns: 1fr;
  }

  .video-detail-side {
    border-left: none;
    padding-left: var(--space-12);
    border-top: 1px solid var(--line);
    padding-top: var(--space-16);
  }

  .video-detail-side-scroll {
    max-height: min(50vh, 480px);
  }
}
</style>
