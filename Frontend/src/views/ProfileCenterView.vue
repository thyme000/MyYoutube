<script setup>
/**
 * 个人中心（我的主页：头像/背景/作品/关注粉丝）。
 * 过渡期：状态/函数/DOM ref 经 App.vue provide 的 profileBridge 注入。
 */
import { inject } from "vue";
import {
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
} from "../utils/userProfile";

const {
  activePreviewVideoUrl,
  avatarFileInput,
  avatarImgStyle,
  avatarImgUsePrecise,
  bgFileInput,
  displayNickname,
  formatHomeShortStat,
  heroCropContainerRef,
  heroImgStyle,
  heroImgUsePrecise,
  introDisplayText,
  isAuthed,
  isCardPreviewPlaying,
  isViewingSelf,
  onAvatarFileChange,
  onBgFileChange,
  onCardHoverEnter,
  onCardHoverLeave,
  onProfileAvatarImgError,
  onProfileAvatarNaturalLoad,
  onProfileBgImgError,
  onProfileHeroBgLoad,
  onProfileWorksChatClick,
  onWorkCardClick,
  openMediaPreview,
  openProfileEdit,
  openRelationTable,
  profileChatBusy,
  profileDisplayAvatar,
  profileDisplayBg,
  profileFollowBusy,
  profileLoading,
  profileMsg,
  profileTargetFollowed,
  profileUser,
  profileWorksDisplay,
  profileWorksFilter,
  profileWorksLoading,
  resolveVideoUrl,
  showProfileCenter,
  toggleProfileTargetFollow,
} = inject("profileBridge");
</script>

