package com.example.webdavgallery.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

/** 登录拦截：API 返回 401 JSON，页面重定向到登录页 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "authUser";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_KEY) != null) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或会话已过期\"}");
        } else {
            response.sendRedirect("/login.html");
        }
        return false;
    }
}
