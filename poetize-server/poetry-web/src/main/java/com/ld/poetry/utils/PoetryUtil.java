package com.ld.poetry.utils;

import com.alibaba.fastjson2.JSON;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.User;
import com.ld.poetry.entity.WebInfo;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.cache.PoetryCache;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

public class PoetryUtil {

    public static HttpServletRequest getRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new PoetryRuntimeException("当前线程不存在 HTTP 请求上下文！");
        }
        return attributes.getRequest();
    }

    public static void checkEmail() {
        User user = (User) PoetryCache.get(PoetryUtil.getToken());
        if (user == null) {
            throw new PoetryRuntimeException("请先登录！");
        }
        if (!StringUtils.hasText(user.getEmail())) {
            throw new PoetryRuntimeException("请先绑定邮箱！");
        }
    }

    public static String getToken() {
        String token = PoetryUtil.getRequest().getHeader(CommonConst.TOKEN_HEADER);
        return !StringUtils.hasText(token) || "null".equals(token) ? null : token;
    }

    public static User getCurrentUser() {
        User user = (User) PoetryCache.get(PoetryUtil.getToken());
        return user;
    }

    public static User getAdminUser() {
        User admin = (User) PoetryCache.get(CommonConst.ADMIN);
        if (admin == null) {
            throw new PoetryRuntimeException("管理员信息尚未初始化！");
        }
        return admin;
    }

    public static Integer getUserId() {
        String token = PoetryUtil.getToken();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        User user = (User) PoetryCache.get(token);
        return user == null ? null : user.getId();
    }

    public static String getUsername() {
        User user = (User) PoetryCache.get(PoetryUtil.getToken());
        return user == null ? null : user.getUsername();
    }

    public static String getRandomAvatar(String key) {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        if (webInfo != null) {
            String randomAvatar = webInfo.getRandomAvatar();
            List<String> randomAvatars = JSON.parseArray(randomAvatar, String.class);
            if (!CollectionUtils.isEmpty(randomAvatars)) {
                if (StringUtils.hasText(key)) {
                    return randomAvatars.get(PoetryUtil.hashLocation(key, randomAvatars.size()));
                } else {
                    String ipAddr = PoetryUtil.getIpAddr(PoetryUtil.getRequest());
                    if (StringUtils.hasText(ipAddr)) {
                        return randomAvatars.get(PoetryUtil.hashLocation(ipAddr, randomAvatars.size()));
                    } else {
                        return randomAvatars.get(0);
                    }
                }
            }
        }
        return null;
    }

    public static String getRandomName(String key) {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        if (webInfo != null) {
            String randomName = webInfo.getRandomName();
            List<String> randomNames = JSON.parseArray(randomName, String.class);
            if (!CollectionUtils.isEmpty(randomNames)) {
                if (StringUtils.hasText(key)) {
                    return randomNames.get(PoetryUtil.hashLocation(key, randomNames.size()));
                } else {
                    String ipAddr = PoetryUtil.getIpAddr(PoetryUtil.getRequest());
                    if (StringUtils.hasText(ipAddr)) {
                        return randomNames.get(PoetryUtil.hashLocation(ipAddr, randomNames.size()));
                    } else {
                        return randomNames.get(0);
                    }
                }
            }
        }
        return null;
    }

    public static String getRandomCover(String key) {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        if (webInfo != null) {
            String randomCover = webInfo.getRandomCover();
            List<String> randomCovers = JSON.parseArray(randomCover, String.class);
            if (!CollectionUtils.isEmpty(randomCovers)) {
                if (StringUtils.hasText(key)) {
                    return randomCovers.get(PoetryUtil.hashLocation(key, randomCovers.size()));
                } else {
                    String ipAddr = PoetryUtil.getIpAddr(PoetryUtil.getRequest());
                    if (StringUtils.hasText(ipAddr)) {
                        return randomCovers.get(PoetryUtil.hashLocation(ipAddr, randomCovers.size()));
                    } else {
                        return randomCovers.get(0);
                    }
                }
            }
        }
        return null;
    }

    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // 只信任容器解析后的远端地址；代理头由 server.forward-headers-strategy
        // 与容器的受信代理配置统一处理，避免客户端直连伪造 X-Forwarded-For。
        return request.getRemoteAddr();
    }


    public static int hashLocation(String key, int length) {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length 必须大于 0");
        }
        int h = key.hashCode();
        return Math.floorMod(h ^ (h >>> 16), length);
    }

    public static long normalizePageCurrent(Long current) {
        if (current == null) {
            return 1L;
        }
        return Math.min(Math.max(current, 1L), 100_000L);
    }

    public static long normalizePageSize(Long size, long defaultSize) {
        long requestedSize = size == null ? defaultSize : size;
        return Math.min(Math.max(requestedSize, 1L), 100L);
    }
}
