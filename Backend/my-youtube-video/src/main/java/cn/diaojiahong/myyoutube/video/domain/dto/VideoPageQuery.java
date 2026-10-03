package cn.diaojiahong.myyoutube.video.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoPageQuery extends PageQuery {

    // 视频分类
    private VideoCategoryEnum category;

    // 开始时间
    private LocalDateTime begin;

    // 结束时间
    private LocalDateTime end;

    // 作者id
    private Long authorId;

    // 视频标签
    private String tags;

    // 需要排序的字段
    private VideoSortEnum sort;

}
