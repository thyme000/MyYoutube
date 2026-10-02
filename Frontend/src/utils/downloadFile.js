// @ts-check
/**
 * 以浏览器下载方式保存远程文件(成片预签名 GET 走 RustFS, CORS 已放开)。
 */
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
