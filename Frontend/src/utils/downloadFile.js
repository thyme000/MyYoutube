// @ts-check
/**
 * 以浏览器下载方式保存远程文件(成片预签名 GET 走 RustFS, CORS 已放开)。
 */
import { resolveVideoUrl } from "./videoCard.js";

export async function downloadRemoteFile(url, filename) {
  const resp = await fetch(url);
  if (!resp.ok) {
    throw new Error(`下载失败: HTTP ${resp.status}`);
  }
  const blob = await resp.blob();
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = filename || "download.mp4";
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 10_000);
}

/** 预签名地址的扩展名不可信（带 query/签名段），只认白名单，其余按 mp4 */
const DOWNLOADABLE_EXT = new Set(["mp4", "mov", "webm", "mkv", "m4v"]);

function guessExt(url) {
  const m = /\.([a-z0-9]{2,4})(?:[?#]|$)/i.exec(String(url || ""));
  const ext = m ? m[1].toLowerCase() : "";
  return DOWNLOADABLE_EXT.has(ext) ? ext : "mp4";
}

/** 视频条目保存文件名：video-<id>.<ext> */
export function videoDownloadName(item) {
  return `video-${String(item?.id ?? "unknown")}.${guessExt(resolveVideoUrl(item))}`;
}
