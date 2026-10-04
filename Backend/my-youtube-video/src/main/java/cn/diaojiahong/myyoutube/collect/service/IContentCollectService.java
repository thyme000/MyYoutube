package cn.diaojiahong.myyoutube.collect.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.collect.domain.vo.CollectVO;

/**
 * 内容收藏(视频/动态同池)
 */
public interface IContentCollectService {

    /** 收藏内容, 已收藏时幂等 */
    void collect(Long contentId);
    /** 取消收藏, 未收藏时幂等 */
    void cancelCollect(Long contentId);
    /** 我的收藏, 按收藏时间倒序 */
    PageResult<CollectVO> page(PageQuery pageQuery);
    /** 我是否收藏了该内容 */
    Boolean status(Long contentId);}
