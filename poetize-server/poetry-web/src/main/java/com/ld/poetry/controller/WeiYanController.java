package com.ld.poetry.controller;


import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.aop.SaveCheck;
import com.ld.poetry.dao.ArticleMapper;
import com.ld.poetry.entity.Article;
import com.ld.poetry.entity.WeiYan;
import com.ld.poetry.service.WeiYanService;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.StringUtil;
import com.ld.poetry.vo.BaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * <p>
 * 微言表 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-10-26
 */
@RestController
@RequestMapping("/weiYan")
public class WeiYanController {

    @Autowired
    private WeiYanService weiYanService;

    @Autowired
    private ArticleMapper articleMapper;

    /**
     * 保存
     */
    @PostMapping("/saveWeiYan")
    @LoginCheck
    @SaveCheck
    public PoetryResult saveWeiYan(@RequestBody WeiYan weiYanVO) {
        if (weiYanVO == null || !StringUtils.hasText(weiYanVO.getContent())) {
            return PoetryResult.fail("微言不能为空！");
        }

        String content = StringUtil.removeHtml(weiYanVO.getContent());
        if (!StringUtils.hasText(content) || content.length() > 1024) {
            return PoetryResult.fail("微言内容不能为空且不能超过 1024 个字符！");
        }
        weiYanVO.setContent(content);

        WeiYan weiYan = new WeiYan();
        weiYan.setUserId(PoetryUtil.getUserId());
        weiYan.setContent(weiYanVO.getContent());
        weiYan.setIsPublic(weiYanVO.getIsPublic());
        weiYan.setType(CommonConst.WEIYAN_TYPE_FRIEND);
        return weiYanService.save(weiYan)
                ? PoetryResult.success()
                : PoetryResult.fail("微言保存失败！");
    }


    /**
     * 保存
     */
    @PostMapping("/saveNews")
    @LoginCheck
    @SaveCheck
    public PoetryResult saveNews(@RequestBody WeiYan weiYanVO) {
        if (weiYanVO == null || !StringUtils.hasText(weiYanVO.getContent()) || weiYanVO.getSource() == null) {
            return PoetryResult.fail("信息不全！");
        }

        if (weiYanVO.getCreateTime() == null) {
            weiYanVO.setCreateTime(LocalDateTime.now());
        }

        String content = StringUtil.removeHtml(weiYanVO.getContent());
        if (!StringUtils.hasText(content) || content.length() > 1024) {
            return PoetryResult.fail("微言内容不能为空且不能超过 1024 个字符！");
        }

        Integer userId = PoetryUtil.getUserId();

        LambdaQueryChainWrapper<Article> wrapper = new LambdaQueryChainWrapper<>(articleMapper);
        long count = wrapper.eq(Article::getId, weiYanVO.getSource()).eq(Article::getUserId, userId).count();

        if (count < 1) {
            return PoetryResult.fail("来源不存在！");
        }

        WeiYan weiYan = new WeiYan();
        weiYan.setUserId(userId);
        weiYan.setContent(content);
        weiYan.setIsPublic(Boolean.TRUE);
        weiYan.setSource(weiYanVO.getSource());
        weiYan.setCreateTime(weiYanVO.getCreateTime());
        weiYan.setType(CommonConst.WEIYAN_TYPE_NEWS);
        return weiYanService.save(weiYan)
                ? PoetryResult.success()
                : PoetryResult.fail("最新进展保存失败！");
    }

    /**
     * 查询List
     */
    @PostMapping("/listNews")
    public PoetryResult<BaseRequestVO> listNews(@RequestBody BaseRequestVO baseRequestVO) {
        if (baseRequestVO.getSource() == null) {
            return PoetryResult.fail("来源不能为空！");
        }
        LambdaQueryChainWrapper<WeiYan> lambdaQuery = weiYanService.lambdaQuery();
        lambdaQuery.eq(WeiYan::getType, CommonConst.WEIYAN_TYPE_NEWS);
        lambdaQuery.eq(WeiYan::getSource, baseRequestVO.getSource());
        lambdaQuery.eq(WeiYan::getIsPublic, PoetryEnum.PUBLIC.getCode());

        lambdaQuery.orderByDesc(WeiYan::getCreateTime).page(baseRequestVO);
        return PoetryResult.success(baseRequestVO);
    }

    /**
     * 删除
     */
    @GetMapping("/deleteWeiYan")
    @LoginCheck
    public PoetryResult deleteWeiYan(@RequestParam("id") Integer id) {
        Integer userId = PoetryUtil.getUserId();
        weiYanService.lambdaUpdate().eq(WeiYan::getId, id)
                .eq(WeiYan::getUserId, userId)
                .remove();
        return PoetryResult.success();
    }


    /**
     * 查询List
     */
    @PostMapping("/listWeiYan")
    public PoetryResult<BaseRequestVO> listWeiYan(@RequestBody BaseRequestVO baseRequestVO) {
        LambdaQueryChainWrapper<WeiYan> lambdaQuery = weiYanService.lambdaQuery();
        lambdaQuery.eq(WeiYan::getType, CommonConst.WEIYAN_TYPE_FRIEND);
        if (baseRequestVO.getUserId() == null) {
            if (PoetryUtil.getUserId() != null) {
                lambdaQuery.eq(WeiYan::getUserId, PoetryUtil.getUserId());
            } else {
                lambdaQuery.eq(WeiYan::getIsPublic, PoetryEnum.PUBLIC.getCode());
                lambdaQuery.eq(WeiYan::getUserId, PoetryUtil.getAdminUser().getId());
            }
        } else {
            if (!baseRequestVO.getUserId().equals(PoetryUtil.getUserId())) {
                lambdaQuery.eq(WeiYan::getIsPublic, PoetryEnum.PUBLIC.getCode());
            }
            lambdaQuery.eq(WeiYan::getUserId, baseRequestVO.getUserId());
        }

        lambdaQuery.orderByDesc(WeiYan::getCreateTime).page(baseRequestVO);
        return PoetryResult.success(baseRequestVO);
    }
}
