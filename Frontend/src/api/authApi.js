// @ts-check
import { httpRequest } from "./http";

/** @typedef {{ username: string, password: string }} LoginPayload */
/** @typedef {{ username: string, password: string, confirmPassword: string, email: string, phone: string }} RegisterPayload */
/** @typedef {{ username: string, phone: string, newPassword: string }} ForgotPayload */

/** @param {LoginPayload} payload @returns {Promise<string>} token 原样（勿加 Bearer） */
export function loginApi(payload) {
  return httpRequest("/login", {
    method: "POST",
    body: payload,
  });
}

/** @param {RegisterPayload} payload @returns {Promise<string>} 提示消息 */
export function registerApi(payload) {
  return httpRequest("/register", {
    method: "POST",
    body: payload,
  });
}

/** @param {ForgotPayload} payload @returns {Promise<string>} 提示消息 */
export function forgotPasswordApi(payload) {
  return httpRequest("/forget-password", {
    method: "POST",
    body: payload,
  });
}
