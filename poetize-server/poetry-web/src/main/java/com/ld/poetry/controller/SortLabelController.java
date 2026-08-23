package com.ld.poetry.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.LabelMapper;
import com.ld.poetry.dao.SortMapper;
import com.ld.poetry.dao.ArticleMapper;
import com.ld.poetry.entity.Article;
import com.ld.poetry.entity.Label;
import com.ld.poetry.entity.Sort;
import com.ld.poetry.utils.CommonQuery;
import com.ld.poetry.utils.cache.PoetryCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 分类标签 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-09-14
 */
@RestController
@RequestMapping("/webInfo")
public class SortLabelController {

    @Autowired
    private SortMapper sortMapper;

    @Autowired
    private LabelMapper labelMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CommonQuery commonQuery;

    /**
     * 获取分类标签信息
     */
    @GetMapping("/getSortInfo")
    public PoetryResult<List<Sort>> getSortInfo() {
        return PoetryResult.success(commonQuery.getSortInfo());
    }

    /**
     * 保存
     */
    @PostMapping("/saveSort")
    @LoginCheck(0)
    public PoetryResult saveSort(@RequestBody Sort sort) {
        if (sort == null || !StringUtils.hasText(sort.getSortName()) || !StringUtils.hasText(sort.getSortDescription())) {
            return PoetryResult.fail("分类名称和分类描述不能为空！");
        }

        if (sort.getPriority() == null) {
            return PoetryResult.fail("分类必须配置优先级！");
        }
        if (!validSortFields(sort)) {
            return PoetryResult.fail("分类字段超过长度限制或分类类型不合法！");
        }

        sort.setId(null);
        if (sortMapper.insert(sort) != 1) {
            return PoetryResult.fail("分类保存失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 删除
     */
    @GetMapping("/deleteSort")
    @LoginCheck(0)
    public PoetryResult deleteSort(@RequestParam("id") Integer id) {
        if (id == null) {
            return PoetryResult.fail("分类 ID 不能为空！");
        }
        long labelCount = new LambdaQueryChainWrapper<>(labelMapper).eq(Label::getSortId, id).count();
        long articleCount = new LambdaQueryChainWrapper<>(articleMapper).eq(Article::getSortId, id).count();
        if (labelCount > 0 || articleCount > 0) {
            return PoetryResult.fail("分类仍被标签或文章引用，不能删除！");
        }
        if (sortMapper.deleteById(id) != 1) {
            return PoetryResult.fail("分类不存在或删除失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 更新
     */
    @PostMapping("/updateSort")
    @LoginCheck(0)
    public PoetryResult updateSort(@RequestBody Sort sort) {
        if (sort == null || sort.getId() == null || !StringUtils.hasText(sort.getSortName())
                || !StringUtils.hasText(sort.getSortDescription()) || sort.getPriority() == null) {
            return PoetryResult.fail("分类 ID、名称、描述和优先级不能为空！");
        }
        if (!validSortFields(sort)) {
            return PoetryResult.fail("分类字段超过长度限制或分类类型不合法！");
        }
        if (sortMapper.updateById(sort) != 1) {
            return PoetryResult.fail("分类不存在或更新失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 查询List
     */
    @GetMapping("/listSort")
    public PoetryResult<List<Sort>> listSort() {
        return PoetryResult.success(new LambdaQueryChainWrapper<>(sortMapper).list());
    }


    /**
     * 保存
     */
    @PostMapping("/saveLabel")
    @LoginCheck(0)
    public PoetryResult saveLabel(@RequestBody Label label) {
        if (label == null || !StringUtils.hasText(label.getLabelName()) || !StringUtils.hasText(label.getLabelDescription()) || label.getSortId() == null) {
            return PoetryResult.fail("标签名称和标签描述和分类Id不能为空！");
        }
        if (sortMapper.selectById(label.getSortId()) == null) {
            return PoetryResult.fail("标签所属分类不存在！");
        }
        if (!validLabelFields(label)) {
            return PoetryResult.fail("标签名称或描述超过长度限制！");
        }
        label.setId(null);
        if (labelMapper.insert(label) != 1) {
            return PoetryResult.fail("标签保存失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 删除
     */
    @GetMapping("/deleteLabel")
    @LoginCheck(0)
    public PoetryResult deleteLabel(@RequestParam("id") Integer id) {
        if (id == null) {
            return PoetryResult.fail("标签 ID 不能为空！");
        }
        long articleCount = new LambdaQueryChainWrapper<>(articleMapper).eq(Article::getLabelId, id).count();
        if (articleCount > 0) {
            return PoetryResult.fail("标签仍被文章引用，不能删除！");
        }
        if (labelMapper.deleteById(id) != 1) {
            return PoetryResult.fail("标签不存在或删除失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 更新
     */
    @PostMapping("/updateLabel")
    @LoginCheck(0)
    public PoetryResult updateLabel(@RequestBody Label label) {
        if (label == null || label.getId() == null || label.getSortId() == null
                || !StringUtils.hasText(label.getLabelName())
                || !StringUtils.hasText(label.getLabelDescription())) {
            return PoetryResult.fail("标签 ID、分类 ID、名称和描述不能为空！");
        }
        Label existing = labelMapper.selectById(label.getId());
        if (existing == null) {
            return PoetryResult.fail("标签不存在！");
        }
        if (!existing.getSortId().equals(label.getSortId())) {
            return PoetryResult.fail("已有标签不能直接更换分类，请新建标签并迁移文章！");
        }
        if (sortMapper.selectById(label.getSortId()) == null) {
            return PoetryResult.fail("标签所属分类不存在！");
        }
        if (!validLabelFields(label)) {
            return PoetryResult.fail("标签名称或描述超过长度限制！");
        }
        if (labelMapper.updateById(label) != 1) {
            return PoetryResult.fail("标签不存在或更新失败！");
        }
        invalidateSortCaches();
        return PoetryResult.success();
    }


    /**
     * 查询List
     */
    @GetMapping("/listLabel")
    public PoetryResult<List<Label>> listLabel() {
        return PoetryResult.success(new LambdaQueryChainWrapper<>(labelMapper).list());
    }


    /**
     * 查询List
     */
    @GetMapping("/listSortAndLabel")
    public PoetryResult<Map> listSortAndLabel() {
        Map<String, List> map = new HashMap<>();
        map.put("sorts", new LambdaQueryChainWrapper<>(sortMapper).list());
        map.put("labels", new LambdaQueryChainWrapper<>(labelMapper).list());
        return PoetryResult.success(map);
    }

    private void invalidateSortCaches() {
        PoetryCache.remove(CommonConst.SORT_INFO);
        PoetryCache.remove(CommonConst.SORT_ARTICLE_LIST);
        PoetryCache.remove(CommonConst.ARTICLE_LIST);
    }

    private boolean validSortFields(Sort sort) {
        return sort.getSortName().length() <= 32
                && sort.getSortDescription().length() <= 256
                && (sort.getSortType() == null || sort.getSortType() == 0 || sort.getSortType() == 1);
    }

    private boolean validLabelFields(Label label) {
        return label.getLabelName().length() <= 32
                && label.getLabelDescription().length() <= 256;
    }
}
