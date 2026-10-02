// @ts-check
/**
 * 个人主页域：自己/他人主页的资料、作品、头像与背景裁剪、关注关系、粉丝/关注列表。
 * 打开主页需要收起搜索、创作中心、详情等 App 级遮罩，这类宿主能力经 registerProfileHooks 注入。
 */
import { defineStore } from "pinia";
import {
  fetchMyFansPage,
  fetchMyFollowingPage,
  followAuthor,
  isFollowed,
  removeFan,
  unfollowAuthor,
} from "../api/followApi";
import {
  getUserInfo,
  getUserInfoById,
  updateUserAvatar,
  updateUserBgImage,
  updateUserProfile,
} from "../api/userApi";
import { fetchOtherUserVideos } from "../api/videoApi";
import { fetchMyContents } from "../api/contentApi";
import { getToken } from "../api/http";
import { startSingleChat } from "./chatStore.js";
import { useAuthStore } from "./authStore";
import { toast as pushToast } from "../utils/toast.js";
import {
  loadCropFromStorage,
  makeDefaultCropRect,
  normalizeRect,
  saveCropToStorage,
} from "../utils/imageCropStorage";
import {
  DEFAULT_PROFILE_AVATAR_URL as defaultAvatar,
  DEFAULT_PROFILE_BG_URL as defaultBg,
  normalizeUserProfile,
  sanitizeImageUrl,
  stripUserIdLikeName,
} from "../utils/userProfile";
import { parseDigitSnowflakeId, safeSnowflakeId } from "../utils/snowflakeId.js";
import { patchRowById, patchRowsByAuthorId, removeRowById } from "../utils/feedRowPatch.js";

const RELATION_PAGE_SIZE = 20;
/** 头像预览框边长，用于换算裁剪后的显示尺寸 */
const AVATAR_BOX_PX = 110;
const FULL_FRAME = { L: 0, T: 0, W: 1, H: 1 };
const RETURN_CTX_STORAGE_KEY = "myyoutube_profile_return_ctx_v1";

/**
 * @type {{
 *   prepareOpen: () => any,
 *   pushHistory: (targetUserId: string) => void,
 *   openWorkFeed: (item: any) => void,
 *   patchAuthorFollow: (authorId: any, isFollow: boolean) => void,
 * }}
 */
const hooks = {
  /** 打开主页前收起 App 级遮罩，返回可恢复的现场快照 */
  prepareOpen: () => null,
  /** 写入 history state，使浏览器后退能退出主页 */
  pushHistory: () => {},
  /** 作品卡进入全屏短视频流（shorts 域） */
  openWorkFeed: () => {},
  /** 跨列表同步同一作者的 isFollow */
  patchAuthorFollow: () => {},
};

/**
 * @param {{
 *   prepareOpen?: () => any,
 *   pushHistory?: (targetUserId: string) => void,
 *   openWorkFeed?: (item: any) => void,
 *   patchAuthorFollow?: (authorId: any, isFollow: boolean) => void,
 * }} [injected]
 */
export function registerProfileHooks({ prepareOpen, pushHistory, openWorkFeed, patchAuthorFollow } = {}) {
  if (prepareOpen) hooks.prepareOpen = prepareOpen;
  if (pushHistory) hooks.pushHistory = pushHistory;
  if (openWorkFeed) hooks.openWorkFeed = openWorkFeed;
  if (patchAuthorFollow) hooks.patchAuthorFollow = patchAuthorFollow;
}

function isCropFullFrame(rect) {
  return rect.L <= 0.001 && rect.T <= 0.001 && rect.W >= 0.999 && rect.H >= 0.999;
}

function preloadNaturalSize(url, apply) {
  if (!url) return;
  const img = new Image();
  img.onload = () => apply({ w: img.naturalWidth, h: img.naturalHeight });
  img.src = url;
}

function persistReturnContext(ctx) {
  try {
    if (ctx == null) sessionStorage.removeItem(RETURN_CTX_STORAGE_KEY);
    else sessionStorage.setItem(RETURN_CTX_STORAGE_KEY, JSON.stringify(ctx));
  } catch {
    /* ignore */
  }
}

