/**
 * 预签名 PUT 直传 RustFS（视频 work、聊天 chat，与后端 /storage/presign 一致）。
 */
import { getStoragePresign } from "../api/otherApi.js";

/**
 * @param {File} file
 * @param {"work"|"chat"} bizType
 * @param {(p: number) => void} [onProgress] 0~1
 */
export async function uploadFileWithPresign(file, bizType, onProgress) {
  const presign = await getStoragePresign(bizType, file?.name);
  await putFile(presign.url, file, onProgress);
  return {
    objectKey: presign.objectKey,
    mimeType: file.type || "application/octet-stream",
    size: file.size,
    fileName: file.name || "file",
  };
}

function putFile(url, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url);
    if (file.type) {
      xhr.setRequestHeader("Content-Type", file.type);
    }
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && typeof onProgress === "function") {
        onProgress(e.loaded / e.total);
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(`直传失败: HTTP ${xhr.status}`));
      }
    };
    xhr.onerror = () => reject(new Error("直传失败: 网络错误"));
    xhr.send(file);
  });
}
