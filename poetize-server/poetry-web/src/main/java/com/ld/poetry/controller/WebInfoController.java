package com.ld.poetry.controller;


import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.*;
import com.ld.poetry.entity.*;
import com.ld.poetry.service.WebInfoService;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.cache.PoetryCache;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 网站信息表 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-09-14
 */
@RestController
@RequestMapping("/webInfo")
public class WebInfoController {

    @Autowired
    private WebInfoService webInfoService;

    @Autowired
    private HistoryInfoMapper historyInfoMapper;

    @Autowired
    private CommonQuery commonQuery;


    /**
     * 更新网站信息
     */
    @LoginCheck(0)
    @PostMapping("/updateWebInfo")
    public PoetryResult<WebInfo> updateWebInfo(@RequestBody WebInfo webInfo) {
        if (webInfo == null || exceeds(webInfo.getWebName(), 16)
                || exceeds(webInfo.getWebTitle(), 512)
                || exceeds(webInfo.getNotices(), 512)
                || exceeds(webInfo.getFooter(), 256)
                || exceeds(webInfo.getBackgroundImage(), 256)
                || exceeds(webInfo.getAvatar(), 256)
                || exceeds(webInfo.getRandomName(), 4096)
                || exceedsText(webInfo.getRandomAvatar())
                || exceedsText(webInfo.getRandomCover())
                || exceedsText(webInfo.getWaifuJson())) {
            return PoetryResult.fail("网站信息字段超过数据库长度限制！");
        }
        WebInfo current = webInfoService.lambdaQuery().orderByAsc(WebInfo::getId).last("limit 1").one();
        if (current == null) {
            return PoetryResult.fail("网站信息尚未初始化！");
        }
        webInfo.setId(current.getId());
        if (!webInfoService.updateById(webInfo)) {
            return PoetryResult.fail("网站信息更新失败！");
        }
        WebInfo updated = webInfoService.getById(current.getId());
        if (updated == null) {
            return PoetryResult.fail("网站信息更新后读取失败！");
        }
        PoetryCache.put(CommonConst.WEB_INFO, updated);
        return PoetryResult.success();
    }

    private boolean exceeds(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private boolean exceedsText(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length > 65_535;
    }


    /**
     * 获取网站信息
     */
    @GetMapping("/getWebInfo")
    public PoetryResult<WebInfo> getWebInfo() {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        if (webInfo != null) {
            WebInfo result = new WebInfo();
            BeanUtils.copyProperties(webInfo, result);
            result.setRandomAvatar(null);
            result.setRandomName(null);
            result.setWaifuJson(null);
            result.setAdminUserId(PoetryUtil.getAdminUser().getId());

            result.setHistoryAllCount("0");
            result.setHistoryDayCount("0");
            Object statistics = PoetryCache.get(CommonConst.IP_HISTORY_STATISTICS);
            if (statistics instanceof Map<?, ?> statisticsMap) {
                Object allCount = statisticsMap.get(CommonConst.IP_HISTORY_COUNT);
                if (allCount instanceof Number number) {
                    result.setHistoryAllCount(number.toString());
                }
                Object hourHistory = statisticsMap.get(CommonConst.IP_HISTORY_HOUR);
                if (hourHistory instanceof Collection<?> collection) {
                    result.setHistoryDayCount(Integer.toString(collection.size()));
                }
            }
            return PoetryResult.success(result);
        }
        return PoetryResult.success();
    }

    /**
     * 获取网站统计信息
     */
    @LoginCheck(0)
    @GetMapping("/getHistoryInfo")
    public PoetryResult<Map<String, Object>> getHistoryInfo() {
        Map<String, Object> result = new HashMap<>();

        Object cachedHistory = PoetryCache.get(CommonConst.IP_HISTORY_STATISTICS);
        Map<?, ?> history = cachedHistory instanceof Map<?, ?> map ? map : Collections.emptyMap();
        List<HistoryInfo> infoList = new LambdaQueryChainWrapper<>(historyInfoMapper)
                .select(HistoryInfo::getIp, HistoryInfo::getUserId, HistoryInfo::getNation, HistoryInfo::getProvince, HistoryInfo::getCity)
                .ge(HistoryInfo::getCreateTime, LocalDateTime.now().with(LocalTime.MIN))
                .list();

        result.put(CommonConst.IP_HISTORY_PROVINCE, history.get(CommonConst.IP_HISTORY_PROVINCE));
        result.put(CommonConst.IP_HISTORY_IP, history.get(CommonConst.IP_HISTORY_IP));
        result.put(CommonConst.IP_HISTORY_COUNT, history.get(CommonConst.IP_HISTORY_COUNT));
        Object cachedHourHistory = history.get(CommonConst.IP_HISTORY_HOUR);
        List<?> ipHistoryCount = cachedHourHistory instanceof List<?> list ? list : Collections.emptyList();
        result.put("ip_count_yest", ipHistoryCount.stream()
                .map(m -> m instanceof Map<?, ?> historyItem ? historyItem.get("ip") : null)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        result.put("username_yest", ipHistoryCount.stream().map(m -> {
            if (!(m instanceof Map<?, ?> historyItem)) {
                return null;
            }
            Object userId = historyItem.get("user_id");
            if (userId != null) {
                User user = commonQuery.getUser(Integer.valueOf(userId.toString()));
                if (user != null) {
                    Map<String, String> userInfo = new HashMap<>();
                    userInfo.put("avatar", user.getAvatar());
                    userInfo.put("username", user.getUsername());
                    return userInfo;
                }
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList()));
        result.put("ip_count_today", infoList.stream().map(HistoryInfo::getIp).distinct().count());
        result.put("username_today", infoList.stream().map(m -> {
            Integer userId = m.getUserId();
            if (userId != null) {
                User user = commonQuery.getUser(userId);
                if (user != null) {
                    Map<String, String> userInfo = new HashMap<>();
                    userInfo.put("avatar", user.getAvatar());
                    userInfo.put("username", user.getUsername());
                    return userInfo;
                }
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList()));

        List<Map<String, Object>> list = infoList.stream()
                .map(HistoryInfo::getProvince).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(m -> m, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    HashMap<String, Object> map = new HashMap<>();
                    map.put("province", entry.getKey());
                    map.put("num", entry.getValue());
                    return map;
                })
                .sorted((o1, o2) -> Long.valueOf(o2.get("num").toString()).compareTo(Long.valueOf(o1.get("num").toString())))
                .collect(Collectors.toList());

        result.put("province_today", list);

        return PoetryResult.success(result);
    }

    /**
     * 获取赞赏
     */
    @GetMapping("/getAdmire")
    public PoetryResult<List<User>> getAdmire() {
        return PoetryResult.success(commonQuery.getAdmire());
    }

    /**
     * 获取看板娘消息
     */
    @GetMapping("/getWaifuJson")
    public String getWaifuJson() {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        if (webInfo != null && StringUtils.hasText(webInfo.getWaifuJson())) {
            return webInfo.getWaifuJson();
        }
        return "{}";
    }
}
