package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IUserService extends IService<User> {
    public Result sendCode(String phone);

    public Result login(LoginFormDTO loginForm);


    Result logout(HttpServletRequest request);

    Result me();

    Result info(Long userId);
}
