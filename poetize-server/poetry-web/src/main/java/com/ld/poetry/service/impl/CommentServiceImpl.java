package com.ld.poetry.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.dao.ArticleMapper;
import com.ld.poetry.dao.CommentMapper;
import com.ld.poetry.entity.Article;
import com.ld.poetry.entity.Comment;
import com.ld.poetry.entity.User;
import com.ld.poetry.enums.CodeMsg;
import com.ld.poetry.enums.CommentTypeEnum;
import com.ld.poetry.service.CommentService;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.utils.mail.MailSendUtil;
import com.ld.poetry.vo.BaseRequestVO;
import com.ld.poetry.vo.CommentVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 文章评论表 服务实现类
 * </p>
 *
 * @author sara
 * @since 2021-08-13
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CommonQuery commonQuery;

    @Autowired
    private MailSendUtil mailSendUtil;

    @Override
    public PoetryResult saveComment(CommentVO commentVO) {
        if (commentVO == null || commentVO.getSource() == null
                || CommentTypeEnum.getEnumByCode(commentVO.getType()) == null) {
            return PoetryResult.fail("评论来源类型不存在！");
        }
        if (!StringUtils.hasText(commentVO.getCommentContent())
                || commentVO.getCommentContent().length() > 1024
                || (commentVO.getCommentInfo() != null && commentVO.getCommentInfo().length() > 256)) {
            return PoetryResult.fail("评论内容或附加信息超过长度限制！");
        }
        Article one = null;
        if (CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode().equals(commentVO.getType())) {
            LambdaQueryChainWrapper<Article> articleWrapper = new LambdaQueryChainWrapper<>(articleMapper);
            one = articleWrapper.eq(Article::getId, commentVO.getSource()).select(Article::getUserId, Article::getArticleTitle, Article::getCommentStatus).one();

            if (one == null) {
                return PoetryResult.fail("文章不存在");
            } else {
                if (!Boolean.TRUE.equals(one.getCommentStatus())) {
                    return PoetryResult.fail("评论功能已关闭！");
                }
            }
        }


        Integer parentCommentId = CommonConst.FIRST_COMMENT;
        Integer floorCommentId = null;
        Integer parentUserId = null;
        if (commentVO.getParentCommentId() != null
                && commentVO.getParentCommentId() != CommonConst.FIRST_COMMENT) {
            if (commentVO.getFloorCommentId() == null) {
                return PoetryResult.fail("回复评论必须指定楼层评论！");
            }
            Comment target = lambdaQuery()
                    .select(Comment::getId, Comment::getUserId, Comment::getParentCommentId, Comment::getFloorCommentId)
                    .eq(Comment::getId, commentVO.getParentCommentId())
                    .eq(Comment::getSource, commentVO.getSource())
                    .eq(Comment::getType, commentVO.getType())
                    .one();
            Comment floor = lambdaQuery()
                    .select(Comment::getId)
                    .eq(Comment::getId, commentVO.getFloorCommentId())
                    .eq(Comment::getSource, commentVO.getSource())
                    .eq(Comment::getType, commentVO.getType())
                    .eq(Comment::getParentCommentId, CommonConst.FIRST_COMMENT)
                    .one();
            boolean targetInFloor = target != null && floor != null
                    && (target.getId().equals(floor.getId())
                    || floor.getId().equals(target.getFloorCommentId()));
            if (!targetInFloor) {
                return PoetryResult.fail("回复目标不属于当前评论楼层！");
            }
            parentCommentId = target.getId();
            floorCommentId = floor.getId();
            parentUserId = target.getUserId();
        }

        Comment comment = new Comment();
        comment.setSource(commentVO.getSource());
        comment.setType(commentVO.getType());
        comment.setCommentContent(commentVO.getCommentContent());
        comment.setParentCommentId(parentCommentId);
        comment.setFloorCommentId(floorCommentId);
        comment.setParentUserId(parentUserId);
        comment.setUserId(PoetryUtil.getUserId());
        if (StringUtils.hasText(commentVO.getCommentInfo())) {
            comment.setCommentInfo(commentVO.getCommentInfo());
        }
        if (!save(comment)) {
            return PoetryResult.fail("评论保存失败！");
        }
        commentVO.setParentCommentId(parentCommentId);
        commentVO.setFloorCommentId(floorCommentId);
        commentVO.setParentUserId(parentUserId);
        invalidateCount(comment);

        try {
            mailSendUtil.sendCommentMail(commentVO, one, this);
        } catch (Exception e) {
            log.error("发送评论邮件失败：", e);
        }

        return PoetryResult.success();
    }

    @Override
    public PoetryResult deleteComment(Integer id) {
        Integer userId = PoetryUtil.getUserId();
        Comment comment = lambdaQuery()
                .select(Comment::getId, Comment::getSource, Comment::getType, Comment::getParentCommentId)
                .eq(Comment::getId, id)
                .eq(Comment::getUserId, userId)
                .one();
        if (comment == null) {
            return PoetryResult.fail("评论不存在或无删除权限！");
        }
        boolean removed;
        if (comment.getParentCommentId() == null
                || comment.getParentCommentId() == CommonConst.FIRST_COMMENT) {
            removed = lambdaUpdate()
                    .eq(Comment::getSource, comment.getSource())
                    .eq(Comment::getType, comment.getType())
                    .and(wrapper -> wrapper.eq(Comment::getId, comment.getId())
                            .or().eq(Comment::getFloorCommentId, comment.getId()))
                    .remove();
        } else {
            removed = removeById(comment.getId());
        }
        if (!removed) {
            return PoetryResult.fail("评论删除失败！");
        }
        invalidateCount(comment);
        return PoetryResult.success();
    }

    @Override
    public PoetryResult<BaseRequestVO> listComment(BaseRequestVO baseRequestVO) {
        if (baseRequestVO.getSource() == null || !StringUtils.hasText(baseRequestVO.getCommentType())) {
            return PoetryResult.fail(CodeMsg.PARAMETER_ERROR);
        }

        if (CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode().equals(baseRequestVO.getCommentType())) {
            LambdaQueryChainWrapper<Article> articleWrapper = new LambdaQueryChainWrapper<>(articleMapper);
            Article one = articleWrapper.eq(Article::getId, baseRequestVO.getSource()).select(Article::getCommentStatus).one();

            if (one == null) {
                return PoetryResult.fail("文章不存在！");
            }
            if (!Boolean.TRUE.equals(one.getCommentStatus())) {
                return PoetryResult.fail("评论功能已关闭！");
            }
        }


        if (baseRequestVO.getFloorCommentId() == null) {
            lambdaQuery().eq(Comment::getSource, baseRequestVO.getSource()).eq(Comment::getType, baseRequestVO.getCommentType()).eq(Comment::getParentCommentId, CommonConst.FIRST_COMMENT).orderByDesc(Comment::getCreateTime).page(baseRequestVO);
            List<Comment> comments = baseRequestVO.getRecords();
            if (CollectionUtils.isEmpty(comments)) {
                return PoetryResult.success(baseRequestVO);
            }
            List<CommentVO> commentVOs = comments.stream().map(c -> {
                CommentVO commentVO = buildCommentVO(c);
                Page page = new Page(1, 5);
                lambdaQuery().eq(Comment::getSource, baseRequestVO.getSource()).eq(Comment::getType, baseRequestVO.getCommentType()).eq(Comment::getFloorCommentId, c.getId()).orderByAsc(Comment::getCreateTime).page(page);
                List<Comment> childComments = page.getRecords();
                if (childComments != null) {
                    List<CommentVO> ccVO = childComments.stream().map(cc -> buildCommentVO(cc)).collect(Collectors.toList());
                    page.setRecords(ccVO);
                }
                commentVO.setChildComments(page);
                return commentVO;
            }).collect(Collectors.toList());
            baseRequestVO.setRecords(commentVOs);
        } else {
            lambdaQuery().eq(Comment::getSource, baseRequestVO.getSource()).eq(Comment::getType, baseRequestVO.getCommentType()).eq(Comment::getFloorCommentId, baseRequestVO.getFloorCommentId()).orderByAsc(Comment::getCreateTime).page(baseRequestVO);
            List<Comment> childComments = baseRequestVO.getRecords();
            if (CollectionUtils.isEmpty(childComments)) {
                return PoetryResult.success(baseRequestVO);
            }
            List<CommentVO> ccVO = childComments.stream().map(cc -> buildCommentVO(cc)).collect(Collectors.toList());
            baseRequestVO.setRecords(ccVO);
        }
        return PoetryResult.success(baseRequestVO);
    }

    @Override
    public PoetryResult<Page> listAdminComment(BaseRequestVO baseRequestVO, Boolean isBoss) {
        LambdaQueryChainWrapper<Comment> wrapper = lambdaQuery();
        if (isBoss) {
            if (baseRequestVO.getSource() != null) {
                wrapper.eq(Comment::getSource, baseRequestVO.getSource());
            }
            if (StringUtils.hasText(baseRequestVO.getCommentType())) {
                wrapper.eq(Comment::getType, baseRequestVO.getCommentType());
            }
            wrapper.orderByDesc(Comment::getCreateTime).page(baseRequestVO);
        } else {
            List<Integer> userArticleIds = commonQuery.getUserArticleIds(PoetryUtil.getUserId());
            if (CollectionUtils.isEmpty(userArticleIds)) {
                baseRequestVO.setTotal(0);
                baseRequestVO.setRecords(new ArrayList());
            } else {
                if (baseRequestVO.getSource() != null) {
                    wrapper.eq(Comment::getSource, baseRequestVO.getSource()).eq(Comment::getType, CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode());
                } else {
                    wrapper.eq(Comment::getType, CommentTypeEnum.COMMENT_TYPE_ARTICLE.getCode()).in(Comment::getSource, userArticleIds);
                }
                wrapper.orderByDesc(Comment::getCreateTime).page(baseRequestVO);
            }
        }
        return PoetryResult.success(baseRequestVO);
    }

    private CommentVO buildCommentVO(Comment c) {
        CommentVO commentVO = new CommentVO();
        BeanUtils.copyProperties(c, commentVO);

        User user = commonQuery.getUser(commentVO.getUserId());
        if (user != null) {
            commentVO.setAvatar(user.getAvatar());
            commentVO.setUsername(user.getUsername());
        }

        if (!StringUtils.hasText(commentVO.getUsername())) {
            commentVO.setUsername(PoetryUtil.getRandomName(commentVO.getUserId().toString()));
        }

        if (commentVO.getParentUserId() != null) {
            User u = commonQuery.getUser(commentVO.getParentUserId());
            if (u != null) {
                commentVO.setParentUsername(u.getUsername());
            }
            if (!StringUtils.hasText(commentVO.getParentUsername())) {
                commentVO.setParentUsername(PoetryUtil.getRandomName(commentVO.getParentUserId().toString()));
            }
        }
        return commentVO;
    }

    private void invalidateCount(Comment comment) {
        PoetryCache.remove(CommonConst.COMMENT_COUNT_CACHE
                + comment.getSource() + "_" + comment.getType());
    }
}
