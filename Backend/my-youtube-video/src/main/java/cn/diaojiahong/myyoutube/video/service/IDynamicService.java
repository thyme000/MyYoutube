package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicDownloadVO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicVO;

public interface IDynamicService {

    String publish(DynamicPublishDTO dto);
    PageResult<DynamicVO> feed(PageQuery pageQuery);
    DynamicVO detail(Long id);
    DynamicDownloadVO requestDownload(Long dynamicId);
    DynamicDownloadVO getDownloadStatus(Long taskId);
    void deleteDynamic(Long id);
    void privateDynamic(Long id);
    void publicDynamic(Long id);
    void openComment(Long id);
    void closeComment(Long id);}
