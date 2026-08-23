package com.ld.poetry.controller;

import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.aop.SaveCheck;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.storage.QiniuUtil;
import com.ld.poetry.utils.storage.UploadSecurityValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 七牛云
 */
@RestController
@RequestMapping("/qiniu")
@ConditionalOnBean(QiniuUtil.class)
public class QiniuController {

    @Autowired
    private QiniuUtil qiniuUtil;

    /**
     * 获取覆盖凭证，用于七牛云
     */
    @GetMapping("/getUpToken")
    @LoginCheck
    @SaveCheck
    public PoetryResult<String> getUpToken(@RequestParam(value = "key") String key) {
        if (UploadSecurityValidator.isAssetsKey(key)
                && !PoetryUtil.getAdminUser().getId().equals(PoetryUtil.getUserId())) {
            return PoetryResult.fail("公共静态资源仅允许站长上传！");
        }
        return PoetryResult.success(qiniuUtil.getToken(key));
    }
}
