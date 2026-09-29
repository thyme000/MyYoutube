// @ts-check
import { httpRequest } from "./http";

/** @typedef {{ username: string, password: string, captcha: string }} LoginPayload */
/** @typedef {{ username: string, password: string, confirmPassword: string, email: string, phone: string, captcha: string }} RegisterPayload */
/** @typedef {{ username: string, phone: string, newPassword: string, captcha: string }} ForgotPayload */

/** @param {string} username @returns {Promise<string>} 开发态验证码明文 */
export function getLoginCaptcha(username) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { username },
  });
}

/** @param {string} phone @returns {Promise<string>} */
export function getRegisterCaptcha(phone) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { phone },
  });
}

// 忘记密码：验证码请求需要同时传 username + phone
/** @param {{ username: string, phone: string }} payload @returns {Promise<string>} */
export function getForgotPasswordCaptcha({ username, phone }) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { username, phone },
  });
}

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
