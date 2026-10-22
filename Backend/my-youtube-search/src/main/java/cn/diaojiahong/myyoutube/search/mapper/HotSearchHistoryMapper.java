package cn.diaojiahong.myyoutube.search.mapper;

import cn.diaojiahong.myyoutube.search.hot.HotSearchHistoryStore.GroupedEvent;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HotSearchHistoryMapper {
    void append(@Param("events") List<GroupedEvent> events);

    void deleteDay(@Param("date") LocalDate date);

    int rebuildDay(@Param("date") LocalDate date);

    List<Day> dates(@Param("before") LocalDate before, @Param("limit") int limit);

    List<Group> groups(@Param("date") LocalDate date, @Param("after") Long after,
                       @Param("minUsers") long minUsers, @Param("limit") int limit);

    List<Keyword> keywords(@Param("date") LocalDate date, @Param("groupId") String groupId,
                           @Param("after") Long after, @Param("limit") int limit);

    String keyword(@Param("date") LocalDate date, @Param("groupId") String groupId, @Param("id") long id);

    List<User> users(@Param("date") LocalDate date, @Param("groupId") String groupId,
                     @Param("rawKeyword") String rawKeyword, @Param("after") Long after, @Param("limit") int limit);

    List<Event> events(@Param("date") LocalDate date, @Param("groupId") String groupId,
                       @Param("rawKeyword") String rawKeyword, @Param("userId") Long userId,
                       @Param("afterAt") Long afterAt, @Param("afterId") Long afterId, @Param("limit") int limit);
}
