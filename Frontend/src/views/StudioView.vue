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
import { useDynamicStore } from "../stores/dynamicStore";
import DynamicPublishModal from "../components/DynamicPublishModal.vue";

const auth = useAuthStore();
const sb = studioBridgeHooks;
const dynamicStore = useDynamicStore();
const { publishOpen } = storeToRefs(dynamicStore);
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
              <div v-else class="studio-table-panel" @click="studioStore.closeRowMenu">
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
                          <span>
                            <i v-if="row.type === 'dynamic'" class="ri-gallery-view-2-line studio-row-dynamic-icon" title="动态"></i>
                            {{ row.title }}
                          </span>
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
                    @click="studioStore.prevPage"
                  >
                    上一页
                  </button>
                  <span class="studio-pager-info">{{ studioPageNo }} / {{ Math.max(1, studioPages || 1) }}</span>
                  <button
                    type="button"
                    class="studio-pager-btn"
                    :disabled="studioPageNo >= (studioPages || 1)"
                    @click="studioStore.nextPage"
                  >
                    下一页
                  </button>
                </div>
                <p v-if="studioMsg && studioContentTab !== 'live'" class="studio-hint">{{ studioMsg }}</p>
                <div class="studio-fab-group">
                  <button type="button" class="studio-upload-fab" @click="publishOpen = true">发布动态</button>
                  <button type="button" class="studio-upload-fab" @click="sb.openUploadModal">上传视频</button>
                </div>
              </div>
            </template>
          </div>
        </div>
        <DynamicPublishModal :open="publishOpen" @close="publishOpen = false" />
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
  background: var(--color-bg-surface);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  padding: var(--space-16) var(--space-12);
  gap: var(--space-16);
}

.studio-sidebar-user {
  display: flex;
  align-items: center;
  gap: var(--space-12);
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
  gap: var(--space-4);
  min-width: 0;
}

.studio-sidebar-title {
  font-size: var(--font-size-12);
  color: var(--muted);
}

.studio-sidebar-name {
  font-size: var(--font-size-15);
  font-weight: 700;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-side-nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.studio-body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-20) var(--space-22) var(--space-28);
}

.studio-body-head {
  margin-bottom: var(--space-18);
}

.studio-body-title {
  margin: 0 0 var(--space-14);
  font-size: var(--font-size-22);
  font-weight: 800;
}

.studio-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-8);
  border-bottom: 1px solid var(--line);
  padding-bottom: var(--space-10);
}

.studio-placeholder-card {
  border: 1px dashed var(--line);
  border-radius: var(--radius-2xl);
  padding: var(--space-48) var(--space-24);
  text-align: center;
  color: var(--muted);
  background: var(--color-bg-surface);
}

.studio-table-panel {
  position: relative;
  background: var(--color-bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-2xl);
  padding: 0 0 var(--space-56);
  overflow: hidden;
}

.studio-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-size-14);
}

.studio-table th,
.studio-table td {
  padding: var(--space-12) var(--space-16);
  text-align: left;
  border-bottom: 1px solid var(--line);
}

.studio-table th {
  color: var(--muted);
  font-weight: 600;
  background: var(--color-bg-sunken);
}

.studio-table-empty {
  text-align: center;
  color: var(--muted);
  padding: var(--space-36) var(--space-16) !important;
}

.studio-td-video {
  display: flex;
  align-items: center;
  gap: var(--space-12);
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
  border-radius: var(--radius-lg);
  flex-shrink: 0;
  background: var(--line);
}

.studio-th-actions {
  width: 52px;
  padding-right: var(--space-12) !important;
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
  border-radius: var(--radius-xl);
  background: var(--color-bg-surface);
  color: var(--muted);
  cursor: pointer;
  font-size: var(--font-size-18);
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
  padding: var(--space-6);
  border-radius: var(--radius-2xl);
  background: var(--color-bg-surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  z-index: 40;
}

.studio-row-action-btn {
  border: 1px solid var(--line);
  background: var(--color-bg-sunken);
  color: var(--text);
  border-radius: var(--radius-xl);
  padding: var(--space-8) var(--space-10);
  font-size: var(--font-size-13);
  line-height: 1.2;
  cursor: pointer;
  text-align: left;
}

.studio-row-action-btn:hover {
  background: var(--hover);
}

.studio-row-action-btn--danger {
  color: var(--red-700);
  border-color: rgb(var(--red-700-rgb) / 0.35);
  background: var(--red-50);
}

.studio-row-action-btn--danger:hover {
  background: var(--red-100);
}

.studio-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-16);
  padding: var(--space-14) var(--space-16);
  border-top: 1px solid var(--line);
  background: var(--color-bg-sunken);
}

.studio-pager-btn {
  border: 1px solid var(--line);
  background: var(--color-bg-surface);
  border-radius: var(--radius-full);
  padding: var(--space-8) var(--space-18);
  font-size: var(--font-size-14);
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
  font-size: var(--font-size-14);
  color: var(--muted);
}

.studio-hint {
  margin: var(--space-10) var(--space-16) 0;
  font-size: var(--font-size-13);
  color: var(--amber-700);
}

.studio-fab-group {
  position: absolute;
  right: 16px;
  bottom: 16px;
  display: flex;
  gap: 10px;
}

.studio-upload-fab {
  border: none;
  border-radius: var(--radius-full);
  padding: var(--space-10) var(--space-20);
  font-size: var(--font-size-14);
  font-weight: 600;
  cursor: pointer;
  background: var(--color-bg-surface);
  color: var(--text);
  border: 1px solid var(--line);
  box-shadow: 0 4px 14px rgb(var(--slate-900-rgb) / 0.08);
}

.studio-upload-fab:hover {
  background: var(--hover);
}
.studio-row-dynamic-icon {
  color: var(--accent, #3ea6ff);
  margin-right: 4px;
}
</style>
