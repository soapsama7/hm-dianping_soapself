package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
        // 校验手机号是否是正确的格式，不符合则直接返回，符合则发送验证码（模拟）
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("无效手机号，请重试！");
        }
        String code = RandomUtil.randomNumbers(6);
        // 把验证码保存到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 模拟发送验证码
        log.debug("验证码为：{}",stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone));

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        // 校验手机号是否是正确的格式，不符合则直接返回，符合则发送验证码（模拟）
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("无效手机号，请重试！");
        }
        // 若手机号正确，则从redis获取验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if (code == null || !code.equals(loginForm.getCode())){
            return Result.fail("请输入正确的验证码");
        }

        // 根据手机号查询用户信息，若用户存在则直接登录，否则先注册
        User user = query().eq("phone",phone).one();
        if (user == null){
            user = createUserWithPhone(phone);
        }

        // 随机生成一个UUID作为用户信息存储的key
        String token = UUID.randomUUID().toString(true);

        // User对象暴露了太多信息，需要转成UserDTO，同时需要用HashMap进行存储
        // 这里的“fieldName,fieldValue”实际上是UserDTO的每个字段和真实值，而不是Map的泛型
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue) ->
                        fieldValue == null?null:fieldValue.toString()
                )
        );

        // 存储进Redis，并设置有效期
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 还得把token返回给前端
        return Result.ok(token);

    }

    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
