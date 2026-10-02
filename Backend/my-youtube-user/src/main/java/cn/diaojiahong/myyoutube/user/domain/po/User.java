package cn.diaojiahong.myyoutube.user.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户）
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键ID，自增（唯一标识用户）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户名，唯一（登录/展示用，如：zhangsan123）
     */
    private String username;

    /**
     * 用户密码（建议加密存储，如BCrypt加密后字符串）
     */
    private String password;

    /**
     * 用户头像URL地址，默认空字符串（无头像时用默认图）
     */
    private String avatarUrl;

    /**
     * 用户邮箱（可选，用于登录/找回密码）
     */
    private String email;

    /**
     * 手机号（可选，备用登录方式）
     */
    private String phone;

    /**
     * 用户昵称（展示用，可和用户名不同）
     */
    private String nickname;

    /**
     * 用户注册时间，默认当前时间
     */
    private LocalDateTime createTime;

    /**
     * 用户信息更新时间，自动刷新
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0=未删除（正常），1=已删除（禁用账号）
     */
    private Integer isDelete;

    /**
     * 粉丝数量
     */
    private Long fans;

    /**
     * 关注数量
     */
     private Long attentionCount;

    /**
     * 视频发布数量
     */
     private Long videoCount;

    /**
     * 个人介绍
     */
    private String introduction;

    /**
     * 主页背景图片URL地址
     */
    private String bgImage;

    /**
     * 喜欢数量
     */
    private Long likeCount;
}
