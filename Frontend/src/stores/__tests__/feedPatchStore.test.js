import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useFeedPatchStore } from "../feedPatchStore";
import { useHomeStore } from "../homeStore";
import { useShortsStore } from "../shortsStore";
import { useLikedStore } from "../likedStore";
import { useProfileStore } from "../profileStore";
import { useStudioStore } from "../studioStore";
import { useSearchStore } from "../searchStore";
import { useCommentPanelStore } from "../commentPanelStore";

const VIDEO = { id: "1001", authorId: "77", likeCount: 1, commentCount: 2, isFollow: false };
const OTHER = { id: "1002", authorId: "88", likeCount: 9, isFollow: false };

/** 把同一个视频铺到所有会被广播的列表里，模拟「同一份数据出现在多处」 */
function seedLists() {
  const home = useHomeStore();
  home.cards = [{ ...VIDEO }, { ...OTHER }];
  home.immersiveRows = [{ ...VIDEO }];

  const shorts = useShortsStore();
  shorts.profileFeedQueue = [{ ...VIDEO }];
  shorts.likedFeedQueue = [{ ...VIDEO }];
  shorts.followFeedQueue = [{ ...VIDEO }];
  shorts.followAuthorFeedQueue = [{ ...VIDEO }];

  const liked = useLikedStore();
  liked.likedRows = [{ ...VIDEO }];

  const profile = useProfileStore();
  profile.worksRows = [{ ...VIDEO }];

  const studio = useStudioStore();
  studio.studioRows = [{ ...VIDEO }];

  const search = useSearchStore();
  search.searchVideoRows = [{ ...VIDEO }];
  search.searchTagRows = [{ ...VIDEO }];
  search.searchUserRows = [{ id: "77", isFollow: false }];
  search.searchAllUserStrip = [{ id: "77", isFollow: false }];
  search.searchExpandCard = { ...VIDEO };

  const panel = useCommentPanelStore();
  panel.searchListCommentCard = { ...VIDEO };

  return { home, shorts, liked, profile, studio, search, panel };
}

/**
 * 各 action 的广播覆盖面（取自实现，不同 action 并不完全一致）：
 * patchVideoById 覆盖全部 13 处；patchAuthorFollow 少 studio 与评论面板卡片；
 * removeVideoById 只覆盖「列表」9 处，不动搜索结果与评论面板卡片。
 */
function patchVideoCopies(s) {
  return {
    "home.cards": s.home.cards[0],
    "home.immersiveRows": s.home.immersiveRows[0],
    "shorts.profileFeedQueue": s.shorts.profileFeedQueue[0],
    "shorts.likedFeedQueue": s.shorts.likedFeedQueue[0],
    "shorts.followFeedQueue": s.shorts.followFeedQueue[0],
    "shorts.followAuthorFeedQueue": s.shorts.followAuthorFeedQueue[0],
    "liked.likedRows": s.liked.likedRows[0],
    "profile.worksRows": s.profile.worksRows[0],
    "studio.studioRows": s.studio.studioRows[0],
    "search.searchVideoRows": s.search.searchVideoRows[0],
    "search.searchTagRows": s.search.searchTagRows[0],
    "search.searchExpandCard": s.search.searchExpandCard,
    "commentPanel.searchListCommentCard": s.panel.searchListCommentCard,
  };
}

