<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { clearToken, getApiBaseUrl, getAuthExpiredEventName, getToken, setRefreshToken, setToken } from "./api/http.js";
import { login } from "./api/authApi.js";
import {
  blockComment,
  ignoreReportComment,
  listAdminVideos,
  listBlockedComments,
  listCommentReports,
  restoreComment,
  reviewBan,
  reviewPass,
} from "./api/adminApi.js";

const token = ref(getToken());
const authed = computed(() => Boolean(token.value));

const loginMode = ref("token");
const username = ref("");
const password = ref("");
const loginBusy = ref(false);
const loginErr = ref("");

const activeTab = ref("videos");

const qPageNo = ref(1);
const qPageSize = ref(10);
const qVideoStatus = ref("REVIEW");
const qVideoType = ref("");
const qVideoCategory = ref("");
const qAuthorId = ref("");

const listLoading = ref(false);
const listError = ref("");
const pageTotal = ref(0);
const pagePages = ref(1);
const rows = ref([]);
const opBusyById = ref({});

const reportPageNo = ref(1);
const reportPageSize = ref(10);
const reportReasonCode = ref("");
const reportReporterId = ref("");
const reportLoading = ref(false);
const reportError = ref("");
const reportTotal = ref(0);
const reportPages = ref(1);
const reportRows = ref([]);

const blockedPageNo = ref(1);
const blockedPageSize = ref(10);
const blockedLoading = ref(false);
const blockedError = ref("");
const blockedTotal = ref(0);
const blockedPages = ref(1);
const blockedRows = ref([]);

const manualCommentId = ref("");
const manualBusy = ref(false);
const manualMsg = ref("");

const videoPreviewOpen = ref(false);
const videoPreviewTitle = ref("");
const videoPreviewUrl = ref("");

const REASON_LABELS = {
  ABUSE: "辱骂",
  PORNOGRAPHY: "涉黄",
  ADVERTISING: "广告",
  TROLLING: "引战",
  OTHER: "其他",
};

function formatReportReason(r) {
  const key = String(r?.reportType ?? r?.reasonCode ?? "").toUpperCase();
  return REASON_LABELS[key] || key || "-";
}

function formatReportStatus(r) {
  const key = String(r?.status ?? "").toUpperCase();
  if (!key) return "-";
  if (key === "PENDING") return "待处理";
  if (key === "PROCESSED") return "已处理";
  return key;
}

const tokenDisplay = computed(() => {
  const t = String(token.value || "");
  if (!t) return "";
  if (t.length <= 18) return t;
  return `${t.slice(0, 8)}…${t.slice(-6)}`;
});

function onLogout() {
  clearToken();
  token.value = "";
  loginErr.value = "";
  rows.value = [];
  reportRows.value = [];
  blockedRows.value = [];
  videoPreviewOpen.value = false;
  videoPreviewTitle.value = "";
  videoPreviewUrl.value = "";
}

async function onLogin() {
  loginErr.value = "";
  if (loginMode.value === "token") {
    const t = token.value.trim();
    if (!t) {
      loginErr.value = "请输入 token";
      return;
    }
    // 手动粘贴的 access token 无 refresh token, 过期后需重新登录
    setToken(t);
    setRefreshToken("");
    token.value = getToken();
    return;
  }
  const u = username.value.trim();
  const p = password.value;
  if (!u || !p) {
    loginErr.value = "用户名/密码不能为空";
    return;
  }
  if (loginBusy.value) return;
  loginBusy.value = true;
  try {
    const data = await login({ username: u, password: p });
    setToken(data.accessToken);
    setRefreshToken(data.refreshToken);
    token.value = getToken();
  } catch (e) {
    loginErr.value = e?.message || "登录失败";
  } finally {
    loginBusy.value = false;
  }
}

function buildQuery() {
  const q = {
    pageNo: qPageNo.value,
    pageSize: qPageSize.value,
  };
  if (qVideoStatus.value) q.videoStatus = qVideoStatus.value;
  if (qVideoType.value) q.videoType = qVideoType.value;
  if (qVideoCategory.value) q.videoCategory = qVideoCategory.value;
  if (qAuthorId.value.trim()) q.userId = qAuthorId.value.trim();
  return q;
}

