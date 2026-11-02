package cn.diaojiahong.myyoutube.transcode.domain.po;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.ItemRole;
import cn.diaojiahong.myyoutube.transcode.domain.enums.PreparationItemStatus;
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
    private ItemRole role;
    private Integer itemIndex;
    private String objectKey;
    private String sourceJson;
    private String resultJson;
    private PreparationItemStatus status;
}
