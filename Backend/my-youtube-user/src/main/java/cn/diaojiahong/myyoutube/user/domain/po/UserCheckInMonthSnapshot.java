package cn.diaojiahong.myyoutube.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
@TableName(value = "user_check_in_month_snapshot", autoResultMap = true)
public class UserCheckInMonthSnapshot {
    @TableId(type = IdType.INPUT)
    private LocalDate monthStart;
    private long signedUsers;
    private long checkIns;
    private Long yearSignedUsers;
    private Long yearCheckIns;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> dailyCounts;
}