<template>
        <section v-if="showProfileCenter" class="profile-page">
          <input
            ref="avatarFileInput"
            type="file"
            accept="image/*"
            class="visually-hidden"
            aria-hidden="true"
            @change="onAvatarFileChange"
          />
          <input
            ref="bgFileInput"
            type="file"
            accept="image/*"
            class="visually-hidden"
            aria-hidden="true"
            @change="onBgFileChange"
          />
          <p v-if="profileLoading" class="profile-api-hint">正在加载用户信息…</p>
          <p v-else-if="profileMsg" class="profile-api-hint">{{ profileMsg }}</p>

          <div class="profile-top-row">
            <aside class="profile-side-card">
              <button
                type="button"
                class="profile-avatar-wrap"
                title="查看头像"
                @click="openMediaPreview('avatar')"
              >
                <img
                  v-if="!isViewingSelf"
                  :src="profileDisplayAvatar"
                  alt="用户头像"
                  class="profile-avatar-sm"
                  @error="onProfileAvatarImgError"
                />
                <div
                  v-else
                  class="profile-avatar-sm profile-avatar-crop-inner"
                  role="img"
                  :aria-label="displayNickname"
                >
                  <img
                    :src="profileDisplayAvatar"
                    :class="['profile-avatar-img-el', avatarImgUsePrecise ? 'profile-avatar-img-el--precise' : 'profile-avatar-img-el--cover']"
                    :style="avatarImgStyle"
                    alt=""
                    draggable="false"
                    @load="onProfileAvatarNaturalLoad"
                    @error="onProfileAvatarImgError"
                  />
                </div>
              </button>
              <h4>个人资料</h4>
              <p>昵称：{{ displayNickname }}</p>
              <p class="profile-id-line">ID：{{ profileUser?.id ?? "—" }}</p>
              <div class="profile-stat-list">
                <button
                  v-if="isViewingSelf"
                  type="button"
                  class="profile-stat-row profile-stat-btn"
                  @click="openRelationTable('following')"
                >
                  <span class="profile-stat-left">关注 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
                </button>
                <div v-else class="profile-stat-row">
                  <span>关注</span>
                  <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
                </div>
                <button
                  v-if="isViewingSelf"
                  type="button"
                  class="profile-stat-row profile-stat-btn"
                  @click="openRelationTable('followers')"
                >
                  <span class="profile-stat-left">粉丝 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.fans ?? "—" }}</strong>
                </button>
                <div v-else class="profile-stat-row">
                  <span>粉丝</span>
                  <strong>{{ profileUser?.fans ?? "—" }}</strong>
                </div>
                <div class="profile-stat-row">
                  <span>获赞</span>
                  <strong>{{ profileUser?.likeCount ?? "—" }}</strong>
                </div>
                <div class="profile-stat-row">
                  <span>作品数</span>
                  <strong>{{ profileUser?.videoCount ?? profileWorksDisplay.length }}</strong>
                </div>
              </div>
              <div
                class="profile-intro"
                :class="{ 'profile-intro--editable': isViewingSelf }"
                :role="isViewingSelf ? 'button' : undefined"
                :tabindex="isViewingSelf ? 0 : undefined"
                @click="isViewingSelf && openProfileEdit()"
                @keydown.enter.prevent="isViewingSelf && openProfileEdit()"
              >
                <h5>个人简介</h5>
                <p>{{ introDisplayText }}</p>
                <span v-if="isViewingSelf" class="profile-intro-edit-hint">点击编辑资料</span>
              </div>
            </aside>

            <section class="profile-hero-col">
              <button
                type="button"
                class="profile-hero profile-hero-clickable profile-hero--stretch"
                title="查看背景图"
                @click="openMediaPreview('bg')"
              >
                <div ref="heroCropContainerRef" class="profile-hero-img" role="img" aria-hidden="true">
                  <img
                    :src="profileDisplayBg"
                    :class="['profile-hero-img-el', heroImgUsePrecise ? 'profile-hero-img-el--precise' : 'profile-hero-img-el--cover']"
                    :style="heroImgStyle"
                    alt=""
                    draggable="false"
                    @load="onProfileHeroBgLoad"
                    @error="onProfileBgImgError"
                  />
                </div>
                <div class="profile-hero-mask"></div>
              </button>
            </section>
          </div>
          <section class="profile-works-section">
              <div class="profile-works-title">
                <h4>作品列表</h4>
                <div class="profile-works-toolbar">
                  <button
                    v-if="!isViewingSelf && isAuthed"
                    type="button"
                    class="profile-page-follow-btn"
                    :disabled="profileFollowBusy"
                    @click="toggleProfileTargetFollow"
                  >
                    {{ profileTargetFollowed ? "已关注" : "+ 关注" }}
                  </button>
                  <button
                    v-if="!isViewingSelf && isAuthed"
                    type="button"
                    class="profile-works-filter-btn"
                    :disabled="profileChatBusy"
                    title="与对方发起私聊"
                    @click="onProfileWorksChatClick"
                  >
                    {{ profileChatBusy ? "创建中…" : "聊一聊" }}
                  </button>
                  <div v-if="isViewingSelf" class="profile-works-filters">
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'ALL' }"
                      @click="profileWorksFilter = 'ALL'"
                    >
                      全部
                    </button>
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'PUBLIC' }"
                      @click="profileWorksFilter = 'PUBLIC'"
                    >
                      公开
                    </button>
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'PRIVATE' }"
                      @click="profileWorksFilter = 'PRIVATE'"
                    >
                      私密
                    </button>
                  </div>
                  <span>共 {{ profileWorksDisplay.length }} 条</span>
                </div>
              </div>
              <p v-if="profileWorksLoading" class="profile-api-hint profile-api-hint--loading">正在加载作品列表…</p>
              <div v-if="profileWorksLoading" class="works-grid works-grid-profile works-grid-profile--skeleton">
                <article v-for="n in 6" :key="`work-skeleton-${n}`" class="work-card work-card--skeleton">
                  <div class="work-thumb"></div>
                  <div class="work-meta">
                    <p class="work-title"></p>
                  </div>
                </article>
              </div>
              <div class="works-grid works-grid-profile">
                <article
                  v-for="item in profileWorksDisplay"
                  :key="item.id"
                  class="work-card"
                  :class="{ 'work-card--playable': resolveVideoUrl(item) }"
                  @click="onWorkCardClick(item)"
                  @mouseenter="onCardHoverEnter(item, 'profile-work')"
                  @mouseleave="onCardHoverLeave(item, 'profile-work')"
                >
                  <div class="work-thumb">
                    <video
                      v-if="isCardPreviewPlaying(item, 'profile-work')"
                      class="work-preview-video"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else :src="item.image" :alt="item.title" />
                    <span class="work-like work-like--bottom-left"><i class="ri-heart-line"></i> {{ item.likes }}</span>
                  </div>
                  <div class="work-meta">
                    <img
                      class="work-author-avatar"
                      :src="item.authorAvatarUrl || profileAvatar"
                      alt=""
                    />
                    <div class="work-meta-main">
                      <div class="work-title-row">
                        <p class="work-title">{{ item.title }}</p>
                        <span class="work-play-stat" title="播放量">
                          <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(item.playCount) }}
                        </span>
                      </div>
                    </div>
                  </div>
                </article>
              </div>
              <div v-if="!profileWorksLoading && !profileWorksDisplay.length" class="profile-works-empty">
                <div class="profile-works-empty-icon"><i class="ri-movie-2-line"></i></div>
                <h5>暂无内容</h5>
                <p>该账号还未发布过作品哦～</p>
              </div>
          </section>
        </section>
</template>
