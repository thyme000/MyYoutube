package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoDTO {
    /**
     * 视频标题
     */
    private String title;

    /**
     * 视频简介/描述
     */
    private String description;

    /**
     * 视频文件地址
     */
    private String videoUrl;

    /**
     * 视频时长（单位：秒）
     */
    private Integer duration;

    /**
     * 是否允许评论（0-不允许，1-允许）
     */
    private Integer allowComment;

}
