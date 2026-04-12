/**
 * 使用后端 STS 直传 OSS（视频 work、聊天 chat 与后端 OtherService 一致）。
 */
import OSS from "ali-oss";
import { getOssSts } from "../api/otherApi.js";

export function sanitizeUploadFilename(name) {
  const base = String(name || "file.bin").replace(/[^a-zA-Z0-9._-]/g, "_");
  return base || "file.bin";
}

export function buildUploadObjectKey(sts, file) {
  const dir = String(sts?.dir || "uploads/");
  const safeDir = dir.endsWith("/") ? dir : `${dir}/`;
  const safeFile = sanitizeUploadFilename(file?.name);
  return `${safeDir}${Date.now()}-${Math.random().toString(16).slice(2, 10)}-${safeFile}`;
}

function tryParseJsonLike(input) {
  if (typeof input !== "string") return input;
  const text = input.trim();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}

function pickStsField(obj, keys) {
  for (const k of keys) {
    const v = obj?.[k];
    if (v !== undefined && v !== null && String(v).trim() !== "") return v;
  }
  return "";
}

function deepCollectObjects(input, out = []) {
  const node = tryParseJsonLike(input);
  if (!node) return out;
  if (Array.isArray(node)) {
    node.forEach((it) => deepCollectObjects(it, out));
    return out;
  }
  if (typeof node !== "object") return out;
  out.push(node);
  Object.values(node).forEach((v) => deepCollectObjects(v, out));
  return out;
}

export function normalizeStsPayload(raw) {
  const r0 = tryParseJsonLike(raw);
  const x0 = r0?.data ?? r0?.result ?? r0 ?? {};
  const objects = deepCollectObjects(x0, []);
  let finalObj = {};
  for (const obj of objects) {
    const accessKeyId = pickStsField(obj, ["accessKeyId", "AccessKeyId", "accessKeyID", "ak", "access_key_id"]);
    const accessKeySecret = pickStsField(obj, ["accessKeySecret", "AccessKeySecret", "sk", "access_key_secret"]);
    const securityToken = pickStsField(obj, ["securityToken", "SecurityToken", "stsToken", "token"]);
    const bucket = pickStsField(obj, ["bucket", "bucketName", "Bucket", "bucket_name"]);
    const region = pickStsField(obj, ["region", "Region", "regionId"]);
    if (accessKeyId || accessKeySecret || securityToken || bucket || region) {
      finalObj = obj;
      break;
    }
  }
  if (!Object.keys(finalObj).length && r0 && typeof r0 === "object") {
    finalObj = r0;
  }
  return {
    accessKeyId: pickStsField(finalObj, ["accessKeyId", "AccessKeyId", "accessKeyID", "ak", "access_key_id"]),
    accessKeySecret: pickStsField(finalObj, ["accessKeySecret", "AccessKeySecret", "sk", "access_key_secret"]),
    securityToken: pickStsField(finalObj, ["securityToken", "SecurityToken", "stsToken", "token"]),
    bucket: pickStsField(finalObj, ["bucket", "bucketName", "Bucket", "bucket_name"]),
    endpoint: pickStsField(finalObj, ["endpoint", "EndPoint", "host"]),
    region: pickStsField(finalObj, ["region", "Region", "regionId"]),
    dir: pickStsField(finalObj, ["dir", "uploadDir", "prefix"]) || "uploads/",
    expiration: pickStsField(finalObj, ["expiration", "Expiration"]),
    __rawKeys: Object.keys(finalObj),
    __rawType: Array.isArray(r0) ? "array" : typeof r0,
  };
}

export function createOssClient(sts) {
  const regionRaw = String(sts?.region || "").trim();
  const region = regionRaw.startsWith("oss-") ? regionRaw : `oss-${regionRaw}`;
  return new OSS({
    region,
    accessKeyId: sts.accessKeyId,
    accessKeySecret: sts.accessKeySecret,
    stsToken: sts.securityToken,
    bucket: sts.bucket,
    secure: true,
    timeout: 120000,
  });
}

/**
 * @param {File} file
 * @param {"work"|"chat"} bizType
 * @param {(p: number) => void} [onProgress] 0~1
 */
export async function uploadFileWithSts(file, bizType, onProgress) {
  const rawSts = await getOssSts(bizType);
  const sts = normalizeStsPayload(rawSts);
  if (!sts.accessKeyId || !sts.accessKeySecret || !sts.securityToken || !sts.bucket || !sts.region) {
    throw new Error(
      `STS 凭证不完整（缺少 accessKeyId/accessKeySecret/securityToken/bucket/region）。响应类型：${sts.__rawType || "unknown"}；已收到字段：${(sts.__rawKeys || []).join(",") || "无"}`,
    );
  }
  const client = createOssClient(sts);
  const objectKey = buildUploadObjectKey(sts, file);
  await client.multipartUpload(objectKey, file, {
    parallel: 4,
    partSize: 4 * 1024 * 1024,
    progress: (p) => {
      if (typeof onProgress === "function") onProgress(p);
    },
  });
  return {
    objectKey,
    mimeType: file.type || "application/octet-stream",
    size: file.size,
    fileName: file.name || "file",
  };
}
