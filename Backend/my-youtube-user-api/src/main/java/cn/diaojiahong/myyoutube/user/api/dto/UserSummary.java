package cn.diaojiahong.myyoutube.user.api.dto;

import lombok.Data;

/**
 * 用户摘要, 跨服务传输用, 字段与 User 对齐
 */
@Data
public class UserSummary {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String introduction;
    private Long fans;
    private Long attentionCount;
    private Long likeCount;
    private Long videoCount;
}
