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
 * 取一条「同作者还有别的作品」的视频 id。
 * 详情页侧栏列的是作者作品，只有 ≥2 条时才有可切换的目标；
 * 否则侧栏唯一项就是当前视频，切视频用例无从跑起（不是产品 bug，是数据不够）。
 * @returns {Promise<string>} 找不到返回 ""
 */
export async function resolveVideoIdWithSiblings(js) {
  const id = await js(`(async () => {
    try {
      const tok = localStorage.getItem("myyoutube_token") || "";
      const r = await fetch("/videos/list?pageNo=1&pageSize=100", {
        headers: { Authorization: "Bearer " + tok },
      });
      const j = await r.json();
      const list = j?.data?.list ?? [];
      const byAuthor = new Map();
      for (const it of list) {
        const a = String(it.authorId ?? "");
        if (!byAuthor.has(a)) byAuthor.set(a, []);
        byAuthor.get(a).push(String(it.id));
      }
      for (const ids of byAuthor.values()) {
        if (ids.length >= 2) return ids[0];
      }
      return "";
    } catch {
      return "";
    }
  })()`);
  return id || "";
}

/** 前置检查：把「端口不通」变成可操作的提示，别让脚本抛 cryptic 的 fetch 失败 */
export async function assertEnv({ dev = "http://localhost:5173", cdpPort = 9222 } = {}) {
  const problems = [];
  const reachable = async (url) => {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(3000) });
      return res.ok;
    } catch {
      return false;
    }
  };
  if (!(await reachable(dev))) {
    problems.push(`dev server 不可达：${dev}（在 Frontend/ 下跑 npm run dev；注意脚本固定打 5173）`);
  }
  if (!(await reachable(`http://127.0.0.1:${cdpPort}/json/version`))) {
    problems.push(
      `CDP 不可达：127.0.0.1:${cdpPort}（需先起带 --remote-debugging-port=${cdpPort} 的 headless 浏览器）`,
    );
  }
  if (problems.length) {
    throw new Error(`前置条件不满足：\n  - ${problems.join("\n  - ")}`);
  }
}

/**
 * 进视频详情层。首页有可播放视频卡就点它（保留真实入口路径），
 * 否则退化为深链 /video/:id（全量导航，back/forward 仍按文档历史工作）。
 * 传 `preferId` 时直接深链该视频（用于需要特定视频的用例，如侧栏有可切换作品的）。
 * @returns {Promise<{ vid: string, via: "card" | "deeplink" | "none" }>} vid 为空表示既无卡片也无数据
 */
export async function openVideoDetail(js, waitFor, { front = "http://localhost:5173", preferId = "" } = {}) {
  const target = preferId ? String(preferId) : "";
  if (!target) {
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
  }

  const vid = target || (await resolveVideoId(js));
  if (!vid) return { vid: "", via: "none" };
  await js(`location.href = ${JSON.stringify(front + "/video/")} + ${JSON.stringify(vid)}`);
  const ok = await waitFor(
    `location.pathname === ${JSON.stringify("/video/" + vid)} && !!document.querySelector("[class*=detail]")`,
    15000,
  );
  return { vid: ok ? vid : "", via: "deeplink" };
}
