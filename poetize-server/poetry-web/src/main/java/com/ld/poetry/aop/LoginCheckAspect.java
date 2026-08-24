package com.ld.poetry.aop;

import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.User;
import com.ld.poetry.enums.CodeMsg;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.handle.PoetryLoginException;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.cache.PoetryCache;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;


@Aspect
@Component
@Order(0)
@Slf4j
public class LoginCheckAspect {

    @Around("@annotation(loginCheck)")
    public Object around(ProceedingJoinPoint joinPoint, LoginCheck loginCheck) throws Throwable {
        String token = PoetryUtil.getToken();
        if (!StringUtils.hasText(token)) {
            throw new PoetryLoginException(CodeMsg.NOT_LOGIN.getMsg());
        }

        boolean userSession = token.startsWith(CommonConst.USER_ACCESS_TOKEN);
        boolean adminSession = token.startsWith(CommonConst.ADMIN_ACCESS_TOKEN);
        if (!userSession && !adminSession) {
            throw new PoetryLoginException(CodeMsg.NOT_LOGIN.getMsg());
        }

        User user = (User) PoetryCache.get(token);

        if (user == null) {
            throw new PoetryLoginException(CodeMsg.LOGIN_EXPIRED.getMsg());
        }

        String userId = user.getId().toString();
        String mappingKey = (userSession ? CommonConst.USER_TOKEN : CommonConst.ADMIN_TOKEN) + userId;
        if (!token.equals(PoetryCache.get(mappingKey))) {
            PoetryCache.remove(token);
            throw new PoetryLoginException(CodeMsg.LOGIN_EXPIRED.getMsg());
        }

        if (userSession) {
            if (loginCheck.value() == PoetryEnum.USER_TYPE_ADMIN.getCode() || loginCheck.value() == PoetryEnum.USER_TYPE_DEV.getCode()) {
                return PoetryResult.fail("请输入管理员账号！");
            }
        } else {
            log.info("管理员请求 IP：{}", PoetryUtil.getIpAddr(PoetryUtil.getRequest()));
            if (loginCheck.value() == PoetryEnum.USER_TYPE_ADMIN.getCode()
                    && !user.getId().equals(PoetryUtil.getAdminUser().getId())) {
                return PoetryResult.fail("请输入管理员账号！");
            }
        }

        if (user.getUserType() == null || loginCheck.value() < user.getUserType()) {
            throw new PoetryRuntimeException("权限不足！");
        }

        //重置过期时间
        String intervalKey = (userSession ? CommonConst.USER_TOKEN_INTERVAL : CommonConst.ADMIN_TOKEN_INTERVAL) + userId;
        boolean flag1 = PoetryCache.get(intervalKey) == null;

        if (flag1) {
            synchronized (userId.intern()) {
                boolean flag2 = PoetryCache.get(intervalKey) == null;

                if (flag2) {
                    PoetryCache.put(token, user, CommonConst.TOKEN_EXPIRE);
                    PoetryCache.put(mappingKey, token, CommonConst.TOKEN_EXPIRE);
                    PoetryCache.put(intervalKey, token, CommonConst.TOKEN_INTERVAL);
                }
            }
        }
        return joinPoint.proceed();
    }
}
