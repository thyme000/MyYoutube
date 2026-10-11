package cn.diaojiahong.myyoutube.video.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("video")
public class Video {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String objectKey;
    private Long durationMs;
}
