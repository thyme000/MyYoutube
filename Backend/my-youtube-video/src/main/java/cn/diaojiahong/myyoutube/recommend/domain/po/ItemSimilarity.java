package cn.diaojiahong.myyoutube.recommend.domain.po;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物品相似度(ItemCF/Swing 离线产物), item_a/item_b 双向各存一行, 在线按 item_a 查邻居
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("item_similarity")
public class ItemSimilarity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private SimAlgorithmEnum algorithm;

    /** 内容类型池: 邻居只在同类型内计算与截断 */
    private ContentTypeEnum contentType;

    private Long itemA;

    private Long itemB;

    private Double score;

    private LocalDateTime updatedAt;
}
