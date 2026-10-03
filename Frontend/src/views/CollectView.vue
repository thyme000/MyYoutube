<script setup>
/**
 * 我的收藏页。content 级混排收藏(视频/动态同池), 状态取自 collectStore。
 * 视频卡 hover 预览+进全屏连播, 动态卡直达详情层; 支持取消收藏。
 */
import { isDynamicRow } from "../utils/contentKind";
import { computed } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useCollectStore } from "../stores/collectStore";
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
  collectRows,
  collectLoading,
  collectPageNo,
  collectPages,
  collectTotal,
} = storeToRefs(useCollectStore());
const collectStore = useCollectStore();
const hoverPreview = useHoverPreviewStore();
const { videoUrl: activePreviewVideoUrl } = storeToRefs(hoverPreview);
const profile = useProfileStore();
const router = useRouter();

function isDynamicItem(item) {
  return isDynamicRow(item);
}

function fmtCollectTime(row) {
  const t = String(row?.collectTime || "");
  return t ? t.slice(5, 16) : "";
}

function openCard(item, index) {
  if (isDynamicItem(item)) {
    void router.push(`/dynamic/${item.id}`);
    return;
  }
  collectStore.openCard(item, index);
}

async function removeRow(item) {
  try {
    await collectStore.removeOne(item.id);
    pushToast("已取消收藏", "success");
  } catch (e) {
    pushToast(e?.message || "取消收藏失败", "error");
  }
}

const hasMore = computed(() => collectPageNo.value < (collectPages.value || 0));
</script>

<template>
          <section class="history-immersive-page">
            <div class="history-immersive-head">
              <h3><i class="ri-bookmark-line"></i> 我的收藏</h3>
              <span class="collect-total-hint">共 {{ collectTotal }} 条</span>
            </div>

            <section class="history-section">
              <div class="history-section-head">
                <h4>收藏的内容</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="collectPageNo <= 1" @click="collectStore.prevPage">
                    上一页
                  </button>
                  <span>{{ collectPageNo }} / {{ Math.max(1, collectPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="!hasMore"
                    @click="collectStore.nextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="collectLoading" class="immersive-feed-loading">正在加载收藏…</div>
              <p v-else-if="!collectRows.length" class="history-empty">还没有收藏, 在详情页点「收藏」试试</p>
              <div v-else class="home-immersive-strip history-immersive-strip history-immersive-strip--expanded history-immersive-strip--long-grid">
                <article
                  v-for="(card, index) in collectRows"
                  :key="`collect-${card.id}-${index}`"
                  class="video-card history-long-card"
                  :class="{ 'video-card--playable': !isDynamicItem(card) && videoCardPlayable(card) }"
                  @click="openCard(card, index)"
                  @mouseenter="!isDynamicItem(card) && hoverPreview.enter(card, 'collect-grid')"
                  @mouseleave="!isDynamicItem(card) && hoverPreview.leave(card, 'collect-grid')"
                >
                  <button
                    type="button"
                    class="history-remove-btn"
                    aria-label="取消收藏"
                    @click.stop="removeRow(card)"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                  <video
                    v-if="!isDynamicItem(card) && hoverPreview.activeId === cardPreviewKey(card, 'collect-grid')"
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
                      <span>{{ fmtCollectTime(card) }} · {{ cardAuthorLabel(card) }}</span>
                    </div>
                  </div>
                </article>
              </div>
            </section>
          </section>
</template>

<style scoped>
.collect-total-hint {
  color: var(--muted);
  font-size: var(--font-size-13);
}
</style>
