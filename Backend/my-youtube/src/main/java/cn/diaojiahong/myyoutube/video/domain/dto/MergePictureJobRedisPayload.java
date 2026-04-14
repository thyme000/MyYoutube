package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 中幻灯片合成任务状态（含 userId 供服务端校验）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergePictureJobRedisPayload {

    private Long userId;
    private String status;
    private String videoObjectKey;
    private String errorMessage;
    private Double secondsPerImage;
    private Integer durationSeconds;
}
