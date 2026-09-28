// @ts-check
/**
 * 头像 / 背景 裁剪区域本地存储（归一化 0~1，相对原图宽高）
 */

function clamp(n, min, max) {
  return Math.min(max, Math.max(min, n));
}

function hashUrl(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return String(h);
}

/** @param {'bg' | 'avatar'} kind */
export function getCropStorageKey(kind, userId, imageUrl) {
  if (userId == null || !imageUrl) {
    return null;
  }
  return `myyoutube_crop_${kind}_${userId}_${hashUrl(String(imageUrl))}`;
}

/** @returns {{ L: number, T: number, W: number, H: number } | null} */
export function loadCropFromStorage(kind, userId, imageUrl) {
  const key = getCropStorageKey(kind, userId, imageUrl);
  if (!key) {
    return null;
  }
  try {
    const raw = localStorage.getItem(key);
    if (!raw) {
      return null;
    }
    const o = JSON.parse(raw);
    const L = Number(o.L);
    const T = Number(o.T);
    const W = Number(o.W);
    const H = Number(o.H);
    if ([L, T, W, H].every((x) => Number.isFinite(x))) {
      return normalizeRect({ L, T, W, H });
    }
  } catch {
    /* ignore */
  }
  return null;
}

/** @param {{ L: number, T: number, W: number, H: number }} rect */
export function saveCropToStorage(kind, userId, imageUrl, rect) {
  const key = getCropStorageKey(kind, userId, imageUrl);
  if (!key) {
    return;
  }
  try {
    const r = normalizeRect({ ...rect });
    localStorage.setItem(key, JSON.stringify(r));
  } catch {
    /* ignore */
  }
}

export function normalizeRect(r) {
  let L = clamp(Number(r.L) || 0, 0, 1);
  let T = clamp(Number(r.T) || 0, 0, 1);
  let W = clamp(Number(r.W) || 1, 0.02, 1);
  let H = clamp(Number(r.H) || 1, 0.02, 1);
  if (L + W > 1) {
    W = 1 - L;
  }
  if (T + H > 1) {
    H = 1 - T;
  }
  return { L, T, W, H };
}

/**
 * 在整张图内取指定宽高比、尽量大的居中裁剪（归一化坐标）
 * @param {number} aspectW aspectH 目标裁剪区宽高比（如横幅 16:5，头像 1:1）
 */
export function makeDefaultCropRect(naturalW, naturalH, aspectW, aspectH) {
  const nw = naturalW;
  const nh = naturalH;
  if (nw <= 0 || nh <= 0) {
    return { L: 0, T: 0, W: 1, H: 1 };
  }
  const targetAspect = aspectW / aspectH;
  const imgAspect = nw / nh;
  let cw;
  let ch;
  if (imgAspect > targetAspect) {
    ch = nh;
    cw = ch * targetAspect;
  } else {
    cw = nw;
    ch = cw / targetAspect;
  }
  const L = (nw - cw) / 2 / nw;
  const T = (nh - ch) / 2 / nh;
  const W = cw / nw;
  const H = ch / nh;
  return normalizeRect({ L, T, W, H });
}
