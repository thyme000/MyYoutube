package cn.diaojiahong.myyoutube.history.mapper;

import cn.diaojiahong.myyoutube.history.domain.po.UserViewHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用户浏览历史 Mapper
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
public interface UserViewHistoryMapper extends BaseMapper<UserViewHistory> {

    /** 重复观看 upsert 置顶: (user_id, content_id) 唯一键冲突时仅刷新 view_time */
    void upsert(@Param("userId") Long userId, @Param("contentId") Long contentId);

    /** 按最近观看倒序分页取 content_id, join content 保证内容仍存在 */
    List<Long> selectHistoryIds(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    int selectHistoryCount(@Param("userId") Long userId);
}
