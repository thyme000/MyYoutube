package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;

/**
 * 统一内容消费流/混排网格装配服务。
 */
public interface IContentFeedService {

    /**
     * 统一消费流(连播): 推荐 ids 全量混排, 动态与视频同池
     */
    PageResult<ContentFeedVO> feed(Long userId, PageQuery pageQuery);

    /**
     * 首页混排网格: RECOMMENDED 走推荐, 其余按 content 计数/时间排序; category 过滤时仅视频
     */
    PageResult<ContentFeedVO> list(Long userId, VideoSortEnum sort, String category, PageQuery pageQuery);
}
