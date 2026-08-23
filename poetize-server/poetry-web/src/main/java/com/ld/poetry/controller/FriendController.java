package com.ld.poetry.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.aop.SaveCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.ResourcePathMapper;
import com.ld.poetry.entity.ResourcePath;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.StringUtil;
import com.ld.poetry.vo.ResourcePathVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * <p>
 * 资源聚合里的友链，其他接口在ResourceAggregationController
 * </p>
 *
 * @author sara
 * @since 2021-09-14
 */
@RestController
@RequestMapping("/webInfo")
public class FriendController {

    @Autowired
    private ResourcePathMapper resourcePathMapper;

    /**
     * 保存友链
     */
    @LoginCheck
    @PostMapping("/saveFriend")
    @SaveCheck
    public PoetryResult saveFriend(@RequestBody ResourcePathVO resourcePathVO) {
        if (resourcePathVO == null || !StringUtils.hasText(resourcePathVO.getTitle()) || !StringUtils.hasText(resourcePathVO.getCover()) ||
                !StringUtils.hasText(resourcePathVO.getUrl()) || !StringUtils.hasText(resourcePathVO.getIntroduction())) {
            return PoetryResult.fail("信息不全！");
        }
        String title = normalizeText(resourcePathVO.getTitle(), 64, "友链标题");
        String cover = resourcePathVO.getCover().trim();
        String url = resourcePathVO.getUrl().trim();
        String introduction = normalizeText(resourcePathVO.getIntroduction(), 1024, "友链简介");
        if (cover.length() > 256 || url.length() > 256 || !isHttpUrl(cover) || !isHttpUrl(url)) {
            return PoetryResult.fail("友链封面和地址必须是长度不超过 256 的 HTTP(S) URL！");
        }
        ResourcePath friend = new ResourcePath();
        friend.setClassify(CommonConst.DEFAULT_FRIEND);
        friend.setTitle(title);
        friend.setIntroduction(introduction);
        friend.setCover(cover);
        friend.setUrl(url);
        friend.setRemark(PoetryUtil.getUserId().toString());
        friend.setType(CommonConst.RESOURCE_PATH_TYPE_FRIEND);
        friend.setStatus(Boolean.FALSE);
        return resourcePathMapper.insert(friend) == 1
                ? PoetryResult.success()
                : PoetryResult.fail("友链保存失败！");
    }

    private String normalizeText(String value, int maxLength, String fieldName) {
        String normalized = StringUtil.removeHtml(value.trim());
        if (normalized.length() > maxLength) {
            throw new PoetryRuntimeException(fieldName + "不能超过 " + maxLength + " 个字符！");
        }
        return normalized;
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equals(scheme.toLowerCase(Locale.ROOT))
                    || "https".equals(scheme.toLowerCase(Locale.ROOT)))
                    && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 查询友链
     */
    @GetMapping("/listFriend")
    public PoetryResult<Map<String, List<ResourcePathVO>>> listFriend() {
        LambdaQueryChainWrapper<ResourcePath> wrapper = new LambdaQueryChainWrapper<>(resourcePathMapper);
        List<ResourcePath> resourcePaths = wrapper.eq(ResourcePath::getType, CommonConst.RESOURCE_PATH_TYPE_FRIEND)
                .eq(ResourcePath::getStatus, Boolean.TRUE)
                .orderByAsc(ResourcePath::getCreateTime)
                .list();
        Map<String, List<ResourcePathVO>> collect = new HashMap<>();
        if (!CollectionUtils.isEmpty(resourcePaths)) {
            collect = resourcePaths.stream().map(rp -> {
                ResourcePathVO resourcePathVO = new ResourcePathVO();
                BeanUtils.copyProperties(rp, resourcePathVO);
                return resourcePathVO;
            }).collect(Collectors.groupingBy(ResourcePathVO::getClassify));
        }
        return PoetryResult.success(collect);
    }
}
