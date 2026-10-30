package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.chain.message.*;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.*;
import cn.diaojiahong.myyoutube.chat.mongo.po.*;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.strategy.*;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.mongodb.client.MongoClients;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="content.integration",matches="true")
class LiveForwardContentTest {
    final com.mongodb.client.MongoClient client=MongoClients.create("mongodb://127.0.0.1:17017/?directConnection=true&replicaSet=content-it");

    final SimpleMongoClientDatabaseFactory factory=new SimpleMongoClientDatabaseFactory(client,"content_it");

    final MongoTemplate mongo=new MongoTemplate(factory);

    final ContentInternalPort contents=mock(ContentInternalPort.class);

    final S3StorageUtil storage=mock(S3StorageUtil.class);

    final RocketMQTemplate mq=mock(RocketMQTemplate.class);
    ChatMessageService service;

    java.util.function.Consumer<MessageSendContext> gate=ctx -> {
    };

    @BeforeEach
    void setup() {
        assertEquals("content-it",mongo.executeCommand(new Document("hello",1)).getString("setName"));
        for(String collection:List.of("chat_messages","chat_conversations","chat_conversation_seq","chat_user_conversation")) {
            mongo.dropCollection(collection);
        }
        mongo.createCollection(ChatMessageDoc.class);
        var conversation=new ChatConversationDoc();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        mongo.save(conversation);
        var registry=new ChatContentTypeStrategyRegistry(List.of(
                new ForwardContentChatContentStrategy(contents,storage),
                new TextChatContentStrategy()));
        var setup=new MessageSendStep(20) { public void apply(MessageSendContext ctx) {
            ctx.setConversationDoc(mongo.findById(ctx.getConversationId(),ChatConversationDoc.class));
            ctx.setInterrelated(true);
        }};
        var afterLookup=new MessageSendStep(55) {
            public void apply(MessageSendContext ctx) {
                gate.accept(ctx);
            }
        };
        var guards=new SyncChainStepRegistry(List.of(
                new TypeValidateStep(registry),
                setup,
                new SenderIdentityStep(),
                new IdempotentStep(mongo),
                afterLookup));
        service=new ChatMessageService(mongo,mock(ChatConversationService.class),mq,mock(UserPort.class),storage,
                mock(StringRedisTemplate.class),
                contents,
                registry,
                guards,
                new TransactionTemplate(new MongoTransactionManager(factory)));
        when(contents.forwardInfo(99L,7L)).thenReturn(new ContentInternalPort.ForwardInfo(
                99L,
                "dynamic",
                "真实标题",
                "chosen"));
        UserContext.setUser("7");
    }

    @AfterEach
    void close() {
        UserContext.removeUser();
        client.close();
    }

    ChatMessageDTO request() {
        var dto=new ChatMessageDTO();
        dto.setConversationId("conversation");
        dto.setSenderId(7L);
        dto.setClientMsgId("one-client-id");
        dto.setContentType(ChatContentTypeEnum.FORWARD_CONTENT);
        var content=new ChatContent();
        content.setContentId(99L);
        content.setTitle("伪造标题");
        dto.setContent(content);
        return dto;
    }

