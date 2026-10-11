package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class DynamicDraftDTO {

    private String title;

    /** 可选简介 */
    private String description;

    /** vertical / horizontal */
    private String canvasType;

    /** cut / fade */
    private String transition;

    /** 可选音频库条目；保存原始音频快照，不按图片循环次数复制。 */
    private Long audioId;

    private String coverImageObjectKey;

    /** 0不允许 1允许评论, 默认允许 */
    private Integer allowComment;

    private List<DynamicItemDTO> items;

    @Data
    public static class DynamicItemDTO {

        private String imageObjectKey;

        /**
         * 可选。单图输入时长(毫秒)，默认 3000，与音频时长无关。
         */
        private Long durationMs;

        /** 裁剪焦点 0~1, 默认 0.5 中心 */
        private Double focusX;
        private Double focusY;
    }
}
