package cn.diaojiahong.myyoutube.video.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

@Data
public class DynamicVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    @JsonSerialize(using = ToStringSerializer.class)
    private String userId;

    private String title;

    private String canvasType;

    private Integer canvasW;

    private Integer canvasH;

    private String transition;

    private Integer fadeMs;

    private String audioUrl;

    private String audioTitle;

    private Integer audioDurationMs;

    private Integer totalDurationMs;

    private List<Item> items;

    // 作者信息
    private String authorNickname;
    private String authorAvatarUrl;

    private Long createTime;

    /** 点赞数(来自 content 池, 详情展示用) */
    private Long likeCount;

    /** 评论数(来自 content 池, 详情展示用) */
    private Long commentCount;

    @Data
    public static class Item {

        private String imageObjectKey;

        /** 图片签名 URL */
        private String imageUrl;

        private Long startMs;
        private Long durationMs;
        private Double focusX;
        private Double focusY;
    }
}
