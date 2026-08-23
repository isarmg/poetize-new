package com.ld.poetry.utils;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.*;
import com.ld.poetry.entity.*;
import com.ld.poetry.service.UserService;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.vo.FamilyVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;


@Component
@Slf4j
public class CommonQuery {
    private static final Object IP_HISTORY_LOCK = new Object();

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private HistoryInfoMapper historyInfoMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private SortMapper sortMapper;

    @Autowired
    private LabelMapper labelMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private FamilyMapper familyMapper;

    private Ip2Region ip2Region;

    @PostConstruct
    public void init() {
        try (InputStream inputStream = new ClassPathResource("ip2region.xdb").getInputStream()) {
            Config v4Config = Config.custom()
                    .setXdbInputStream(inputStream)
                    .setCachePolicy(Config.BufferCache)
                    .asV4();
            ip2Region = Ip2Region.create(v4Config, null);
        } catch (Exception e) {
            log.warn("ip2region 初始化失败，将跳过 IP 归属地解析", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (ip2Region != null) {
            try {
                ip2Region.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void saveHistory(String ip) {
        if (!StringUtils.hasText(ip)) {
            return;
        }

        Integer userId = PoetryUtil.getUserId();
        String ipUser = ip + (userId != null ? "_" + userId.toString() : "");

        CopyOnWriteArraySet<String> ipHistory = (CopyOnWriteArraySet<String>) PoetryCache.get(CommonConst.IP_HISTORY);
        if (ipHistory == null) {
            synchronized (IP_HISTORY_LOCK) {
                ipHistory = (CopyOnWriteArraySet<String>) PoetryCache.get(CommonConst.IP_HISTORY);
                if (ipHistory == null) {
                    ipHistory = new CopyOnWriteArraySet<>();
                    PoetryCache.put(CommonConst.IP_HISTORY, ipHistory);
                }
            }
        }

        if (!ipHistory.add(ipUser)) {
            return;
        }

        try {
            HistoryInfo historyInfo = new HistoryInfo();
            historyInfo.setIp(ip);
            historyInfo.setUserId(userId);
            if (ip2Region != null) {
                populateRegion(historyInfo, ip);
            }
            historyInfoMapper.insert(historyInfo);
        } catch (RuntimeException e) {
            ipHistory.remove(ipUser);
            throw e;
        }
    }

    private void populateRegion(HistoryInfo historyInfo, String ip) {
        try {
            String search = ip2Region.search(ip);
            if (!StringUtils.hasText(search)) {
                return;
            }
            String[] region = search.split("\\|", -1);
            if (region.length > 0 && !"0".equals(region[0])) {
                historyInfo.setNation(region[0]);
            }
            if (region.length > 2 && !"0".equals(region[2])) {
                historyInfo.setProvince(region[2]);
            }
            if (region.length > 3 && !"0".equals(region[3])) {
                historyInfo.setCity(region[3]);
            }
        } catch (Exception e) {
            log.debug("IP 归属地解析失败：{}", ip, e);
        }
    }

    public User getUser(Integer userId) {
        User user = (User) PoetryCache.get(CommonConst.USER_CACHE + userId.toString());
        if (user != null) {
            return user;
        }
        User u = userService.getById(userId);
        if (u != null) {
            PoetryCache.put(CommonConst.USER_CACHE + userId.toString(), u, CommonConst.EXPIRE);
            return u;
        }
        return null;
    }

    public List<User> getAdmire() {
        List<User> admire = (List<User>) PoetryCache.get(CommonConst.ADMIRE);
        if (admire != null) {
            return admire;
        }

        synchronized (CommonConst.ADMIRE.intern()) {
            admire = (List<User>) PoetryCache.get(CommonConst.ADMIRE);
            if (admire != null) {
                return admire;
            } else {
                List<User> users = userService.lambdaQuery().select(User::getId, User::getUsername, User::getAdmire, User::getAvatar).isNotNull(User::getAdmire).list();

                PoetryCache.put(CommonConst.ADMIRE, users, CommonConst.EXPIRE);

                return users;
            }
        }
    }

    public List<FamilyVO> getFamilyList() {
        List<FamilyVO> familyVOList = (List<FamilyVO>) PoetryCache.get(CommonConst.FAMILY_LIST);
        if (familyVOList != null) {
            return familyVOList;
        }

        synchronized (CommonConst.FAMILY_LIST.intern()) {
            familyVOList = (List<FamilyVO>) PoetryCache.get(CommonConst.FAMILY_LIST);
            if (familyVOList != null) {
                return familyVOList;
            } else {
                LambdaQueryChainWrapper<Family> queryChainWrapper = new LambdaQueryChainWrapper<>(familyMapper);
                List<Family> familyList = queryChainWrapper.eq(Family::getStatus, Boolean.TRUE).list();
                if (!CollectionUtils.isEmpty(familyList)) {
                    familyVOList = familyList.stream().map(family -> {
                        FamilyVO familyVO = new FamilyVO();
                        BeanUtils.copyProperties(family, familyVO);
                        return familyVO;
                    }).collect(Collectors.toList());
                } else {
                    familyVOList = new ArrayList<>();
                }

                PoetryCache.put(CommonConst.FAMILY_LIST, familyVOList);
                return familyVOList;
            }
        }
    }

    public Integer getCommentCount(Integer source, String type) {
        Integer count = (Integer) PoetryCache.get(CommonConst.COMMENT_COUNT_CACHE + source.toString() + "_" + type);
        if (count != null) {
            return count;
        }
        LambdaQueryChainWrapper<Comment> wrapper = new LambdaQueryChainWrapper<>(commentMapper);
        Integer c = Math.toIntExact(wrapper.eq(Comment::getSource, source).eq(Comment::getType, type).count());
        PoetryCache.put(CommonConst.COMMENT_COUNT_CACHE + source.toString() + "_" + type, c, CommonConst.EXPIRE);
        return c;
    }

    public List<Integer> getUserArticleIds(Integer userId) {
        List<Integer> ids = (List<Integer>) PoetryCache.get(CommonConst.USER_ARTICLE_LIST + userId.toString());
        if (ids != null) {
            return ids;
        }

        synchronized ((CommonConst.USER_ARTICLE_LIST + userId.toString()).intern()) {
            ids = (List<Integer>) PoetryCache.get(CommonConst.USER_ARTICLE_LIST + userId.toString());
            if (ids != null) {
                return ids;
            } else {
                LambdaQueryChainWrapper<Article> wrapper = new LambdaQueryChainWrapper<>(articleMapper);
                List<Article> articles = wrapper.eq(Article::getUserId, userId).select(Article::getId).list();
                List<Integer> collect = articles.stream().map(Article::getId).collect(Collectors.toList());
                PoetryCache.put(CommonConst.USER_ARTICLE_LIST + userId.toString(), collect, CommonConst.EXPIRE);
                return collect;
            }
        }
    }

    public List<List<Integer>> getArticleIds(String searchText) {
        List<Article> articles = (List<Article>) PoetryCache.get(CommonConst.ARTICLE_LIST);
        if (articles == null) {
            synchronized (CommonConst.ARTICLE_LIST.intern()) {
                articles = (List<Article>) PoetryCache.get(CommonConst.ARTICLE_LIST);
                if (articles == null) {
                    LambdaQueryChainWrapper<Article> wrapper = new LambdaQueryChainWrapper<>(articleMapper);
                    articles = wrapper.select(Article::getId, Article::getArticleTitle, Article::getArticleContent)
                            .eq(Article::getViewStatus, Boolean.TRUE)
                            .orderByDesc(Article::getCreateTime)
                            .list();
                    PoetryCache.put(CommonConst.ARTICLE_LIST, articles);
                }
            }
        }

        List<List<Integer>> ids = new ArrayList<>();
        List<Integer> titleIds = new ArrayList<>();
        List<Integer> contentIds = new ArrayList<>();

        for (Article article : articles) {
            if (StringUtil.matchString(article.getArticleTitle(), searchText)) {
                titleIds.add(article.getId());
            } else if (StringUtil.matchString(article.getArticleContent(), searchText)) {
                contentIds.add(article.getId());
            }
        }

        ids.add(titleIds);
        ids.add(contentIds);
        return ids;
    }

    public List<Sort> getSortInfo() {
        List<Sort> sortInfo = (List<Sort>) PoetryCache.get(CommonConst.SORT_INFO);
        if (sortInfo != null) {
            return sortInfo;
        }

        synchronized (CommonConst.SORT_INFO.intern()) {
            sortInfo = (List<Sort>) PoetryCache.get(CommonConst.SORT_INFO);
            if (sortInfo == null) {
                List<Sort> sorts = new LambdaQueryChainWrapper<>(sortMapper).list();
                if (!CollectionUtils.isEmpty(sorts)) {
                    sorts.forEach(sort -> {
                        LambdaQueryChainWrapper<Article> sortWrapper = new LambdaQueryChainWrapper<>(articleMapper);
                        Integer countOfSort = Math.toIntExact(sortWrapper.eq(Article::getSortId, sort.getId()).count());
                        sort.setCountOfSort(countOfSort);

                        LambdaQueryChainWrapper<Label> wrapper = new LambdaQueryChainWrapper<>(labelMapper);
                        List<Label> labels = wrapper.eq(Label::getSortId, sort.getId()).list();
                        if (!CollectionUtils.isEmpty(labels)) {
                            labels.forEach(label -> {
                                LambdaQueryChainWrapper<Article> labelWrapper = new LambdaQueryChainWrapper<>(articleMapper);
                                Integer countOfLabel = Math.toIntExact(labelWrapper.eq(Article::getLabelId, label.getId()).count());
                                label.setCountOfLabel(countOfLabel);
                            });
                            sort.setLabels(labels);
                        }
                    });
                }
                PoetryCache.put(CommonConst.SORT_INFO, sorts);
                return sorts;
            } else {
                return sortInfo;
            }
        }
    }
}
