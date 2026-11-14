package cn.diaojiahong.myyoutube.recommend.mapper;

import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用户标签偏好表（内容推荐用） Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface UserTagPreferenceMapper extends BaseMapper<UserTagPreference> {

    void addBehaviorScore(@Param("userId") Long userId, @Param("contentId") Long contentId,
                          @Param("delta") int delta);

    void addTagScore(@Param("userId") Long userId, @Param("tagIds") List<Long> tagIds, @Param("delta") int delta);
}
