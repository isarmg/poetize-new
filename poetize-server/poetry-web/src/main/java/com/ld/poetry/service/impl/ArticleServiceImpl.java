package com.ld.poetry.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.aop.ResourceCheck;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.ArticleMapper;
import com.ld.poetry.dao.CommentMapper;
import com.ld.poetry.dao.LabelMapper;
import com.ld.poetry.dao.SortMapper;
import com.ld.poetry.entity.*;
import com.ld.poetry.enums.CommentTypeEnum;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.service.ArticleService;
import com.ld.poetry.service.UserService;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.mail.MailUtil;
import com.ld.poetry.vo.ArticleVO;
import com.ld.poetry.vo.BaseRequestVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 文章表 服务实现类
 * </p>
 *
 * @author sara
 * @since 2021-08-13
 */
@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CommonQuery commonQuery;

    @Autowired
    private UserService userService;

    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private SortMapper sortMapper;

    @Autowired
    private LabelMapper labelMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Value("${user.subscribe.format}")
    private String subscribeFormat;

    @Override
    public PoetryResult saveArticle(ArticleVO articleVO) {
        String validationError = validateArticleInput(articleVO, false);
        if (validationError != null) {
            return PoetryResult.fail(validationError);
        }
        if (articleVO.getViewStatus() != null && !articleVO.getViewStatus() && !StringUtils.hasText(articleVO.getPassword())) {
            return PoetryResult.fail("请设置文章密码！");
        }
        Article article = new Article();
        if (StringUtils.hasText(articleVO.getArticleCover())) {
            article.setArticleCover(articleVO.getArticleCover());
        }
        if (StringUtils.hasText(articleVO.getVideoUrl())) {
            article.setVideoUrl(articleVO.getVideoUrl());
        }
        if (articleVO.getViewStatus() != null && !articleVO.getViewStatus() && StringUtils.hasText(articleVO.getPassword())) {
            article.setPassword(articleVO.getPassword());
            article.setTips(articleVO.getTips());
        }
        article.setViewStatus(articleVO.getViewStatus());
        article.setCommentStatus(articleVO.getCommentStatus());
        article.setRecommendStatus(articleVO.getRecommendStatus());
        article.setArticleTitle(articleVO.getArticleTitle());
        article.setArticleContent(articleVO.getArticleContent());
        article.setSortId(articleVO.getSortId());
        article.setLabelId(articleVO.getLabelId());
        article.setUserId(PoetryUtil.getUserId());
        if (!save(article)) {
            return PoetryResult.fail("文章保存失败！");
        }

        invalidateArticleCaches();

        try {
            if (Boolean.TRUE.equals(articleVO.getViewStatus())) {
                List<User> users = userService.lambdaQuery().select(User::getEmail, User::getSubscribe).eq(User::getUserStatus, PoetryEnum.STATUS_ENABLE.getCode()).list();
                List<String> emails = users.stream().filter(u -> {
                    List<Integer> sub = JSON.parseArray(u.getSubscribe(), Integer.class);
                    return !CollectionUtils.isEmpty(sub) && sub.contains(articleVO.getLabelId());
                }).map(User::getEmail).collect(Collectors.toList());

                if (!CollectionUtils.isEmpty(emails)) {
                    LambdaQueryChainWrapper<Label> wrapper = new LambdaQueryChainWrapper<>(labelMapper);
                    Label label = wrapper.select(Label::getLabelName).eq(Label::getId, articleVO.getLabelId()).one();
                    String text = getSubscribeMail(label == null ? "未知标签" : label.getLabelName(),
                            articleVO.getArticleTitle());
                    WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
                    mailUtil.sendMailMessage(emails, "您有一封来自" + (webInfo == null ? "POETIZE" : webInfo.getWebName()) + "的回执！", text);
                }
            }
        } catch (Exception e) {
            log.error("订阅邮件发送失败：", e);
        }
        return PoetryResult.success();
    }

    private String validateArticleInput(ArticleVO articleVO, boolean requireId) {
        if (articleVO == null || (requireId && articleVO.getId() == null)) {
            return requireId ? "文章 ID 不能为空！" : "文章信息不能为空！";
        }
        if (!StringUtils.hasText(articleVO.getArticleContent())
                || articleVO.getArticleContent().getBytes(StandardCharsets.UTF_8).length > 65_535) {
            return "文章内容不能为空，且 UTF-8 编码后不能超过 65535 字节！";
        }
        if (articleVO.getSortId() == null || articleVO.getLabelId() == null) {
            return "文章分类和标签不能为空！";
        }
        long sortCount = new LambdaQueryChainWrapper<>(sortMapper)
                .eq(Sort::getId, articleVO.getSortId())
                .count();
        long labelCount = new LambdaQueryChainWrapper<>(labelMapper)
                .eq(Label::getId, articleVO.getLabelId())
                .eq(Label::getSortId, articleVO.getSortId())
                .count();
        if (sortCount != 1 || labelCount != 1) {
            return "文章分类或标签不存在，或标签不属于所选分类！";
        }
        return null;
    }

    private String getSubscribeMail(String labelName, String articleTitle) {
        WebInfo webInfo = (WebInfo) PoetryCache.get(CommonConst.WEB_INFO);
        String webName = HtmlUtils.htmlEscape(webInfo == null ? "POETIZE" : webInfo.getWebName());
        String adminName = HtmlUtils.htmlEscape(PoetryUtil.getAdminUser().getUsername());
        return String.format(mailUtil.getMailText(),
                webName,
                String.format(MailUtil.notificationMail, adminName),
                adminName,
                String.format(subscribeFormat,
                        HtmlUtils.htmlEscape(labelName == null ? "" : labelName),
                        HtmlUtils.htmlEscape(articleTitle == null ? "" : articleTitle)),
                "",
                webName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult deleteArticle(Integer id) {
        if (id == null) {
            return PoetryResult.fail("文章 ID 不能为空！");
        }
        Integer userId = PoetryUtil.getUserId();
        boolean removed = lambdaUpdate().eq(Article::getId, id)
                .eq(Article::getUserId, userId)
                .remove();
        if (!removed) {
            return PoetryResult.fail("文章不存在或无删除权限！");
        }
        new LambdaUpdateChainWrapper<>(commentMapper)
                .eq(Comment::getSource, id)
                .eq(Comment::getType, CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode())
                .remove();
        PoetryCache.remove(CommonConst.COMMENT_COUNT_CACHE
                + id + "_" + CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode());
        invalidateArticleCaches();
        return PoetryResult.success();
    }

    @Override
    public PoetryResult updateArticle(ArticleVO articleVO) {
        String validationError = validateArticleInput(articleVO, true);
        if (validationError != null) {
            return PoetryResult.fail(validationError);
        }
        if (articleVO.getViewStatus() != null && !articleVO.getViewStatus() && !StringUtils.hasText(articleVO.getPassword())) {
            return PoetryResult.fail("请设置文章密码！");
        }

        Integer userId = PoetryUtil.getUserId();
        LambdaUpdateChainWrapper<Article> updateChainWrapper = lambdaUpdate()
                .eq(Article::getId, articleVO.getId())
                .eq(Article::getUserId, userId)
                .set(Article::getLabelId, articleVO.getLabelId())
                .set(Article::getSortId, articleVO.getSortId())
                .set(Article::getArticleTitle, articleVO.getArticleTitle())
                .set(Article::getUpdateBy, PoetryUtil.getUsername())
                .set(Article::getUpdateTime, LocalDateTime.now())
                .set(Article::getVideoUrl, StringUtils.hasText(articleVO.getVideoUrl()) ? articleVO.getVideoUrl() : null)
                .set(Article::getArticleContent, articleVO.getArticleContent());

        if (StringUtils.hasText(articleVO.getArticleCover())) {
            updateChainWrapper.set(Article::getArticleCover, articleVO.getArticleCover());
        }
        if (articleVO.getCommentStatus() != null) {
            updateChainWrapper.set(Article::getCommentStatus, articleVO.getCommentStatus());
        }
        if (articleVO.getRecommendStatus() != null) {
            updateChainWrapper.set(Article::getRecommendStatus, articleVO.getRecommendStatus());
        }
        if (articleVO.getViewStatus() != null && !articleVO.getViewStatus() && StringUtils.hasText(articleVO.getPassword())) {
            updateChainWrapper.set(Article::getPassword, articleVO.getPassword());
            updateChainWrapper.set(StringUtils.hasText(articleVO.getTips()), Article::getTips, articleVO.getTips());
        }
        if (articleVO.getViewStatus() != null) {
            updateChainWrapper.set(Article::getViewStatus, articleVO.getViewStatus());
        }
        if (!updateChainWrapper.update()) {
            return PoetryResult.fail("文章不存在或无修改权限！");
        }
        invalidateArticleCaches();
        return PoetryResult.success();
    }

    @Override
    public PoetryResult<Page> listArticle(BaseRequestVO baseRequestVO) {
        List<Integer> ids = null;
        List<List<Integer>> idList = null;
        if (StringUtils.hasText(baseRequestVO.getArticleSearch())) {
            idList = commonQuery.getArticleIds(baseRequestVO.getArticleSearch());
            ids = idList.stream().flatMap(Collection::stream).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(ids)) {
                baseRequestVO.setRecords(new ArrayList<>());
                return PoetryResult.success(baseRequestVO);
            }
        }

        LambdaQueryChainWrapper<Article> lambdaQuery = lambdaQuery();
        lambdaQuery.in(!CollectionUtils.isEmpty(ids), Article::getId, ids);
        lambdaQuery.like(StringUtils.hasText(baseRequestVO.getSearchKey()), Article::getArticleTitle, baseRequestVO.getSearchKey());
        lambdaQuery.eq(baseRequestVO.getRecommendStatus() != null && baseRequestVO.getRecommendStatus(), Article::getRecommendStatus, PoetryEnum.STATUS_ENABLE.getCode());


        if (baseRequestVO.getLabelId() != null) {
            lambdaQuery.eq(Article::getLabelId, baseRequestVO.getLabelId());
        } else if (baseRequestVO.getSortId() != null) {
            lambdaQuery.eq(Article::getSortId, baseRequestVO.getSortId());
        }

        lambdaQuery.orderByDesc(Article::getCreateTime);

        lambdaQuery.page(baseRequestVO);

        List<Article> records = baseRequestVO.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            List<ArticleVO> articles = new ArrayList<>();
            List<ArticleVO> titles = new ArrayList<>();
            List<ArticleVO> contents = new ArrayList<>();

            for (Article article : records) {
                if (!Boolean.TRUE.equals(article.getViewStatus())) {
                    article.setArticleContent("");
                } else if (article.getArticleContent().length() > CommonConst.SUMMARY) {
                    article.setArticleContent(article.getArticleContent().substring(0, CommonConst.SUMMARY).replace("`", "").replace("#", "").replace(">", ""));
                }
                ArticleVO articleVO = buildArticleVO(article, false);
                articleVO.setHasVideo(StringUtils.hasText(articleVO.getVideoUrl()));
                articleVO.setPassword(null);
                articleVO.setVideoUrl(null);
                if (CollectionUtils.isEmpty(ids)) {
                    articles.add(articleVO);
                } else if (idList.get(0).contains(articleVO.getId())) {
                    titles.add(articleVO);
                } else if (idList.get(1).contains(articleVO.getId())) {
                    contents.add(articleVO);
                }
            }

            List<ArticleVO> collect = new ArrayList<>();
            collect.addAll(articles);
            collect.addAll(titles);
            collect.addAll(contents);
            baseRequestVO.setRecords(collect);
        }
        return PoetryResult.success(baseRequestVO);
    }

    @Override
    @ResourceCheck(CommonConst.RESOURCE_ARTICLE_DOC)
    public PoetryResult<ArticleVO> getArticleById(Integer id, String password) {
        LambdaQueryChainWrapper<Article> lambdaQuery = lambdaQuery();
        lambdaQuery.eq(Article::getId, id);

        Article article = lambdaQuery.one();
        if (article == null) {
            return PoetryResult.success();
        }
        if (!Boolean.TRUE.equals(article.getViewStatus())) {
            if (!StringUtils.hasText(password)) {
                return PoetryResult.fail("密码错误" + (StringUtils.hasText(article.getTips()) ? article.getTips() : "请联系作者获取密码"));
            }
            String failureKey = CommonConst.ARTICLE_PASSWORD_FAILURE + id + "_"
                    + PoetryUtil.getIpAddr(PoetryUtil.getRequest());
            if (PoetryCache.getCount(failureKey) >= CommonConst.ARTICLE_PASSWORD_FAILURE_LIMIT) {
                return PoetryResult.fail("密码尝试次数过多，请稍后再试！");
            }
            if (!password.equals(article.getPassword())) {
                PoetryCache.increment(failureKey, CommonConst.ARTICLE_PASSWORD_FAILURE_EXPIRE);
                return PoetryResult.fail("密码错误" + (StringUtils.hasText(article.getTips()) ? article.getTips() : "请联系作者获取密码"));
            }
            PoetryCache.remove(failureKey);
        }
        if (articleMapper.updateViewCount(id) == 1) {
            article.setViewCount((article.getViewCount() == null ? 0 : article.getViewCount()) + 1);
        }
        article.setPassword(null);
        if (StringUtils.hasText(article.getVideoUrl())) {
            article.setVideoUrl(SecureUtil.aes(CommonConst.CRYPOTJS_KEY.getBytes(StandardCharsets.UTF_8)).encryptBase64(article.getVideoUrl()));
        }
        ArticleVO articleVO = buildArticleVO(article, false);
        return PoetryResult.success(articleVO);
    }

    @Override
    public PoetryResult<Page> listAdminArticle(BaseRequestVO baseRequestVO, Boolean isBoss) {
        LambdaQueryChainWrapper<Article> lambdaQuery = lambdaQuery();
        lambdaQuery.select(Article.class, a -> !a.getColumn().equals("article_content"));
        if (!isBoss) {
            lambdaQuery.eq(Article::getUserId, PoetryUtil.getUserId());
        } else {
            if (baseRequestVO.getUserId() != null) {
                lambdaQuery.eq(Article::getUserId, baseRequestVO.getUserId());
            }
        }
        if (StringUtils.hasText(baseRequestVO.getSearchKey())) {
            lambdaQuery.like(Article::getArticleTitle, baseRequestVO.getSearchKey());
        }
        if (baseRequestVO.getRecommendStatus() != null && baseRequestVO.getRecommendStatus()) {
            lambdaQuery.eq(Article::getRecommendStatus, PoetryEnum.STATUS_ENABLE.getCode());
        }

        if (baseRequestVO.getLabelId() != null) {
            lambdaQuery.eq(Article::getLabelId, baseRequestVO.getLabelId());
        }

        if (baseRequestVO.getSortId() != null) {
            lambdaQuery.eq(Article::getSortId, baseRequestVO.getSortId());
        }

        lambdaQuery.orderByDesc(Article::getCreateTime).page(baseRequestVO);

        List<Article> records = baseRequestVO.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            List<ArticleVO> collect = records.stream().map(article -> {
                article.setPassword(null);
                ArticleVO articleVO = buildArticleVO(article, true);
                return articleVO;
            }).collect(Collectors.toList());
            baseRequestVO.setRecords(collect);
        }
        return PoetryResult.success(baseRequestVO);
    }

    @Override
    public PoetryResult<ArticleVO> getArticleByIdForUser(Integer id) {
        LambdaQueryChainWrapper<Article> lambdaQuery = lambdaQuery();
        lambdaQuery.eq(Article::getId, id).eq(Article::getUserId, PoetryUtil.getUserId());
        Article article = lambdaQuery.one();
        if (article == null) {
            return PoetryResult.fail("文章不存在！");
        }
        ArticleVO articleVO = new ArticleVO();
        BeanUtils.copyProperties(article, articleVO);
        return PoetryResult.success(articleVO);
    }

    @Override
    public PoetryResult<Map<Integer, List<ArticleVO>>> listSortArticle() {
        Map<Integer, List<ArticleVO>> result = (Map<Integer, List<ArticleVO>>) PoetryCache.get(CommonConst.SORT_ARTICLE_LIST);
        if (result != null) {
            return PoetryResult.success(result);
        }

        synchronized (CommonConst.SORT_ARTICLE_LIST.intern()) {
            result = (Map<Integer, List<ArticleVO>>) PoetryCache.get(CommonConst.SORT_ARTICLE_LIST);
            if (result == null) {
                Map<Integer, List<ArticleVO>> map = new HashMap<>();

                List<Sort> sorts = new LambdaQueryChainWrapper<>(sortMapper).select(Sort::getId).list();
                for (Sort sort : sorts) {
                    LambdaQueryChainWrapper<Article> lambdaQuery = lambdaQuery()
                            .eq(Article::getSortId, sort.getId())
                            .orderByDesc(Article::getCreateTime)
                            .last("limit 6");
                    List<Article> articleList = lambdaQuery.list();
                    if (CollectionUtils.isEmpty(articleList)) {
                        continue;
                    }

                    List<ArticleVO> articleVOList = articleList.stream().map(article -> {
                        if (!Boolean.TRUE.equals(article.getViewStatus())) {
                            article.setArticleContent("");
                        } else if (article.getArticleContent().length() > CommonConst.SUMMARY) {
                            article.setArticleContent(article.getArticleContent().substring(0, CommonConst.SUMMARY).replace("`", "").replace("#", "").replace(">", ""));
                        }

                        ArticleVO vo = buildArticleVO(article, false);
                        vo.setHasVideo(StringUtils.hasText(article.getVideoUrl()));
                        vo.setPassword(null);
                        vo.setVideoUrl(null);
                        return vo;
                    }).collect(Collectors.toList());
                    map.put(sort.getId(), articleVOList);
                }

                PoetryCache.put(CommonConst.SORT_ARTICLE_LIST, map, CommonConst.TOKEN_INTERVAL);
                return PoetryResult.success(map);
            } else {
                return PoetryResult.success(result);
            }
        }
    }

    private ArticleVO buildArticleVO(Article article, Boolean isAdmin) {
        ArticleVO articleVO = new ArticleVO();
        BeanUtils.copyProperties(article, articleVO);
        if (!isAdmin) {
            if (!StringUtils.hasText(articleVO.getArticleCover())) {
                articleVO.setArticleCover(PoetryUtil.getRandomCover(articleVO.getId().toString()));
            }
        }

        User user = commonQuery.getUser(articleVO.getUserId());
        if (user != null && StringUtils.hasText(user.getUsername())) {
            articleVO.setUsername(user.getUsername());
        } else if (!isAdmin) {
            articleVO.setUsername(PoetryUtil.getRandomName(articleVO.getUserId().toString()));
        }
        if (Boolean.TRUE.equals(articleVO.getCommentStatus())) {
            articleVO.setCommentCount(commonQuery.getCommentCount(articleVO.getId(), CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode()));
        } else {
            articleVO.setCommentCount(0);
        }

        List<Sort> sortInfo = commonQuery.getSortInfo();
        if (!CollectionUtils.isEmpty(sortInfo)) {
            for (Sort s : sortInfo) {
                if (s.getId().intValue() == articleVO.getSortId().intValue()) {
                    Sort sort = new Sort();
                    BeanUtils.copyProperties(s, sort);
                    sort.setLabels(null);
                    articleVO.setSort(sort);
                    if (!CollectionUtils.isEmpty(s.getLabels())) {
                        for (int j = 0; j < s.getLabels().size(); j++) {
                            Label l = s.getLabels().get(j);
                            if (l.getId().intValue() == articleVO.getLabelId().intValue()) {
                                Label label = new Label();
                                BeanUtils.copyProperties(l, label);
                                articleVO.setLabel(label);
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
        return articleVO;
    }

    private void invalidateArticleCaches() {
        PoetryCache.remove(CommonConst.ARTICLE_LIST);
        PoetryCache.remove(CommonConst.SORT_ARTICLE_LIST);
        PoetryCache.remove(CommonConst.SORT_INFO);
    }
}
