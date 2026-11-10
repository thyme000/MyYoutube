package cn.diaojiahong.myyoutube.like.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 作者获赞冗余。一行一个作者，差额来自 content.like_count 的同库更新。
 */
@Data
@TableName("author_like_count")
public class AuthorLikeCount {

    @TableId(value = "author_id", type = IdType.INPUT)
    private Long authorId;

    private Long likeCount;

    /** 获赞数每变一次加一 */
    private Long version;

    /** 已成功抄到 user.like_count 的版本 */
    private Long syncedVersion;
}
