package cn.diaojiahong.myyoutube.user.mapper;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 粉丝数和关注数的漂移行。口径与 resources/verify/counter-drift.sql 一致，只计未取消的关注。 */
public interface CounterDriftMapper {

    List<CounterDrift> fans(@Param("afterId") long afterId, @Param("limit") int limit);

    List<CounterDrift> attentions(@Param("afterId") long afterId, @Param("limit") int limit);
}