async function loadVideos(resetPage = false) {
  if (!authed.value) return;
  if (resetPage) qPageNo.value = 1;
  listLoading.value = true;
  listError.value = "";
  try {
    const data = await listAdminVideos(buildQuery());
    const list = Array.isArray(data?.list) ? data.list : [];
    pageTotal.value = Number(data?.total) || 0;
    pagePages.value = Number(data?.pages) || 1;
    rows.value = list;
  } catch (e) {
    rows.value = [];
    listError.value = e?.message || "加载失败";
  } finally {
    listLoading.value = false;
  }
}

function buildReportQuery() {
  const q = {
    pageNo: reportPageNo.value,
    pageSize: reportPageSize.value,
  };
  if (reportReasonCode.value) q.reasonCode = reportReasonCode.value;
  if (reportReporterId.value.trim()) q.reporterId = reportReporterId.value.trim();
  return q;
}

async function loadReports(resetPage = false) {
  if (!authed.value) return;
  if (resetPage) reportPageNo.value = 1;
  reportLoading.value = true;
  reportError.value = "";
  try {
    const data = await listCommentReports(buildReportQuery());
    const list = Array.isArray(data?.list) ? data.list : [];
    reportTotal.value = Number(data?.total) || 0;
    reportPages.value = Number(data?.pages) || 1;
    reportRows.value = list;
  } catch (e) {
    reportRows.value = [];
    reportError.value = e?.message || "加载失败";
  } finally {
    reportLoading.value = false;
  }
}

async function loadBlocked(resetPage = false) {
  if (!authed.value) return;
  if (resetPage) blockedPageNo.value = 1;
  blockedLoading.value = true;
  blockedError.value = "";
  try {
    const data = await listBlockedComments({ pageNo: blockedPageNo.value, pageSize: blockedPageSize.value });
    const list = Array.isArray(data?.list) ? data.list : [];
    blockedTotal.value = Number(data?.total) || 0;
    blockedPages.value = Number(data?.pages) || 1;
    blockedRows.value = list;
  } catch (e) {
    blockedRows.value = [];
    blockedError.value = e?.message || "加载失败";
  } finally {
    blockedLoading.value = false;
  }
}

async function onPass(row) {
  const id = row?.videoId;
  if (id == null || id === "") return;
  const key = String(id);
  if (opBusyById.value[key]) return;
  opBusyById.value = { ...opBusyById.value, [key]: true };
  try {
    await reviewPass(id);
    await loadVideos(false);
  } catch (e) {
    listError.value = e?.message || "操作失败";
  } finally {
    opBusyById.value = { ...opBusyById.value, [key]: false };
  }
}

async function onBan(row) {
  const id = row?.videoId;
  if (id == null || id === "") return;
  const key = String(id);
  if (opBusyById.value[key]) return;
  opBusyById.value = { ...opBusyById.value, [key]: true };
  try {
    await reviewBan(id);
    await loadVideos(false);
  } catch (e) {
    listError.value = e?.message || "操作失败";
  } finally {
    opBusyById.value = { ...opBusyById.value, [key]: false };
  }
}

async function onBlockCommentId(id) {
  const cid = String(id ?? "").trim();
  if (!cid) return;
  if (opBusyById.value[cid]) return;
  opBusyById.value = { ...opBusyById.value, [cid]: true };
  try {
    await blockComment(cid);
    if (activeTab.value === "reports") await loadReports(false);
    if (activeTab.value === "blocked") await loadBlocked(false);
  } catch (e) {
    if (activeTab.value === "reports") reportError.value = e?.message || "操作失败";
    if (activeTab.value === "blocked") blockedError.value = e?.message || "操作失败";
  } finally {
    opBusyById.value = { ...opBusyById.value, [cid]: false };
  }
}

