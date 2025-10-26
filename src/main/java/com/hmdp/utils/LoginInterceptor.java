package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取session
        HttpSession session = request.getSession();
        // 2.查询用户
        Object user = session.getAttribute("user");
        if(user == null) {
            response.setStatus(401);
            return false; //用户不存在，拦截，返回 401 状态码
        }
        // 3. 用户存在，保存到ThreadLocal，这里是封装到了UserHolder里
        UserHolder.saveUser((UserDTO) user);
        // 4. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