function authorFollowCopies(s) {
  return {
    "home.cards": s.home.cards[0],
    "home.immersiveRows": s.home.immersiveRows[0],
    "shorts.profileFeedQueue": s.shorts.profileFeedQueue[0],
    "shorts.likedFeedQueue": s.shorts.likedFeedQueue[0],
    "shorts.followFeedQueue": s.shorts.followFeedQueue[0],
    "shorts.followAuthorFeedQueue": s.shorts.followAuthorFeedQueue[0],
    "liked.likedRows": s.liked.likedRows[0],
    "profile.worksRows": s.profile.worksRows[0],
    "search.searchVideoRows": s.search.searchVideoRows[0],
    "search.searchTagRows": s.search.searchTagRows[0],
    "search.searchExpandCard": s.search.searchExpandCard,
  };
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe("feedPatchStore.patchVideoById", () => {
  it("把补丁广播到该视频出现的全部 13 处", () => {
    const stores = seedLists();
    useFeedPatchStore().patchVideoById("1001", { likeCount: 42, commentCount: 7 });
    const copies = patchVideoCopies(stores);
    expect(Object.keys(copies)).toHaveLength(13);
    for (const [where, row] of Object.entries(copies)) {
      expect(row, where).toMatchObject({ likeCount: 42, commentCount: 7 });
      // 补丁里没提到的字段保留原值
      expect(row.authorId, where).toBe("77");
    }
  });

  it("只改命中行，同列表里的其它行保持原引用", () => {
    const stores = seedLists();
    const hitBefore = stores.home.cards[0];
    const otherBefore = stores.home.cards[1];

    useFeedPatchStore().patchVideoById("1001", { likeCount: 42 });

    expect(stores.home.cards[0]).not.toBe(hitBefore);
    expect(stores.home.cards[1]).toBe(otherBefore);
    expect(stores.home.cards[1].likeCount).toBe(9);
  });

  it("列表里没有该视频时整份列表保持原引用（避免无谓的响应式更新）", () => {
    const stores = seedLists();
    const before = {
      cards: stores.home.cards,
      liked: stores.liked.likedRows,
      works: stores.profile.worksRows,
      studio: stores.studio.studioRows,
      searchVideo: stores.search.searchVideoRows,
    };
    useFeedPatchStore().patchVideoById("9999", { likeCount: 42 });
    expect(stores.home.cards).toBe(before.cards);
    expect(stores.liked.likedRows).toBe(before.liked);
    expect(stores.profile.worksRows).toBe(before.works);
    expect(stores.studio.studioRows).toBe(before.studio);
    expect(stores.search.searchVideoRows).toBe(before.searchVideo);
  });

  it("空 id 或非法补丁直接返回", () => {
    const stores = seedLists();
    const patch = useFeedPatchStore();
    const before = stores.home.cards;
    patch.patchVideoById("", { likeCount: 42 });
    patch.patchVideoById(null, { likeCount: 42 });
    patch.patchVideoById("1001", null);
    patch.patchVideoById("1001", "nope");
    expect(stores.home.cards).toBe(before);
    expect(stores.home.cards[0].likeCount).toBe(1);
  });
});

describe("feedPatchStore.patchAuthorFollow", () => {
  it("同步作者名下所有视频行（含首页/四份全屏队列/赞过/主页/搜索）", () => {
    const stores = seedLists();
    useFeedPatchStore().patchAuthorFollow("77", true);
    const copies = authorFollowCopies(stores);
    expect(Object.keys(copies)).toHaveLength(11);
    for (const [where, row] of Object.entries(copies)) {
      expect(row.isFollow, where).toBe(true);
    }
  });

  it("用户列表里 id 即作者 id，也一并同步", () => {
    const stores = seedLists();
    useFeedPatchStore().patchAuthorFollow("77", true);
    expect(stores.search.searchUserRows[0].isFollow).toBe(true);
    expect(stores.search.searchAllUserStrip[0].isFollow).toBe(true);
  });

  it("取关同样生效（false 不会被当成空值跳过）", () => {
    const stores = seedLists();
    useFeedPatchStore().patchAuthorFollow("77", true);
    useFeedPatchStore().patchAuthorFollow("77", false);
    expect(stores.home.cards[0].isFollow).toBe(false);
    expect(stores.liked.likedRows[0].isFollow).toBe(false);
  });

  it("创作中心与评论面板卡片不在作者关注态的广播范围内", () => {
    const stores = seedLists();
    useFeedPatchStore().patchAuthorFollow("77", true);
    expect(stores.studio.studioRows[0].isFollow).toBe(false);
    expect(stores.panel.searchListCommentCard.isFollow).toBe(false);
  });

  it("空作者 id 直接返回", () => {
    const stores = seedLists();
    const before = stores.home.cards;
    useFeedPatchStore().patchAuthorFollow("", true);
    useFeedPatchStore().patchAuthorFollow(null, true);
    expect(stores.home.cards).toBe(before);
    expect(stores.home.cards[0].isFollow).toBe(false);
  });
});

describe("feedPatchStore.removeVideoById", () => {
  it("把该视频从 9 处列表移除，其它行保留", () => {
    const stores = seedLists();
    useFeedPatchStore().removeVideoById("1001");
    expect(stores.home.cards.map((r) => r.id)).toEqual(["1002"]);
    expect(stores.home.immersiveRows).toEqual([]);
    expect(stores.shorts.profileFeedQueue).toEqual([]);
    expect(stores.shorts.likedFeedQueue).toEqual([]);
    expect(stores.shorts.followFeedQueue).toEqual([]);
    expect(stores.shorts.followAuthorFeedQueue).toEqual([]);
    expect(stores.liked.likedRows).toEqual([]);
    expect(stores.profile.worksRows).toEqual([]);
    expect(stores.studio.studioRows).toEqual([]);
  });

  it("搜索结果与评论面板卡片不被清掉（删除只影响列表）", () => {
    const stores = seedLists();
    useFeedPatchStore().removeVideoById("1001");
    expect(stores.search.searchVideoRows).toHaveLength(1);
    expect(stores.search.searchTagRows).toHaveLength(1);
    expect(stores.panel.searchListCommentCard).toEqual(VIDEO);
  });

  it("列表里没有该视频时内容不变", () => {
    const stores = seedLists();
    useFeedPatchStore().removeVideoById("9999");
    expect(stores.home.cards).toEqual([VIDEO, OTHER]);
    expect(stores.liked.likedRows).toEqual([VIDEO]);
  });

  it("空 id 直接返回", () => {
    const stores = seedLists();
    const before = stores.home.cards;
    useFeedPatchStore().removeVideoById("");
    useFeedPatchStore().removeVideoById(null);
    expect(stores.home.cards).toBe(before);
  });
});
