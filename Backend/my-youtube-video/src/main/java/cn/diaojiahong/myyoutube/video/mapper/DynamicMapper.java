package cn.diaojiahong.myyoutube.video.mapper;

import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface DynamicMapper extends BaseMapper<Dynamic> {
    @Select("""
            SELECT d.id AS contentId, a.id AS audioId, a.index_version AS audioVersion
            FROM dynamic d
            JOIN audio a ON a.id = d.audio_id
            JOIN content c ON c.id = d.id
            WHERE d.id > #{afterId} AND a.review_status = 'banned'
              AND c.type = 'dynamic' AND c.is_delete = 0 AND c.status IN ('review', 'published')
            ORDER BY d.id LIMIT #{limit}
            """)
    @Options(timeout = 5)
    List<AudioContentBanMessage> pendingAudioBans(@Param("afterId") long afterId, @Param("limit") int limit);
}
