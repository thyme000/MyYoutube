package cn.diaojiahong.myyoutube.video.mapper;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 视频信息表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
public interface VideoMapper extends BaseMapper<Video> {

    List<Video> selectOrderedList(@Param("ids") Set<String> ids);

    List<Video> selectOrderedListByList(@Param("ids") List<String> ids);

    /**
     * 公开视频分页, 按 content 表计数列排序(MOST_LIKED/MOST_SHARED/MOST_PLAYED/RECOMMENDED)
     */
    Page<Video> selectPageSorted(Page<Video> page,
                                 @Param("excludeUserId") Long excludeUserId,
                                 @Param("categoryCode") String categoryCode,
                                 @Param("begin") LocalDateTime begin,
                                 @Param("end") LocalDateTime end,
                                 @Param("tags") String tags,
                                 @Param("sort") String sort);
}
