package com.ld.poetry.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.aop.SaveCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.TreeHoleMapper;
import com.ld.poetry.entity.TreeHole;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Random;

/**
 * <p>
 * 弹幕 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-09-14
 */
@RestController
@RequestMapping("/webInfo")
public class TreeHoleController {

    @Autowired
    private TreeHoleMapper treeHoleMapper;

    /**
     * 保存
     */
    @PostMapping("/saveTreeHole")
    @SaveCheck
    public PoetryResult<TreeHole> saveTreeHole(@RequestBody TreeHole treeHole) {
        if (treeHole == null || !StringUtils.hasText(treeHole.getMessage())) {
            return PoetryResult.fail("留言不能为空！");
        }
        String message = StringUtil.removeHtml(treeHole.getMessage().trim());
        if (message.length() > 64) {
            return PoetryResult.fail("留言不能超过 64 个字符！");
        }
        TreeHole saved = new TreeHole();
        saved.setMessage(message);
        saved.setAvatar(PoetryUtil.getRandomAvatar(null));
        if (treeHoleMapper.insert(saved) != 1) {
            return PoetryResult.fail("留言保存失败！");
        }
        return PoetryResult.success(saved);
    }


    /**
     * 删除
     */
    @GetMapping("/deleteTreeHole")
    @LoginCheck(0)
    public PoetryResult deleteTreeHole(@RequestParam("id") Integer id) {
        treeHoleMapper.deleteById(id);
        return PoetryResult.success();
    }


    /**
     * 查询List
     */
    @GetMapping("/listTreeHole")
    public PoetryResult<List<TreeHole>> listTreeHole() {
        List<TreeHole> treeHoles;
        int count = Math.toIntExact(new LambdaQueryChainWrapper<>(treeHoleMapper).count());
        if (count > CommonConst.TREE_HOLE_COUNT) {
            int i = new Random().nextInt(count + 1 - CommonConst.TREE_HOLE_COUNT);
            treeHoles = treeHoleMapper.queryAllByLimit(i, CommonConst.TREE_HOLE_COUNT);
        } else {
            treeHoles = new LambdaQueryChainWrapper<>(treeHoleMapper).list();
        }

        treeHoles.forEach(treeHole -> {
            if (!StringUtils.hasText(treeHole.getAvatar())) {
                treeHole.setAvatar(PoetryUtil.getRandomAvatar(treeHole.getId().toString()));
            }
        });
        return PoetryResult.success(treeHoles);
    }
}
