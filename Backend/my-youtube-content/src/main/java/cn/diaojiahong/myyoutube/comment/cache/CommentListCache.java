package cn.diaojiahong.myyoutube.comment.cache;

import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;

import java.util.List;

/**
 * 评论名单缓存: 每个「内容 × tab」一份有序名单, 只存顺序不存内容。
 * 没有写路径, 靠固定 TTL 过期后惰性重建; 条目失效由读时过滤兜住。
 */
public interface CommentListCache {

    /** 取有序名单(榜序降序); 返回 null 表示未建或未达准入阈值, 调用方回退 DB */
    List<CommentCursor> get(Long contentId, CommentTab tab);

    /** 单向重建: 达阈值才建, 已建或抢不到锁直接返回 */
    void build(Long contentId, CommentTab tab, long commentCount);
}