function readPersistedReturnContext() {
  try {
    const raw = sessionStorage.getItem(RETURN_CTX_STORAGE_KEY);
    const o = raw ? JSON.parse(raw) : null;
    return o && typeof o === "object" ? o : null;
  } catch {
    return null;
  }
}

function relationRawAvatar(raw) {
  if (!raw || typeof raw !== "object") return "";
  const direct =
    raw.authorAvatarUrl ??
    raw.avatar_url ??
    raw.authorAvatar ??
    raw.avatarUrl ??
    raw.avatar ??
    "";
  if (typeof direct === "string" && direct.trim()) return direct.trim();
  if (direct != null && direct !== "") return String(direct).trim();
  const nested = raw.user ?? raw.followee ?? raw.followUser ?? raw.fanUser;
  return nested && typeof nested === "object" ? relationRawAvatar(nested) : "";
}

function normalizeRelationUser(raw) {
  if (!raw || typeof raw !== "object") return null;
  const authorAvatarUrl = relationRawAvatar(raw);
  const trimmed = String(authorAvatarUrl).trim();
  return {
    id: raw.followeeId ?? raw.fanId ?? raw.id ?? raw.userId ?? raw.user_id ?? "",
    nickName: raw.nickname ?? raw.nickName ?? raw.name ?? raw.nick_name ?? "",
    authorAvatarUrl,
    avatarUrl: authorAvatarUrl,
    avatarDisplaySrc: trimmed ? sanitizeImageUrl(trimmed) || defaultAvatar : defaultAvatar,
    introduction: raw.introduction ?? "",
  };
}

/** 分页接口的列表字段在不同接口间不统一 */
function extractPageList(data) {
  if (Array.isArray(data)) return data;
  if (!data || typeof data !== "object") return [];
  for (const key of ["list", "records", "content"]) {
    if (Array.isArray(data[key])) return data[key];
  }
  const inner = data.data;
  if (inner && typeof inner === "object") {
    for (const key of ["list", "records"]) {
      if (Array.isArray(inner[key])) return inner[key];
    }
  }
  return [];
}

