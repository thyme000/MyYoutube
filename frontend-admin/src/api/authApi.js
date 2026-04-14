import { httpRequest } from "./http.js";

export function getLoginCaptcha(username) {
  return httpRequest("/captcha", { method: "POST", body: { username } });
}

export function login(payload) {
  return httpRequest("/login", { method: "POST", body: payload });
}

