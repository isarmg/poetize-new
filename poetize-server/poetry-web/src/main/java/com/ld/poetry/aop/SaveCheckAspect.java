package com.ld.poetry.aop;

import com.ld.poetry.entity.User;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.PoetryUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Aspect
@Component
@Order(1)
@Slf4j
public class SaveCheckAspect {

    @Around("@annotation(saveCheck)")
    public Object around(ProceedingJoinPoint joinPoint, SaveCheck saveCheck) throws Throwable {
        boolean flag = false;

        String token = PoetryUtil.getToken();
        if (StringUtils.hasText(token)) {
            User user = (User) PoetryCache.get(token);
            if (user != null) {
                User admin = PoetryUtil.getAdminUser();
                if (admin != null && user.getId().equals(admin.getId())) {
                    return joinPoint.proceed();
                }

                int userIdCount = PoetryCache.increment(
                        CommonConst.SAVE_COUNT_USER_ID + user.getId(), CommonConst.SAVE_EXPIRE);
                if (userIdCount > CommonConst.SAVE_MAX_COUNT) {
                    log.info("用户保存超限：{}，次数：{}", user.getId(), userIdCount);
                    flag = true;
                }
            }
        }

        String ip = PoetryUtil.getIpAddr(PoetryUtil.getRequest());
        int ipCount = PoetryCache.increment(CommonConst.SAVE_COUNT_IP + ip, CommonConst.SAVE_EXPIRE);
        if (ipCount > CommonConst.SAVE_MAX_COUNT) {
            log.info("IP保存超限：{}，次数：{}", ip, ipCount);
            flag = true;
        }

        if (flag) {
            throw new PoetryRuntimeException("今日提交次数已用尽，请一天后再来！");
        }

        return joinPoint.proceed();
    }
}
