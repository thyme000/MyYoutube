package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;

public interface ChatUserConversationMapper extends BaseMapper<ChatUserConversationDoc> {

    int touchMembers(@Param("conversationId") String conversationId,
                     @Param("userIds") Collection<Long> userIds,
                     @Param("now") LocalDateTime now,
                     @Param("increaseUnread") boolean increaseUnread);

    long sumUnread(@Param("userId") Long userId);
}
