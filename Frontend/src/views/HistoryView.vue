<script setup>
/**
 * 观看历史页。content 级混排历史(视频/动态同池), 状态取自 historyStore。
 * 视频卡 hover 预览+进全屏连播, 动态卡直达详情层; 支持删除单条与一键清空。
 */
import { isDynamicRow } from "../utils/contentKind";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
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
import { toast as pushToast } from "../utils/toast.js";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile.js";

const {
  historyRows,
  historyLoading,
  historyPageNo,
  historyPages,
  historyExpanded,
} = storeToRefs(useHistoryStore());
const historyStore = useHistoryStore();
const hoverPreview = useHoverPreviewStore();
const { videoUrl: activePreviewVideoUrl } = storeToRefs(hoverPreview);
const profile = useProfileStore();
const router = useRouter();

function isDynamicItem(item) {
  return isDynamicRow(item);
}

function fmtViewTime(row) {
  const t = String(row?.viewTime || "");
  return t ? t.slice(5, 16) : "";
}

function openCard(item, index) {
  if (isDynamicItem(item)) {
    void router.push(`/dynamic/${item.id}`);
    return;
  }
  historyStore.openCard(item, index);
}

async function removeRow(item) {
  try {
    await historyStore.removeOne(item.id);
    pushToast("已删除该条历史", "success");
  } catch (e) {
    pushToast(e?.message || "删除失败", "error");
  }
}

/** 清空两段式确认: 首次点击进入待确认态, 3 秒内再点才执行 */
const clearArmed = ref(false);
let clearArmTimer = 0;
const clearBtnLabel = computed(() => (clearArmed.value ? "再点一次确认清空" : "清空历史"));

function onClearClick() {
  if (!clearArmed.value) {
    clearArmed.value = true;
    clearTimeout(clearArmTimer);
    clearArmTimer = setTimeout(() => (clearArmed.value = false), 3000);
    return;
  }
  clearTimeout(clearArmTimer);
  clearArmed.value = false;
  void historyStore
    .clearAll()
    .then(() => pushToast("观看历史已清空", "success"))
    .catch((e) => pushToast(e?.message || "清空失败", "error"));
}
</script>

<template>
          <section class="history-immersive-page">
            <div class="history-immersive-head">
              <h3><i class="ri-history-line"></i> 观看历史</h3>
              <button
                type="button"
                class="history-clear-btn"
                :class="{ 'history-clear-btn--armed': clearArmed }"
                :disabled="historyLoading || (!historyRows.length && !clearArmed)"
                @click="onClearClick"
              >
                <i class="ri-delete-bin-line"></i> {{ clearBtnLabel }}
              </button>
            </div>

            <section class="history-section">
              <div class="history-section-head">
                <h4>看过的内容</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="historyPageNo <= 1" @click="historyStore.prevPage">
                    上一页
                  </button>
                  <span>{{ historyPageNo }} / {{ Math.max(1, historyPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="historyPageNo >= (historyPages || 1)"
                    @click="historyStore.nextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="historyLoading" class="immersive-feed-loading">正在加载观看历史…</div>
              <p v-else-if="!historyRows.length" class="history-empty">还没有观看记录, 去首页逛逛吧</p>
              <div
                v-else
                class="home-immersive-strip history-immersive-strip"
                :class="{
                  'history-immersive-strip--expanded history-immersive-strip--long-grid': historyExpanded,
                }"
              >
                <article
                  v-for="(card, index) in historyRows"
                  :key="`history-${card.id}-${index}`"
                  class="video-card history-long-card"
                  :class="{ 'video-card--playable': !isDynamicItem(card) && videoCardPlayable(card) }"
                  @click="openCard(card, index)"
                  @mouseenter="!isDynamicItem(card) && hoverPreview.enter(card, 'history-long')"
                  @mouseleave="!isDynamicItem(card) && hoverPreview.leave(card, 'history-long')"
                >
                  <button
                    type="button"
                    class="history-remove-btn"
                    aria-label="删除该条历史"
                    @click.stop="removeRow(card)"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                  <video
                    v-if="!isDynamicItem(card) && hoverPreview.activeId === cardPreviewKey(card, 'history-long')"
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
                      <strong>{{ card.title || "未命名内容" }}</strong>
                      <span>{{ fmtViewTime(card) }} · {{ cardAuthorLabel(card) }}</span>
                    </div>
                  </div>
                </article>
              </div>
              <div class="history-section-foot">
                <button type="button" class="history-expand-btn" @click="historyStore.toggleExpanded">
                  <i class="ri-arrow-down-s-line"></i>{{ historyExpanded ? "收起" : "展开" }}
                </button>
              </div>
            </section>
          </section>
</template>

<style scoped>
.history-immersive-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-16);
}

.history-immersive-head {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-10);
  flex-wrap: wrap;
}

.history-immersive-head h3 {
  margin: 0;
  font-size: var(--font-size-18);
  display: inline-flex;
  align-items: center;
  gap: var(--space-6);
}

.history-clear-btn {
  margin-left: auto;
  border: 1px solid var(--line);
  background: var(--color-bg-surface);
  color: var(--text);
  border-radius: var(--radius-full);
  height: 32px;
  padding: 0 var(--space-12);
  font-size: var(--font-size-13);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: var(--space-4);
}
.history-clear-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.history-clear-btn--armed {
  border-color: #ff4d6d;
  color: #ff4d6d;
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

.history-empty {
  margin: 0;
  padding: var(--space-24) 0;
  color: var(--muted);
  font-size: var(--font-size-14);
}

/* 卡片右上角单条删除 */
.history-long-card {
  position: relative;
}
.history-remove-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 3;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 50%;
  background: rgb(0 0 0 / 0.55);
  color: #fff;
  font-size: 15px;
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.history-long-card:hover .history-remove-btn,
.history-remove-btn:focus-visible {
  opacity: 1;
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
