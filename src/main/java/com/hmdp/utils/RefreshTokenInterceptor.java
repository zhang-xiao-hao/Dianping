package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    /**
     通过构造函数给stringRedisTemplate做依赖注入，LoginInterceptor类为自定义类，
     因此不能通过@Resource给stringRedisTemplate进行装配？
     MicConfig类为一个配置类（@Configuration标注的类在容器中），
     该类注册了LoginInterceptor，可以通过MicConfig给LoginInterceptor的stringRedisTemplate做依赖注入
     */
    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取Header中的token
        String token = request.getHeader("authorization");
        // 判断
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String savedToken = LOGIN_USER_KEY + token;
        // 获取该token的用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(savedToken);
        // 判断
        if (userMap.isEmpty()){
            return true;
        }
        // 转回为userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 保存用户信息到 ThreadLocal，它是一个线程本地的变量，
        // 它能够在同一个线程内共享数据，但其他线程无法访问该变量。
        // 以确保每个线程都能够独立地获取到自己所处理的请求的用户信息。
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(savedToken, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除ThreadLocal中的用户信息
        UserHolder.removeUser();
    }
}
