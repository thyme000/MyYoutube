// @ts-check
/**
 * 点赞 / 取消点赞的统一编排：调接口 → 乐观补丁 → 回读详情校正计数。
 * 登录判断、节流与在途去重、行为上报的粒度各列表不同，留在调用方；
 * 补丁经 patch 回调广播出去，各域只改自己的列表。
 */
import { likeVideo, cancelLikeVideo } from "../api/likeApi.js";
import { fetchVideoDetail, normalizeVideoDetail } from "../api/videoApi.js";

/**
 * @param {any} videoId 已解析成数字的雪花 id（调用方负责解析与空值判断）
 * @param {{
 *   wasLiked: boolean,
 *   likeCount: any,
 *   patch: (patch: { isLike?: boolean, likeCount?: number }) => void,
 * }} opts
 * @returns {Promise<void>} 失败时抛出，由调用方决定提示文案
 */
export async function toggleVideoLikeAndSync(videoId, { wasLiked, likeCount, patch }) {
  if (wasLiked) {
    await cancelLikeVideo(videoId);
    patch({ isLike: false, likeCount: Math.max(0, Number(likeCount ?? 0) - 1) });
  } else {
    await likeVideo(videoId);
    patch({ isLike: true, likeCount: Number(likeCount ?? 0) + 1 });
  }
  // 乐观计数可能盖掉后端的并发结果，回读详情再校正一次
  const merged = normalizeVideoDetail(await fetchVideoDetail(videoId));
  if (merged && merged.likeCount != null) {
    patch({ likeCount: merged.likeCount });
  }
}
