// @ts-check
/**
 * 动态域 store: 信息流分页 + 下载任务跟踪。
 * 下载是懒转码: 提交后拿 taskId 挂在表里, SSE 收到 TRANSCODE_DONE/FAILED 后
 * 凭 payload.taskIds 对号拉取下载地址并触发浏览器保存。
 */
import { defineStore } from "pinia";
import {
  fetchDynamicFeed,
  requestDynamicDownload,
  fetchDynamicDownloadStatus,
} from "../api/dynamicApi";
import { downloadRemoteFile } from "../utils/downloadFile";
import { useToastStore } from "./toastStore";

export const useDynamicStore = defineStore("dynamic", {
  state: () => ({
    /** @type {Array<object>} */
    feedList: [],
    pageNo: 1,
    pages: 0,
    total: 0,
    feedLoading: false,
    feedErrorMsg: "",
    publishOpen: false,
    /** taskId -> dynamicId, 等待 SSE 完成通知的转码任务 */
    /** @type {Record<string, string>} */
    pendingDownloads: {},
  }),
  actions: {
    async loadFeed(reset) {
      if (this.feedLoading) return;
      if (!reset && this.pageNo >= this.pages && this.pages > 0) return;
      this.feedLoading = true;
      if (reset) this.feedErrorMsg = "";
      const page = reset ? 1 : this.pageNo + 1;
      try {
        const { total, pages, list } = await fetchDynamicFeed({ pageNo: page, pageSize: 8 });
        this.total = total;
        this.pages = pages;
        this.pageNo = page;
        this.feedList = reset ? list : [...this.feedList, ...list];
      } catch (e) {
        this.feedErrorMsg = e?.message || "加载动态失败";
      } finally {
        this.feedLoading = false;
      }
    },
    /** 点下载: 缓存命中直接存, 否则登记任务等 SSE 通知 */
    async requestDownload(dynamic) {
      const toastStore = useToastStore();
      try {
        const { taskId, status, downloadUrl } = await requestDynamicDownload(dynamic.id);
        if (status === "DONE" && downloadUrl) {
          await downloadRemoteFile(downloadUrl, `dynamic-${dynamic.id}.mp4`);
          toastStore.pushToast("成片已开始下载", "success");
          return;
        }
        if (status === "FAILED") {
          toastStore.pushToast("成片生成失败，请稍后重试", "error");
          return;
        }
        this.pendingDownloads[taskId] = dynamic.id;
        toastStore.pushToast("成片生成中，完成后会自动开始下载", "info");
      } catch (e) {
        toastStore.pushToast(e?.message || "请求下载失败", "error");
      }
    },
    /** SSE 门铃信号: 转码完成/失败。报文形如 { type, payload: { taskIds } }, 兼容顶层 taskIds */
    async onTranscodeSignal(type, message) {
      const inner = message?.payload && typeof message.payload === "object" ? message.payload : message;
      const taskIds = Array.isArray(inner?.taskIds) ? inner.taskIds.map(String) : [];
      const toastStore = useToastStore();
      for (const taskId of taskIds) {
        const dynamicId = this.pendingDownloads[taskId];
        if (!dynamicId) continue;
        delete this.pendingDownloads[taskId];
        if (type !== "TRANSCODE_DONE") {
          toastStore.pushToast("成片生成失败，请稍后重试", "error");
          continue;
        }
        try {
          const { status, downloadUrl } = await fetchDynamicDownloadStatus(taskId);
          if (status === "DONE" && downloadUrl) {
            await downloadRemoteFile(downloadUrl, `dynamic-${dynamicId}.mp4`);
            toastStore.pushToast("成片已生成并开始下载", "success");
          }
        } catch (e) {
          toastStore.pushToast(e?.message || "获取成片下载地址失败", "error");
        }
      }
    },
  },
});
