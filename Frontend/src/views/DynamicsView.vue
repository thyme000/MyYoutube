<script setup>
// @ts-check
/**
 * 「动态」页: 图集配乐动态信息流。每条动态由 DynamicPlayer 本地合成预览,
 * 下载按钮走懒转码(缓存命中即存, 否则等 SSE 通知自动下载)。
 */
import { onMounted } from "vue";
import { storeToRefs } from "pinia";
import DynamicPlayer from "../components/DynamicPlayer.vue";
import { useDynamicStore } from "../stores/dynamicStore";
import { DEFAULT_PROFILE_AVATAR_URL } from "../utils/userProfile";

const dynamicStore = useDynamicStore();
const { feedList, feedLoading, feedErrorMsg } = storeToRefs(dynamicStore);

onMounted(() => {
  if (!feedList.value.length) {
    void dynamicStore.loadFeed(true);
  }
});

function fmtDate(ms) {
  const t = Number(ms);
  if (!t) return "";
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
</script>

<template>
  <section class="dynamics-page">
    <header class="dynamics-head">
      <h4><i class="ri-gallery-upload-line"></i> 动态</h4>
    </header>

    <p v-if="feedErrorMsg" class="dynamics-error">{{ feedErrorMsg }}</p>
    <p v-if="!feedLoading && !feedList.length && !feedErrorMsg" class="dynamics-empty">
      还没有动态，去创作中心发布第一条吧
    </p>

    <div class="dynamics-grid">
      <article v-for="d in feedList" :key="d.id" class="dynamics-card">
        <DynamicPlayer :dynamic="d" />
        <div class="dynamics-card-meta">
          <div class="dynamics-author">
            <img class="dynamics-avatar" :src="d.authorAvatarUrl || DEFAULT_PROFILE_AVATAR_URL" alt="" />
            <div class="dynamics-author-text">
              <span class="dynamics-nick">{{ d.authorNickname || "匿名用户" }}</span>
              <span class="dynamics-date">{{ fmtDate(d.createTime) }}</span>
            </div>
          </div>
          <button type="button" class="dynamics-download" @click="dynamicStore.requestDownload(d)">
            <i class="ri-download-2-line"></i> 保存成片
          </button>
        </div>
        <p v-if="d.title" class="dynamics-title">{{ d.title }}</p>
      </article>
    </div>

    <div v-if="feedLoading" class="dynamics-more">加载中…</div>
    <button
      v-else-if="feedList.length && dynamicStore.pageNo < dynamicStore.pages"
      type="button"
      class="dynamics-more dynamics-more--btn"
      @click="dynamicStore.loadFeed(false)"
    >
      加载更多
    </button>
  </section>
</template>

<style scoped>
.dynamics-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 2px 24px;
}
.dynamics-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.dynamics-head h4 {
  margin: 0;
  font-size: 16px;
}
.dynamics-error,
.dynamics-empty,
.dynamics-more {
  font-size: 13px;
  color: var(--ink-500, #999);
}
.dynamics-more--btn {
  border: none;
  background: transparent;
  cursor: pointer;
  align-self: center;
}
.dynamics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 18px;
}
/* 统一竖版卡位: 横屏动态在黑底上 contain 铅封, 保证行高一致不割裂;
   铅封时焦点裁剪不参与, object-position 用 !important 压过内联焦点样式, 固定居中防漂移 */
.dynamics-grid :deep(.dyn-player) {
  aspect-ratio: 1080 / 1920;
}
.dynamics-grid :deep(.dyn-player--horizontal .dyn-img) {
  object-fit: contain;
  object-position: center !important;
}
.dynamics-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dynamics-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dynamics-author {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dynamics-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
}
.dynamics-author-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.dynamics-nick {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dynamics-date {
  font-size: 11px;
  color: var(--ink-500, #999);
}
.dynamics-download {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--line, #333);
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.dynamics-download:hover {
  background: rgb(255 255 255 / 0.06);
}
.dynamics-title {
  margin: 0;
  font-size: 13px;
  color: var(--ink-300, #ccc);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