export const useProfileStore = defineStore("profile", {
  state: () => ({
    /** 主页为 App 级全屏遮罩，可从任意页面打开 */
    open: false,
    /** null = 自己的主页；有值 = 正在查看该 userId 的主页 */
    targetUserId: null,
    user: null,
    loading: false,
    msg: "",
    followBusy: false,
    chatBusy: false,
    uploadBusy: false,
    /** 他人主页：是否已关注（GET /follow/isFollow/{authorId}） */
    targetFollowed: false,
    worksRows: [],
    worksLoading: false,

    avatarImgError: false,
    bgImgError: false,

    mediaPreviewOpen: false,
    /** 'avatar' | 'bg' */
    mediaPreviewType: "avatar",
    pendingCropFile: null,
    pendingCropType: null,
    pendingCropUrl: "",

    relationOpen: false,
    relationTitle: "",
    relationUsers: [],
    /** 'following' | 'followers' */
    relationKind: "followers",
    relationFollowerMenuId: null,
    followingRows: [],
    followingPageNo: 1,
    followingLoading: false,
    followingHasMore: true,
    followerRows: [],
    followerPageNo: 1,
    followerLoading: false,
    followerHasMore: true,

    editOpen: false,
    editForm: { nickName: "", introduction: "" },
    editSaving: false,

    /** 裁剪区域为归一化坐标（相对原图） */
    cropRectBg: { ...FULL_FRAME },
    cropRectAvatar: { ...FULL_FRAME },
    cropRectDraft: { ...FULL_FRAME },
    cropNaturalSize: { w: 0, h: 0 },
    heroSize: { w: 0, h: 0 },
    bgNaturalSize: { w: 0, h: 0 },
    avatarNaturalSize: { w: 0, h: 0 },
  }),

  getters: {
    isViewingSelf(state) {
      if (state.targetUserId == null) return true;
      const uid = useAuthStore().userId;
      if (uid == null) return false;
      return String(state.targetUserId) === String(uid);
    },
    displayNickname(state) {
      const u = state.user;
      if (!u) return "加载中…";
      return u.nickName?.trim() || u.username?.trim() || "未命名用户";
    },
    /** @returns {string} */
    introDisplayText(state) {
      const t = state.user?.introduction?.trim();
      if (t) return t;
      return this.isViewingSelf ? "暂无简介，点击此处编辑" : "暂无简介";
    },
    displayAvatar(state) {
      if (state.avatarImgError) return defaultAvatar;
      return sanitizeImageUrl(state.user?.avatarUrl) || defaultAvatar;
    },
    displayBg(state) {
      if (state.bgImgError) return defaultBg;
      return sanitizeImageUrl(state.user?.bgImage) || defaultBg;
    },
    /** @returns {string} */
    mediaPreviewSrc(state) {
      if (state.pendingCropUrl) return state.pendingCropUrl;
      return state.mediaPreviewType === "avatar" ? this.displayAvatar : this.displayBg;
    },
    /** @returns {boolean} */
    avatarImgUsePrecise(state) {
      if (!this.isViewingSelf || isCropFullFrame(state.cropRectAvatar)) return false;
      return state.avatarNaturalSize.w > 0 && state.avatarNaturalSize.h > 0;
    },
    /**
     * scale = 展示框宽 / 选区像素宽，再平移使选区左上角对齐容器左上角
     * @returns {Record<string, string>}
     */
    avatarImgStyle(state) {
      if (!this.avatarImgUsePrecise) return {};
      const { L, T, W } = state.cropRectAvatar;
      const { w: nw, h: nh } = state.avatarNaturalSize;
      const scale = AVATAR_BOX_PX / (W * nw);
      return {
        position: "absolute",
        width: `${nw * scale}px`,
        height: `${nh * scale}px`,
        left: `${-L * nw * scale}px`,
        top: `${-T * nh * scale}px`,
        maxWidth: "none",
      };
    },
    /** @returns {boolean} */
    heroImgUsePrecise(state) {
      if (!this.isViewingSelf || isCropFullFrame(state.cropRectBg)) return false;
      const { w: nw, h: nh } = state.bgNaturalSize;
      return nw > 0 && nh > 0 && state.heroSize.w > 0 && state.heroSize.h > 0;
    },
    /** @returns {Record<string, string>} */
    heroImgStyle(state) {
      if (!this.heroImgUsePrecise) return {};
      const { L, T, W } = state.cropRectBg;
      const { w: nw, h: nh } = state.bgNaturalSize;
      const scale = state.heroSize.w / (W * nw);
      return {
        position: "absolute",
        width: `${nw * scale}px`,
        height: `${nh * scale}px`,
        left: `${-L * nw * scale}px`,
        top: `${-T * nh * scale}px`,
        maxWidth: "none",
      };
    },
    cropBoxStyle(state) {
      const { L, T, W, H } = state.cropRectDraft;
      return {
        left: `${L * 100}%`,
        top: `${T * 100}%`,
        width: `${W * 100}%`,
        height: `${H * 100}%`,
      };
    },
  },

  actions: {
    // 同一份视频/作者会同时出现在多份列表，feedPatchStore 负责广播，各域只改自己的
    patchVideoById(id, patch) {
      this.worksRows = patchRowById(this.worksRows, id, patch);
    },
    patchAuthorFollow(authorId, isFollow) {
      this.worksRows = patchRowsByAuthorId(this.worksRows, authorId, isFollow);
    },
    removeVideoById(id) {
      this.worksRows = removeRowById(this.worksRows, id);
    },

    /** 打开主页前收起其它遮罩并记录返回现场 */
    _enter(targetUserId) {
      const returnCtx = hooks.prepareOpen?.() ?? null;
      persistReturnContext(returnCtx);
      this.targetUserId = targetUserId;
      this.open = true;
      this.avatarImgError = false;
      this.bgImgError = false;
      this.relationOpen = false;
      this.editOpen = false;
      hooks.pushHistory?.(targetUserId == null ? "self" : String(targetUserId));
      void this.loadUser({ withWorks: true });
    },

    openSelf() {
      this._enter(null);
    },

    openOther(userId) {
      if (userId == null || userId === "") return;
      this._enter(userId);
    },

    /** 头像点击：自己进自己主页，他人进他人主页 */
    openByAuthorId(authorId) {
      if (authorId == null || authorId === "") return;
      const uid = useAuthStore().userId;
      if (uid != null && String(authorId) === String(uid)) {
        this.openSelf();
        return;
      }
      this.openOther(authorId);
    },

    close() {
      this.open = false;
      this.targetUserId = null;
      this.mediaPreviewOpen = false;
      this.clearPendingCropUpload();
      this.closeRelationTable();
      this.editOpen = false;
      this.clearReturnContext();
    },

    clearReturnContext() {
      persistReturnContext(null);
    },

    readReturnContext() {
      return readPersistedReturnContext();
    },

    async loadUser({ withWorks = false } = {}) {
      if (!getToken()) return;
      const auth = useAuthStore();
      this.loading = true;
      this.msg = "";
      try {
        const me = normalizeUserProfile(await getUserInfo());
        if (me) auth.setSelfProfile(me);
        if (me?.id != null) auth.setCurrentUser({ userId: me.id });
        this.user =
          this.targetUserId == null
            ? me
            : normalizeUserProfile(await getUserInfoById(this.targetUserId));
        if (this.targetUserId == null) this.targetFollowed = false;
      } catch (error) {
        this.msg = `加载用户信息失败：${error.message}`;
      } finally {
        this.loading = false;
        this.applyCropsFromStorage();
      }
      void this.refreshFollowState();
      if (withWorks) void this.loadWorks();
    },

    async refreshFollowState() {
      const uid = useAuthStore().userId;
      if (!getToken() || this.targetUserId == null) {
        this.targetFollowed = false;
        return;
      }
      if (uid != null && String(this.targetUserId) === String(uid)) {
        this.targetFollowed = false;
        return;
      }
      try {
        this.targetFollowed = await isFollowed(this.targetUserId);
      } catch {
        this.targetFollowed = false;
      }
    },

    async loadWorks() {
      const auth = useAuthStore();
      const uid = this.targetUserId ?? auth.userId;
      if (!uid) {
        this.worksRows = [];
        return;
      }
      this.worksLoading = true;
      try {
        const isSelf = this.targetUserId == null;
        // 我的作品: 视频与动态同列(统一内容池); 他人主页暂只显示视频
        const res = isSelf
          ? await fetchMyContents({ pageNo: 1, pageSize: 24 })
          : await fetchOtherUserVideos(uid, { pageNo: 1, pageSize: 24 });
        const fallbackNick = stripUserIdLikeName(this.user?.nickName || this.user?.nickname);
        const map = new Map();
        for (const item of res?.list || []) {
          if (!item?.id) continue;
          const row = { ...item };
          row.authorNickname =
            stripUserIdLikeName(row.authorNickname) ||
            stripUserIdLikeName(row.author) ||
            fallbackNick ||
            row.authorNickname ||
            row.author;
          map.set(String(item.id), row);
        }
        this.worksRows = Array.from(map.values());
      } catch {
        this.worksRows = [];
      } finally {
        this.worksLoading = false;
      }
    },

    async toggleFollow() {
      const auth = useAuthStore();
      if (!auth.isAuthed || this.targetUserId == null) {
        pushToast("请先登录", "info");
        return;
      }
      if (this.isViewingSelf) return;
      const aid = this.targetUserId;
      this.followBusy = true;
      try {
        if (this.targetFollowed) {
          await unfollowAuthor(aid);
          this.targetFollowed = false;
        } else {
          await followAuthor(aid);
          this.targetFollowed = true;
        }
        hooks.patchAuthorFollow?.(aid, this.targetFollowed);
        pushToast(this.targetFollowed ? "关注成功" : "已取消关注", "success");
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      } finally {
        this.followBusy = false;
      }
    },

    /** 他人主页「聊一聊」：建会话后由 chatStore 打开 */
    async startChat() {
      const auth = useAuthStore();
      if (!auth.isAuthed) {
        pushToast("请先登录", "info");
        return;
      }
      if (this.targetUserId == null || this.isViewingSelf) return;
      const u = this.user;
      const peerId =
        parseDigitSnowflakeId(u?.id) ??
        parseDigitSnowflakeId(this.targetUserId) ??
        safeSnowflakeId(u?.id) ??
        safeSnowflakeId(this.targetUserId);
      const name = stripUserIdLikeName(u?.nickName || u?.nickname || "") || `用户 ${peerId}`;
      const avatar = sanitizeImageUrl(u?.avatarUrl) || defaultAvatar;
      this.chatBusy = true;
      try {
        await startSingleChat(peerId, { name, avatar });
        pushToast("已打开会话", "success");
      } catch (e) {
        pushToast(e?.message || "发起聊天失败", "error");
      } finally {
        this.chatBusy = false;
      }
    },

    onWorkCardClick(item) {
      hooks.openWorkFeed?.(item);
    },

    /* ---------- 头像 / 背景 裁剪 ---------- */

    applyCropsFromStorage() {
      if (!this.isViewingSelf) return;
      const uid = useAuthStore().userId ?? this.user?.id;
      if (!uid) return;
      const urlBg = sanitizeImageUrl(this.user?.bgImage) || this.displayBg;
      const urlAv = sanitizeImageUrl(this.user?.avatarUrl) || this.displayAvatar;
      if (urlBg) this.cropRectBg = loadCropFromStorage("bg", uid, urlBg) || { ...FULL_FRAME };
      if (urlAv) this.cropRectAvatar = loadCropFromStorage("avatar", uid, urlAv) || { ...FULL_FRAME };
    },

    applyDraftCrop() {
      if (!this.isViewingSelf) return;
      const uid = useAuthStore().userId ?? this.user?.id;
      if (!uid) return;
      const rect = normalizeRect({ ...this.cropRectDraft });
      if (this.mediaPreviewType === "bg") {
        const url = sanitizeImageUrl(this.user?.bgImage) || this.displayBg;
        this.cropRectBg = rect;
        saveCropToStorage("bg", uid, url, rect);
      } else {
        const url = sanitizeImageUrl(this.user?.avatarUrl) || this.displayAvatar;
        this.cropRectAvatar = rect;
        saveCropToStorage("avatar", uid, url, rect);
      }
    },

    /** 横幅容器尺寸变化会影响精确裁剪定位，需在挂载/展开时重新量取 */
    measureHero(el) {
      if (!el) return;
      const r = el.getBoundingClientRect();
      this.heroSize = { w: r.width, h: r.height };
    },

    syncNaturalSizes() {
      preloadNaturalSize(this.displayBg, (s) => {
        this.bgNaturalSize = s;
      });
      preloadNaturalSize(this.displayAvatar, (s) => {
        this.avatarNaturalSize = s;
      });
    },

    setBgNaturalSize(size) {
      this.bgNaturalSize = size;
    },

    setAvatarNaturalSize(size) {
      this.avatarNaturalSize = size;
    },

    /** 新上传的图默认按目标比例取最大居中选区 */
    applyDefaultCropForUpload(naturalW, naturalH) {
      const { L, T, W, H } = this.cropRectDraft;
      if (L > 0.001 || T > 0.001 || W < 0.999 || H < 0.999) return;
      if (naturalW <= 0 || naturalH <= 0) return;
      this.cropNaturalSize = { w: naturalW, h: naturalH };
      const aspect = this.mediaPreviewType === "bg" ? 16 / 5 : 1;
      this.cropRectDraft = makeDefaultCropRect(naturalW, naturalH, aspect, 1);
    },

    setCropDraft(rect) {
      this.cropRectDraft = rect;
    },

    setCropNaturalSize(size) {
      this.cropNaturalSize = size;
    },

    openMediaPreview(type) {
      this.clearPendingCropUpload();
      this.mediaPreviewType = type;
      this.cropRectDraft = { ...(type === "bg" ? this.cropRectBg : this.cropRectAvatar) };
      this.cropNaturalSize = { w: 0, h: 0 };
      this.mediaPreviewOpen = true;
    },

    closeMediaPreview() {
      if (this.isViewingSelf && !this.pendingCropFile) this.applyDraftCrop();
      this.clearPendingCropUpload();
      this.mediaPreviewOpen = false;
    },

    clearPendingCropUpload() {
      this.pendingCropFile = null;
      this.pendingCropType = null;
      if (this.pendingCropUrl) {
        try {
          URL.revokeObjectURL(this.pendingCropUrl);
        } catch {
          /* ignore */
        }
      }
      this.pendingCropUrl = "";
    },

    beginCropUpload(type, file) {
      this.clearPendingCropUpload();
      this.pendingCropFile = file;
      this.pendingCropType = type;
      this.pendingCropUrl = URL.createObjectURL(file);
      this.mediaPreviewType = type;
      this.cropRectDraft = { ...FULL_FRAME };
      this.mediaPreviewOpen = true;
    },

    async submitCropUpload() {
      if (!this.pendingCropFile || !this.pendingCropType) return;
      this.uploadBusy = true;
      this.msg = "";
      try {
        const cropped = await buildCroppedUploadFile(
          this.pendingCropUrl,
          this.pendingCropType,
          normalizeRect({ ...this.cropRectDraft }),
          this.pendingCropFile?.name,
        );
        if (this.pendingCropType === "avatar") {
          const url = await updateUserAvatar(cropped);
          if (this.user) this.user = normalizeUserProfile({ ...this.user, avatarUrl: url });
          this.cropRectAvatar = { ...FULL_FRAME };
          this.msg = "头像已裁剪并上传";
        } else {
          const url = await updateUserBgImage(cropped);
          if (this.user) this.user = normalizeUserProfile({ ...this.user, bgImage: url });
          this.cropRectBg = { ...FULL_FRAME };
          this.msg = "背景图已裁剪并上传";
        }
        this.clearPendingCropUpload();
        this.mediaPreviewOpen = false;
      } catch (error) {
        this.msg = `裁剪上传失败：${error.message}`;
      } finally {
        this.uploadBusy = false;
      }
    },

    markAvatarError() {
      this.avatarImgError = true;
    },

    markBgError() {
      this.bgImgError = true;
    },

    markMediaPreviewError() {
      if (this.mediaPreviewType === "bg") this.markBgError();
      else this.markAvatarError();
    },

    /* ---------- 关注 / 粉丝列表 ---------- */

    openRelationTable(kind) {
      if (!this.isViewingSelf) return;
      this.relationFollowerMenuId = null;
      this.relationKind = kind;
      this.relationTitle = kind === "following" ? "关注列表" : "粉丝列表";
      this.relationUsers = kind === "following" ? this.followingRows : this.followerRows;
      void this.loadRelationPage(kind, { reset: true });
      this.relationOpen = true;
    },

    closeRelationTable() {
      this.relationOpen = false;
      this.relationFollowerMenuId = null;
    },

    toggleFollowerMenu(userId) {
      this.relationFollowerMenuId = this.relationFollowerMenuId === userId ? null : userId;
    },

    async loadRelationPage(kind, { reset = false } = {}) {
      const auth = useAuthStore();
      if (!auth.isAuthed) return;
      const isFollowing = kind === "following";
      if (reset) {
        if (isFollowing) {
          this.followingPageNo = 1;
          this.followingHasMore = true;
        } else {
          this.followerPageNo = 1;
          this.followerHasMore = true;
        }
      }
      const loadingKey = isFollowing ? "followingLoading" : "followerLoading";
      const hasMoreKey = isFollowing ? "followingHasMore" : "followerHasMore";
      const pageNoKey = isFollowing ? "followingPageNo" : "followerPageNo";
      if (this[loadingKey]) return;
      if (!this[hasMoreKey] && !reset) return;
      this[loadingKey] = true;
      try {
        const pageNo = reset ? 1 : this[pageNoKey];
        const data = isFollowing
          ? await fetchMyFollowingPage({ pageNo, pageSize: RELATION_PAGE_SIZE })
          : await fetchMyFansPage({ pageNo, pageSize: RELATION_PAGE_SIZE });
        const normalized = extractPageList(data).map(normalizeRelationUser).filter(Boolean);
        const rowsKey = isFollowing ? "followingRows" : "followerRows";
        this[rowsKey] = reset ? normalized : [...this[rowsKey], ...normalized];
        if (this.relationOpen && this.relationKind === kind) this.relationUsers = this[rowsKey];
        this[pageNoKey] = pageNo;
        this[hasMoreKey] = normalized.length === RELATION_PAGE_SIZE;
      } catch {
        if (reset) {
          this[isFollowing ? "followingRows" : "followerRows"] = [];
          this[pageNoKey] = 1;
        }
        this[hasMoreKey] = false;
      } finally {
        this[loadingKey] = false;
      }
    },

    loadRelationNextPage(kind) {
      const isFollowing = kind === "following";
      if (isFollowing) {
        if (this.followingLoading || !this.followingHasMore) return;
        this.followingPageNo += 1;
      } else {
        if (this.followerLoading || !this.followerHasMore) return;
        this.followerPageNo += 1;
      }
      void this.loadRelationPage(kind);
    },

    async removeFollower(user) {
      this.relationFollowerMenuId = null;
      try {
        await removeFan(user.id);
        this.followerRows = this.followerRows.filter((x) => String(x.id) !== String(user.id));
        if (this.relationKind === "followers") this.relationUsers = this.followerRows;
        if (this.user && this.isViewingSelf) {
          const n = Number(this.user.fans);
          if (!Number.isNaN(n) && n > 0) {
            this.user = normalizeUserProfile({ ...this.user, fans: n - 1 });
          }
        }
        pushToast(`已移除粉丝「${user.nickName || "该用户"}」`, "success");
      } catch (e) {
        pushToast(`移除失败：${e.message || e}`, "error");
      }
    },

    async followBack(user) {
      this.relationFollowerMenuId = null;
      try {
        await followAuthor(user.id);
        pushToast(`已回关「${user.nickName || "该用户"}」`, "success");
      } catch (e) {
        pushToast(`回关失败：${e.message || e}`, "error");
      }
    },

    async unfollowFollowing(user) {
      try {
        await unfollowAuthor(user.id);
        this.followingRows = this.followingRows.filter((x) => String(x.id) !== String(user.id));
        if (this.relationKind === "following") this.relationUsers = this.followingRows;
        pushToast(`已取关「${user.nickName || "该用户"}」`, "success");
      } catch (e) {
        pushToast(`取关失败：${e.message || e}`, "error");
      }
    },

    /* ---------- 资料编辑 ---------- */

    openEdit() {
      if (!this.isViewingSelf || !this.user) return;
      this.editForm = {
        nickName: this.user.nickName || "",
        introduction: this.user.introduction || "",
      };
      this.editOpen = true;
    },

    closeEdit() {
      this.editOpen = false;
    },

    async saveEdit() {
      if (!this.user) return;
      const auth = useAuthStore();
      this.editSaving = true;
      this.msg = "";
      const nickName = this.editForm.nickName.trim();
      const introduction = this.editForm.introduction.trim();
      const uid = this.user.id;
      try {
        if (uid == null || uid === "") throw new Error("缺少用户 ID，无法请求后端");
        const data = await updateUserProfile(uid, { nickname: nickName, introduction });
        const merged = data && typeof data === "object" ? { ...this.user, ...data } : { ...this.user };
        this.user = normalizeUserProfile({ ...merged, nickName, introduction });
        this.msg = "资料已保存";
        auth.setSelfProfile(this.user);
      } catch (error) {
        this.user = normalizeUserProfile({ ...this.user, nickName, introduction });
        this.msg = `已保存到本地展示（接口未就绪或失败：${error.message}）`;
      } finally {
        this.editSaving = false;
        this.editOpen = false;
      }
    },
  },
});

