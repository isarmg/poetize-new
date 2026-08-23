package com.ld.poetry.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.Article;
import com.ld.poetry.entity.Comment;
import com.ld.poetry.enums.CommentTypeEnum;
import com.ld.poetry.service.ArticleService;
import com.ld.poetry.service.CommentService;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.vo.BaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * <p>
 * 后台评论 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-08-13
 */
@RestController
@RequestMapping("/admin")
public class AdminCommentController {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CommentService commentService;

    /**
     * 作者删除评论
     */
    @GetMapping("/comment/user/deleteComment")
    @LoginCheck(1)
    public PoetryResult userDeleteComment(@RequestParam("id") Integer id) {
        Comment comment = commentService.lambdaQuery()
                .select(Comment::getId, Comment::getSource, Comment::getType, Comment::getParentCommentId)
                .eq(Comment::getId, id).one();
        if (comment == null) {
            return PoetryResult.success();
        }
        if (!CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode().equals(comment.getType())) {
            return PoetryResult.fail("权限不足！");
        }
        Article one = articleService.lambdaQuery().eq(Article::getId, comment.getSource()).select(Article::getUserId).one();
        if (one == null || !Objects.equals(PoetryUtil.getUserId(), one.getUserId())) {
            return PoetryResult.fail("权限不足！");
        }
        return removeComment(comment);
    }

    /**
     * Boss删除评论
     */
    @GetMapping("/comment/boss/deleteComment")
    @LoginCheck(0)
    public PoetryResult bossDeleteComment(@RequestParam("id") Integer id) {
        Comment comment = commentService.lambdaQuery()
                .select(Comment::getId, Comment::getSource, Comment::getType, Comment::getParentCommentId)
                .eq(Comment::getId, id).one();
        if (comment == null) {
            return PoetryResult.success();
        }
        return removeComment(comment);
    }

    /**
     * 用户查询评论
     */
    @PostMapping("/comment/user/list")
    @LoginCheck(1)
    public PoetryResult<Page> listUserComment(@RequestBody BaseRequestVO baseRequestVO) {
        return commentService.listAdminComment(baseRequestVO, false);
    }

    /**
     * Boss查询评论
     */
    @PostMapping("/comment/boss/list")
    @LoginCheck(0)
    public PoetryResult<Page> listBossComment(@RequestBody BaseRequestVO baseRequestVO) {
        return commentService.listAdminComment(baseRequestVO, true);
    }

    private PoetryResult removeComment(Comment comment) {
        boolean removed;
        if (comment.getParentCommentId() == null
                || comment.getParentCommentId() == CommonConst.FIRST_COMMENT) {
            removed = commentService.lambdaUpdate()
                    .eq(Comment::getSource, comment.getSource())
                    .eq(Comment::getType, comment.getType())
                    .and(wrapper -> wrapper.eq(Comment::getId, comment.getId())
                            .or().eq(Comment::getFloorCommentId, comment.getId()))
                    .remove();
        } else {
            removed = commentService.removeById(comment.getId());
        }
        if (!removed) {
            return PoetryResult.fail("评论删除失败！");
        }
        PoetryCache.remove(CommonConst.COMMENT_COUNT_CACHE
                + comment.getSource() + "_" + comment.getType());
        return PoetryResult.success();
    }
}
