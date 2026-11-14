package cn.diaojiahong.myyoutube.recommend.mapper;

import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 用户-视频行为流水表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface UserVideoBehaviorMapper extends BaseMapper<UserVideoBehavior> {

    List<Seed> recentPositiveSeeds(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                                   @Param("limit") int limit);

    record Seed(Long contentId, LocalDateTime createdAt) {
    }
}
