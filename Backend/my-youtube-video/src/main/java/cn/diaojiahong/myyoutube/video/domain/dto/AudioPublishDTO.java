package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

@Data
public class AudioPublishDTO {

    private String title;

    /** 已直传对象存储的音频 objectKey */
    private String objectKey;

    private Long durationMs;

    /** public / private, 默认 public */
    private String visibility;
}
