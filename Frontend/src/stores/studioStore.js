// @ts-check
/**
 * 创作中心域：侧栏/tab/作品列表/行内菜单与操作。
 * loadProfileUser（App）与 patchFeedVideoById（跨列表同步）经注册钩子注入；
 * 撤桥过渡期 App 在 setup 末尾调用两个 registerXxx 完成接线。
 */
import { defineStore } from "pinia";
import { setMyVideoPublic, setMyVideoPrivate, openMyVideoComment, closeMyVideoComment } from "../api/videoApi";
import {
  setMyDynamicPublic,
  setMyDynamicPrivate,
  openMyDynamicComment,
  closeMyDynamicComment,
} from "../api/dynamicApi";
import { fetchMyContents } from "../api/contentApi";
import { getToken } from "../api/http";
import { toast as pushToast } from "../utils/toast.js";
import { patchRowById, removeRowById } from "../utils/feedRowPatch.js";

export const studioTabs = [
  { key: "video", label: "视频" },
  { key: "live", label: "直播" },
];

export const useStudioStore = defineStore("studio", {
  state: () => ({
    showStudio: false,
    studioSideNav: "info",
    /** 频道内容子 tab：video=长视频 immersive=短视频 live=直播（直播接口未就绪时仅占位） */
    studioContentTab: "video",
    studioPageNo: 1,
    studioPageSize: 10,
    studioRows: [],
    studioTotal: 0,
    studioPages: 0,
    studioLoading: false,
    studioMsg: "",
    studioRowMenuOpenId: null,
  }),
  actions: {
    // 同一份视频会同时出现在多份列表，feedPatchStore 负责广播，各域只改自己的
    patchVideoById(id, patch) {
      this.studioRows = patchRowById(this.studioRows, id, patch);
    },
    removeVideoById(id) {
      this.studioRows = removeRowById(this.studioRows, id);
    },

    selectSideNav(key) {
      this.studioSideNav = key;
      if (key === "info") {
        void this.load();
      }
    },
    switchTab(key) {
      this.studioContentTab = key;
      this.studioPageNo = 1;
      void this.load();
    },
    // 翻页与 tab 切换同属导航，页码边界与在途请求都由 store 兜住，视图只发意图
    prevPage() {
      if (this.studioPageNo <= 1 || this.studioLoading) return;
      this.studioPageNo -= 1;
      void this.load();
    },
    nextPage() {
      if (this.studioPageNo >= (this.studioPages || 1) || this.studioLoading) return;
      this.studioPageNo += 1;
      void this.load();
    },
    closeRowMenu() {
      this.studioRowMenuOpenId = null;
    },
    toggleRowMenu(row, ev) {
      ev?.stopPropagation?.();
      const id = row?.id != null ? String(row.id) : "";
      if (!id) return;
      this.studioRowMenuOpenId = this.studioRowMenuOpenId === id ? null : id;
    },
    async load() {
      if (this.studioSideNav !== "info") {
        return;
      }
      if (!getToken()) {
        this.studioMsg = "请先登录";
        return;
      }
      if (!hooks.getUserId()) {
        await hooks.ensureProfile?.();
      }
      const uid = hooks.getUserId();
      if (!uid) {
        this.studioMsg = "无法获取当前用户 ID";
        this.studioRows = [];
        return;
      }
      this.studioLoading = true;
      this.studioMsg = "";
      try {
        if (this.studioContentTab === "live") {
          this.studioRows = [];
          this.studioTotal = 0;
          this.studioPages = 0;
          this.studioMsg = "直播内容尚未接入，敬请期待。";
          return;
        }
        // 我的内容: 视频与动态同列(统一内容池)
        const res = await fetchMyContents({
          pageNo: this.studioPageNo,
          pageSize: this.studioPageSize,
        });
        this.studioRows = (res.list || []).map((row) => ({
          ...row,
          avatarUrl: row.coverUrl || row.avatarUrl,
          viewsText: row.viewsText || (row.playCount != null ? `${row.playCount} 次播放` : ""),
        }));
        this.studioTotal = res.total;
        this.studioPages = res.pages;
      } catch (e) {
        this.studioRows = [];
        this.studioMsg = `加载失败：${e.message}`;
      } finally {
        this.studioLoading = false;
      }
    },
    async toggleRowVisibility(row) {
      if (!row?.id) return;
      this.closeRowMenu();
      const isDynamic = String(row?.type) === "dynamic";
      const isPrivate = rowVisibilityIsPrivate(row);
      try {
        if (isPrivate) {
          await (isDynamic ? setMyDynamicPublic(row.id) : setMyVideoPublic(row.id));
          hooks.patchFeedById?.(row.id, { visibility: "public" });
          pushToast("已设为公开", "success");
        } else {
          await (isDynamic ? setMyDynamicPrivate(row.id) : setMyVideoPrivate(row.id));
          hooks.patchFeedById?.(row.id, { visibility: "private" });
          pushToast("已设为私密", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },
    async toggleRowComment(row) {
      if (!row?.id) return;
      this.closeRowMenu();
      const isDynamic = String(row?.type) === "dynamic";
      const current = Number(row.allowComment ?? 1);
      const enable = current !== 1;
      try {
        if (enable) {
          await (isDynamic ? openMyDynamicComment(row.id) : openMyVideoComment(row.id));
          hooks.patchFeedById?.(row.id, { allowComment: 1 });
          pushToast("已开启评论", "success");
        } else {
          await (isDynamic ? closeMyDynamicComment(row.id) : closeMyVideoComment(row.id));
          hooks.patchFeedById?.(row.id, { allowComment: 0 });
          pushToast("已关闭评论", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },
  },
});

/** 宿主(App.vue)注册的跨域依赖 */
const hooks = {
  getUserId: () => null,
  ensureProfile: null,
  patchFeedById: null,
  /** 打开上传弹窗(App 级，重置上传态) */
  openUploadModal: () => {},
  /** 请求删除频道视频(App 级，弹确认框) */
  requestDeleteRow: () => {},
};

export function registerStudioHooks({ getUserId, ensureProfile, patchFeedById, openUploadModal, requestDeleteRow }) {
  if (getUserId) hooks.getUserId = getUserId;
  if (ensureProfile) hooks.ensureProfile = ensureProfile;
  if (patchFeedById) hooks.patchFeedById = patchFeedById;
  if (openUploadModal) hooks.openUploadModal = openUploadModal;
  if (requestDeleteRow) hooks.requestDeleteRow = requestDeleteRow;
}

/** StudioView 经此取回 App 级交互，避免反向 import App */
export const studioBridgeHooks = hooks;

export function formatStudioDate(row) {
  const raw = row?.createTime ?? row?.createAt ?? row?.createdAt ?? row?.publishTime;
  if (!raw) return "—";
  if (typeof raw === "string") {
    if (raw.includes("T")) {
      return raw.replace("T", " ").slice(0, 19);
    }
    return raw.length > 19 ? raw.slice(0, 19) : raw;
  }
  try {
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return "—";
    const yyyy = d.getFullYear();
    const MM = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    return `${yyyy}-${MM}-${dd} ${hh}:${mm}`;
  } catch {
    return "—";
  }
}

export function formatStudioVisibility(row) {
  const v = row?.visibility?.code ?? row?.visibility?.name ?? row?.visibility ?? "PUBLIC";
  const s = String(v).toUpperCase();
  if (s.includes("PRIVATE")) return "私有";
  if (s.includes("UNLISTED")) return "不公开";
  return "公开";
}

export function rowVisibilityIsPrivate(row) {
  const v = row?.visibility?.code ?? row?.visibility?.name ?? row?.visibility ?? "";
  return String(v).toUpperCase().includes("PRIVATE");
}
