// @ts-check
/**
 * 视频详情域：播放器状态机、长视频续播上报、评论区、作者作品侧栏、URL/sessionStorage 现场恢复。
 * 详情页会收起搜索/创作中心/短视频流等 App 级遮罩，并需要跨列表同步关注态，这类宿主能力经 registerDetailHooks 注入。
 */
import { defineStore } from "pinia";
import { computed, nextTick, ref, watch } from "vue";
import { fetchCommentPage, deleteComment as deleteCommentApi, publishComment as publishCommentApi } from "../api/commentApi";
import { cancelLikeVideo, fetchVideoLikedStatus, likeVideo, dislikeVideo } from "../api/likeApi";
import { fetchAuthorVideos, fetchVideoDetail, normalizeVideoDetail } from "../api/videoApi";
import { followAuthor, unfollowAuthor } from "../api/followApi";
import { parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { toast } from "../utils/toast";
import { cardAuthorLabel, resolveVideoUrl } from "../utils/videoCard.js";
import { commentDisplayNickname, isBlockedComment } from "../utils/comment.js";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar, DEFAULT_PROFILE_BG_URL as profileBanner, sanitizeImageUrl } from "../utils/userProfile";
import { useAuthStore } from "./authStore";
import { useBehaviorStore } from "./behaviorStore";
import { useCommentStore } from "./commentStore";
import { useFollowStore } from "./followStore";
import { useProfileStore } from "./profileStore";
import { useShareStore } from "./shareStore";
import { useStudioStore } from "./studioStore";

const SIDEBAR_PAGE_SIZE = 10;
const COMMENT_PAGE_SIZE = 10;
/** 仅当加载超过阈值才显示加载层，避免闪一下 */
const BUFFERING_UI_DELAY_MS = 280;
/** 顺播累计达到总时长该比例即上报 PLAY；单次 timeupdate 跳跃过大视为拖动，不计入 */
const PLAY_THRESHOLD = 0.7;
const MAX_PLAY_DELTA_SEC = 3;
const DETAIL_STORAGE_PREFIX = "myyoutube_vdetail_";

/**
 * @type {{
 *   pauseShortsFeed: () => void,
 *   closeProfileFeedIfOpen: () => void,
 *   closeUploadModal: () => void,
 *   patchAuthorFollow: (authorId: any, isFollow: boolean) => void,
 *   onSearchTagNavigate: (tag: any) => void,
 *   navigateToVideo: (id: string, opts?: { replace?: boolean }) => void,
 *   leaveVideoDetail: () => void,
 * }}
 */
const hooks = {
  /** 打开详情前停掉后台短视频音频（浏览器可能继续播放隐藏元素） */
  pauseShortsFeed: () => {},
  /** 主页全屏流开着时收起 */
  closeProfileFeedIfOpen: () => {},
  closeUploadModal: () => {},
  /** 跨列表同步同一作者的 isFollow */
  patchAuthorFollow: () => {},
  onSearchTagNavigate: () => {},
  /** 进详情 = push /video/:id；详情内切视频 = replace，不叠历史 */
  navigateToVideo: () => {},
  /** 关闭详情：有来路 router.back，深链直入则 replace 回菜单页；状态由 route watcher 收敛 */
  leaveVideoDetail: () => {},
};

export function registerDetailHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

function videoIdsEqual(a, b) {
  if (a == null || b == null) return false;
  return String(a) === String(b);
}

function buildDetailFromCard(card) {
  const vid = parseDigitSnowflakeId(card?.id);
  return normalizeVideoDetail({
    id: vid ?? (card?.id != null ? String(card.id) : ""),
    title: card?.title ?? "未命名视频",
    description: card?.description ?? "",
    coverUrl: card?.coverUrl || card?.avatarUrl || profileBanner,
    videoUrl: resolveVideoUrl(card) || "",
    playCount: card?.playCount ?? 0,
    likeCount: card?.likeCount ?? 0,
    commentCount: card?.commentCount ?? 0,
    authorId: card?.authorId ?? null,
    authorNickname: card?.authorNickname ?? card?.author ?? "频道",
    authorAvatarUrl: card?.authorAvatarUrl || "",
    authorFanCount: card?.authorFanCount ?? 0,
    isFollow: card?.isFollow,
    isLike: card?.isLike,
    allowComment: card?.allowComment,
  });
}

