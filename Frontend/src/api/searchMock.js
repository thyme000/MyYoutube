/**
 * 仅开发联调示例（与联调说明一致）。生产勿打包依赖此文件逻辑。
 */
import { normalizePageResult } from "./videoApi.js";

export function mockSearchPrompt(keyword) {
  const k = String(keyword ?? "").trim();
  if (!k) return [];
  if (k.includes("周") || k === "周") {
    return ["周杰伦", "周杰伦 新歌", "周董", "周末vlog", "周边开箱"];
  }
  return [`${k} 相关`, `${k} 教程`, `${k} 合集`];
}

export function mockSearchHot() {
  return ["AI编程", "周杰伦", "短剧推荐", "Java面试", "前端性能", "SpringBoot", "旅行vlog"];
}

export async function mockSearchVideoPage(query) {
  const keyword = String(query.keyword ?? "").trim();
  const pageNo = Number(query.pageNo) || 1;
  const pageSize = Number(query.pageSize) || 10;
  const list = [];
  for (let i = 0; i < pageSize; i++) {
    const n = (pageNo - 1) * pageSize + i + 1;
    list.push({
      id: `${1000000000000000000 + n}`,
      title: `${keyword || "视频"} 示例 ${n}`,
      description: "翻山越岭也找不到 像我一样存在 #日常 #天秤座",
      coverUrl: "",
      videoUrl: "",
      authorId: "10001",
      authorNickname: "雪蕊呀!",
      playCount: 61000 + n,
      commentCount: 300 + n,
      likeCount: 61000,
      videoType: n % 2 === 0 ? "SHORT_VIDEO" : "LONG_VIDEO",
      isFollow: n % 3 === 0,
      createTime: new Date().toISOString(),
    });
  }
  return normalizePageResult({
    total: 50,
    pages: 5,
    list,
  });
}

export async function mockSearchUserPage(query) {
  const keyword = String(query.keyword ?? "").trim();
  const pageNo = Number(query.pageNo) || 1;
  const pageSize = Number(query.pageSize) || 10;
  const list = [];
  for (let i = 0; i < pageSize; i++) {
    const n = (pageNo - 1) * pageSize + i + 1;
    list.push({
      id: 200000 + n,
      nickname: `${keyword || "用户"}_${n}`,
      username: `u_${n}`,
      avatarUrl: "",
      introduction: "这是简介示例",
      fans: 100 + n,
      likeCount: 50 + n,
      isFollow: n % 2 === 0,
    });
  }
  return {
    total: 30,
    pages: 3,
    list,
  };
}

export async function mockSearchTagPage(query) {
  const tag =
    String(query.tagName ?? query.keyword ?? "").trim() || "标签";
  return mockSearchVideoPage({ ...query, keyword: `标签:${tag}` });
}
