package cn.diaojiahong.myyoutube.transcode.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("media_preparation_item")
public class PreparationItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long preparationId;
    private Long objectOwnerId;
    private String role;
    private Integer itemIndex;
    private String objectKey;
    private String sourceJson;
    private String resultJson;
    private String status;
}
