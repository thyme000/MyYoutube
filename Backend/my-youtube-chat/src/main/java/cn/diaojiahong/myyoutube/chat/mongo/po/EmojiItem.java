package cn.diaojiahong.myyoutube.chat.mongo.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author thyme
 * @since 2026-04-12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("emoji_item")
public class EmojiItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 如 nature、animals，与前端 Tab 对应
     */
    private String categoryCode;

    private String glyph;

    /**
     * 图标下方说明
     */
    private String nameZh;

    private Integer sortOrder;


}
