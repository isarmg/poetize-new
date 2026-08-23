package com.ld.poetry.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.dao.HistoryInfoMapper;
import com.ld.poetry.dao.WebInfoMapper;
import com.ld.poetry.entity.*;
import com.ld.poetry.im.websocket.TioUtil;
import com.ld.poetry.im.websocket.TioWebsocketStarter;
import com.ld.poetry.service.FamilyService;
import com.ld.poetry.service.UserService;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.enums.PoetryEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

@Component
public class PoetryApplicationRunner implements ApplicationRunner {
    private static final String INSECURE_DEFAULT_ADMIN_PASSWORD = "47bce5c74f589f4867dbd57e9ca9f808";


    @Value("${store.type}")
    private String defaultType;

    @Value("${poetry.admin.initial-password:}")
    private String initialAdminPassword;

    @Autowired
    private WebInfoMapper webInfoMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private FamilyService familyService;

    @Autowired
    private HistoryInfoMapper historyInfoMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LambdaQueryChainWrapper<WebInfo> wrapper = new LambdaQueryChainWrapper<>(webInfoMapper);
        List<WebInfo> list = wrapper.list();
        if (!CollectionUtils.isEmpty(list)) {
            list.get(0).setDefaultStoreType(defaultType);
            PoetryCache.put(CommonConst.WEB_INFO, list.get(0));
        }

        User admin = userService.lambdaQuery().eq(User::getUserType, PoetryEnum.USER_TYPE_ADMIN.getCode()).one();
        if (admin == null) {
            throw new IllegalStateException("未找到管理员用户，请检查数据库初始化数据");
        }
        replaceInsecureDefaultPassword(admin);
        PoetryCache.put(CommonConst.ADMIN, admin);

        Family family = familyService.lambdaQuery().eq(Family::getUserId, admin.getId()).one();
        PoetryCache.put(CommonConst.ADMIN_FAMILY, family);

        List<HistoryInfo> infoList = new LambdaQueryChainWrapper<>(historyInfoMapper)
                .select(HistoryInfo::getIp, HistoryInfo::getUserId)
                .ge(HistoryInfo::getCreateTime, LocalDateTime.now().with(LocalTime.MIN))
                .list();

        PoetryCache.put(CommonConst.IP_HISTORY, new CopyOnWriteArraySet<>(infoList.stream().map(info -> info.getIp() + (info.getUserId() != null ? "_" + info.getUserId().toString() : "")).collect(Collectors.toList())));

        Map<String, Object> history = new HashMap<>();
        history.put(CommonConst.IP_HISTORY_PROVINCE, historyInfoMapper.getHistoryByProvince());
        history.put(CommonConst.IP_HISTORY_IP, historyInfoMapper.getHistoryByIp());
        history.put(CommonConst.IP_HISTORY_HOUR, historyInfoMapper.getHistoryBy24Hour());
        history.put(CommonConst.IP_HISTORY_COUNT, historyInfoMapper.getHistoryCount());
        PoetryCache.put(CommonConst.IP_HISTORY_STATISTICS, history);

        TioUtil.buildTio();
        TioWebsocketStarter websocketStarter = TioUtil.getTio();
        if (websocketStarter != null) {
            websocketStarter.start();
        }
    }

    private void replaceInsecureDefaultPassword(User admin) {
        if (!INSECURE_DEFAULT_ADMIN_PASSWORD.equalsIgnoreCase(admin.getPassword())) {
            return;
        }
        if (!StringUtils.hasText(initialAdminPassword)
                || initialAdminPassword.length() < 8
                || !initialAdminPassword.codePoints().anyMatch(Character::isLetter)
                || !initialAdminPassword.codePoints().anyMatch(Character::isDigit)
                || initialAdminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException(
                    "检测到公开默认管理员密码，请通过 POETRY_ADMIN_INITIAL_PASSWORD 配置 8 位以上且包含字母和数字的初始密码");
        }

        String bcryptPassword = BCrypt.hashpw(initialAdminPassword);
        initialAdminPassword = null;
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, admin.getId())
                .eq(User::getPassword, INSECURE_DEFAULT_ADMIN_PASSWORD)
                .set(User::getPassword, bcryptPassword)
                .update();
        if (!updated) {
            throw new IllegalStateException("管理员初始密码更新失败");
        }
        admin.setPassword(bcryptPassword);
    }
}
