<script setup>
// @ts-check
/**
 * 动态详情层: /dynamic/:id 路由命名视图(与视频详情同一遮罩层, main 被 v-show 隐藏)。
 * 复用 DynamicPlayer 本地合成预览 + 互动栏(点赞/评论, 统一内容池) + 保存成片(懒转码)。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import DynamicPlayer from "../components/DynamicPlayer.vue";
import { fetchDynamicDetail, requestDynamicDownload } from "../api/dynamicApi";
import { fetchCommentPage, publishComment } from "../api/commentApi";
import { likeVideo, cancelLikeVideo, fetchVideoLikedStatus } from "../api/likeApi";
import { cancelCollectContent, collectContent, fetchCollectStatus } from "../api/collectApi";
import { dislikeVideo } from "../api/likeApi";
import { followAuthor, isFollowed, unfollowAuthor } from "../api/followApi";
import { toast as pushToast } from "../utils/toast.js";
import { useFollowStore } from "../stores/followStore";
import { useShareStore } from "../stores/shareStore";
import { normalizeCommentVo } from "../api/commentApi";
import { useAuthStore } from "../stores/authStore";
import { useBehaviorStore } from "../stores/behaviorStore";
import { useHistoryStore, HISTORY_MIN_PLAY_SEC } from "../stores/historyStore";
import { useCommentStore } from "../stores/commentStore";
import { useNavigationStore } from "../stores/navigationStore";
import { applyAvatarFallback, DEFAULT_PROFILE_AVATAR_URL } from "../utils/userProfile";

const route = useRoute();
const auth = useAuthStore();
const navigationStore = useNavigationStore();
const commentStore = useCommentStore();
const behavior = useBehaviorStore();
const historyStore = useHistoryStore();
const followStore = useFollowStore();
const shareStore = useShareStore();

/** 与 detailStore 同口径: 播放时长过总时长 70% 记 PLAY, 单次进度增量超 3s 视为 seek 丢弃 */
const PLAY_THRESHOLD = 0.7;
const MAX_PLAY_DELTA_SEC = 3;

const dynamic = ref(null);
const loading = ref(true);
const errorMsg = ref("");

let dynPlayAccSec = 0;
let lastDynTimeSec = null;
let dynFinishReported = false;

const liked = ref(false);
const likeCount = ref(0);
const likeInFlight = ref(false);
const collected = ref(false);
const collectInFlight = ref(false);
const isFollow = ref(false);
const followBusy = ref(false);

const isSelfDynamic = computed(() => {
  const uid = auth.userId;
  const authorId = dynamic.value?.authorId;
  return uid != null && authorId != null && String(uid) === String(authorId);
});
const followState = computed(() => followStore.getOverride(dynamic.value?.authorId) ?? isFollow.value);

/** 标签展示: 逗号分隔字符串收敛成数组 */
const dynamicTags = computed(() =>
  String(dynamic.value?.tags || "")
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
);

function onTagClick(tag) {
  navigationStore.navigateToTag(tag);
}

const comments = ref([]);
const commentsLoading = ref(false);
const commentTotal = ref(0);
const commentDraft = ref("");
const commentSubmitting = ref(false);

const downloadState = ref({ status: "", taskId: "", downloadUrl: "" });

const isAuthed = computed(() => auth.isAuthed);
const commentsAllowed = computed(() => Number(dynamic.value?.allowComment ?? 1) === 1);

