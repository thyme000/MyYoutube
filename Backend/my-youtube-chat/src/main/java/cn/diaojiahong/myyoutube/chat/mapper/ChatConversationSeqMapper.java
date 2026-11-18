package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatSeqDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface ChatConversationSeqMapper extends BaseMapper<ChatSeqDoc> {

    int allocate(@Param("conversationId") String conversationId);

    long lastAllocated();
}
