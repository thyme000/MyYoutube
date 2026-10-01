import { httpRequest } from "./http.js";

/** 后端返回 { accessToken, refreshToken } 双 token 对象 */
export function login(payload) {
  return httpRequest("/login", { method: "POST", body: payload });
}
