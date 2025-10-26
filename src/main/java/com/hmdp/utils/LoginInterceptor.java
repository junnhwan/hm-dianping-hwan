package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 判断用户是否存在
        if(UserHolder.getUser() == null) {
            // 设置 401 状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 2. 放行
        return true;
    }
}
