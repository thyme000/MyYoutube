package cn.diaojiahong.myyoutube.collect.mapper;

import cn.diaojiahong.myyoutube.collect.domain.po.ContentCollect;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ContentCollectMapper extends BaseMapper<ContentCollect> {

    /** 按收藏时间倒序分页取 content_id, join content 保证内容仍存在 */
    List<Long> selectCollectIds(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("blockedAuthors") List<Long> blockedAuthors,
            @Param("offset") int offset,
            @Param("size") int size);

    int selectCollectCount(@Param("userId") Long userId, @Param("type") String type, @Param("blockedAuthors") List<Long> blockedAuthors);
}
