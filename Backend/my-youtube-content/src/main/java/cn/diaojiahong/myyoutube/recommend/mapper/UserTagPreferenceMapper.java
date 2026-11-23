package cn.diaojiahong.myyoutube.recommend.mapper;

import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserTagPreferenceMapper extends BaseMapper<UserTagPreference> {

    void addBehaviorScore(@Param("userId") Long userId, @Param("contentId") Long contentId,
                          @Param("delta") int delta);

    void addTagScore(@Param("userId") Long userId, @Param("tagIds") List<Long> tagIds, @Param("delta") int delta);
}
