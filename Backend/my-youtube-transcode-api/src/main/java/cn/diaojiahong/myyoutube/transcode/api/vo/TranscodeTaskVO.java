package cn.diaojiahong.myyoutube.transcode.api.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeTaskVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private Long taskId;
    private String taskKey;
    private String status;
    /** 任务归属用户, 查询时校验归属用 */
    private Long userId;
    private Long contentId;
    /** DONE 时有值: 成片 objectKey, 调用方负责签发下载 URL */
    private String resultObjectKey;
    private String errorMessage;
}
