/**
 * 从 URL 拉取音频 → decode → 截取 [startSec, endSec) → 导出 16bit PCM WAV（供幻灯片等上传 OSS）
 */

function writeString(view, offset, str) {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i));
  }
}

/**
 * @param {AudioBuffer} buffer
 * @returns {Blob}
 */
export function audioBufferToWavBlob(buffer) {
  const numChannels = buffer.numberOfChannels;
  const sampleRate = buffer.sampleRate;
  const bitDepth = 16;
  const bytesPerSample = bitDepth / 8;
  const blockAlign = numChannels * bytesPerSample;
  const numSamples = buffer.length;
  const dataSize = numSamples * blockAlign;
  const bufferSize = 44 + dataSize;
  const arrayBuffer = new ArrayBuffer(bufferSize);
  const view = new DataView(arrayBuffer);

  writeString(view, 0, "RIFF");
  view.setUint32(4, 36 + dataSize, true);
  writeString(view, 8, "WAVE");
  writeString(view, 12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * blockAlign, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitDepth, true);
  writeString(view, 36, "data");
  view.setUint32(40, dataSize, true);

  const chData = [];
  for (let c = 0; c < numChannels; c++) {
    chData.push(buffer.getChannelData(c));
  }
  let offset = 44;
  for (let i = 0; i < numSamples; i++) {
    for (let c = 0; c < numChannels; c++) {
      let s = Math.max(-1, Math.min(1, chData[c][i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      offset += 2;
    }
  }
  return new Blob([arrayBuffer], { type: "audio/wav" });
}

/**
 * @param {string} url 可跨域拉取的音频地址
 * @param {number} startSec
 * @param {number} endSec
 * @param {string} fileName
 * @returns {Promise<File>}
 */
export async function trimUrlToWavFile(url, startSec, endSec, fileName) {
  const res = await fetch(url, { mode: "cors", credentials: "omit" });
  if (!res.ok) {
    throw new Error(`下载音频失败（HTTP ${res.status}），请检查解析地址是否允许跨域`);
  }
  const ab = await res.arrayBuffer();
  const Ctx = window.AudioContext || window.webkitAudioContext;
  const ctx = new Ctx();
  let decoded;
  try {
    decoded = await ctx.decodeAudioData(ab.slice(0));
  } catch (e) {
    await ctx.close().catch(() => {});
    throw new Error(e?.message || "无法解码音频（格式不支持或数据不完整）");
  }

  const rate = decoded.sampleRate;
  const nCh = decoded.numberOfChannels;
  const i0 = Math.max(0, Math.floor(startSec * rate));
  const i1 = Math.min(decoded.length, Math.max(i0 + 1, Math.ceil(endSec * rate)));
  const nFrames = i1 - i0;

  try {
    const out = ctx.createBuffer(nCh, nFrames, rate);
    for (let c = 0; c < nCh; c++) {
      out.copyToChannel(decoded.getChannelData(c).subarray(i0, i1), c, 0);
    }
    const blob = audioBufferToWavBlob(out);
    const safeName = String(fileName || "bgm-trim.wav").replace(/[/\\?%*:|"<>]/g, "_");
    return new File([blob], safeName, { type: "audio/wav" });
  } finally {
    await ctx.close().catch(() => {});
  }
}
