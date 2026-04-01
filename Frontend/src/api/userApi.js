import { httpRequest, uploadMultipart } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/**
 * 获取当前登录用户信息（需 Authorization）
 * GET /user/info → UserVO
 */
export function getUserInfo() {
  return httpRequest("/user/info", { method: "GET" });
}

/**
 * 获取指定用户信息（需 Authorization）
 * GET /user/info/{userId} → UserVO
 */
export function getUserInfoById(userId) {
  const id = encodeURIComponent(safeSnowflakeId(userId));
  if (!id) {
    throw new Error("userId 不能为空");
  }
  return httpRequest(`/user/info/${id}`, { method: "GET" });
}

/**
 * 修改头像：表单字段名 avatar（与后端 MultipartFile 参数名一致）
 * POST /user/avatar
 */
export function updateUserAvatar(file) {
  const fd = new FormData();
  fd.append("avatar", file);
  return uploadMultipart("/user/avatar", fd);
}

/**
 * 修改背景图：表单字段名 bgImage
 * POST /user/bg-image
 */
export function updateUserBgImage(file) {
  const fd = new FormData();
  fd.append("bgImage", file);
  return uploadMultipart("/user/bg-image", fd);
}

/**
 * 更新用户资料（昵称、个人简介）
 * PUT /user/{id}，body 字段：nickname、introduction
 */
export function updateUserProfile(userId, { nickname, introduction }) {
  const id = encodeURIComponent(safeSnowflakeId(userId));
  if (!id) {
    throw new Error("userId 不能为空");
  }
  return httpRequest(`/user/${id}`, {
    method: "PUT",
    body: { nickname, introduction },
  });
}
