package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminController;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminContentPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminContentVO;
import cn.diaojiahong.myyoutube.admin.service.impl.AdminServiceImpl;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminServiceContractTest {
    final UserPort users = mock(UserPort.class);

    final ContentAdminPort content = mock(ContentAdminPort.class);

    final CommentAdminPort comments = mock(CommentAdminPort.class);

    final AdminServiceImpl service = new AdminServiceImpl(
            users,
            content,
            comments,
            mock(S3StorageUtil.class),
            AuthorizationFixture.authorization());

    @BeforeEach
    void identity() {
        UserContext.setUser("1");
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    @Test
    void directEmptyPagesKeepTheirOriginalTotals() {
        when(content.pageContents(any())).thenReturn(PageResult.of(12, 3, List.of()));
        when(comments.pageBlockedComments(any())).thenReturn(PageResult.of(15, 4, List.of()));
        when(comments.pageReports(any())).thenReturn(PageResult.of(20, 5, List.of()));
        PageResult<AdminContentVO> contents = service.getContentsPage(new AdminContentPageQueryDTO());
        PageResult<AdminCommentVO> blocked = service.getCommentsPage(new PageQuery());
        PageResult<AdminCommentReportVO> reports = service.getReportCommentsPage(new AdminCommentReportPageQueryDTO());
        assertEquals(12, contents.getTotal());
        assertEquals(3, contents.getPages());
        assertEquals(15, blocked.getTotal());
        assertEquals(4, blocked.getPages());
        assertEquals(20, reports.getTotal());
        assertEquals(5, reports.getPages());
        verifyNoInteractions(users);
    }

    @Test
    void missingPageIsAFailureRatherThanAnEmptySuccess() {
        when(content.pageContents(any())).thenReturn(null);
        assertThrows(DependencyException.class, () -> service.getContentsPage(new AdminContentPageQueryDTO()));
    }

    @Test
    void nullReviewResponseIsDifferentFromAConcurrentStateChange() {
        when(content.markStatus(any())).thenReturn(null, false, true);
        assertThrows(DependencyException.class, () -> service.pass(7L, "reason"));
        assertEquals(
                ErrorCode.STATE_CONFLICT,
                assertThrows(BizException.class, () -> service.pass(7L, "reason")).getErrorCode());
        assertEquals("审核通过", service.pass(7L, "reason"));
    }

    @Test
    void voidCommandsPropagateTheOriginalFailure() {
        service.blockComment(7L);
        service.ignoreReportComment(7L);
        var error = new BizException(ErrorCode.STATE_CONFLICT, "评论已被删除");
        doThrow(error).when(comments).restore(any());
        assertSame(error, assertThrows(BizException.class, () -> service.restoreComment(7L)));
        verify(comments).block(any());
        verify(comments).ignoreReport(any());
        verify(comments).restore(any());
    }

    @Test
    void externalControllerWrapsTheDirectDtoExactlyOnce() throws Exception {
        when(content.media(any())).thenReturn(new ContentMediaVO("video", new ContentMediaVO.Video("url", 1000), null));
        MockMvcBuilders.standaloneSetup(new AdminController(service, content, AuthorizationFixture.authorization()))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/admin/content/7/media"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("video"))
                .andExpect(jsonPath("$.data.code").doesNotExist());
    }
}
