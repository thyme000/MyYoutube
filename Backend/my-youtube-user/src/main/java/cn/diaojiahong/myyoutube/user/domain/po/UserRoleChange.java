package cn.diaojiahong.myyoutube.user.domain.po;

import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("user_role_change")
public class UserRoleChange {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long actorId;
    private Long targetUserId;
    private UserRole previousRole;
    private UserRole newRole;
    private LocalDateTime createTime;
}
