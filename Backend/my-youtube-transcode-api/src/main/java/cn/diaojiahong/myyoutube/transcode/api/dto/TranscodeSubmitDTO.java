package cn.diaojiahong.myyoutube.transcode.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TranscodeSubmitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long contentId;
    private Integer canvasW;
    private Integer canvasH;
    /** cut / fade */
    private String transition;
    private Integer fadeMs;
    private String audioObjectKey;
    /** 音频时长(毫秒), 无音频为 0 */
    private Long audioDurationMs;
    /** 成片总时长(毫秒) */
    private Long totalDurationMs;
    private List<TranscodeItemDTO> items;
}
