/**
 * 冒烟脚本共用夹具。
 *
 * 首页自 e554ba1 起是「视频 + 动态」混排，动态卡点击落 /dynamic/:id，
 * 所以「首卡即视频」的老假设失效——不要再 slice location.pathname 取 id，也不要假定首卡类型。
 * 需要真实视频 id 的脚本一律经此解析。
 */

/** 取一条真实视频 id：走页面自身 fetch，自带登录态与 Nginx 代理 */
export async function resolveVideoId(js) {
  const id = await js(`(async () => {
    try {
      const tok = localStorage.getItem("myyoutube_token") || "";
      const r = await fetch("/videos/list?pageNo=1&pageSize=1", {
        headers: { Authorization: "Bearer " + tok },
      });
      const j = await r.json();
      const list = j?.data?.list ?? [];
      return list[0]?.id != null ? String(list[0].id) : "";
    } catch {
      return "";
    }
  })()`);
  return id || "";
}

/**
 * 进视频详情层。首页有可播放视频卡就点它（保留真实入口路径），
 * 否则退化为深链 /video/:id（全量导航，back/forward 仍按文档历史工作）。
 * @returns {Promise<{ vid: string, via: "card" | "deeplink" | "none" }>} vid 为空表示既无卡片也无数据
 */
export async function openVideoDetail(js, waitFor, { front = "http://localhost:5173" } = {}) {
  const clicked = await js(`(() => {
    const c = document.querySelector(".video-card--playable:not(.video-card--skeleton)");
    if (!c) return false;
    c.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    return true;
  })()`);
  if (clicked && (await waitFor(`/^\\/video\\/\\d+$/.test(location.pathname)`, 12000))) {
    const vid = await js(`location.pathname.slice("/video/".length)`);
    return { vid: String(vid || ""), via: "card" };
  }

  const vid = await resolveVideoId(js);
  if (!vid) return { vid: "", via: "none" };
  await js(`location.href = ${JSON.stringify(front + "/video/")} + ${JSON.stringify(vid)}`);
  const ok = await waitFor(
    `location.pathname === ${JSON.stringify("/video/" + vid)} && !!document.querySelector("[class*=detail]")`,
    15000,
  );
  return { vid: ok ? vid : "", via: "deeplink" };
}
