package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicDownloadVO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicVO;

public interface IDynamicService {

    Result<String> publish(DynamicPublishDTO dto);

    Result<PageResult<DynamicVO>> feed(PageQuery pageQuery);

    Result<DynamicVO> detail(Long id);

    Result<DynamicDownloadVO> requestDownload(Long dynamicId);

    Result<DynamicDownloadVO> getDownloadStatus(Long taskId);

    Result<Void> deleteDynamic(Long id);

    Result<Void> privateDynamic(Long id);

    Result<Void> publicDynamic(Long id);

    Result<Void> openComment(Long id);

    Result<Void> closeComment(Long id);
}
