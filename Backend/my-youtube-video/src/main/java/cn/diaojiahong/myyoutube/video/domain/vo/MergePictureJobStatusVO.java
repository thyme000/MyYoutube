package cn.diaojiahong.myyoutube.video.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多图合成任务状态（完成后可将 {@link #videoObjectKey} 交给发布接口，与 STS 上传后的流程一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergePictureJobStatusVO {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAIL = "FAIL";

    private String status;
    /** 成功时的 objectKey，用于 publishVideo 的 videoUrl */
    private String videoObjectKey;
    private String errorMessage;
    /** 每张展示时长（秒），满足总时长 ≥6 且默认约 2 秒/张 */
    private Double secondsPerImage;
    /** 成片时长（秒），发布时填 duration */
    private Integer durationSeconds;
}
