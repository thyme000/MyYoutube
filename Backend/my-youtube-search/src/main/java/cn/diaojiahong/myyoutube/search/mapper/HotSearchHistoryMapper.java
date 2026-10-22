package cn.diaojiahong.myyoutube.search.mapper;

import cn.diaojiahong.myyoutube.search.hot.HotSearchHistoryStore.GroupedEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HotSearchHistoryMapper {
    void append(@Param("events") List<GroupedEvent> events);

    void deleteDay(@Param("date") LocalDate date);

    int rebuildDay(@Param("date") LocalDate date);
}