async function onIgnoreReportCommentId(id) {
  const cid = String(id ?? "").trim();
  if (!cid) return;
  const key = `ignore-${cid}`;
  if (opBusyById.value[key]) return;
  opBusyById.value = { ...opBusyById.value, [key]: true };
  try {
    await ignoreReportComment(cid);
    if (activeTab.value === "reports") await loadReports(false);
  } catch (e) {
    if (activeTab.value === "reports") reportError.value = e?.message || "操作失败";
  } finally {
    opBusyById.value = { ...opBusyById.value, [key]: false };
  }
}

async function onManualBlock() {
  manualMsg.value = "";
  const id = manualCommentId.value.trim();
  if (!id) {
    manualMsg.value = "请输入 commentId";
    return;
  }
  if (manualBusy.value) return;
  manualBusy.value = true;
  try {
    await blockComment(id);
    manualMsg.value = "已设置为违规";
    await loadBlocked(true);
  } catch (e) {
    manualMsg.value = e?.message || "操作失败";
  } finally {
    manualBusy.value = false;
  }
}

function openVideoPreview(row) {
  const url = String(row?.videoUrl || "").trim();
  if (!url) {
    listError.value = "该视频缺少 videoUrl，无法预览";
    return;
  }
  videoPreviewTitle.value = String(row?.title || "").trim() || "视频预览";
  videoPreviewUrl.value = url;
  videoPreviewOpen.value = true;
}

function closeVideoPreview() {
  videoPreviewOpen.value = false;
  videoPreviewTitle.value = "";
  videoPreviewUrl.value = "";
}

function onGlobalKeyDown(ev) {
  if (ev?.key === "Escape") closeVideoPreview();
}

watch(
  () => videoPreviewOpen.value,
  (open) => {
    if (open) {
      window.addEventListener("keydown", onGlobalKeyDown);
    } else {
      window.removeEventListener("keydown", onGlobalKeyDown);
    }
  }
);

async function onRestore(row) {
  const id = row?.commentId;
  if (id == null || id === "") return;
  const key = `restore-${String(id)}`;
  if (opBusyById.value[key]) return;
  opBusyById.value = { ...opBusyById.value, [key]: true };
  try {
    await restoreComment(id);
    await loadBlocked(false);
  } catch (e) {
    blockedError.value = e?.message || "操作失败";
  } finally {
    opBusyById.value = { ...opBusyById.value, [key]: false };
  }
}

function onPrev() {
  if (qPageNo.value <= 1) return;
  qPageNo.value -= 1;
}

function onNext() {
  if (qPageNo.value >= pagePages.value) return;
  qPageNo.value += 1;
}

function onReportPrev() {
  if (reportPageNo.value <= 1) return;
  reportPageNo.value -= 1;
}

function onReportNext() {
  if (reportPageNo.value >= reportPages.value) return;
  reportPageNo.value += 1;
}

function onBlockedPrev() {
  if (blockedPageNo.value <= 1) return;
  blockedPageNo.value -= 1;
}

function onBlockedNext() {
  if (blockedPageNo.value >= blockedPages.value) return;
  blockedPageNo.value += 1;
}

watch(
  () => qPageNo.value,
  () => {
    if (activeTab.value === "videos") void loadVideos(false);
  }
);

watch(
  () => reportPageNo.value,
  () => {
    if (activeTab.value === "reports") void loadReports(false);
  }
);

watch(
  () => blockedPageNo.value,
  () => {
    if (activeTab.value === "blocked") void loadBlocked(false);
  }
);

watch(
  () => token.value,
  (t) => {
    const nt = String(t || "").trim();
    if (nt) setToken(nt);
  }
);

watch(
  () => activeTab.value,
  (tab) => {
    if (tab === "videos") void loadVideos(true);
    if (tab === "reports") void loadReports(true);
    if (tab === "blocked") void loadBlocked(true);
  }
);

