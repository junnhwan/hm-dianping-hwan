package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 先校验手机号是否格式正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 2. 手机号合法，则生成验证码并存到session中以便后续校验，以及发送验证码，这里用日志模拟发送
        String code = RandomUtil.randomNumbers(6);  // 生成验证码
        session.setAttribute("code", code);  // 验证码存到session
        log.debug("发送验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1. 先校验手机号和验证码是否正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        Object cacheCode = session.getAttribute("code");
        // 验证码为空或错误直接返回错误
        if(cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误"); // 验证码错误直接返回
        }
        // 2. 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 3. 若不存在，创建新用户保存到数据库，并保存到session
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 5. 保存用户信息至session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(user);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
