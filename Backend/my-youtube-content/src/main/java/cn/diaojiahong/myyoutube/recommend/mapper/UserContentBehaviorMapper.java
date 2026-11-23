package cn.diaojiahong.myyoutube.recommend.mapper;

import cn.diaojiahong.myyoutube.recommend.domain.po.UserContentBehavior;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserContentBehaviorMapper extends BaseMapper<UserContentBehavior> {

    /** 统一上报链路按 (user, content, session, event) 去重写入, 返回 1 表示首次插入(才计推荐分) */
    int insertIgnore(@Param("id") Long id, @Param("userId") Long userId, @Param("contentId") Long contentId,
                     @Param("sessionId") String sessionId, @Param("eventType") String eventType,
                     @Param("durationMs") Long durationMs);

    List<Seed> recentPositiveSeeds(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                                   @Param("limit") int limit);

    record Seed(Long contentId, LocalDateTime createdAt) {
    }
}
