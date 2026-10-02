package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;

public interface IAudioService {

    Result<String> publish(AudioPublishDTO dto);

    Result<PageResult<AudioVO>> myAudios(PageQuery pageQuery);

    Result<PageResult<AudioVO>> library(String keyword, PageQuery pageQuery);

    Result<Void> setVisibility(Long id, String visibility);

    Result<Void> delete(Long id);

    /**
     * 引用校验 + 取库条目: 公开或本人私有才可见, 不可见/不存在返回 fail 信息。
     * 动态发布与幻灯片合成共用。
     */
    Result<Audio> getReferenceable(Long audioId, Long userId);

    AudioVO toVO(Audio audio, boolean withUrl);
}
