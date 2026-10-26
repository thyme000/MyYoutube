package cn.diaojiahong.myyoutube.content.mapper;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * <p>
 * 统一内容池 Mapper 接口
 * </p>
 */
public interface ContentMapper extends BaseMapper<Content> {
    List<AudioContentBanMessage> pendingAudioBans(@Param("afterId") long afterId, @Param("limit") int limit);
}
