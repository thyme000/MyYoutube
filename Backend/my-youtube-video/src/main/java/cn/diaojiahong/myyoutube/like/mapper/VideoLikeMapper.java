package cn.diaojiahong.myyoutube.like.mapper;

import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 视频点赞表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface VideoLikeMapper extends BaseMapper<VideoLike> {

    List<Long> selectListByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    int selectUserLikedCount(@Param("userId") Long userId);
}
