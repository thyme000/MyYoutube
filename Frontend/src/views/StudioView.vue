<script setup>
/**
 * 创作中心（视频管理与数据）。状态与动作直连 studioStore；
 * 残余桥仅含 App 级依赖：资料昵称/顶栏头像/上传弹窗/删除确认。
 */
import { computed } from "vue";
import { storeToRefs } from "pinia";
import { useStudioStore, studioBridgeHooks, studioTabs, formatStudioDate, formatStudioVisibility, rowVisibilityIsPrivate } from "../stores/studioStore";
import { DEFAULT_PROFILE_BG_URL as profileBanner } from "../utils/userProfile";
import { useAuthStore } from "../stores/authStore";

const auth = useAuthStore();
const sb = studioBridgeHooks;
/** 侧栏昵称/头像直接取 authStore 的自有资料，与 App 同源 */
const displayNickname = computed(() => {
  const u = auth.selfProfile;
  if (!u) return "加载中…";
  if (u.nickName?.trim()) return u.nickName.trim();
  if (u.username?.trim()) return u.username.trim();
  return "未命名用户";
});
const topbarAvatarSrc = computed(() => auth.selfAvatarUrl);

const studioStore = useStudioStore();
const {
  showStudio,
  studioSideNav,
  studioContentTab,
  studioPageNo,
  studioPages,
  studioRows,
  studioLoading,
  studioMsg,
  studioRowMenuOpenId,
} = storeToRefs(studioStore);
</script>

<template>
        <div v-if="showStudio" class="studio-page">
          <aside class="studio-sidebar">
            <div class="studio-sidebar-user">
              <img :src="topbarAvatarSrc" alt="" class="studio-sidebar-avatar" />
              <div class="studio-sidebar-meta">
                <span class="studio-sidebar-title">我的频道</span>
                <span class="studio-sidebar-name">{{ displayNickname }}</span>
              </div>
            </div>
            <nav class="studio-side-nav">
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'info' }"
                @click="studioStore.selectSideNav('info')"
              >
                <i class="ri-dashboard-3-line"></i> 信息中心
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'data' }"
                @click="studioStore.selectSideNav('data')"
              >
                <i class="ri-bar-chart-2-line"></i> 数据分析
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'audio' }"
                @click="studioStore.selectSideNav('audio')"
              >
                <i class="ri-music-2-line"></i> 音频库
              </button>
            </nav>
          </aside>
          <div class="studio-body">
            <div class="studio-body-head">
              <h2 class="studio-body-title">频道内容</h2>
              <div v-if="studioSideNav === 'info'" class="studio-tabs">
                <button
                  v-for="tab in studioTabs"
                  :key="tab.key"
                  type="button"
                  class="studio-tab"
                  :class="{ active: studioContentTab === tab.key }"
                  @click="studioStore.switchTab(tab.key)"
                >
                  {{ tab.label }}
                </button>
              </div>
            </div>
            <div v-if="studioSideNav === 'data'" class="studio-placeholder-card">
              <p>数据分析功能开发中，敬请期待。</p>
            </div>
            <div v-else-if="studioSideNav === 'audio'" class="studio-placeholder-card">
              <p>音频库功能开发中，敬请期待。</p>
            </div>
            <template v-else>
              <div v-if="studioContentTab === 'live'" class="studio-placeholder-card">
                <p>{{ studioMsg || "直播内容尚未接入。" }}</p>
              </div>
              <div v-else-if="studioLoading" class="studio-placeholder-card">加载中…</div>
              <div v-else class="studio-table-panel" @click="closeStudioRowMenu">
                <table class="studio-table" @click.stop>
                  <thead>
                    <tr>
                      <th>视频</th>
                      <th>公开范围</th>
                      <th>日期</th>
                      <th>观看次数</th>
                      <th class="studio-th-actions" aria-label="操作"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!studioRows.length">
                      <td colspan="5" class="studio-table-empty">没有可显示的内容</td>
                    </tr>
                    <tr v-for="row in studioRows" :key="row.id">
                      <td>
                        <div class="studio-td-video">
                          <img :src="row.avatarUrl || profileBanner" alt="" class="studio-thumb" />
                          <span>{{ row.title }}</span>
                        </div>
                      </td>
                      <td>{{ formatStudioVisibility(row) }}</td>
                      <td>{{ formatStudioDate(row) }}</td>
                      <td>{{ row.viewsText }}</td>
                      <td class="studio-td-actions">
                        <button
                          type="button"
                          class="studio-row-more-btn"
                          aria-label="更多操作"
                          @click.stop="studioStore.toggleRowMenu(row, $event)"
                        >
                          <i class="ri-more-2-fill"></i>
                        </button>
                        <div
                          v-if="studioRowMenuOpenId === String(row.id)"
                          class="studio-row-action-menu"
                          @click.stop
                        >
                          <button type="button" class="studio-row-action-btn" @click="studioStore.toggleRowVisibility(row)">
                            {{ rowVisibilityIsPrivate(row) ? "设为公开" : "设为私密" }}
                          </button>
                          <button type="button" class="studio-row-action-btn" @click="studioStore.toggleRowComment(row)">
                            {{ Number(row.allowComment ?? 1) === 1 ? "关闭评论" : "开启评论" }}
                          </button>
                          <button
                            type="button"
                            class="studio-row-action-btn studio-row-action-btn--danger"
                            @click="sb.requestDeleteRow(row)"
                          >
                            删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div class="studio-pager">
                  <button
                    type="button"
                    class="studio-pager-btn"
                    :disabled="studioPageNo <= 1"
                    @click="studioPageNo--; loadStudioVideos()"
                  >
                    上一页
                  </button>
                  <span class="studio-pager-info">{{ studioPageNo }} / {{ Math.max(1, studioPages || 1) }}</span>
                  <button
                    type="button"
                    class="studio-pager-btn"
                    :disabled="studioPageNo >= (studioPages || 1)"
                    @click="studioPageNo++; loadStudioVideos()"
                  >
                    下一页
                  </button>
                </div>
                <p v-if="studioMsg && studioContentTab !== 'live'" class="studio-hint">{{ studioMsg }}</p>
                <button type="button" class="studio-upload-fab" @click="sb.openUploadModal">上传视频</button>
              </div>
            </template>
          </div>
        </div>
