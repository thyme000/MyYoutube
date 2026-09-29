<script setup>
/**
 * 历史播放记录页（长视频 + 短视频两个区块）。
 * 过渡期：状态取自 historyStore，交互函数经 App.vue provide 的 cardsBridge 注入。
 */
import { inject } from "vue";
import { storeToRefs } from "pinia";
import { useHistoryStore } from "../stores/historyStore";
import { useHoverPreviewStore, cardPreviewKey } from "../stores/hoverPreviewStore";
import {
  videoCardPlayable,
  cardAuthorAvatar,
  cardCoverSrc,
  cardAuthorLabel,
  formatHomeShortStat,
} from "../utils/videoCard.js";

const bridge = inject("cardsBridge");
const {
  historyLongRows,
  historyLongLoading,
  historyLongPageNo,
  historyLongPages,
  historyLongExpanded,
  historyShortRows,
  historyShortLoading,
  historyShortPageNo,
  historyShortPages,
  historyShortExpanded,
} = storeToRefs(useHistoryStore());
const historyStore = useHistoryStore();
const hoverPreview = useHoverPreviewStore();

/** 残余桥: 进他人主页(App 级 overlay) */
const { onAuthorAvatarClick } = bridge;
</script>

<template>
          <section class="history-shorts-page">
            <div class="history-shorts-head">
              <h3><i class="ri-history-line"></i> 历史播放记录</h3>
            </div>

            <section class="history-section">
              <div class="history-section-head">
                <h4>长视频历史记录</h4>
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
              <div v-if="historyLongLoading" class="shorts-feed-loading">正在加载长视频历史记录…</div>
              <div v-else-if="!historyLongRows.length" class="shorts-feed-empty">暂无长视频历史记录</div>
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
                      @click.stop="onAuthorAvatarClick($event, card.authorId)"
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

            <section class="history-section">
              <div class="history-section-head">
                <h4>短视频历史记录</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="historyShortPageNo <= 1" @click="historyStore.shortPrevPage">
                    上一页
                  </button>
                  <span>{{ historyShortPageNo }} / {{ Math.max(1, historyShortPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="historyShortPageNo >= (historyShortPages || 1)"
                    @click="historyStore.shortNextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="historyShortLoading" class="shorts-feed-loading">正在加载短视频历史记录…</div>
              <div v-else-if="!historyShortRows.length" class="shorts-feed-empty">暂无短视频历史记录</div>
              <div
                v-else
                class="home-shorts-strip history-shorts-strip"
                :class="{
                  'history-shorts-strip--expanded history-shorts-strip--short-grid': historyShortExpanded,
                }"
              >
                <article
                  v-for="(card, index) in historyShortRows"
                  :key="`history-short-${card.id}-${index}`"
                  class="home-short-card"
                  @click="historyStore.openShortCard(card, index)"
                  @mouseenter="hoverPreview.enter(card, 'history-short')"
                  @mouseleave="hoverPreview.leave(card, 'history-short')"
                >
                  <div class="home-short-media">
                    <video
                      v-if="hoverPreview.activeId === cardPreviewKey(card, 'history-short')"
                      class="home-short-cover home-short-cover--video video-cover--preview"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else class="home-short-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  </div>
                  <div class="home-short-card-footer">
                    <div class="home-short-card-main">
                      <p class="home-short-title">{{ card.title }}</p>
                      <span class="home-short-author">{{ cardAuthorLabel(card) }}</span>
                    </div>
                    <div class="home-short-stats">
                      <span class="home-short-stat" title="播放量">
                        <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.playCount) }}
                      </span>
                    </div>
                  </div>
                </article>
              </div>
              <div class="history-section-foot">
                <button type="button" class="history-expand-btn" @click="historyStore.toggleShortExpanded">
                  <i class="ri-arrow-down-s-line"></i>{{ historyShortExpanded ? "收起" : "展开" }}
                </button>
              </div>
            </section>
          </section>
</template>

<style scoped>
.history-shorts-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.history-shorts-head {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}

.history-shorts-head h3 {
  margin: 0;
  font-size: 18px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.history-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.history-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.history-section-head h4 {
  margin: 0;
  font-size: 16px;
}

.history-pager {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
}

.history-section-foot {
  display: flex;
  justify-content: flex-end;
}

.history-expand-btn {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--text);
  border-radius: 999px;
  height: 34px;
  padding: 0 14px;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
