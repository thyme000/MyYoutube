package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mapper.ChatConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ChatUserConversationMapper;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatUserConversationServiceTest {
    @Test
    void excludesFilteredAndMissingConversationsWithoutDereferencingThem() {
        ChatUserConversationMapper memberships = mock(ChatUserConversationMapper.class);
        ChatConversationMapper conversations = mock(ChatConversationMapper.class);
        S3StorageUtil storage = mock(S3StorageUtil.class);
        ChatUserConversationService service = new ChatUserConversationService(
                memberships, conversations, storage, mock(UserPort.class));
        ChatUserConversationDoc blocked = membership("blocked");
        ChatUserConversationDoc missing = membership("missing");
        ChatUserConversationDoc allowed = membership("allowed");
        ChatConversationDoc blockedGroup = new ChatConversationDoc();
        blockedGroup.setId("blocked");
        ChatConversationDoc allowedGroup = new ChatConversationDoc();
        allowedGroup.setId("allowed");
        allowedGroup.setName("可发送群");
        when(memberships.selectPage(any(), any())).thenAnswer(call -> {
            Page<ChatUserConversationDoc> page = call.getArgument(0);
            page.setRecords(List.of(blocked, missing, allowed));
            page.setTotal(3);
            return page;
        });
        when(conversations.selectList(any())).thenReturn(List.of(blockedGroup), List.of(allowedGroup));
        UserContext.setUser("9001");
        try {
            PageResult<ValidUserConversationVO> result = service.getValidConversations(1L, 3L);
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
