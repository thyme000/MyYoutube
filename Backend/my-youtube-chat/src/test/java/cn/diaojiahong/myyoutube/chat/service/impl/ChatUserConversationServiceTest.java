package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatUserConversationServiceTest {
    @Test
    void excludesFilteredAndMissingConversationsWithoutDereferencingThem() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        S3StorageUtil storage = mock(S3StorageUtil.class);
        ChatUserConversationService service = new ChatUserConversationService(mongo, storage, mock(UserPort.class));
        ChatUserConversationDoc blocked = membership("blocked");
        ChatUserConversationDoc missing = membership("missing");
        ChatUserConversationDoc allowed = membership("allowed");
        ChatConversationDoc blockedGroup = new ChatConversationDoc();
        blockedGroup.setId("blocked");
        ChatConversationDoc allowedGroup = new ChatConversationDoc();
        allowedGroup.setId("allowed");
        allowedGroup.setName("可发送群");
        when(mongo.count(any(Query.class), eq(ChatUserConversationDoc.class))).thenReturn(3L);
        when(mongo.find(any(Query.class), eq(ChatUserConversationDoc.class)))
                .thenReturn(List.of(blocked, missing, allowed));
        when(mongo.find(any(Query.class), eq(ChatConversationDoc.class)))
                .thenReturn(List.of(blockedGroup), List.of(allowedGroup));
        UserContext.setUser("9001");
        try {
            var result = service.getValidConversations(1L, 3L);
            assertEquals(List.of("allowed"), result.getList().stream().map(v -> v.getConversationId()).toList());
            assertEquals(3L, result.getTotal());
        } finally {
            UserContext.removeUser();
        }
    }

    private static ChatUserConversationDoc membership(String id) {
        ChatUserConversationDoc doc = new ChatUserConversationDoc();
        doc.setConversationId(id);
        return doc;
    }
}
