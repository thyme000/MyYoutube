package cn.diaojiahong.myyoutube.user.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCounterEventMapper {
    @Insert("INSERT IGNORE INTO user_counter_event(event_id, user_id) VALUES(#{eventId}, #{userId})")
    int record(@Param("eventId") String eventId, @Param("userId") Long userId);

    @Update("UPDATE user SET content_count=content_count+#{delta} WHERE id=#{userId} AND content_count+#{delta} >= 0")
    int applyDelta(@Param("userId") Long userId, @Param("delta") int delta);
}
