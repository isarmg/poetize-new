package com.ld.poetry.controller;


import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.config.SysConfigPolicy;
import com.ld.poetry.entity.SysConfig;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.service.SysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 参数配置表 前端控制器
 * </p>
 *
 * @author sara
 * @since 2024-03-23
 */
@RestController
@RequestMapping("/sysConfig")
public class SysConfigController {

    @Autowired
    private SysConfigService sysConfigService;

    /**
     * 查询系统参数
     */
    @GetMapping("/listSysConfig")
    public PoetryResult<Map<String, String>> listSysConfig() {
        LambdaQueryChainWrapper<SysConfig> wrapper = new LambdaQueryChainWrapper<>(sysConfigService.getBaseMapper());
        List<SysConfig> sysConfigs = wrapper.eq(SysConfig::getConfigType, Integer.toString(PoetryEnum.SYS_CONFIG_PUBLIC.getCode()))
                .orderByAsc(SysConfig::getId)
                .list();
        Map<String, String> collect = sysConfigs.stream()
                // 即使旧数据曾将敏感键误标为“公开”，也不向未认证客户端返回。
                .filter(config -> !SysConfigPolicy.mustRemainPrivate(config.getConfigKey()))
                .collect(Collectors.toMap(
                SysConfig::getConfigKey,
                config -> config.getConfigValue() == null ? "" : config.getConfigValue(),
                (oldValue, newValue) -> newValue,
                LinkedHashMap::new));
        return PoetryResult.success(collect);
    }

    /**
     * 保存或更新
     */
    @PostMapping("/saveOrUpdateConfig")
    @LoginCheck(0)
    public PoetryResult saveConfig(@RequestBody SysConfig sysConfig) {
        if (sysConfig == null || !StringUtils.hasText(sysConfig.getConfigName()) ||
                !StringUtils.hasText(sysConfig.getConfigKey()) ||
                !StringUtils.hasText(sysConfig.getConfigType())) {
            return PoetryResult.fail("请完善所有配置信息！");
        }
        String configName = sysConfig.getConfigName().trim();
        String configKey = sysConfig.getConfigKey().trim();
        if (configName.length() > 128 || configKey.length() > 64
                || !configKey.matches("^[A-Za-z][A-Za-z0-9_.-]*$")
                || (sysConfig.getConfigValue() != null && sysConfig.getConfigValue().length() > 256)) {
            return PoetryResult.fail("配置名称、键或值不合法！");
        }
        sysConfig.setConfigName(configName);
        sysConfig.setConfigKey(configKey);
        String configType = sysConfig.getConfigType();
        if (!Integer.toString(PoetryEnum.SYS_CONFIG_PUBLIC.getCode()).equals(configType) &&
                !Integer.toString(PoetryEnum.SYS_CONFIG_PRIVATE.getCode()).equals(configType)) {
            return PoetryResult.fail("配置类型不正确！");
        }
        if (SysConfigPolicy.mustRemainPrivate(configKey)
                && Integer.toString(PoetryEnum.SYS_CONFIG_PUBLIC.getCode()).equals(configType)) {
            return PoetryResult.fail("该运行时配置包含敏感信息，不能设为公开！");
        }
        if (sysConfig.getId() != null) {
            SysConfig existing = sysConfigService.getById(sysConfig.getId());
            if (existing == null) {
                return PoetryResult.fail("配置不存在！");
            }
            if (!existing.getConfigKey().equals(configKey)) {
                return PoetryResult.fail("配置键不允许修改，请新增配置！");
            }
        }
        long duplicateCount = sysConfigService.lambdaQuery()
                .eq(SysConfig::getConfigKey, configKey)
                .ne(sysConfig.getId() != null, SysConfig::getId, sysConfig.getId())
                .count();
        if (duplicateCount > 0) {
            return PoetryResult.fail("配置键已存在！");
        }
        if (!sysConfigService.saveOrUpdate(sysConfig)) {
            return PoetryResult.fail("配置保存失败！");
        }
        return PoetryResult.success();
    }

    /**
     * 删除
     */
    @GetMapping("/deleteConfig")
    @LoginCheck(0)
    public PoetryResult deleteConfig(@RequestParam("id") Integer id) {
        if (id == null) {
            return PoetryResult.fail("配置 ID 不能为空！");
        }
        SysConfig existing = sysConfigService.getById(id);
        if (existing == null) {
            return PoetryResult.fail("配置不存在！");
        }
        if (SysConfigPolicy.isBuiltInKey(existing.getConfigKey())) {
            return PoetryResult.fail("内置配置不能删除，可以将其值置空！");
        }
        if (!sysConfigService.removeById(id)) {
            return PoetryResult.fail("配置删除失败！");
        }
        return PoetryResult.success();
    }

    /**
     * 查询
     */
    @GetMapping("/listConfig")
    @LoginCheck(0)
    public PoetryResult<List<SysConfig>> listConfig() {
        return PoetryResult.success(new LambdaQueryChainWrapper<>(sysConfigService.getBaseMapper()).list());
    }
}
