package com.ld.poetry.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.ld.poetry.utils.PoetryUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DataAutoFill implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createBy", String.class, currentUsername());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateBy", String.class, currentUsername());
    }

    private String currentUsername() {
        try {
            String username = PoetryUtil.getUsername();
            return StringUtils.hasText(username) ? username : "Sara";
        } catch (RuntimeException ignored) {
            return "Sara";
        }
    }
}
