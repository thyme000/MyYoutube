import { httpRequest } from "./http";

const moduleApiMap = {
  home: "/api/videos/home",
  shorts: "/api/videos/shorts",
  subscribed: "/api/videos/subscribed",
  history: "/api/videos/history",
  liked: "/api/videos/liked",
  watchLater: "/api/videos/watch-later",
  music: "/api/videos/music",
  movies: "/api/videos/movies",
  live: "/api/videos/live",
  news: "/api/videos/news",
  fashion: "/api/videos/fashion",
};

function normalizeVideo(item, index) {
  return {
    id: item.id ?? `video-${index}`,
    title: item.title ?? "未命名视频",
    author: item.author ?? item.channelName ?? "未知频道",
    viewsText: item.viewsText ?? item.viewCountText ?? "0次观看",
    videoUrl: item.videoUrl ?? item.playUrl ?? item.url ?? "",
    avatarUrl: item.avatarUrl ?? item.authorAvatar ?? item.coverUrl ?? "",
  };
}

export function normalizeVideoList(payload) {
  if (Array.isArray(payload)) {
    return payload.map(normalizeVideo);
  }
  if (payload && Array.isArray(payload.list)) {
    return payload.list.map(normalizeVideo);
  }
  if (payload && Array.isArray(payload.records)) {
    return payload.records.map(normalizeVideo);
  }
  return [];
}

export async function fetchVideosByModule(moduleKey) {
  const apiPath = moduleApiMap[moduleKey] || moduleApiMap.home;
  const data = await httpRequest(apiPath);
  return normalizeVideoList(data);
}

export async function searchVideos(keyword) {
  const data = await httpRequest("/api/search", {
    query: { keyword },
  });
  return normalizeVideoList(data);
}
