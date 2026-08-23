package com.ld.poetry.handle;

import com.ld.poetry.dao.HistoryInfoMapper;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.cache.PoetryCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@EnableScheduling
@Slf4j
public class ScheduleTask {

    @Autowired
    private HistoryInfoMapper historyInfoMapper;

    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanIpHistory() {
        CopyOnWriteArraySet<String> ipHistory = (CopyOnWriteArraySet<String>) PoetryCache.get(CommonConst.IP_HISTORY);
        if (ipHistory == null) {
            ipHistory = new CopyOnWriteArraySet<>();
            PoetryCache.put(CommonConst.IP_HISTORY, ipHistory);
        }
        ipHistory.clear();

        Map<String, Object> history = new HashMap<>();
        history.put(CommonConst.IP_HISTORY_PROVINCE, historyInfoMapper.getHistoryByProvince());
        history.put(CommonConst.IP_HISTORY_IP, historyInfoMapper.getHistoryByIp());
        history.put(CommonConst.IP_HISTORY_HOUR, historyInfoMapper.getHistoryBy24Hour());
        history.put(CommonConst.IP_HISTORY_COUNT, historyInfoMapper.getHistoryCount());
        // Build the complete snapshot first, then replace it in one cache write so
        // concurrent readers never observe a temporarily missing statistics map.
        PoetryCache.put(CommonConst.IP_HISTORY_STATISTICS, history);
    }
}
