package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;

/**
 * 统一内容消费流/混排网格装配服务。
 */
public interface IContentFeedService {

    /**
     * 统一消费流(连播): 推荐 ids 全量混排, 动态与视频同池
     */
    PageResult<ContentFeedVO> feed(Long userId, ContentPageQuery pageQuery);

    /**
     * 首页混排网格: RECOMMENDED 走推荐, 其余按 content 计数/时间排序; category 与形式过滤适用于两种内容
     */
    PageResult<ContentFeedVO> list(Long userId, ContentSortEnum sort, String category, ContentPageQuery pageQuery);

    /**
     * 我的内容(创作中心/我的作品): 视频与动态同列, 含审核状态与可见性, 不做公开性过滤
     */
    PageResult<ContentFeedVO> mine(Long userId, ContentPageQuery pageQuery);

    /**
     * 按作者查内容(详情层侧栏): 视频与动态同列;
     * 他人视角仅公开已发布, 本人视角已发布全可见(含私密)
     */
    PageResult<ContentFeedVO> byAuthor(Long viewerId, Long authorId, ContentPageQuery pageQuery);

    /**
     * 关注流(关注页连播): 所有关注作者的内容, 视频与动态同池, 时间序
     */
    PageResult<ContentFeedVO> followFeed(Long userId, ContentPageQuery pageQuery);
}
