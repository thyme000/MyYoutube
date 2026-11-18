package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface ChatConversationMapper extends BaseMapper<ChatConversationDoc> {

    long countSinglePair(@Param("userId") Long userId, @Param("peerId") Long peerId);
}
