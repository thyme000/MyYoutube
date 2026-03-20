import { httpRequest } from "./http";

export function getLoginCaptcha(username) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { username },
  });
}

export function getRegisterCaptcha(phone) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { phone },
  });
}

// 忘记密码：验证码请求需要同时传 username + phone
export function getForgotPasswordCaptcha({ username, phone }) {
  return httpRequest("/captcha", {
    method: "POST",
    body: { username, phone },
  });
}

export function loginApi(payload) {
  return httpRequest("/login", {
    method: "POST",
    body: payload,
  });
}

export function registerApi(payload) {
  return httpRequest("/register", {
    method: "POST",
    body: payload,
  });
}

export function forgotPasswordApi(payload) {
  return httpRequest("/forget-password", {
    method: "POST",
    body: payload,
  });
}
