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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.ErrorMessageConstants.CODE_ERROR_MESSAGE;
import static com.hmdp.utils.ErrorMessageConstants.PHONE_ERROR_MESSAGE;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_SUFFIX_LENGTH;
import static com.hmdp.utils.VerificationCodeConstants.*;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码逻辑
     * @param phone 手机号
     * @param session session
     * @return ok/fail
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、校验手机号[正则表达式]
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2、不符合则返回错误信息
            return Result.fail(PHONE_ERROR_MESSAGE);
        }
        // 3、符合则生成验证码
        String code = RandomUtil.randomNumbers(USER_CODE_LENGTH);
        // 4、保存验证码到redis,phone为key，code为value,2分钟有效
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5、模拟发送验证码
        log.debug("验证码发送成功，验证码:{}", code);
        return Result.ok();
    }

    /**
     * 登录
     * @param loginForm 手机号和验证码
     * @param session session
     * @return ok/fail
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合则返回错误信息
            return Result.fail(PHONE_ERROR_MESSAGE);
        }
        // 2、校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            // 不一致
            return Result.fail(CODE_ERROR_MESSAGE);
        }
        // 3、根据手机号查询用户（mybatis-plus）
        User user = query().eq("phone", phone).one();
        // 4、判断用户是否存在
        if (user == null){
            // 不存在则创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 5、随机生成token作为登录令牌，将
        // 对象以hash（相比于转为json存储在list中更省内存）存储在redis中。
        String token = UUID.randomUUID().toString(true);
        String savedToken = LOGIN_USER_KEY + token;
        // 存储UserDTO,粒度小，更安全
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 转为map一次性保存进hash,因为stringRedisTemplate保存的值都必须是string类型，
        // 但userDTO的id为long类型，所以要进行setFieldValueEditor修改类型为string
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));


        stringRedisTemplate.opsForHash().putAll(savedToken, userMap);
        // 30分钟有效期,并在登录拦截器中刷新有效期时长，防止用户访问期间token失效
        stringRedisTemplate.expire(savedToken, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 返回token给浏览器保存
        return Result.ok(token);
    }

    /**
     * 用户签到。redis bitmap。每个bit表示1天的签到情况
     * @return result
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        // 获取当前年月
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取日(1-31)
        int dayOfMonth = now.getDayOfMonth();
        // 写入bitmaps
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1 , true);
        return Result.ok();
    }
    // 统计用户连续签到次数
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        // 获取当前年月
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取日(1-31)
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月到今天为止的签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands
                .create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        // 统计连续签到次数
        int count = 0;
        while (true){
            // 未签到的一天
            if ((num & 1) == 0){
                break;
            }else {
                count ++;
            }
            num = num >>> 1; // 无符号右移
        }
        return Result.ok(count);
    }

    /**
     * 创建用户
     * @param phone 手机号
     * @return newUser
     */
    private User createUserWithPhone(String phone) {
        User newUser = new User().setPhone(phone).setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(USER_NICK_NAME_SUFFIX_LENGTH));
        // 保存新用户
        save(newUser);
        return newUser;
    }
}