function fmtDate(ms) {
  const t = Number(ms);
  if (!t) return "";
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function resetBehaviorAcc() {
  dynPlayAccSec = 0;
  lastDynTimeSec = null;
  dynFinishReported = false;
}

/** 动态三信号: 累计播放过 70% 记 PLAY(计播放量+偏好分), 翻完一轮记 FINISH, 时长随事件携带 */
function onDynTimeUpdate(sec) {
  const d = dynamic.value;
  if (!d?.id) return;
  const prev = lastDynTimeSec;
  lastDynTimeSec = sec;
  if (prev != null) {
    const delta = sec - prev;
    if (delta > 0 && delta <= MAX_PLAY_DELTA_SEC) dynPlayAccSec += delta;
  }
  const totalSec = Number(d.totalDurationMs) / 1000;
  if (totalSec > 0 && dynPlayAccSec >= totalSec * PLAY_THRESHOLD && !behavior.hasPlayReported(d.id)) {
    behavior.markPlayReported(d.id);
    void behavior.report(d.id, "PLAY", sec * 1000);
  }
  // 翻到第 2 张(无第 2 张时退化为 3 秒)写观看历史
  const secondStartMs = Number(d.items?.[1]?.startMs) || 0;
  const historyThresholdSec = secondStartMs > 0 ? secondStartMs / 1000 : HISTORY_MIN_PLAY_SEC;
  if (sec >= historyThresholdSec) {
    void historyStore.recordViewed(d.id);
  }
}

function onDynEnded() {
  const d = dynamic.value;
  if (!d?.id || dynFinishReported) return;
  dynFinishReported = true;
  void behavior.report(d.id, "FINISH", (lastDynTimeSec || 0) * 1000);
}

async function loadDetail() {
  loading.value = true;
  errorMsg.value = "";
  try {
    // 路由参数可能是数组(可重复段)，统一收敛成字符串再交给 api
    const data = await fetchDynamicDetail(String(route.params.id ?? ""));
    // httpRequest 已拆掉 {code,message,data} 外壳：非 200 一律 throw 走 catch，
    // 所以这里只可能拿到成功态的 data，"动态不存在" 由 catch 里的后端 message 兜住
    if (!data) {
      errorMsg.value = "动态不存在";
      dynamic.value = null;
      return;
    }
    dynamic.value = data;
    resetBehaviorAcc();
    likeCount.value = Number(data.likeCount) || 0;
    collected.value = false;
    if (isAuthed.value) {
      liked.value = await fetchVideoLikedStatus(data.id);
      try {
        collected.value = await fetchCollectStatus(data.id);
      } catch {
        /* 收藏状态拉取失败按未收藏展示 */
      }
    }
    isFollow.value = false;
    if (isAuthed.value && !isSelfDynamic.value && data.authorId != null) {
      try {
        isFollow.value = Boolean(await isFollowed(data.authorId));
      } catch {
        /* 关注态拉取失败按未关注展示 */
      }
    }
    void loadComments(true);
  } catch (e) {
    errorMsg.value = e?.message || "动态加载失败";
  } finally {
    loading.value = false;
  }
}

async function toggleLike() {
  if (!isAuthed.value || likeInFlight.value || !dynamic.value) return;
  likeInFlight.value = true;
  try {
    if (liked.value) {
      await cancelLikeVideo(dynamic.value.id);
      likeCount.value = Math.max(0, likeCount.value - 1);
    } else {
      await likeVideo(dynamic.value.id);
      likeCount.value += 1;
    }
    liked.value = !liked.value;
  } catch {
    /* 互动失败不打断浏览 */
  } finally {
    likeInFlight.value = false;
  }
}

async function toggleFollow() {
  const authorId = dynamic.value?.authorId;
  if (!isAuthed.value || followBusy.value || authorId == null) return;
  if (isSelfDynamic.value) return;
  followBusy.value = true;
  try {
    if (followState.value) {
      await unfollowAuthor(authorId);
      isFollow.value = false;
      pushToast("已取消关注", "success");
    } else {
      await followAuthor(authorId);
      isFollow.value = true;
      pushToast("关注成功", "success");
    }
    followStore.setOverride(authorId, isFollow.value);
  } catch (e) {
    pushToast(e?.message || "关注操作失败", "error");
  } finally {
    followBusy.value = false;
  }
}

async function onDislike() {
  const d = dynamic.value;
  if (!isAuthed.value || !d?.id) return;
  try {
    await dislikeVideo(d.id);
    void behavior.report(d.id, "DISLIKE");
    pushToast("已标记为不感兴趣", "success");
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
}

function reportComment(comment) {
  shareStore.openCommentReport(comment);
}

async function toggleCollect() {
  if (!isAuthed.value || collectInFlight.value || !dynamic.value) return;
  collectInFlight.value = true;
  try {
    if (collected.value) {
      await cancelCollectContent(dynamic.value.id);
      collected.value = false;
    } else {
      await collectContent(dynamic.value.id);
      collected.value = true;
    }
  } catch {
    /* 收藏失败不打断浏览 */
  } finally {
    collectInFlight.value = false;
  }
}

async function loadComments(reset = false) {
  if (!dynamic.value) return;
  if (commentsLoading.value) return;
  commentsLoading.value = true;
  try {
    const res = await fetchCommentPage({
      videoId: dynamic.value.id,
      parentId: 0,
      pageNo: 1,
      pageSize: 20,
    });
    const list = (res.list || []).map(normalizeCommentVo).filter(Boolean);
    comments.value = reset ? list : [...comments.value, ...list];
    commentTotal.value = Number(res.total) || 0;
  } catch {
    if (reset) comments.value = [];
  } finally {
    commentsLoading.value = false;
  }
}

async function submitComment() {
  if (!isAuthed.value || !commentsAllowed.value || commentSubmitting.value || !dynamic.value) return;
  const content = commentDraft.value.trim();
  if (!content) return;
  commentSubmitting.value = true;
  try {
    await publishComment({ videoId: dynamic.value.id, parentId: 0, content });
    commentDraft.value = "";
    commentTotal.value += 1;
    await loadComments(true);
  } catch {
    /* 评论失败不打断 */
  } finally {
    commentSubmitting.value = false;
  }
}

async function likeOneComment(comment) {
  const patch = await commentStore.toggleLike(comment);
  if (patch) {
    comment.isLike = patch.isLike;
    comment.likeCount = patch.likeCount;
  }
}

/** SSE 转码完成通知下载地址兜底: 轮询一次状态即可, 不引入监听链 */
async function pollDownload() {
  if (!downloadState.value.taskId) return;
  try {
    const { fetchDynamicDownloadStatus } = await import("../api/dynamicApi");
    const st = await fetchDynamicDownloadStatus(downloadState.value.taskId);
    if (st?.status === "DONE" && st.downloadUrl) {
      downloadState.value = { status: "DONE", taskId: st.taskId, downloadUrl: st.downloadUrl };
    } else if (st?.status) {
      downloadState.value.status = st.status;
      setTimeout(() => void pollDownload(), 3000);
    }
  } catch {
    /* ignore */
  }
}

async function saveClip() {
  if (!dynamic.value) return;
  try {
    const res = await requestDynamicDownload(dynamic.value.id);
    if (res?.status === "DONE" && res.downloadUrl) {
      downloadState.value = { status: "DONE", taskId: res.taskId, downloadUrl: res.downloadUrl };
    } else if (res?.taskId) {
      downloadState.value = { status: res.status || "PENDING", taskId: res.taskId, downloadUrl: "" };
      setTimeout(() => void pollDownload(), 3000);
    }
  } catch {
    /* ignore */
  }
}

function triggerBrowserDownload() {
  const url = downloadState.value.downloadUrl;
  if (!url) return;
  const a = document.createElement("a");
  a.href = url;
  a.download = `${dynamic.value?.title || "dynamic"}.mp4`;
  a.click();
}

onMounted(loadDetail);
onBeforeUnmount(resetBehaviorAcc);
watch(() => route.params.id, (id, prev) => {
  if (route.name === "dynamic" && id && id !== prev) void loadDetail();
});

/** 头像兜底 */
function onAvatarError(e) {
  applyAvatarFallback(e, DEFAULT_PROFILE_AVATAR_URL);
}

</script>

<template>
  <div class="dyn-detail-page dyn-detail-page--layer">
    <header class="dyn-detail-toolbar">
      <button type="button" class="dyn-detail-back" aria-label="返回" @click="navigationStore.leaveDetailLayer">
        <i class="ri-arrow-left-line"></i> 返回
      </button>
      <span class="dyn-detail-toolbar-title">动态详情</span>
    </header>

    <p v-if="loading" class="dyn-detail-hint">加载中…</p>
    <p v-else-if="errorMsg" class="dyn-detail-hint">{{ errorMsg }}</p>

    <div v-else-if="dynamic" class="dyn-detail-body">
      <div class="dyn-detail-stage">
        <DynamicPlayer
          :dynamic="dynamic"
          @timeupdate="onDynTimeUpdate"
          @ended="onDynEnded"
        />
      </div>

      <aside class="dyn-detail-side">
        <header class="dyn-detail-head">
          <img
            class="dyn-detail-avatar"
            :src="dynamic.authorAvatarUrl || DEFAULT_PROFILE_AVATAR_URL"
            alt=""
            @error="onAvatarError"
          />
          <div class="dyn-detail-author">
            <strong>{{ dynamic.authorNickname || "匿名用户" }}</strong>
            <span>{{ fmtDate(dynamic.createTime) }}</span>
          </div>
          <button
            v-if="!isSelfDynamic"
            type="button"
            class="dyn-detail-follow"
            :class="{ 'dyn-detail-follow--active': followState }"
            :disabled="!isAuthed || followBusy"
            @click="toggleFollow"
          >
            {{ followState ? "已关注" : "+ 关注" }}
          </button>
        </header>

        <p v-if="dynamic.title" class="dyn-detail-title">{{ dynamic.title }}</p>

        <div v-if="dynamicTags.length" class="dyn-detail-tags">
          <button
            v-for="tg in dynamicTags"
            :key="'dytg-' + tg"
            type="button"
            class="dyn-detail-tag"
            @click="onTagClick(tg)"
          >
            #{{ tg }}
          </button>
        </div>

        <div class="dyn-detail-actions">
          <button
            type="button"
            class="dyn-detail-like"
            :class="{ 'dyn-detail-like--active': liked }"
            :disabled="!isAuthed || likeInFlight"
            @click="toggleLike"
          >
            <i :class="liked ? 'ri-heart-3-fill' : 'ri-heart-3-line'"></i>
            {{ likeCount }}
          </button>
          <button
            type="button"
            class="dyn-detail-like"
            :class="{ 'dyn-detail-like--active': collected }"
            :disabled="!isAuthed || collectInFlight"
            @click="toggleCollect"
          >
            <i :class="collected ? 'ri-bookmark-fill' : 'ri-bookmark-line'"></i>
            {{ collected ? "已收藏" : "收藏" }}
          </button>
          <span class="dyn-detail-stat"><i class="ri-chat-3-line"></i> {{ commentTotal }}</span>
          <button type="button" class="dyn-detail-like" :disabled="!isAuthed" @click="onDislike">
            <i class="ri-thumb-down-line"></i> 不感兴趣
          </button>
          <button type="button" class="dyn-detail-like" :disabled="!isAuthed" @click="shareStore.openForwardDynamic(dynamic)">
            <i class="ri-share-forward-line"></i> 分享
          </button>
          <button type="button" class="dyn-detail-download" @click="saveClip">
            <i class="ri-download-2-line"></i>
            {{ downloadState.status === "DONE" ? "已生成, 点击保存" : "保存成片" }}
          </button>
        </div>
        <a
          v-if="downloadState.downloadUrl"
          class="dyn-detail-download-link"
          :href="downloadState.downloadUrl"
          download
          @click.prevent="triggerBrowserDownload"
        >下载已生成的成片</a>

        <section class="dyn-detail-comments">
          <div class="dyn-detail-comments-head">
            <h4>评论 {{ commentTotal }}</h4>
            <span v-if="!commentsAllowed" class="dyn-detail-comment-note">作者已关闭评论</span>
          </div>
          <div v-if="isAuthed" class="dyn-detail-comment-editor">
            <input
              v-model="commentDraft"
              class="dyn-detail-comment-input"
              type="text"
              maxlength="500"
              :disabled="!commentsAllowed"
              :placeholder="commentsAllowed ? '说点什么…' : '评论已关闭'"
              @keydown.enter="submitComment"
            />
            <button
              type="button"
              class="dyn-detail-comment-send"
              :disabled="!commentsAllowed || commentSubmitting || !commentDraft.trim()"
              @click="submitComment"
            >发送</button>
          </div>
          <p v-else class="dyn-detail-comment-login">登录后参与评论</p>

          <div v-if="commentsLoading && !comments.length" class="dyn-detail-hint">评论加载中…</div>
          <p v-else-if="!comments.length" class="dyn-detail-hint">还没有评论, 来抢沙发</p>
          <ul v-else class="dyn-detail-comment-list">
            <li v-for="c in comments" :key="c.id" class="dyn-detail-comment">
              <img class="dyn-detail-comment-avatar" :src="c.avatarUrl || DEFAULT_PROFILE_AVATAR_URL" alt="" @error="onAvatarError" />
              <div class="dyn-detail-comment-main">
                <div class="dyn-detail-comment-head">
                  <span class="dyn-detail-comment-nick">{{ c.nickname }}</span>
                  <span class="dyn-detail-comment-ops">
                    <button
                      type="button"
                      class="dyn-detail-comment-like"
                      :class="{ 'dyn-detail-comment-like--active': c.isLike }"
                      @click="likeOneComment(c)"
                    >
                      <i :class="c.isLike ? 'ri-heart-3-fill' : 'ri-heart-3-line'"></i>{{ c.likeCount }}
                    </button>
                    <button
                      type="button"
                      class="dyn-detail-comment-report"
                      aria-label="举报该评论"
                      @click="reportComment(c)"
                    >
                      <i class="ri-flag-line"></i>
                    </button>
                  </span>
                </div>
                <p class="dyn-detail-comment-text">{{ c.content }}</p>
              </div>
            </li>
          </ul>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* —— 动态详情: 与视频详情同款 layer 布局, 独立占满主区域, 顶栏保留 —— */
.dyn-detail-page--layer {
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
.dyn-detail-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--line);
}
.dyn-detail-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font-size: 13px;
  cursor: pointer;
}
.dyn-detail-back:hover {
  background: rgb(0 0 0 / 0.05);
}
.dyn-detail-toolbar-title {
  font-size: 14px;
  font-weight: 600;
}
.dyn-detail-hint {
  padding: 32px;
  color: var(--muted, #888);
  font-size: 14px;
}
.dyn-detail-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 20px;
  padding: 18px;
  overflow: hidden;
}
.dyn-detail-stage {
  flex: 0 0 auto;
  width: min(360px, 40vw);
  overflow-y: auto;
  display: grid;
  place-items: start center;
}
.dyn-detail-stage :deep(.dyn-player) {
  max-height: 78vh;
}
.dyn-detail-side {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--panel, var(--bg));
  overflow-y: auto;
}
.dyn-detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dyn-detail-avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  object-fit: cover;
}
.dyn-detail-author {
  display: flex;
  flex-direction: column;
}
.dyn-detail-author span {
  font-size: 12px;
  color: var(--muted, #999);
}
.dyn-detail-title {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
}
.dyn-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.dyn-detail-tag {
  height: 26px;
  padding: 0 10px;
  border: none;
  border-radius: 999px;
  background: rgb(var(--slate-900-rgb, 15 23 42) / 0.06);
  color: inherit;
  font-size: 12px;
  cursor: pointer;
}
.dyn-detail-tag:hover {
  background: rgb(var(--slate-900-rgb, 15 23 42) / 0.12);
}
.dyn-detail-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
.dyn-detail-like,
.dyn-detail-download {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: transparent;
  color: inherit;
  font-size: 13px;
  cursor: pointer;
}
.dyn-detail-like:disabled,
.dyn-detail-download:disabled {
  opacity: 0.5;
  cursor: default;
}
.dyn-detail-like--active {
  color: #ff4d6d;
  border-color: #ff4d6d;
}
.dyn-detail-follow {
  margin-left: auto;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--accent, #2f6fed);
  background: transparent;
  color: var(--accent, #2f6fed);
  font-size: 13px;
  cursor: pointer;
}
.dyn-detail-follow:disabled {
  opacity: 0.5;
  cursor: default;
}
.dyn-detail-follow--active {
  border-color: var(--line);
  color: var(--muted, #999);
}
.dyn-detail-comment-ops {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.dyn-detail-comment-report {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  opacity: 0.5;
}
.dyn-detail-comment-report:hover {
  opacity: 1;
  color: #ff4d6d;
}
.dyn-detail-stat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--muted, #999);
}
.dyn-detail-download-link {
  font-size: 12px;
  color: #2f6fed;
}
.dyn-detail-comments {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
}
.dyn-detail-comments-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dyn-detail-comments h4 {
  margin: 0;
  font-size: 14px;
}
.dyn-detail-comment-note {
  font-size: 12px;
  color: var(--muted, #999);
}
.dyn-detail-comment-editor {
  display: flex;
  gap: 8px;
}
.dyn-detail-comment-input {
  flex: 1;
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: transparent;
  color: inherit;
  font-size: 13px;
}
.dyn-detail-comment-input:disabled {
  opacity: 0.5;
  cursor: default;
}
.dyn-detail-comment-send {
  height: 34px;
  padding: 0 14px;
  border: none;
  border-radius: 999px;
  background: var(--accent, #2f6fed);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}
.dyn-detail-comment-send:disabled {
  opacity: 0.5;
  cursor: default;
}
.dyn-detail-comment-login {
  margin: 0;
  font-size: 12px;
  color: var(--muted, #999);
}
.dyn-detail-comment-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dyn-detail-comment {
  display: flex;
  gap: 10px;
}
.dyn-detail-comment-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  flex: 0 0 auto;
}
.dyn-detail-comment-main {
  min-width: 0;
  flex: 1;
}
.dyn-detail-comment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dyn-detail-comment-nick {
  font-size: 12px;
  color: var(--muted, #999);
}
.dyn-detail-comment-like {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  opacity: 0.75;
}
.dyn-detail-comment-like--active {
  color: #ff4d6d;
  opacity: 1;
}
.dyn-detail-comment-text {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}
@media (max-width: 760px) {
  .dyn-detail-body {
    flex-direction: column;
    align-items: center;
    overflow-y: auto;
  }
  .dyn-detail-stage {
    width: min(300px, 80vw);
  }
}
</style>