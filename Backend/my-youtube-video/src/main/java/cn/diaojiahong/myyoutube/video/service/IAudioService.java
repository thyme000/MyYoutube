package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.vo.AudioPage;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;

public interface IAudioService {

    String publish(AudioPublishDTO dto);
    AudioVO getMine(Long id);
    boolean retry(Long id);
    PageResult<AudioVO> myAudios(PageQuery pageQuery);
    AudioPage library(String keyword, PageQuery pageQuery);
    AudioPage searchMine(String keyword, PageQuery pageQuery);
    AudioVO detail(Long id);
    void setVisibility(Long id, String visibility);
    void delete(Long id);
    /**
     * 新增引用必须处理完成且审核通过，私密音频仅本人可用。
     */
    Audio getReferenceable(Long audioId, Long userId);
}
