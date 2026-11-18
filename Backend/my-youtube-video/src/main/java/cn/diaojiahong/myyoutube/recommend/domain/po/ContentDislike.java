package cn.diaojiahong.myyoutube.recommend.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("content_dislike")
public class ContentDislike {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long contentId;
    private Long expiresAt;
}