export const useDetailStore = defineStore("detail", () => {
  const auth = useAuthStore();
  const behavior = useBehaviorStore();
  const comments = useCommentStore();
  const follows = useFollowStore();
  const profile = useProfileStore();
  const share = useShareStore();
  const studio = useStudioStore();

  const open = ref(false);
  const video = ref(null);
  const loading = ref(false);
  const buffering = ref(false);
  const errorMsg = ref("");
  /** 播放状态机要读播放器元素（进度、暂停态），ref 由 store 持有并交给模板绑定 */
  const playerRef = ref(null);

  const sidebarList = ref([]);
  const sidebarPageNo = ref(1);
  const sidebarPages = ref(0);
  const sidebarLoading = ref(false);
  const sidebarLoadingMore = ref(false);
  const sidebarHasMore = ref(true);

  const topComments = ref([]);
  const commentsLoading = ref(false);
  const commentsPageNo = ref(1);
  const commentsPages = ref(0);
  const draft = ref("");
  const submitting = ref(false);
  const replyTarget = ref(null);
  /** 楼主 id → { loaded, loading, list } */
  const threadByRootId = ref({});
  const expandedRoots = ref({});

  const sessionLiked = ref(false);
  const likeBusy = ref(false);
  const followBusy = ref(false);

  let bufferingTimer = null;
  let playAccSec = 0;
  let lastTimeSec = null;
  /** 用户手动暂停后禁止自动 play 重试，直到再次点击播放 */
  let userPausedManually = false;
  let resumeInFlight = false;
  let resumeLastSentAtMs = 0;
  let resumeTickCount = 0;
  let resumeAppliedVideoId = "";
  let lastLikeAt = 0;

  const authorAvatar = computed(() => {
    const raw = video.value?.authorAvatarUrl;
    return (raw ? sanitizeImageUrl(raw) : "") || profileAvatar;
  });

  const fanText = computed(() => {
    const n = video.value?.authorFanCount;
    if (n == null || n === "") {
      return "粉丝 —";
    }
    return `${n} 位粉丝`;
  });

  const subscribeFollowed = computed(() => {
    const aid = video.value?.authorId;
    if (aid == null || aid === "") {
      return false;
    }
    const override = follows.getOverride(aid);
    return override === undefined ? Boolean(video.value?.isFollow) : override;
  });

  const allowComment = computed(() => Number(video.value?.allowComment ?? 1) === 1);
  const commentHasMore = computed(() => commentsPageNo.value < commentsPages.value);
  const displayCommentNickname = (userId, nickname) =>
    commentDisplayNickname(userId, nickname, auth.userId);

  function clearBufferingTimer() {
    if (bufferingTimer != null) {
      clearTimeout(bufferingTimer);
      bufferingTimer = null;
    }
  }

  function scheduleBufferingOverlay() {
    clearBufferingTimer();
    buffering.value = false;
    bufferingTimer = window.setTimeout(() => {
      bufferingTimer = null;
      buffering.value = true;
    }, BUFFERING_UI_DELAY_MS);
  }

  function hideBufferingOverlay() {
    clearBufferingTimer();
    buffering.value = false;
  }

  function resetPlaybackResumeState() {
    resumeTickCount = 0;
    resumeInFlight = false;
    resumeAppliedVideoId = "";
    userPausedManually = false;
  }

  // 观看历史统一挂在续播记录上, 详情弹层对所有内容都上报
  function isLongDetailVideo() {
    return true;
  }

  function currentPlayTimeMs() {
    const el = playerRef.value;
    if (!el) return 0;
    const ms = Math.floor((Number(el.currentTime || 0) || 0) * 1000);
    return ms > 0 ? ms : 0;
  }

  function playbackResumePayload(playTimeMs) {
    const userId = auth.userId;
    const videoId = video.value?.id;
    if (!userId || userId === "" || !videoId) return null;
    if (!Number.isFinite(Number(playTimeMs))) return null;
    return { videoId, playTime: playTimeMs, userId };
  }

  async function flushPlaybackResume(reason) {
    if (!isLongDetailVideo()) return;
    const player = playerRef.value;
    if (!player) return;
    // 「切走/关页/离开详情」只在播放态上报；用户手动 pause 由 reason="pause" 单独处理
    if (reason !== "pause" && player.paused) return;

    const playTimeMs = currentPlayTimeMs();
    if (
      reason !== "pause" &&
      reason !== "switch" &&
      reason !== "leave" &&
      reason !== "unload" &&
      playTimeMs <= 0
    ) {
      return;
    }
    const payload = playbackResumePayload(playTimeMs);
    if (!payload) return;

    const now = Date.now();
    // tick 兜底避免过于频繁；pause/switch/leave/unload 立即发
    if (reason === "tick" && now - resumeLastSentAtMs < 8_000) return;
    if (resumeInFlight) return;
    resumeInFlight = true;
    resumeLastSentAtMs = now;

    const keepalive = reason === "unload" || reason === "leave";
    await sendPlaybackResume(payload, { keepalive });
    resumeInFlight = false;
  }

  async function sendPlaybackResume(payload, { keepalive = false } = {}) {
    if (!payload) return;
    try {
      const headers = { "Content-Type": "application/json" };
      const authTok = auth.token;
      if (authTok) {
        headers.Authorization = authTok;
      }
      await fetch("/videos/playback/resume", {
        method: "PUT",
        headers,
        body: JSON.stringify(payload),
        keepalive,
      });
    } catch {
      // 静默失败：续播记录不影响播放体验
    }
  }

  function applyPlaybackResumeIfNeeded() {
    if (!isLongDetailVideo()) return;
    const detail = video.value;
    const player = playerRef.value;
    if (!detail || !player) return;
    const vid = String(detail.id ?? "");
    if (!vid || resumeAppliedVideoId === vid) return;
    const playbackMs = Number(detail.playbackTime ?? 0);
    if (!Number.isFinite(playbackMs) || playbackMs <= 0) {
      resumeAppliedVideoId = vid;
      return;
    }
    const targetSec = playbackMs / 1000;
    if (!Number.isFinite(targetSec) || targetSec <= 0) {
      resumeAppliedVideoId = vid;
      return;
    }
    const duration = Number(player.duration);
    if (Number.isFinite(duration) && duration > 0) {
      player.currentTime = Math.max(0, Math.min(targetSec, Math.max(0, duration - 1)));
      resumeAppliedVideoId = vid;
      return;
    }
    // duration 尚未可用时，loadedmetadata/canplay 到来后会再次调用
  }

  async function tryAutoplay() {
    const el = playerRef.value;
    if (!el || !video.value?.videoUrl) return;
    if (userPausedManually) return;
    if (!el.paused) return;
    try {
      el.playsInline = true;
    } catch {
      /* ignore */
    }
    try {
      el.muted = false;
      await el.play();
    } catch {
      try {
        el.muted = true;
        await el.play();
      } catch {
        /* 静音仍被拦截则只能由用户点击控件播放 */
      }
    }
  }

  function scheduleAutoplayRetries() {
    void tryAutoplay();
    for (const delay of [80, 320, 700, 1200, 2000, 3000, 4500]) {
      setTimeout(() => void tryAutoplay(), delay);
    }
  }

  function onVideoLoadStart() {
    scheduleBufferingOverlay();
  }

  function onVideoMediaError() {
    hideBufferingOverlay();
  }

  function onVideoCanPlay() {
    hideBufferingOverlay();
    applyPlaybackResumeIfNeeded();
    void tryAutoplay();
  }

  function onVideoTimeUpdate(e) {
    const el = e?.target;
    if (!el) return;
    const d = video.value;
    if (!d?.id) return;
    const duration = Number(el.duration);
    const current = Number(el.currentTime || 0);
    if (!Number.isFinite(duration) || duration <= 0) return;

    const prev = lastTimeSec;
    lastTimeSec = current;
    if (prev != null) {
      const delta = current - prev;
      if (delta > 0 && delta <= MAX_PLAY_DELTA_SEC) {
        playAccSec += delta;
      }
    }
    if (playAccSec >= duration * PLAY_THRESHOLD && !behavior.hasPlayReported(d.id)) {
      behavior.markPlayReported(d.id);
      void behavior.report(d.id, "PLAY", current * 1000);
    }

    // 长视频按 timeupdate 次数兜底上报续播，每 30 次触发一次
    if (isLongDetailVideo() && !el.paused) {
      resumeTickCount += 1;
      if (resumeTickCount >= 30) {
        resumeTickCount = 0;
        void flushPlaybackResume("tick");
      }
    }
  }

  function onVideoEnded(e) {
    const d = video.value;
    if (!d?.id) return;
    void behavior.report(d.id, "FINISH", Number(e?.target?.currentTime || 0) * 1000);
    // ended 也写一次续播，减少断点缺失
    void flushPlaybackResume("ended");
  }

  function onVideoPause(e) {
    // 用户手势暂停后禁止自动 play 重试，否则 canplay/定时重试会把暂停顶掉
    const isUser = e?.isTrusted !== false;
    if (isUser) {
      userPausedManually = true;
    }
    if (!isLongDetailVideo()) return;
    resumeTickCount = 0;
    if (isUser) {
      void flushPlaybackResume("pause");
    }
  }

  function onVideoPlay(e) {
    if (e?.isTrusted !== false) {
      userPausedManually = false;
    }
    if (!isLongDetailVideo()) return;
    // 播放恢复后按 timeupdate 次数上报，这里重置计数避免刚播放就上报
    resumeTickCount = 0;
  }

  function onVideoSeeked() {
    lastTimeSec = null;
  }

  function handleKeydown(ev) {
    if (!open.value || !playerRef.value) return;
    const tag = ev.target?.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable) return;
    const player = playerRef.value;
    const key = ev.key;
    if (key === " ") {
      ev.preventDefault();
      if (player.paused) {
        userPausedManually = false;
        player.play().catch(() => {});
      } else {
        userPausedManually = true;
        player.pause();
      }
      return;
    }
    if (key === "f" || key === "F") {
      ev.preventDefault();
      if (document.fullscreenElement) {
        document.exitFullscreen?.();
      } else {
        player.requestFullscreen?.().catch(() => {});
      }
      return;
    }
    if (key === "m" || key === "M") {
      ev.preventDefault();
      player.muted = !player.muted;
      return;
    }
    if (key === "ArrowLeft") {
      ev.preventDefault();
      player.currentTime = Math.max(0, Number(player.currentTime || 0) - 5);
      return;
    }
    if (key === "ArrowRight") {
      ev.preventDefault();
      const duration = Number(player.duration || 0);
      const next = Number(player.currentTime || 0) + 5;
      player.currentTime = duration > 0 ? Math.min(duration, next) : next;
    }
  }

  async function onLikeClick() {
    const d = video.value;
    if (!d?.id) return;
    if (!auth.token) {
      toast("请先登录后再点赞", "info");
      return;
    }
    const now = Date.now();
    if (now - lastLikeAt < 300) return;
    lastLikeAt = now;
    if (likeBusy.value) return;
    const vid = parseDigitSnowflakeId(d.id);
    if (vid == null) return;
    const current = Number(playerRef.value?.currentTime || 0);
    likeBusy.value = true;
    try {
      if (sessionLiked.value) {
        await cancelLikeVideo(vid);
        sessionLiked.value = false;
      } else {
        await likeVideo(vid);
        sessionLiked.value = true;
      }
      const merged = normalizeVideoDetail(await fetchVideoDetail(vid));
      if (merged) {
        video.value = {
          ...d,
          ...merged,
          videoUrl: d.videoUrl || merged.videoUrl,
          coverUrl: d.coverUrl ?? merged.coverUrl,
          isLike: sessionLiked.value,
        };
      } else {
        video.value = { ...d, isLike: sessionLiked.value };
      }
      void behavior.report(d.id, "LIKE", current * 1000);
    } catch (error) {
      toast(error.message || "点赞失败", "error");
    } finally {
      likeBusy.value = false;
    }
  }

  async function onDislikeClick() {
    const d = video.value;
    if (!d?.id) return;
    if (!auth.token) {
      toast("请先登录", "info");
      return;
    }
    const vid = parseDigitSnowflakeId(d.id);
    if (vid == null) return;
    const current = Number(playerRef.value?.currentTime || 0);
    try {
      await dislikeVideo(vid);
      void behavior.report(d.id, "DISLIKE", current * 1000);
      toast("已标记为不感兴趣", "success");
    } catch (error) {
      toast(error.message || "操作失败", "error");
    }
  }

  async function onFollowClick() {
    const d = video.value;
    if (!d?.authorId) return;
    if (!auth.token) {
      toast("请先登录后再关注", "info");
      return;
    }
    const aid = String(d.authorId);
    if (auth.userId != null && aid === String(auth.userId)) return;

    const nextFollowed = !subscribeFollowed.value;
    followBusy.value = true;
    try {
      if (nextFollowed) {
        await followAuthor(aid);
      } else {
        await unfollowAuthor(aid);
      }
      follows.setOverride(aid, nextFollowed);
      hooks.patchAuthorFollow(aid, nextFollowed);
      video.value = { ...d, isFollow: nextFollowed };
      if (profile.targetUserId != null && String(profile.targetUserId) === aid) {
        profile.targetFollowed = nextFollowed;
      }
      toast(nextFollowed ? "关注成功" : "已取消关注", "success");
    } catch (e) {
      toast(e?.message || "操作失败", "error");
    } finally {
      followBusy.value = false;
    }
  }

  function isSidebarItemNowPlaying(item) {
    return Boolean(item && video.value && videoIdsEqual(item.id, video.value.id));
  }

  async function loadSidebar(reset) {
    if (!video.value) return;
    if (reset) {
      sidebarPageNo.value = 1;
      sidebarList.value = [];
      sidebarHasMore.value = true;
    }
    if (!sidebarHasMore.value && !reset) return;
    if (sidebarLoading.value || sidebarLoadingMore.value) return;

    const authorId = video.value.authorId;
    if (authorId == null) {
      sidebarList.value = [];
      sidebarHasMore.value = false;
      return;
    }
    if (reset || sidebarList.value.length === 0) {
      sidebarLoading.value = true;
    } else {
      sidebarLoadingMore.value = true;
    }
    try {
      const query = { pageNo: sidebarPageNo.value, pageSize: SIDEBAR_PAGE_SIZE };
      const res = await fetchAuthorVideos(authorId, query);
      const batch = res.list || [];
      sidebarList.value = reset ? batch : [...sidebarList.value, ...batch];
      sidebarPages.value = res.pages ?? 0;
      sidebarHasMore.value = sidebarPageNo.value < (res.pages || 0);
      if (sidebarHasMore.value) {
        sidebarPageNo.value += 1;
      }
    } catch {
      sidebarList.value = [];
      sidebarHasMore.value = false;
    } finally {
      sidebarLoading.value = false;
      sidebarLoadingMore.value = false;
    }
  }

  function onSidebarScroll(e) {
    const el = e.target;
    if (!el) return;
    if (sidebarLoading.value || sidebarLoadingMore.value) return;
    if (!sidebarHasMore.value) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 140) {
      void loadSidebar(false);
    }
  }

  function openFromSidebar(item) {
    if (!item || item.id === video.value?.id) return;
    void openDetail({
      id: item.id,
      title: item.title,
      author: item.author,
      authorNickname: item.authorNickname ?? item.author,
      viewsText: item.viewsText,
      videoUrl: item.videoUrl,
      coverUrl: item.coverUrl || item.avatarUrl,
      avatarUrl: item.coverUrl || item.avatarUrl,
      authorAvatarUrl: item.authorAvatarUrl,
      authorId: item.authorId,
    });
  }

  function resetCommentsState() {
    topComments.value = [];
    commentsLoading.value = false;
    commentsPageNo.value = 1;
    commentsPages.value = 0;
    draft.value = "";
    submitting.value = false;
    replyTarget.value = null;
    threadByRootId.value = {};
    expandedRoots.value = {};
  }

  async function loadTopComments(reset, { preserveThreadUi = false } = {}) {
    const savedExpanded = preserveThreadUi && reset ? { ...expandedRoots.value } : null;
    const vid = parseDigitSnowflakeId(video.value?.id);
    if (!vid) return;

    if (reset) {
      commentsPageNo.value = 1;
      topComments.value = [];
      if (!preserveThreadUi) {
        threadByRootId.value = {};
        expandedRoots.value = {};
      }
    }
    commentsLoading.value = true;
    try {
      const { pages, list } = await fetchCommentPage({
        videoId: vid,
        parentId: 0,
        pageNo: commentsPageNo.value,
        pageSize: COMMENT_PAGE_SIZE,
      });
      commentsPages.value = pages || 0;
      topComments.value = reset ? list : [...topComments.value, ...list];

      if (savedExpanded) {
        const topIds = new Set(topComments.value.map((x) => String(x.id)));
        const nextExpanded = {};
        for (const k of Object.keys(savedExpanded)) {
          if (savedExpanded[k] && topIds.has(k)) {
            nextExpanded[k] = true;
          }
        }
        expandedRoots.value = nextExpanded;
        const nextThreads = { ...threadByRootId.value };
        for (const k of Object.keys(nextExpanded)) {
          delete nextThreads[k];
        }
        threadByRootId.value = nextThreads;
        for (const k of Object.keys(nextExpanded)) {
          void ensureThreadLoaded(k);
        }
      }
    } catch (e) {
      toast(e?.message || "评论加载失败", "error");
    } finally {
      commentsLoading.value = false;
    }
  }

  async function loadMoreComments() {
    if (commentsLoading.value || !commentHasMore.value) return;
    commentsPageNo.value += 1;
    await loadTopComments(false);
  }

  async function ensureThreadLoaded(rootId) {
    const vid = parseDigitSnowflakeId(video.value?.id);
    const k = String(rootId);
    if (!vid || threadByRootId.value[k]?.loaded) return;
    threadByRootId.value = {
      ...threadByRootId.value,
      [k]: { ...(threadByRootId.value[k] || {}), loading: true, list: [] },
    };
    try {
      const { list } = await fetchCommentPage({
        videoId: vid,
        parentId: rootId,
        rootId,
        pageNo: 1,
        pageSize: 100,
      });
      threadByRootId.value = { ...threadByRootId.value, [k]: { loaded: true, loading: false, list } };
    } catch (e) {
      threadByRootId.value = {
        ...threadByRootId.value,
        [k]: { loaded: false, loading: false, list: [] },
      };
      toast(e?.message || "回复加载失败", "error");
    }
  }

  function toggleThread(rootId) {
    const k = String(rootId);
    if (expandedRoots.value[k]) {
      const next = { ...expandedRoots.value };
      delete next[k];
      expandedRoots.value = next;
      return;
    }
    expandedRoots.value = { ...expandedRoots.value, [k]: true };
    void ensureThreadLoaded(rootId);
  }

  function setReplyTarget(row) {
    replyTarget.value = row ? { id: row.id, nickname: row.nickname || "用户" } : null;
  }

  function applyLikePatch(commentId, patch) {
    const sid = String(commentId);
    const merge = (row) => (String(row.id) === sid ? { ...row, ...patch } : row);
    topComments.value = topComments.value.map(merge);
    const threads = { ...threadByRootId.value };
    for (const k of Object.keys(threads)) {
      const t = threads[k];
      if (t?.list?.length) {
        threads[k] = { ...t, list: t.list.map(merge) };
      }
    }
    threadByRootId.value = threads;
  }

  async function submitCommentLike(row) {
    const patch = await comments.toggleLike(row);
    if (patch) {
      applyLikePatch(row.id, patch);
    }
  }

  async function submitComment() {
    const text = draft.value.trim();
    if (!text) return;
    const vid = parseDigitSnowflakeId(video.value?.id);
    if (!vid) return;
    if (!auth.token) {
      toast("请先登录后再评论", "info");
      return;
    }
    if (!allowComment.value) {
      toast("作者已关闭评论", "info");
      return;
    }
    const wasTopLevel = replyTarget.value == null;
    const parentId = wasTopLevel ? 0 : replyTarget.value.id;
    submitting.value = true;
    try {
      await publishCommentApi({ videoId: vid, content: text, parentId });
      draft.value = "";
      replyTarget.value = null;
      if (video.value && wasTopLevel) {
        video.value = {
          ...video.value,
          commentCount: Number(video.value.commentCount ?? 0) + 1,
        };
      }
      await loadTopComments(true, { preserveThreadUi: true });
    } catch (e) {
      toast(e?.message || "发送失败", "error");
    } finally {
      submitting.value = false;
    }
  }

  function onCommentUserClick(ev, userId) {
    if (userId == null || userId === "") return;
    ev?.stopPropagation?.();
    profile.openByAuthorId(userId);
  }

  async function deleteComment(c) {
    if (!c?.id || !auth.token) return;
    if (String(c.userId) !== String(auth.userId)) return;
    try {
      await deleteCommentApi(c.id);
      toast("已删除", "success");
      await loadTopComments(true, { preserveThreadUi: true });
      const pid = c.parentId;
      if (video.value && (pid === 0 || pid === "0")) {
        video.value = {
          ...video.value,
          commentCount: Math.max(0, Number(video.value.commentCount ?? 0) - 1),
        };
      }
    } catch (e) {
      toast(e?.message || "删除失败", "error");
    }
  }

  function snapshotForRestore(d) {
    if (!d || d.id == null) return;
    const snap = {
      id: d.id,
      title: d.title ?? "",
      author: d.authorNickname ?? "",
      authorNickname: d.authorNickname ?? "",
      viewsText: d.viewsText,
      videoUrl: d.videoUrl ?? "",
      coverUrl: d.coverUrl ?? "",
      avatarUrl: d.coverUrl ?? "",
      authorAvatarUrl: d.authorAvatarUrl ?? "",
      authorId: d.authorId,
      description: d.description,
    };
    try {
      sessionStorage.setItem(`${DETAIL_STORAGE_PREFIX}${encodeURIComponent(String(d.id))}`, JSON.stringify(snap));
    } catch {
      /* ignore quota */
    }
  }

  /** 从首页进详情：push /video/:id 让浏览器返回能回到原页；详情内切换视频用 replace */
  function syncToAddressBar(wasAlreadyInDetail) {
    const d = video.value;
    if (!d || d.id == null) return;
    hooks.navigateToVideo(String(d.id), { replace: wasAlreadyInDetail });
  }

  async function restoreFromUrl(vidRaw) {
    if (!vidRaw) return false;
    const stored = sessionStorage.getItem(`${DETAIL_STORAGE_PREFIX}${encodeURIComponent(vidRaw)}`);
    if (stored) {
      try {
        await openDetail(JSON.parse(stored));
        return true;
      } catch {
        /* fall through */
      }
    }
    const numericId = parseDigitSnowflakeId(vidRaw);
    if (numericId != null) {
      await openDetail({ id: numericId });
      return true;
    }
    return false;
  }

  function resetUi() {
    open.value = false;
    video.value = null;
    hideBufferingOverlay();
    errorMsg.value = "";
    sidebarList.value = [];
  }

  async function openDetail(card, { skipRemoteDetail = false } = {}) {
    if (!card) return;
    // main 被 v-show 隐藏时部分浏览器仍会继续播放短视频音频，必须先停掉
    hooks.pauseShortsFeed();
    const skipRemote = skipRemoteDetail || card?.skipDetailFetch === true;

    // 切换详情视频前：上一条长视频正在播放则先 flush 续播
    if (open.value && isLongDetailVideo() && playerRef.value && !playerRef.value.paused) {
      void flushPlaybackResume("switch");
    }

    playAccSec = 0;
    lastTimeSec = null;
    hooks.closeProfileFeedIfOpen();
    const wasAlreadyInDetail = open.value;
    open.value = true;
    // 只收起主页遮罩，不调 profile.close()——那会连主页的返回现场一起清掉
    profile.open = false;
    studio.showStudio = false;
    hooks.closeUploadModal();
    video.value = null;
    errorMsg.value = "";
    resetPlaybackResumeState();
    loading.value = true;

    const vid = parseDigitSnowflakeId(card.id);
    const instantDetail = buildDetailFromCard(card);
    video.value = instantDetail;
    sessionLiked.value = Boolean(instantDetail?.isLike);
    loading.value = false;
    nextTick(() => scheduleAutoplayRetries());

    try {
      if (vid != null && !skipRemote) {
        const d = normalizeVideoDetail(await fetchVideoDetail(vid));
        if (d && card) {
          if (d.authorId == null && card.authorId != null) {
            d.authorId = card.authorId;
          }
          if (!d.authorAvatarUrl && card.authorAvatarUrl) {
            d.authorAvatarUrl = card.authorAvatarUrl;
          }
          const listNick = cardAuthorLabel(card);
          if (
            listNick &&
            listNick !== "—" &&
            (!d.authorNickname ||
              String(d.authorNickname).startsWith("用户 ") ||
              d.authorNickname === "频道")
          ) {
            d.authorNickname = listNick;
          }
          if (!d.coverUrl) {
            d.coverUrl = card?.coverUrl || card?.avatarUrl || "";
          }
        }
        video.value = d;
        if (auth.token && auth.userId && vid != null) {
          try {
            const liked = await fetchVideoLikedStatus(vid, auth.userId);
            video.value = { ...video.value, isLike: liked };
            sessionLiked.value = liked;
          } catch {
            sessionLiked.value = Boolean(d?.isLike);
          }
        } else {
          sessionLiked.value = Boolean(d?.isLike);
        }
        nextTick(() => applyPlaybackResumeIfNeeded());
      } else {
        video.value = instantDetail;
        sessionLiked.value = Boolean(instantDetail?.isLike);
      }
    } catch (e) {
      video.value = instantDetail;
      errorMsg.value = `详情接口不可用，已展示本地数据：${e.message}`;
      sessionLiked.value = Boolean(instantDetail?.isLike);
    }
    loading.value = false;
    await loadSidebar(true);
    nextTick(() => {
      requestAnimationFrame(() => {
        scheduleAutoplayRetries();
        setTimeout(() => scheduleAutoplayRetries(), 400);
      });
      if (video.value) {
        snapshotForRestore(video.value);
        syncToAddressBar(wasAlreadyInDetail);
      }
    });
  }

  /** 关闭详情：只管离开路由，flush/暂停/状态复位由 route watcher 走 closeByHistory 单一路径 */
  function closeDetail() {
    hooks.leaveVideoDetail();
  }

  /** 浏览器返回/URL 去掉 ?v= 时收敛状态（不动地址栏，不刷新列表） */
  function closeByHistory() {
    void flushPlaybackResume("leave");
    resetPlaybackResumeState();
    try {
      playerRef.value?.pause?.();
    } catch {
      /* ignore */
    }
    resetUi();
  }

  /** 刷新 / 往返缓存后 DOM 就绪较晚，多补几轮 */
  function rescheduleAutoplay() {
    nextTick(() => {
      scheduleAutoplayRetries();
      setTimeout(() => scheduleAutoplayRetries(), 150);
      setTimeout(() => scheduleAutoplayRetries(), 600);
    });
  }

  watch(
    () => [open.value, parseDigitSnowflakeId(video.value?.id)],
    ([isOpen, vid], prev) => {
      if (!isOpen) {
        resetCommentsState();
        return;
      }
      if (!vid) return;
      if (isOpen && vid === prev?.[1] && prev?.[0]) return;
      void loadTopComments(true);
    },
  );

  return {
    open,
    video,
    loading,
    buffering,
    errorMsg,
    playerRef,
    sidebarList,
    sidebarLoading,
    sidebarLoadingMore,
    sidebarHasMore,
    topComments,
    commentsLoading,
    submitting,
    draft,
    replyTarget,
    threadByRootId,
    expandedRoots,
    sessionLiked,
    likeBusy,
    followBusy,
    authorAvatar,
    fanText,
    subscribeFollowed,
    allowComment,
    commentHasMore,
    isBlockedComment,
    displayCommentNickname,
    isCommentLikeBusy: comments.isLikeBusy,
    openDetail,
    closeDetail,
    closeByHistory,
    hideBufferingOverlay,
    restoreFromUrl,
    snapshotForRestore,
    handleKeydown,
    rescheduleAutoplay,
    scheduleAutoplayRetries,
    tryAutoplay,
    applyPlaybackResumeIfNeeded,
    flushPlaybackResume,
    onVideoLoadStart,
    onVideoMediaError,
    onVideoCanPlay,
    onVideoTimeUpdate,
    onVideoEnded,
    onVideoPause,
    onVideoPlay,
    onVideoSeeked,
    onLikeClick,
    onDislikeClick,
    onFollowClick,
    isSidebarItemNowPlaying,
    onSidebarScroll,
    openFromSidebar,
    loadMoreComments,
    toggleThread,
    setReplyTarget,
    submitComment,
    submitCommentLike,
    onCommentUserClick,
    deleteComment,
    openCommentReport: (comment) => share.openCommentReport(comment),
    openForwardVideo: (card) => share.openForwardVideo(card),
    onSearchTagNavigate: (tag) => hooks.onSearchTagNavigate(tag),
  };
});
