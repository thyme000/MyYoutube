package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import cn.diaojiahong.myyoutube.chat.domain.vo.MessageItemVO;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.chat.service.strategy.TextChatContentStrategy;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话内搜索的查询契约: findRecord 的 JSON_EXTRACT 片段在 H2 上无法执行,
 * 这里用桩住的 Mapper 检查生成的查询条件本身——LIKE 通配符转义、正文与文件名两路匹配、
 * 排除撤回消息、可见下限与游标边界, 以及结果的撤回隐藏。
 */
class MessageSearchQueryTest {

    private final MessageMapper messages = mock(MessageMapper.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final UserPort users = mock(UserPort.class);
    private final S3StorageUtil storage = mock(S3StorageUtil.class);
    private final ChatMessageQueryService service = new ChatMessageQueryService(
            messages, chatMessageService, users, storage,
            new ChatContentTypeStrategyRegistry(List.of(new TextChatContentStrategy())));

    private LambdaQueryWrapper<Message> capturedQuery;

    @BeforeAll
    static void registerColumns() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Message.class);
    }

    @BeforeEach
    void signInAndStubAccess() {
        UserContext.setUser("11");
        when(chatMessageService.getConversation("c1")).thenReturn(new Conversation());
        ConversationMember member = new ConversationMember();
        member.setUserId(11L);
        member.setVisibleAfterSeq(5L);
        when(chatMessageService.requireActiveMember("c1")).thenReturn(member);
        when(messages.selectPage(any(), any())).thenAnswer(invocation -> {
            capturedQuery = invocation.getArgument(1);
            Page<Message> page = invocation.getArgument(0);
            page.setRecords(List.of());
            return page;
        });
    }

    @AfterEach
    void signOut() {
        UserContext.removeUser();
    }

    @Test
    void emptyKeywordReturnsAnEmptyPageWithoutQuerying() {
        CursorPage<MessageItemVO, Long> page = service.findRecord("c1", "   ", null);

        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        assertFalse(page.hasMore());
        verifyNoInteractions(messages);
    }

    @Test
    void keywordIsEscapedAndMatchedAgainstTextAndFileName() {
        service.findRecord("c1", "100%_x", null);

        String segment = capturedQuery.getSqlSegment();
        assertTrue(segment.contains("'$.text'"));
        assertTrue(segment.contains("'$.fileName'"));
        assertEquals(2, segment.split("LIKE", -1).length - 1);
        assertTrue(segment.contains("ESCAPE"));
        // 撤回过的消息不进搜索结果
        assertTrue(segment.contains("IS NULL"));
        // 可见下限生效, 按 seq 向旧翻页
        assertTrue(segment.contains("seq >"));
        assertTrue(segment.contains("ORDER BY seq DESC"));
        Collection<Object> params = capturedQuery.getParamNameValuePairs().values();
        // % 和 _ 转成字面量, 反斜杠走 ESCAPE
        assertTrue(params.contains("%100\\%\\_x%"));
        assertTrue(params.contains(5L));
    }

    @Test
    void searchCursorBoundsFlowIntoTheQuery() {
        service.findRecord("c1", "你好", 33L);

        String segment = capturedQuery.getSqlSegment();
        assertTrue(segment.contains("seq >"));
        assertTrue(segment.contains("seq <"));
        Collection<Object> params = capturedQuery.getParamNameValuePairs().values();
        assertTrue(params.contains(33L));
        assertTrue(params.contains(5L));
    }

    @Test
    void searchResultsKeepRecalledRowHiddenAndProfileLoaded() {
        // 已经用 when().thenAnswer() 打过桩, 再打桩必须走 doAnswer: when() 会先把旧 Answer
        // 按匹配器的 null 值执行一遍, 直接 NPE
        doAnswer(invocation -> {
            capturedQuery = invocation.getArgument(1);
            Page<Message> page = invocation.getArgument(0);
            page.setRecords(List.of(message(2L, 22L, true), message(1L, 11L, false)));
            return page;
        }).when(messages).selectPage(any(), any());
        when(users.listByIds(any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().map(MessageSearchQueryTest::user).toList();
        });

        CursorPage<MessageItemVO, Long> page = service.findRecord("c1", "你好", null);

        assertEquals(2, page.items().size());
        MessageItemVO hidden = page.items().get(0);
        assertEquals(2L, hidden.getSeq());
        assertNull(hidden.getContent());
        assertEquals(MessageStatusEnum.WITHDRAWN, hidden.getStatus());
        assertEquals(RecallTypeEnum.SENDER, hidden.getRecallType());
        MessageItemVO kept = page.items().get(1);
        assertEquals("正文", kept.getContent().getText());
        assertEquals("用户11", kept.getSenderNickName());
    }

    private static Message message(long seq, long senderId, boolean recalled) {
        ChatContent content = new ChatContent();
        content.setText("正文");
        Message message = new Message();
        message.setId("m-" + seq);
        message.setConversationId("c1");
        message.setSenderId(senderId);
        message.setContentType(ChatContentTypeEnum.TEXT);
        message.setContent(content);
        message.setSeq(seq);
        message.setStatus(recalled ? MessageStatusEnum.WITHDRAWN : MessageStatusEnum.SENT);
        message.setRecalled(recalled);
        message.setRecallType(recalled ? RecallTypeEnum.SENDER : null);
        return message;
    }

    private static UserSummary user(long id) {
        UserSummary user = new UserSummary();
        user.setId(id);
        user.setNickname("用户" + id);
        return user;
    }
}
