package cn.diaojiahong.myyoutube.video.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("dynamic")
public class Dynamic {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long audioId;
    private String mediaJson;
}
