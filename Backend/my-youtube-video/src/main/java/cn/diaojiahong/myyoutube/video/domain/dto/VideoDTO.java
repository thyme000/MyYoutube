package cn.diaojiahong.myyoutube.video.domain.dto;

import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
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
     * 视频分类（如：游戏、生活）
     */
    private VideoCategoryEnum category;

    /**
     * 标签（JSON数组或逗号分隔字符串）
     */
    private String tags;
    /**
     * 可见范围（public/private/unlisted）
     */
    private VideoVisibilityEnum visibility;

    /**
     * 是否允许评论（0-不允许，1-允许）
     */
    private Integer allowComment;

}
