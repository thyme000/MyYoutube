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
  return httpRequest("/forgot-password", {
    method: "POST",
    body: payload,
  });
}
