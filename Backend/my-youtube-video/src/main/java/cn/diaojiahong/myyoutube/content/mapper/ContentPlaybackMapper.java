package cn.diaojiahong.myyoutube.content.mapper;

import cn.diaojiahong.myyoutube.content.domain.dto.ContentPlaybackDTO;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentPlaybackVO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentPlaybackMapper {
    @Select("SELECT video_position_ms, image_index, image_elapsed_ms, audio_position_ms FROM content_playback WHERE user_id=#{userId} AND content_id=#{contentId}")
    ContentPlaybackVO find(@Param("userId") Long userId, @Param("contentId") Long contentId);

    void save(
            @Param("userId") Long userId,
            @Param("contentId") Long contentId,
            @Param("state") ContentPlaybackDTO state);
}