onMounted(() => {
  // 续签失败(登出/过期)时回到登录页
  window.addEventListener(getAuthExpiredEventName(), onLogout);
  if (!authed.value) return;
  if (activeTab.value === "videos") void loadVideos(true);
  if (activeTab.value === "reports") void loadReports(true);
  if (activeTab.value === "blocked") void loadBlocked(true);
});
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="topbar__inner">
        <div class="brand">
          <div class="brand__title">THYME 管理端</div>
        </div>
        <div class="topbar__actions">
          <span v-if="authed" class="pill">Token：{{ tokenDisplay }}</span>
          <button v-if="authed" type="button" class="btn btn--danger" @click="onLogout">退出</button>
        </div>
      </div>
    </header>

    <main class="wrap">
      <div v-if="!authed" class="card">
        <div class="tabs">
          <button type="button" class="tab" :class="{ 'tab--active': loginMode === 'token' }" @click="loginMode = 'token'">
            Token 登录
          </button>
          <button type="button" class="tab" :class="{ 'tab--active': loginMode === 'account' }" @click="loginMode = 'account'">
            账号登录
          </button>
        </div>

        <div v-if="loginMode === 'token'" class="grid2">
          <div class="field">
            <label>Authorization Token</label>
            <input v-model="token" class="input" placeholder="粘贴 token" />
          </div>
          <div class="row" style="align-items: flex-end">
            <button type="button" class="btn btn--primary" @click="onLogin">进入管理端</button>
          </div>
        </div>

        <div v-else class="grid2">
          <div class="field">
            <label>用户名</label>
            <input v-model="username" class="input" autocomplete="username" />
          </div>
          <div class="field">
            <label>密码</label>
            <input v-model="password" class="input" type="password" autocomplete="current-password" />
          </div>
          <div class="row" style="align-items: flex-end">
            <button type="button" class="btn btn--primary" :disabled="loginBusy" @click="onLogin">
              {{ loginBusy ? "登录中…" : "登录" }}
            </button>
          </div>
        </div>

        <p v-if="loginErr" class="err">{{ loginErr }}</p>
      </div>

      <template v-else>
        <div class="tabs">
          <button type="button" class="tab" :class="{ 'tab--active': activeTab === 'videos' }" @click="activeTab = 'videos'">
            视频审核
          </button>
          <button type="button" class="tab" :class="{ 'tab--active': activeTab === 'reports' }" @click="activeTab = 'reports'">
            举报评论
          </button>
          <button type="button" class="tab" :class="{ 'tab--active': activeTab === 'blocked' }" @click="activeTab = 'blocked'">
            违规评论
          </button>
        </div>

        <section v-if="activeTab === 'videos'" class="card">
          <div class="grid2">
            <div class="field">
              <label>状态</label>
              <select v-model="qVideoStatus" class="input">
                <option value="">全部</option>
                <option value="REVIEW">REVIEW</option>
                <option value="PUBLISHED">PUBLISHED</option>
                <option value="BANNED">BANNED</option>
              </select>
            </div>
            <div class="field">
              <label>类型</label>
              <select v-model="qVideoType" class="input">
                <option value="">全部</option>
                <option value="SHORT_VIDEO">SHORT_VIDEO</option>
                <option value="LONG_VIDEO">LONG_VIDEO</option>
              </select>
            </div>
            <div class="field">
              <label>分类</label>
              <select v-model="qVideoCategory" class="input">
                <option value="">全部</option>
                <option value="GAME">GAME</option>
                <option value="MOVIE">MOVIE</option>
                <option value="MUSIC">MUSIC</option>
                <option value="KNOWLEDGE">KNOWLEDGE</option>
                <option value="LIFE">LIFE</option>
                <option value="TECH">TECH</option>
                <option value="ENTERTAINMENT">ENTERTAINMENT</option>
                <option value="OTHER">OTHER</option>
              </select>
            </div>
            <div class="field">
              <label>作者 userId（可选）</label>
              <input v-model="qAuthorId" class="input" placeholder="例如 1" />
            </div>
          </div>

          <div class="row" style="margin-top: 12px">
            <button type="button" class="btn btn--primary" :disabled="listLoading" @click="loadVideos(true)">
              {{ listLoading ? "加载中…" : "查询" }}
            </button>
            <span class="pill">总数：{{ pageTotal }}</span>
            <span class="pill">页码：{{ qPageNo }} / {{ pagePages }}</span>
            <button type="button" class="btn" :disabled="qPageNo <= 1 || listLoading" @click="onPrev">上一页</button>
            <button type="button" class="btn" :disabled="qPageNo >= pagePages || listLoading" @click="onNext">下一页</button>
          </div>

          <p v-if="listError" class="err">{{ listError }}</p>

          <div style="margin-top: 12px; overflow: auto">
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 92px">封面</th>
                  <th>标题</th>
                  <th style="width: 190px">作者</th>
                  <th style="width: 150px">分类/类型</th>
                  <th style="width: 180px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in rows" :key="String(r.videoId)">
                  <td>
                    <button type="button" class="cover-btn" :disabled="!r.videoUrl" @click="openVideoPreview(r)">
                      <img class="cover" :src="r.coverUrl" alt="" />
                    </button>
                  </td>
                  <td>
                    <div style="font-weight: 800">{{ r.title }}</div>
                    <div class="muted" style="margin-top: 4px; word-break: break-word">{{ r.description }}</div>
                    <div class="muted" style="margin-top: 6px">videoId：{{ r.videoId }}</div>
                  </td>
                  <td>
                    <div style="display: flex; gap: 10px; align-items: center">
                      <img class="cover" style="width: 36px; height: 36px" :src="r.authorAvatarUrl" alt="" />
                      <div style="min-width: 0">
                        <div style="font-weight: 800; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
                          {{ r.authorNickname }}
                        </div>
                        <div class="muted">userId：{{ r.authorId }}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <div style="font-weight: 800">{{ r.videoCategory }}</div>
                    <div class="muted">{{ r.videoType }}</div>
                  </td>
                  <td>
                    <div class="actions">
                      <button type="button" class="btn" :disabled="!r.videoUrl" @click="openVideoPreview(r)">观看</button>
                      <button type="button" class="btn btn--primary" :disabled="!!opBusyById[String(r.videoId)]" @click="onPass(r)">
                        {{ opBusyById[String(r.videoId)] ? "处理中…" : "通过" }}
                      </button>
                      <button type="button" class="btn btn--danger" :disabled="!!opBusyById[String(r.videoId)]" @click="onBan(r)">
                        禁用
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!listLoading && rows.length === 0">
                  <td colspan="5" class="muted" style="text-align: center; padding: 18px">暂无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-else-if="activeTab === 'reports'" class="card">
          <div class="grid2">
            <div class="field">
              <label>举报原因（可选）</label>
              <select v-model="reportReasonCode" class="input">
                <option value="">全部</option>
                <option value="ABUSE">ABUSE</option>
                <option value="PORNOGRAPHY">PORNOGRAPHY</option>
                <option value="ADVERTISING">ADVERTISING</option>
                <option value="TROLLING">TROLLING</option>
                <option value="OTHER">OTHER</option>
              </select>
            </div>
            <div class="field">
              <label>举报人 reporterId（可选）</label>
              <input v-model="reportReporterId" class="input" placeholder="例如 2" />
            </div>
          </div>
          <div class="row" style="margin-top: 12px">
            <button type="button" class="btn btn--primary" :disabled="reportLoading" @click="loadReports(true)">
              {{ reportLoading ? "加载中…" : "查询" }}
            </button>
            <span class="pill">总数：{{ reportTotal }}</span>
            <span class="pill">页码：{{ reportPageNo }} / {{ reportPages }}</span>
            <button type="button" class="btn" :disabled="reportPageNo <= 1 || reportLoading" @click="onReportPrev">上一页</button>
            <button type="button" class="btn" :disabled="reportPageNo >= reportPages || reportLoading" @click="onReportNext">下一页</button>
          </div>
          <p v-if="reportError" class="err">{{ reportError }}</p>
          <div style="margin-top: 12px; overflow: auto">
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 120px">commentId</th>
                  <th>内容</th>
                  <th style="width: 160px">举报人</th>
                  <th style="width: 160px">原因</th>
                  <th style="width: 180px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in reportRows" :key="String(r.commentId) + '-' + String(r.reporterUserId)">
                  <td class="muted">{{ r.commentId }}</td>
                  <td>
                    <div style="font-weight: 800; word-break: break-word">{{ r.content }}</div>
                    <div v-if="r.remark" class="muted" style="margin-top: 6px">备注：{{ r.remark }}</div>
                  </td>
                  <td class="muted">{{ r.reporterUserId }}</td>
                  <td>
                    <div style="font-weight: 800">{{ formatReportReason(r) }}</div>
                    <div class="muted">{{ formatReportStatus(r) }}</div>
                  </td>
                  <td>
                    <div class="actions">
                      <button
                        type="button"
                        class="btn btn--danger"
                        :disabled="!!opBusyById[String(r.commentId)]"
                        @click="onBlockCommentId(r.commentId)"
                      >
                        设为违规
                      </button>
                      <button
                        type="button"
                        class="btn"
                        :disabled="!!opBusyById[`ignore-${String(r.commentId)}`]"
                        @click="onIgnoreReportCommentId(r.commentId)"
                      >
                        忽略
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!reportLoading && reportRows.length === 0">
                  <td colspan="5" class="muted" style="text-align: center; padding: 18px">暂无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-else class="card">
          <div class="grid2">
            <div class="field">
              <label>手动设置违规：commentId</label>
              <input v-model="manualCommentId" class="input" placeholder="输入评论 ID 后提交" />
            </div>
            <div class="row" style="align-items: flex-end">
              <button type="button" class="btn btn--danger" :disabled="manualBusy" @click="onManualBlock">
                {{ manualBusy ? "处理中…" : "设为违规" }}
              </button>
              <span v-if="manualMsg" class="pill">{{ manualMsg }}</span>
            </div>
          </div>

          <div class="row" style="margin-top: 12px">
            <button type="button" class="btn btn--primary" :disabled="blockedLoading" @click="loadBlocked(true)">
              {{ blockedLoading ? "加载中…" : "刷新" }}
            </button>
            <span class="pill">总数：{{ blockedTotal }}</span>
            <span class="pill">页码：{{ blockedPageNo }} / {{ blockedPages }}</span>
            <button type="button" class="btn" :disabled="blockedPageNo <= 1 || blockedLoading" @click="onBlockedPrev">上一页</button>
            <button type="button" class="btn" :disabled="blockedPageNo >= blockedPages || blockedLoading" @click="onBlockedNext">下一页</button>
          </div>

          <p v-if="blockedError" class="err">{{ blockedError }}</p>

          <div style="margin-top: 12px; overflow: auto">
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 120px">commentId</th>
                  <th>内容</th>
                  <th style="width: 120px">userId</th>
                  <th style="width: 200px">时间</th>
                  <th style="width: 140px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in blockedRows" :key="String(r.commentId)">
                  <td class="muted">{{ r.commentId }}</td>
                  <td style="font-weight: 800; word-break: break-word">{{ r.content }}</td>
                  <td class="muted">{{ r.userId }}</td>
                  <td class="muted">{{ r.createTime }}</td>
                  <td>
                    <div class="actions">
                      <button
                        type="button"
                        class="btn"
                        :disabled="!!opBusyById[`restore-${String(r.commentId)}`]"
                        @click="onRestore(r)"
                      >
                        恢复
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!blockedLoading && blockedRows.length === 0">
                  <td colspan="5" class="muted" style="text-align: center; padding: 18px">暂无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </main>

    <div v-if="videoPreviewOpen" class="admin-modal-overlay" @click.self="closeVideoPreview">
      <div class="admin-modal" role="dialog" aria-modal="true">
        <div class="admin-modal__head">
          <div class="admin-modal__title">{{ videoPreviewTitle }}</div>
          <button type="button" class="admin-modal__close" aria-label="关闭" @click="closeVideoPreview">×</button>
        </div>
        <div class="admin-modal__body">
          <video class="admin-video" :src="videoPreviewUrl" controls autoplay playsinline preload="metadata"></video>
        </div>
      </div>
    </div>
  </div>
</template>

