<script setup>
/**
 * 创作中心（视频管理与数据）。
 * 过渡期：状态/函数/DOM ref 经 App.vue provide 的 studioBridge 注入。
 */
import { inject } from "vue";
import { DEFAULT_PROFILE_BG_URL as profileBanner } from "../utils/userProfile";

const {
  closeStudioRowMenu,
  displayNickname,
  formatStudioDate,
  formatStudioVisibility,
  loadStudioVideos,
  onStudioRowRequestDelete,
  onStudioRowToggleComment,
  onStudioRowToggleVisibility,
  openUploadModal,
  selectStudioSideNav,
  showStudio,
  studioContentTab,
  studioLoading,
  studioMsg,
  studioPageNo,
  studioPages,
  studioRowMenuOpenId,
  studioRowVisibilityIsPrivate,
  studioRows,
  studioSideNav,
  studioTabs,
  switchStudioTab,
  toggleStudioRowMenu,
  topbarAvatarSrc,
} = inject("studioBridge");
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
                @click="selectStudioSideNav('info')"
              >
                <i class="ri-dashboard-3-line"></i> 信息中心
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'data' }"
                @click="selectStudioSideNav('data')"
              >
                <i class="ri-bar-chart-2-line"></i> 数据分析
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'audio' }"
                @click="selectStudioSideNav('audio')"
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
                  @click="switchStudioTab(tab.key)"
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
                          @click.stop="toggleStudioRowMenu(row, $event)"
                        >
                          <i class="ri-more-2-fill"></i>
                        </button>
                        <div
                          v-if="studioRowMenuOpenId === String(row.id)"
                          class="studio-row-action-menu"
                          @click.stop
                        >
                          <button type="button" class="studio-row-action-btn" @click="onStudioRowToggleVisibility(row)">
                            {{ studioRowVisibilityIsPrivate(row) ? "设为公开" : "设为私密" }}
                          </button>
                          <button type="button" class="studio-row-action-btn" @click="onStudioRowToggleComment(row)">
                            {{ Number(row.allowComment ?? 1) === 1 ? "关闭评论" : "开启评论" }}
                          </button>
                          <button
                            type="button"
                            class="studio-row-action-btn studio-row-action-btn--danger"
                            @click="onStudioRowRequestDelete(row)"
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
                <button type="button" class="studio-upload-fab" @click="openUploadModal">上传视频</button>
              </div>
            </template>
          </div>
        </div>
</template>
