package cn.diaojiahong.myyoutube.history.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.history.domain.vo.HistoryVO;
import cn.diaojiahong.myyoutube.common.page.PageResult;

/**
 * 用户浏览历史(视频/动态同池)
 */
public interface IHistoryService {

    /** 记录一次观看, 同内容 upsert 置顶 */
    Result<Void> record(Long contentId);

    /** 我的浏览历史, 按观看时间倒序 */
    Result<PageResult<HistoryVO>> page(PageQuery pageQuery);

    /** 删除单条历史 */
    Result<Void> removeOne(Long contentId);

    /** 清空我的历史 */
    Result<Void> clear();
}
