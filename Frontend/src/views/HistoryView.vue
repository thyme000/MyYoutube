<script setup>
/**
 * 历史播放记录页。状态取自 historyStore。
 */
import { storeToRefs } from "pinia";
import { useHistoryStore } from "../stores/historyStore";
import { useProfileStore } from "../stores/profileStore";
import { useHoverPreviewStore, cardPreviewKey } from "../stores/hoverPreviewStore";
import {
  videoCardPlayable,
  cardAuthorAvatar,
  cardCoverSrc,
  cardAuthorLabel,
} from "../utils/videoCard.js";

const {
  historyLongRows,
  historyLongLoading,
  historyLongPageNo,
  historyLongPages,
  historyLongExpanded,
} = storeToRefs(useHistoryStore());
const historyStore = useHistoryStore();
const hoverPreview = useHoverPreviewStore();
const profile = useProfileStore();
</script>

<template>
          <section class="history-shorts-page">
            <div class="history-shorts-head">
              <h3><i class="ri-history-line"></i> 历史播放记录</h3>
            </div>

            <section class="history-section">
              <div class="history-section-head">
                <h4>观看历史</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="historyLongPageNo <= 1" @click="historyStore.longPrevPage">
                    上一页
                  </button>
                  <span>{{ historyLongPageNo }} / {{ Math.max(1, historyLongPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="historyLongPageNo >= (historyLongPages || 1)"
                    @click="historyStore.longNextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="historyLongLoading" class="shorts-feed-loading">正在加载观看历史…</div>
              <div
                v-else
                class="home-shorts-strip history-shorts-strip"
                :class="{
                  'history-shorts-strip--expanded history-shorts-strip--long-grid': historyLongExpanded,
                }"
              >
                <article
                  v-for="(card, index) in historyLongRows"
                  :key="`history-long-${card.id}-${index}`"
                  class="video-card history-long-card"
                  :class="{ 'video-card--playable': videoCardPlayable(card) }"
                  @click="historyStore.openLongCard(card, index)"
                  @mouseenter="hoverPreview.enter(card, 'history-long')"
                  @mouseleave="hoverPreview.leave(card, 'history-long')"
                >
                  <video
                    v-if="hoverPreview.activeId === cardPreviewKey(card, 'history-long')"
                    class="video-cover video-cover--preview"
                    :src="activePreviewVideoUrl"
                    muted
                    autoplay
                    loop
                    playsinline
                    preload="metadata"
                  ></video>
                  <img v-else class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  <div class="video-info">
                    <img
                      class="video-card-author-avatar"
                      :src="cardAuthorAvatar(card, index)"
                      alt=""
                      role="button"
                      title="查看作者主页"
                      @click.stop="profile.openByAuthorId(card.authorId)"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                    <div class="video-info-text">
                      <strong>{{ card.title }}</strong>
                      <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "—" }}</span>
                    </div>
                  </div>
                </article>
              </div>
              <div class="history-section-foot">
                <button type="button" class="history-expand-btn" @click="historyStore.toggleLongExpanded">
                  <i class="ri-arrow-down-s-line"></i>{{ historyLongExpanded ? "收起" : "展开" }}
                </button>
              </div>
            </section>
          </section>
</template>

<style scoped>
.history-shorts-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-16);
}

.history-shorts-head {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-10);
  flex-wrap: wrap;
}

.history-shorts-head h3 {
  margin: 0;
  font-size: var(--font-size-18);
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
}

.history-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-10);
}

.history-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-10);
  flex-wrap: wrap;
}

.history-section-head h4 {
  margin: 0;
  font-size: var(--font-size-16);
}

.history-pager {
  display: inline-flex;
  align-items: center;
  gap: var(--space-8);
  color: var(--muted);
  font-size: var(--font-size-13);
}

.history-section-foot {
  display: flex;
  justify-content: flex-end;
}

.history-expand-btn {
  border: 1px solid var(--line);
  background: var(--color-bg-surface);
  color: var(--text);
  border-radius: var(--radius-full);
  height: 34px;
  padding: 0 var(--space-14);
  font-size: var(--font-size-13);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: var(--space-4);
}
</style>
