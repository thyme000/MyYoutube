package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class DynamicPublishDTO {

    private String title;

    /** vertical / horizontal */
    private String canvasType;

    /** cut / fade */
    private String transition;

    /** 音频库条目ID(必填): 后端校验可见性并把 objectKey/时长/标题作为快照落库 */
    private Long audioId;

    /** 可选标签, 逗号分隔 */
    private String tags;

    private List<DynamicItemDTO> items;

    @Data
    public static class DynamicItemDTO {

        private String imageObjectKey;

        /**
         * 可选。单图输入时长(毫秒), 不传则按音频/默认时长均分。
         */
        private Long durationMs;

        /** 裁剪焦点 0~1, 默认 0.5 中心 */
        private Double focusX;
        private Double focusY;
    }
}
