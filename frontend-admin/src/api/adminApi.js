import { httpRequest } from "./http.js";

export function listAdminVideos(query) {
  const q = query && typeof query === "object" ? query : {};
  return httpRequest("/admin/list", {
    method: "GET",
    query: { ...q, adminVideoPageQueryDTO: JSON.stringify(q) },
  });
}

export function reviewPass(videoId) {
  return httpRequest(`/admin/review/pass/${encodeURIComponent(String(videoId))}`, { method: "POST" });
}

export function reviewBan(videoId) {
  return httpRequest(`/admin/review/ban/${encodeURIComponent(String(videoId))}`, { method: "POST" });
}

export function blockComment(commentId) {
  return httpRequest(`/admin/comment/blocked/${encodeURIComponent(String(commentId))}`, { method: "POST" });
}

export function restoreComment(commentId) {
  return httpRequest(`/admin/comment/restore/${encodeURIComponent(String(commentId))}`, { method: "POST" });
}

export function listBlockedComments(query) {
  return httpRequest("/admin/comment/list", { method: "GET", query });
}

export function listCommentReports(query) {
  return httpRequest("/admin/comment/report", { method: "GET", query });
}

export function ignoreReportComment(commentId) {
  return httpRequest(`/admin/comment/report/ignore/${encodeURIComponent(String(commentId))}`, { method: "POST" });
}
