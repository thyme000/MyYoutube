import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/**
 * 规范化单条评论（后端 CommentVO）
 */
export function normalizeCommentVo(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  return {
    id: raw.id,
    content: raw.content ?? "",
    videoId: raw.videoId,
    userId: raw.userId,
    nickname: raw.nickname ?? "用户",
    avatarUrl: raw.avatarUrl ?? "",
    createTime: raw.createTime ?? "",
    status: raw.status ?? raw.commentStatus ?? raw.comment_status,
    likeCount: Number(raw.likeCount) || 0,
    isLike: raw.isLike === true || raw.isLike === 1 || raw.isLike === "1",
    replyCount: Number(raw.replyCount) || 0,
    parentId: raw.parentId,
    replyNickname: raw.replyNickname ?? "",
    replyUserId: raw.replyUserId,
  };
}

/**
 * GET /comment/list 分页
 * - 一级：parentId=0（不传 rootId），后端 isNull(rootId)
 * - 某楼回复：parentId≠0 且必须传 rootId=楼主评论 id，后端 eq(rootId, rootId)
 */
export async function fetchCommentPage(query = {}) {
  const videoId = safeSnowflakeId(query.videoId);
  if (!videoId) {
    throw new Error("videoId 不能为空");
  }
  let parentId = query.parentId;
  if (parentId === 0 || parentId === "0" || parentId == null || parentId === "") {
    parentId = 0;
  } else {
    parentId = safeSnowflakeId(parentId);
    if (!parentId) {
      throw new Error("parentId 无效");
    }
  }
  const q = {
    videoId,
    parentId,
    pageNo: query.pageNo ?? 1,
    pageSize: query.pageSize ?? 10,
  };
  if (parentId !== 0) {
    const rootId = safeSnowflakeId(query.rootId ?? query.parentId);
    if (!rootId) {
      throw new Error("rootId 不能为空（展开回复时需传楼主评论 id）");
    }
    q.rootId = rootId;
  }
  const data = await httpRequest("/comment/list", { method: "GET", query: q });
  if (!data || typeof data !== "object") {
    return { total: 0, pages: 0, list: [] };
  }
  const list = Array.isArray(data.list) ? data.list : [];
  return {
    total: Number(data.total) || 0,
    pages: Number(data.pages) || 0,
    list: list.map((x) => normalizeCommentVo(x)).filter(Boolean),
  };
}

/**
 * POST /comment/publish JSON：videoId, content, parentId（0=一级）
 */
export async function publishComment(body) {
  const videoId = safeSnowflakeId(body?.videoId);
  if (!videoId) {
    throw new Error("videoId 不能为空");
  }
  const content = String(body?.content ?? "").trim();
  if (!content) {
    throw new Error("评论内容不能为空");
  }
  let parentId = body?.parentId;
  if (parentId == null || parentId === "") {
    parentId = 0;
  } else if (parentId === 0 || parentId === "0") {
    parentId = 0;
  } else {
    parentId = safeSnowflakeId(parentId);
    if (!parentId) {
      throw new Error("parentId 无效");
    }
  }
  return httpRequest("/comment/publish", {
    method: "POST",
    body: { videoId, content, parentId },
  });
}

/**
 * POST /comment/delete?commentId=
 */
export async function deleteComment(commentId) {
  const id = safeSnowflakeId(commentId);
  if (!id) {
    throw new Error("commentId 不能为空");
  }
  return httpRequest("/comment/delete", { method: "POST", query: { commentId: id } });
}

/**
 * PUT /comment/report
 * body: { commentId, reportType, remark }
 */
export async function reportComment(body) {
  const commentId = safeSnowflakeId(body?.commentId);
  if (!commentId) {
    throw new Error("commentId 不能为空");
  }
  const reportType = String(body?.reportType ?? "").trim();
  if (!reportType) {
    throw new Error("举报原因不能为空");
  }
  const remark = String(body?.remark ?? "").trim();
  if (!remark) {
    throw new Error("补充说明不能为空");
  }
  return httpRequest("/comment/report", {
    method: "PUT",
    body: { commentId, reportType, remark },
  });
}
