package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.InternalApiErrorDecoder;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.content.service.ContentReadService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO;
import cn.diaojiahong.myyoutube.video.controller.InternalAdminCommentController;
import cn.diaojiahong.myyoutube.video.controller.InternalAdminContentController;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.service.ReviewDecisionService;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class InternalAdminFeignContractTest {
    final ContentReadService reads = mock(ContentReadService.class);
    final IContentCommentService comments = mock(IContentCommentService.class);
    final AtomicInteger calls = new AtomicInteger();
    final HttpServer server;
    final ContentAdminPort content;
    final CommentAdminPort comment;
    String route;
    boolean corruptBody;

    InternalAdminFeignContractTest() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalAdminContentController(mock(ContentUnderstandingService.class), mock(ReviewDecisionService.class),
                        mock(IContentService.class), mock(ContentTypeStrategyRegistry.class), reads),
                new InternalAdminCommentController(comments, mock(ICommentReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                var result = mvc.perform(post(route == null ? exchange.getRequestURI().getPath() : route)
                        .contentType("application/json").content(corruptBody ? new byte[]{'{' } : body)).andReturn().getResponse();
                var bytes = result.getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.getStatus(), result.getStatus() == 204 ? -1 : bytes.length);
                if (result.getStatus() != 204) exchange.getResponseBody().write(bytes);
            } catch (Exception error) { throw new java.io.IOException(error); }
            finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        content = builder().target(ContentAdminPort.class, base + "/internal/admin/content");
        comment = builder().target(CommentAdminPort.class, base + "/internal/admin/comment");
    }
    Feign.Builder builder() {
        return Feign.builder().contract(new SpringMvcContract()).errorDecoder(new InternalApiErrorDecoder())
                .retryer(Retryer.NEVER_RETRY)
                .encoder((value, type, template) -> template.body(JsonUtil.toJson(value)))
                .decoder((response, type) -> JsonUtil.GSON.fromJson(response.body().asReader(StandardCharsets.UTF_8), type));
    }
    @AfterEach void close() { server.stop(0); }

    @Test void providerDtoAnd204CommandsMatchTheNewFeignSignatures() {
        var media = new ContentMediaVO("video", new ContentMediaVO.Video("url", 1200), null);
        when(reads.adminMedia(7L)).thenReturn(media);
        assertEquals(media, content.media(new ContentAdminPort.ContentId(7L)));
        when(comments.getById(7L)).thenReturn(new ContentComment().setStatus("visible"));
        assertDoesNotThrow(() -> comment.restore(new CommentAdminPort.CommentId(7L)));
        assertEquals(2, calls.get());
    }

    @Test void missingResourceAndDeletedStateRemainBusinessRejections() {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> comment.restore(new CommentAdminPort.CommentId(7L))).getErrorCode());
        when(comments.getById(7L)).thenReturn(new ContentComment().setStatus("deleted"));
        assertEquals(ErrorCode.STATE_CONFLICT, assertThrows(BizException.class,
                () -> comment.restore(new CommentAdminPort.CommentId(7L))).getErrorCode());
        assertEquals(2, calls.get());
    }

    @Test void routeAndSerializationContractErrorsAreNotBusinessErrors() {
        route = "/missing-internal-route";
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, assertThrows(DependencyException.class,
                () -> content.media(new ContentAdminPort.ContentId(7L))).getErrorCode());
        route = null; corruptBody = true;
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, assertThrows(DependencyException.class,
                () -> content.media(new ContentAdminPort.ContentId(7L))).getErrorCode());
        verifyNoInteractions(reads);
        assertEquals(2, calls.get());
    }
}
