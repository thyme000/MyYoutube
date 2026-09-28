/**
 * 列表行补丁：同一份视频/作者数据会同时出现在多份列表里，任一处变更都要同步其余副本。
 * 三个函数都返回新数组，未命中时原样返回（避免无谓的响应式更新）。
 */

/** 按 id 浅合并一行 */
export function patchRowById(list, id, patch) {
  if (!Array.isArray(list) || !list.length) return list;
  let hit = false;
  const next = list.map((row) => {
    if (row && String(row.id) === id) {
      hit = true;
      return { ...row, ...patch };
    }
    return row;
  });
  return hit ? next : list;
}

/** 把某个作者名下所有视频条目的 isFollow 改成同一个值 */
export function patchRowsByAuthorId(list, authorId, isFollow) {
  if (!Array.isArray(list) || !list.length) return list;
  let hit = false;
  const next = list.map((row) => {
    if (row && String(row.authorId) === authorId) {
      hit = true;
      return { ...row, isFollow };
    }
    return row;
  });
  return hit ? next : list;
}

/** 用户列表里 id 就是用户自己的 id，用于同步 isFollow */
export function patchRowsById(list, id, isFollow) {
  if (!Array.isArray(list) || !list.length) return list;
  let hit = false;
  const next = list.map((row) => {
    if (row && String(row.id) === id) {
      hit = true;
      return { ...row, isFollow };
    }
    return row;
  });
  return hit ? next : list;
}

/** 按 id 移除；用户列表里 id 就是用户自己的 id */
export function removeRowById(list, id) {
  if (!Array.isArray(list) || !list.length) return list;
  return list.filter((row) => String(row?.id) !== id);
}
