package cn.diaojiahong.myyoutube.like.mapper;

import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 内容点赞表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
public interface ContentLikeMapper extends BaseMapper<ContentLike> {

    List<Long> selectListByUserId(
            @Param("userId") Long userId,
            @Param("viewerId") Long viewerId,
            @Param("type") String type,
            @Param("offset") int offset,
            @Param("size") int size);

    int selectUserLikedCount(
            @Param("userId") Long userId,
            @Param("viewerId") Long viewerId,
            @Param("type") String type);
}
