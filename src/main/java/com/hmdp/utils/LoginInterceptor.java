package com.hmdp.utils;

import com.hmdp.annotation.PublicEndpoint;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            boolean publicMethod = AnnotatedElementUtils.hasAnnotation(
                    handlerMethod.getMethod(), PublicEndpoint.class
            );
            boolean publicController = AnnotatedElementUtils.hasAnnotation(
                    handlerMethod.getBeanType(), PublicEndpoint.class
            );
            if (publicMethod || publicController) {
                return true;
            }
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
