package cn.diaojiahong.myyoutube.video.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class MyVideoListVO {
    // 视频id
    @JsonSerialize(using = ToStringSerializer.class)
    private String id;
    // 标题
    private String title;
    // 封面图 url
    private String coverUrl;
    // 点赞数
    private Long likeCount;
}
