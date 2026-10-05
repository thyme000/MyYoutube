package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

/**
 * 手动触发封面生成入参: 不接受客户端传 userId, 一律从登录态取
 */
@Data
public class GenerateCoverDTO {
    private Long videoId;
    private String videoObjectKey;
}