    @Test
    void concurrentLookupMissCannotCreateSecondMessageAfterFirstCommit() throws Exception {
        var both=new CountDownLatch(2);
        var committed=new CountDownLatch(1);
        gate=ctx -> {
            both.countDown();
            try {
                assertTrue(both.await(5,TimeUnit.SECONDS));
                if(Thread.currentThread().getName().equals("delayed")) assertTrue(committed.await(5,TimeUnit.SECONDS));
            } catch(InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<ChatMessageAckDTO> first=pool.submit(() -> { Thread.currentThread().setName("first"); UserContext.setUser("7");
                try {
                    return service.send(request());
                } finally {
                    committed.countDown();
                    UserContext.removeUser();
                }
                });
            Future<ChatMessageAckDTO> delayed=pool.submit(() -> { Thread.currentThread().setName("delayed"); UserContext.setUser("7");
                try {
                    return service.send(request());
                } finally {
                    UserContext.removeUser();
                }
                });
            assertEquals(first.get(10,TimeUnit.SECONDS).getMessageId(),delayed.get(10,TimeUnit.SECONDS).getMessageId());
            assertEquals(1,mongo.count(new Query(),ChatMessageDoc.class));
            verify(contents,times(1)).incrementShareCount(99L,7L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retryAfterMqFailureUsesDurableSnapshotAndRejectsSpoofedSender() {
        doThrow(new IllegalStateException("MQ unavailable")).when(mq).syncSend(anyString(),any(Object.class));
        assertThrows(IllegalStateException.class,() -> service.send(request()));
        ChatMessageAckDTO ack=service.send(request());
        assertNotNull(ack.getMessageId());
        ChatMessageDoc saved=mongo.findById(ack.getMessageId(),ChatMessageDoc.class);
        assertEquals("真实标题",saved.getContent().getTitle());
        assertEquals("dynamic",saved.getContent().getMediaType());
        assertEquals(1,mongo.count(new Query(),ChatMessageDoc.class));
        verify(contents).incrementShareCount(99L,7L);
        ChatMessageDTO spoof=request();
        spoof.setSenderId(8L);
        assertThrows(BizException.class,() -> service.send(spoof));
        ChatMessageDTO other=request();
        other.setConversationId("other");
        assertThrows(BizException.class,() -> service.send(other));
    }

    @Test
    void simultaneousCrossConversationReuseCannotBypassGlobalIdempotency() throws Exception {
        var other=new ChatConversationDoc();
        other.setId("other");
        other.setType(ConversationTypeEnum.SINGLE);
        mongo.save(other);
        var ready=new CountDownLatch(2);
        gate=ctx -> { ready.countDown(); try { assertTrue(ready.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException e) {
                throw new IllegalStateException(e);
            }
            };
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<?>>();
            for(String conversation:List.of("conversation","other")) results.add(pool.submit(() -> {
                UserContext.setUser("7");
                ChatMessageDTO dto=request();
                dto.setConversationId(conversation);
                try {
                    service.send(dto);
                } catch(RuntimeException expected) {
                    return expected;
                } finally {
                    UserContext.removeUser();
                }
                return null;
            }));
            int failures=0;
            for(Future<?> result:results) if(result.get(10,TimeUnit.SECONDS)!=null) failures++;
            assertEquals(1,failures);
            assertEquals(1,mongo.count(new Query(),ChatMessageDoc.class));
            verify(contents,times(1)).incrementShareCount(99L,7L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void conversationWriteFailureRollsBackMessageAndSequence() {
        mongo.executeCommand(new Document("collMod","chat_conversations").append("validator",
                new Document("lastMessageId",new Document("$type","int"))));
        assertThrows(RuntimeException.class,() -> service.send(request()));
        assertEquals(0,mongo.count(new Query(),ChatMessageDoc.class));
        assertEquals(0,mongo.count(new Query(),ChatSeqDoc.class));
        verify(contents,never()).incrementShareCount(anyLong(),anyLong());
        verifyNoInteractions(mq);
    }

    @Test
    void textSendAndRetryKeepOneMessageAndOneSequence() {
        ChatMessageDTO dto=request();
        dto.setContentType(ChatContentTypeEnum.TEXT);
        dto.getContent().setText("普通文本");
        ChatMessageAckDTO first=service.send(dto);
        ChatMessageAckDTO second=service.send(dto);
        assertEquals(first.getMessageId(),second.getMessageId());
        assertEquals(1L,first.getSeq());
        assertEquals("普通文本",mongo.findById(first.getMessageId(),ChatMessageDoc.class).getContent().getText());
        assertEquals("普通文本",mongo.findById("conversation",ChatConversationDoc.class).getLastMessagePreview());
        assertEquals(1,mongo.count(new Query(),ChatMessageDoc.class));
        verifyNoInteractions(contents);
        verify(mq,times(1)).syncSend(anyString(),any(Object.class));
    }

    @Test
    void newConversationMembersReferenceTheGeneratedConversationId() {
        var conversations = new ChatConversationService(
                mongo,
                mock(cn.diaojiahong.myyoutube.video.api.FollowPort.class),
                mock(UserPort.class), storage, mq, mock(cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher.class),
                new TransactionTemplate(new MongoTransactionManager(factory)));
        var dto = new cn.diaojiahong.myyoutube.chat.mongo.dto.CreateConversationDTO();
        dto.setType(ConversationTypeEnum.SINGLE);
        dto.setMemberIds(new ArrayList<>(List.of(8L)));
        ConversationVO created = conversations.createConversation(dto);
        assertNotNull(created.getId());
        List<ChatUserConversationDoc> members = mongo.find(
                Query.query(Criteria.where("conversationId").is(created.getId())),
                ChatUserConversationDoc.class);
        assertEquals(
                Set.of(7L, 8L),
                members.stream().map(ChatUserConversationDoc::getUserId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                0,
                mongo.count(Query.query(Criteria.where("conversationId").is(null)), ChatUserConversationDoc.class));
    }
}
