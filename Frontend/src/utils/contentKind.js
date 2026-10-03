// @ts-check
/**
 * 统一内容池行的类型判定。动态只是内容池里"轮播时间轴"这一种媒体形态,
 * 与视频同池同构; 全站按行分流一律走这里, 不许再散落字面量比较。
 */

/**
 * @param {any} row 内容池行/详情行/快照(有 type 字段即可)
 * @returns {boolean}
 */
export function isDynamicRow(row) {
  return String(row?.type ?? "") === "dynamic";
}

/**
 * @param {any} row
 * @returns {boolean}
 */
export function isVideoRow(row) {
  return !isDynamicRow(row);
}