/** 按目标比例把选区内图像绘制到 canvas 并导出 jpeg */
async function buildCroppedUploadFile(sourceUrl, type, rect, fallbackName) {
  const img = await new Promise((resolve, reject) => {
    const el = new Image();
    el.onload = () => resolve(el);
    el.onerror = () => reject(new Error("图片解码失败"));
    el.src = sourceUrl;
  });
  const nw = img.naturalWidth || 0;
  const nh = img.naturalHeight || 0;
  if (nw <= 0 || nh <= 0) throw new Error("无法读取图片尺寸");
  const r = normalizeRect(rect);
  const outW = type === "avatar" ? 640 : 1600;
  const outH = type === "avatar" ? 640 : 500;
  const canvas = document.createElement("canvas");
  canvas.width = outW;
  canvas.height = outH;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("浏览器不支持画布裁剪");
  ctx.drawImage(
    img,
    Math.round(r.L * nw),
    Math.round(r.T * nh),
    Math.max(1, Math.round(r.W * nw)),
    Math.max(1, Math.round(r.H * nh)),
    0,
    0,
    outW,
    outH,
  );
  const blob = await new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error("导出裁剪图片失败"))),
      "image/jpeg",
      0.92,
    );
  });
  const safeName = String(fallbackName || `${type}-${Date.now()}.jpg`).replace(/\.[^.]+$/, "");
  return new File([blob], `${safeName}-crop.jpg`, { type: "image/jpeg" });
}