</template>

<style scoped>
/* 创作中心 Studio */
.studio-page {
  display: flex;
  flex: 1;
  min-height: 0;
  min-width: 0;
  background: var(--bg);
}

.studio-sidebar {
  width: 240px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  gap: 16px;
}

.studio-sidebar-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.studio-sidebar-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--line);
}

.studio-sidebar-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.studio-sidebar-title {
  font-size: 12px;
  color: var(--muted);
}

.studio-sidebar-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-side-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.studio-body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 22px 28px;
}

.studio-body-head {
  margin-bottom: 18px;
}

.studio-body-title {
  margin: 0 0 14px;
  font-size: 22px;
  font-weight: 800;
}

.studio-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 10px;
}

.studio-placeholder-card {
  border: 1px dashed var(--line);
  border-radius: 12px;
  padding: 48px 24px;
  text-align: center;
  color: var(--muted);
  background: #fff;
}

.studio-table-panel {
  position: relative;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 0 0 56px;
  overflow: hidden;
}

.studio-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.studio-table th,
.studio-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--line);
}

.studio-table th {
  color: var(--muted);
  font-weight: 600;
  background: #f8fafc;
}

.studio-table-empty {
  text-align: center;
  color: var(--muted);
  padding: 36px 16px !important;
}

.studio-td-video {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.studio-td-video span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-thumb {
  width: 96px;
  height: 54px;
  object-fit: cover;
  border-radius: 8px;
  flex-shrink: 0;
  background: var(--line);
}

.studio-th-actions {
  width: 52px;
  padding-right: 12px !important;
  text-align: right;
}

.studio-td-actions {
  position: relative;
  width: 52px;
  text-align: right;
  vertical-align: middle;
}

.studio-row-more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--muted);
  cursor: pointer;
  font-size: 18px;
}

.studio-row-more-btn:hover {
  background: var(--hover);
  color: var(--text);
}

.studio-row-action-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  min-width: 132px;
  padding: 6px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--line);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.12);
  display: flex;
  flex-direction: column;
  gap: 6px;
  z-index: 40;
}

.studio-row-action-btn {
  border: 1px solid var(--line);
  background: #f8fafc;
  color: var(--text);
  border-radius: 9px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.2;
  cursor: pointer;
  text-align: left;
}

.studio-row-action-btn:hover {
  background: var(--hover);
}

.studio-row-action-btn--danger {
  color: #b91c1c;
  border-color: rgba(185, 28, 28, 0.35);
  background: #fef2f2;
}

.studio-row-action-btn--danger:hover {
  background: #fee2e2;
}

.studio-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 14px 16px;
  border-top: 1px solid var(--line);
  background: #fafbfc;
}

.studio-pager-btn {
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 999px;
  padding: 8px 18px;
  font-size: 14px;
  cursor: pointer;
}

.studio-pager-btn:hover:not(:disabled) {
  background: var(--hover);
}

.studio-pager-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.studio-pager-info {
  font-size: 14px;
  color: var(--muted);
}

.studio-hint {
  margin: 10px 16px 0;
  font-size: 13px;
  color: #b45309;
}

.studio-upload-fab {
  position: absolute;
  right: 16px;
  bottom: 16px;
  border: none;
  border-radius: 999px;
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  background: #fff;
  color: var(--text);
  border: 1px solid var(--line);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.08);
}

.studio-upload-fab:hover {
  background: var(--hover);
}
</style>
