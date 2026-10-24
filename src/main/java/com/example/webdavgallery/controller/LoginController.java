package com.example.webdavgallery.controller;

import com.example.webdavgallery.config.AuthInterceptor;
import com.example.webdavgallery.dto.ApiResponse;
import com.example.webdavgallery.exception.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 登录/退出，账号密码来自 application.yml 的 app.auth 配置 */
@RestController
public class LoginController {

    @Value("${app.auth.username}")
    private String username;

    @Value("${app.auth.password}")
    private String password;

    @PostMapping("/api/login")
    public ApiResponse<Void> login(@RequestBody Map<String, String> body, HttpSession session) {
        String user = body.getOrDefault("username", "");
        String pass = body.getOrDefault("password", "");
        if (!username.equals(user) || !password.equals(pass)) {
            throw new BusinessException("用户名或密码错误");
        }
        session.setAttribute(AuthInterceptor.SESSION_KEY, username);
        return ApiResponse.ok();
    }

    @GetMapping("/api/login/status")
    public ApiResponse<Map<String, Object>> status(HttpSession session) {
        Object user = session == null ? null : session.getAttribute(AuthInterceptor.SESSION_KEY);
        return ApiResponse.ok(Map.of("loggedIn", user != null));
    }

    @PostMapping("/api/logout")
    public ApiResponse<Void> logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok();
    }
}
