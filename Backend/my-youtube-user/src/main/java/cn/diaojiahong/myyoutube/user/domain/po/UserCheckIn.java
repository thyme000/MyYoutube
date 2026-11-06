package cn.diaojiahong.myyoutube.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("user_check_in")
public class UserCheckIn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate checkInDate;
    private LocalDateTime checkedInAt;
}
