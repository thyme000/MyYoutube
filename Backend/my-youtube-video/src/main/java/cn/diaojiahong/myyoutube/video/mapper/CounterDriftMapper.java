package cn.diaojiahong.myyoutube.video.mapper;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 作品侧四种计数的漂移行。口径与 resources/verify/counter-drift.sql 一致。 */
public interface CounterDriftMapper {

    List<CounterDrift> contentLikes(@Param("afterId") long afterId, @Param("limit") int limit);

    List<CounterDrift> commentLikes(@Param("afterId") long afterId, @Param("limit") int limit);

    List<CounterDrift> collects(@Param("afterId") long afterId, @Param("limit") int limit);

    List<CounterDrift> shares(@Param("afterId") long afterId, @Param("limit") int limit);
}
