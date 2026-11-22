package cn.diaojiahong.myyoutube.common.filter;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class UserIdHeaderFilterTest {
    final ObjectMapper mapper = new ObjectMapper();

    final UserIdHeaderFilter filter = new UserIdHeaderFilter(mapper);

    @Test
    void rejectedIdentityUsesTheSharedErrorContract() throws Exception {
        ReflectionTestUtils.setField(filter, "internalToken", "expected-token");
        var request = new MockHttpServletRequest();
        request.addHeader(UserIdHeaderFilter.USER_ID_HEADER, "123");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Rejected identity must not reach the controller"));
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertEquals(401, response.getStatus());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.get("code").asInt());
        assertEquals("非法请求来源", body.get("message").asText());
        assertTrue(body.get("data").isNull());
    }

    @Test
    void acceptedIdentityIsAvailableOnlyDuringTheRequest() throws Exception {
        ReflectionTestUtils.setField(filter, "internalToken", "expected-token");
        var request = new MockHttpServletRequest();
        request.addHeader(UserIdHeaderFilter.USER_ID_HEADER, "123");
        request.addHeader(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "expected-token");
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> assertEquals("123", UserContext.getUserId()));
        assertNull(UserContext.getUserId());
    }
}
