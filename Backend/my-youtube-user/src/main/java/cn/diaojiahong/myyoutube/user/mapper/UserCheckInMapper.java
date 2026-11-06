package cn.diaojiahong.myyoutube.user.mapper;

import cn.diaojiahong.myyoutube.user.domain.po.UserCheckIn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface UserCheckInMapper extends BaseMapper<UserCheckIn> {
    record YearSummary(int daysInYear, LocalDate latestDate) {
    }

    @Select("""
            SELECT COUNT(*) AS daysInYear, MAX(check_in_date) AS latestDate
            FROM user_check_in
            WHERE user_id = #{userId}
              AND check_in_date >= #{yearStart}
              AND check_in_date <= #{today}
            """)
    YearSummary summarizeYear(@Param("userId") Long userId, @Param("yearStart") LocalDate yearStart,
                              @Param("today") LocalDate today);
}
