package com.ld.poetry.config;

import java.util.Locale;
import java.util.Set;

/**
 * 限制可从数据库注入 Spring Environment 的配置项，避免通过管理界面
 * 覆盖数据源、端口、初始化脚本等启动安全边界。
 */
public final class SysConfigPolicy {

    private static final Set<String> RUNTIME_KEYS = Set.of(
            "spring.mail.username",
            "spring.mail.password",
            "user.code.format",
            "user.subscribe.format",
            "local.uploadUrl",
            "local.downloadUrl",
            "im.enable"
    );

    private static final Set<String> BUILT_IN_KEYS = Set.of(
            "spring.mail.username",
            "spring.mail.password",
            "user.code.format",
            "user.subscribe.format",
            "local.uploadUrl",
            "local.downloadUrl",
            "im.enable",
            "beian",
            "webStaticResourcePrefix"
    );

    private static final Set<String> PRIVATE_KEYS = Set.of(
            "spring.mail.username",
            "spring.mail.password",
            "user.code.format",
            "user.subscribe.format",
            "local.uploadUrl",
            "im.enable"
    );

    private SysConfigPolicy() {
    }

    public static boolean isRuntimeKey(String key) {
        return RUNTIME_KEYS.contains(key);
    }

    public static boolean isBuiltInKey(String key) {
        return BUILT_IN_KEYS.contains(key);
    }

    public static boolean mustRemainPrivate(String key) {
        if (key == null) {
            return false;
        }
        if (PRIVATE_KEYS.contains(key)) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "-");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("private-key")
                || normalized.contains("access-key")
                || normalized.contains("api-key");
    }
}
