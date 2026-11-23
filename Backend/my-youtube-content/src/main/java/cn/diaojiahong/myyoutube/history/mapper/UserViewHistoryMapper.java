package cn.diaojiahong.myyoutube.history.mapper;

import cn.diaojiahong.myyoutube.history.domain.po.UserViewHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 用户浏览历史 Mapper(含续播位置)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
public interface UserViewHistoryMapper extends BaseMapper<UserViewHistory> {

    /** 最近观看时间取较晚值，续播位置只接受更新的版本。 */
    void upsertPlayback(@Param("userId") Long userId, @Param("contentId") Long contentId,
                        @Param("viewTime") LocalDateTime viewTime, @Param("positionMs") long positionMs,
                        @Param("version") long version);

    /** 物理删除单条: 历史与续播位置同行同生命周期 */
    int deleteOne(@Param("userId") Long userId, @Param("contentId") Long contentId);

    /** 清空: 物理删除该用户全部历史(含续播位置) */
    int deleteAll(@Param("userId") Long userId);

    long selectResumePosition(@Param("userId") Long userId, @Param("contentId") Long contentId);

    /** 按最近观看倒序分页取 content_id, join content 保证内容仍存在 */
    List<Long> selectHistoryIds(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("blockedAuthors") List<Long> blockedAuthors,
            @Param("offset") int offset,
            @Param("size") int size);

    int selectHistoryCount(@Param("userId") Long userId, @Param("type") String type, @Param("blockedAuthors") List<Long> blockedAuthors);
}
