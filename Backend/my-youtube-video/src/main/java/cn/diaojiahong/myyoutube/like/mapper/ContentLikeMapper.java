package cn.diaojiahong.myyoutube.like.mapper;

import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ContentLikeMapper extends BaseMapper<ContentLike> {

    List<Long> selectListByUserId(
            @Param("userId") Long userId,
            @Param("viewerId") Long viewerId,
            @Param("type") String type,
            @Param("blockedAuthors") List<Long> blockedAuthors,
            @Param("offset") int offset,
            @Param("size") int size);

    int selectUserLikedCount(
            @Param("userId") Long userId,
            @Param("viewerId") Long viewerId,
            @Param("type") String type, @Param("blockedAuthors") List<Long> blockedAuthors);
}
