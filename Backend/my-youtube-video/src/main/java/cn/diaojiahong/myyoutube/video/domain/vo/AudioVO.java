package cn.diaojiahong.myyoutube.video.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AudioVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    private String title;

    private Integer durationMs;
    private Long fileSize;
    private String mime;
    private cn.diaojiahong.myyoutube.video.domain.enums.AudioProcessingStatus processingStatus;
    private cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus reviewStatus;
    private String errorMessage;
    private String coverUrl;

    /** public/private */
    private String visibility;

    /** 播放/预览用签名地址 */
    private String previewUrl;

    @JsonSerialize(using = ToStringSerializer.class)
    private String uploaderId;

    private LocalDateTime createTime;
}
