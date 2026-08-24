package com.ld.poetry.utils.storage;

import com.ld.poetry.entity.User;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.PoetryUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
@Component
public class FileFilter {

    private final AntPathMatcher matcher = new AntPathMatcher();

    public boolean doFilterFile(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        if (matcher.match("/resource/upload", httpServletRequest.getRequestURI())) {
            // 过滤器执行时 DispatcherServlet 尚未必建立 RequestContextHolder，
            // 直接使用已传入的 request，避免上传请求偶发无 HTTP 上下文。
            String token = httpServletRequest.getHeader(CommonConst.TOKEN_HEADER);
            if (StringUtils.hasText(token)) {
                User user = (User) PoetryCache.get(token);

                if (user != null) {
                    if (user.getId().intValue() == PoetryUtil.getAdminUser().getId().intValue()) {
                        return false;
                    }

                    int userIdCount = PoetryCache.increment(
                            CommonConst.SAVE_COUNT_USER_ID + user.getId(), CommonConst.SAVE_EXPIRE);

                    String ip = PoetryUtil.getIpAddr(httpServletRequest);
                    int ipCount = PoetryCache.increment(CommonConst.SAVE_COUNT_IP + ip, CommonConst.SAVE_EXPIRE);

                    return userIdCount > CommonConst.SAVE_MAX_COUNT || ipCount > CommonConst.SAVE_MAX_COUNT;
                }
            }
            return true;
        } else {
            return false;
        }
    }
}
