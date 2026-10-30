package cn.diaojiahong.myyoutube.collect.mapper;

import cn.diaojiahong.myyoutube.collect.domain.po.ContentCollect;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 内容收藏 Mapper
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
public interface ContentCollectMapper extends BaseMapper<ContentCollect> {

    /** 按收藏时间倒序分页取 content_id, join content 保证内容仍存在 */
    List<Long> selectCollectIds(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("offset") int offset,
            @Param("size") int size);

    int selectCollectCount(@Param("userId") Long userId, @Param("type") String type);
}
