package com.hmdp.utils;

import com.hmdp.annotation.PublicEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginInterceptorTest {

    private final LoginInterceptor interceptor = new LoginInterceptor();

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void shouldAllowExplicitPublicEndpointWithoutLogin() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        HandlerMethod handler = new HandlerMethod(
                new ExampleController(),
                ExampleController.class.getMethod("publicQuery")
        );

        assertTrue(interceptor.preHandle(request, response, handler));
    }

    @Test
    void shouldRejectProtectedEndpointWithoutLogin() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        HandlerMethod handler = new HandlerMethod(
                new ExampleController(),
                ExampleController.class.getMethod("protectedWrite")
        );

        assertFalse(interceptor.preHandle(request, response, handler));
        verify(response).setStatus(401);
    }

    private static class ExampleController {
        @PublicEndpoint
        public void publicQuery() {
        }

        public void protectedWrite() {
        }
    }
}
