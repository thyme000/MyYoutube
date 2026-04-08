/**
 * 从图片 URL（含 blob）采样若干区域，生成用于背景流光 / 网格渐变的 CSS 颜色串。
 */

function rgbToHsl(r, g, b) {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let h = 0;
  let s = 0;
  const l = (max + min) / 2;
  const d = max - min;
  if (d > 1e-6) {
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r:
        h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
        break;
      case g:
        h = ((b - r) / d + 2) / 6;
        break;
      default:
        h = ((r - g) / d + 4) / 6;
    }
  }
  return { h: h * 360, s: s * 100, l: l * 100 };
}

function hslString(h, s, l, a = 1) {
  return `hsla(${Math.round(h)}, ${Math.min(92, Math.round(s))}%, ${Math.round(l)}%, ${a})`;
}

function sampleAvg(data, w, h, x0, y0, rw, rh) {
  let r = 0;
  let g = 0;
  let b = 0;
  let n = 0;
  const x1 = Math.min(w, x0 + rw);
  const y1 = Math.min(h, y0 + rh);
  for (let y = Math.floor(y0); y < y1; y++) {
    for (let x = Math.floor(x0); x < x1; x++) {
      const i = (y * w + x) * 4;
      r += data[i];
      g += data[i + 1];
      b += data[i + 2];
      n++;
    }
  }
  if (!n) return { r: 80, g: 80, b: 100 };
  return { r: r / n, g: g / n, b: b / n };
}

/**
 * @param {string} imageUrl
 * @returns {Promise<{ c1: string; c2: string; c3: string; c4: string; shimmer: string } | null>}
 */
export function extractCoverPalette(imageUrl) {
  return new Promise((resolve) => {
    const img = new Image();
    if (typeof imageUrl === "string" && !imageUrl.startsWith("blob:")) {
      img.crossOrigin = "anonymous";
    }
    img.onload = () => {
      try {
        const canvas = document.createElement("canvas");
        const w = 56;
        const h = 56;
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          resolve(null);
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        const { data } = ctx.getImageData(0, 0, w, h);
        const q = w / 2;
        const tl = sampleAvg(data, w, h, 0, 0, q, q);
        const tr = sampleAvg(data, w, h, q, 0, q, q);
        const bl = sampleAvg(data, w, h, 0, q, q, q);
        const br = sampleAvg(data, w, h, q, q, q, q);
        const ce = sampleAvg(data, w, h, q / 2, q / 2, q, q);

        const toMesh = (rgb) => {
          const { h: hue, s, l } = rgbToHsl(rgb.r, rgb.g, rgb.b);
          const s2 = Math.max(52, Math.min(88, s * 1.15));
          const l2 = Math.max(32, Math.min(58, l * 0.92));
          return hslString(hue, s2, l2, 0.72);
        };
        const toGlow = (rgb) => {
          const { h: hue, s, l } = rgbToHsl(rgb.r, rgb.g, rgb.b);
          return hslString(hue, Math.max(48, s), Math.min(62, l + 8), 0.55);
        };

        resolve({
          c1: toMesh(tl),
          c2: toMesh(tr),
          c3: toMesh(bl),
          c4: toMesh(br),
          shimmer: toGlow(ce),
        });
      } catch {
        resolve(null);
      }
    };
    img.onerror = () => resolve(null);
    img.src = imageUrl;
  });
}
