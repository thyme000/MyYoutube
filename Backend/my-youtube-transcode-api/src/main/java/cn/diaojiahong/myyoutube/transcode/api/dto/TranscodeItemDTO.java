package cn.diaojiahong.myyoutube.transcode.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态时间轴单项。durationMs 为该图输入时长(可见段+叠化), 预览端与 ffmpeg 用同一套
 * 公式换算切换点: 第 k 次切换发生在 k*(durationMs-fadeMs)。
 */
@Data
public class TranscodeItemDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String imageObjectKey;

    /** 切换点时刻(毫秒), 首图为 0 */
    private Long startMs;

    /** 输入时长(毫秒) */
    private Long durationMs;
    /** 封面裁剪焦点 0~1, 默认 0.5 中心 */
    private Double focusX;
    private Double focusY;
}
