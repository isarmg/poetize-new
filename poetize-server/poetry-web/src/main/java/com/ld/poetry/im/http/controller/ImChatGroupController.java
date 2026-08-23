package com.ld.poetry.im.http.controller;


import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.aop.SaveCheck;
import com.ld.poetry.entity.User;
import com.ld.poetry.im.http.entity.ImChatGroup;
import com.ld.poetry.im.http.entity.ImChatGroupUser;
import com.ld.poetry.im.http.entity.ImChatUserGroupMessage;
import com.ld.poetry.im.http.service.ImChatGroupService;
import com.ld.poetry.im.http.service.ImChatGroupUserService;
import com.ld.poetry.im.http.service.ImChatUserGroupMessageService;
import com.ld.poetry.im.http.vo.GroupVO;
import com.ld.poetry.im.websocket.ImConfigConst;
import com.ld.poetry.im.websocket.TioUtil;
import com.ld.poetry.im.websocket.TioWebsocketStarter;
import com.ld.poetry.enums.CodeMsg;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.StringUtil;
import com.ld.poetry.vo.BaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.tio.core.Tio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 聊天群 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-12-02
 */
@RestController
@RequestMapping("/imChatGroup")
public class ImChatGroupController {

    @Autowired
    private ImChatGroupService imChatGroupService;

    @Autowired
    private ImChatGroupUserService imChatGroupUserService;

    @Autowired
    private ImChatUserGroupMessageService imChatUserGroupMessageService;

    /**
     * 创建群组
     */
    @PostMapping("/creatGroupCommon")
    @LoginCheck
    @SaveCheck
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult creatGroup(@RequestBody ImChatGroup imChatGroup) {
        if (imChatGroup == null) {
            return PoetryResult.fail(CodeMsg.PARAMETER_ERROR);
        }
        Integer userId = PoetryUtil.getUserId();
        ImChatGroup group = buildNewGroup(imChatGroup, ImConfigConst.GROUP_COMMON, userId);
        if (!imChatGroupService.save(group)) {
            throw new PoetryRuntimeException("群组创建失败！");
        }

        ImChatGroupUser imChatGroupUser = new ImChatGroupUser();
        imChatGroupUser.setGroupId(group.getId());
        imChatGroupUser.setUserId(userId);
        imChatGroupUser.setAdminFlag(ImConfigConst.ADMIN_FLAG_TRUE);
        imChatGroupUser.setUserStatus(ImConfigConst.GROUP_USER_STATUS_PASS);
        if (!imChatGroupUserService.save(imChatGroupUser)) {
            throw new PoetryRuntimeException("群主成员关系保存失败！");
        }

        TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
        if (tioWebsocketStarter != null) {
            Tio.bindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), String.valueOf(group.getId()));
        }

        return PoetryResult.success();
    }

    /**
     * 创建话题
     */
    @PostMapping("/creatGroupTopic")
    @LoginCheck(0)
    public PoetryResult creatGroupTopic(@RequestBody ImChatGroup imChatGroup) {
        if (imChatGroup == null) {
            return PoetryResult.fail(CodeMsg.PARAMETER_ERROR);
        }
        Integer userId = PoetryUtil.getUserId();
        ImChatGroup group = buildNewGroup(imChatGroup, ImConfigConst.GROUP_TOPIC, userId);
        group.setInType(ImConfigConst.IN_TYPE_FALSE);
        if (!imChatGroupService.save(group)) {
            return PoetryResult.fail("话题创建失败！");
        }

        return PoetryResult.success();
    }


    /**
     * 更新组
     * <p>
     * 只有群主才能修改组
     */
    @PostMapping("/updateGroup")
    @LoginCheck
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult updateGroup(@RequestBody ImChatGroup imChatGroup) {
        if (imChatGroup == null || imChatGroup.getId() == null) {
            return PoetryResult.fail("群组 ID 不能为空！");
        }
        Integer currentUserId = PoetryUtil.getUserId();
        ImChatGroup currentGroup = imChatGroupService.lambdaQuery()
                .eq(ImChatGroup::getId, imChatGroup.getId())
                .eq(ImChatGroup::getMasterUserId, currentUserId)
                .one();
        if (currentGroup == null) {
            return PoetryResult.fail("群组不存在或无修改权限！");
        }

        boolean transferMaster = imChatGroup.getMasterUserId() != null
                && !imChatGroup.getMasterUserId().equals(currentUserId);
        if (transferMaster) {
            if (!Integer.valueOf(ImConfigConst.GROUP_COMMON).equals(currentGroup.getGroupType())) {
                return PoetryResult.fail("话题不支持转让！");
            }
            long targetCount = imChatGroupUserService.lambdaQuery()
                    .eq(ImChatGroupUser::getGroupId, imChatGroup.getId())
                    .eq(ImChatGroupUser::getUserId, imChatGroup.getMasterUserId())
                    .in(ImChatGroupUser::getUserStatus,
                            ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE)
                    .count();
            if (targetCount < 1) {
                return PoetryResult.fail("新群主不是本群有效成员！");
            }
        }

        String groupName = normalizeGroupText(imChatGroup.getGroupName(), 32, "群名称", false);
        String avatar = normalizeGroupText(imChatGroup.getAvatar(), 256, "群头像", false);
        String introduction = normalizeGroupText(imChatGroup.getIntroduction(), 128, "群简介", false);
        String notice = normalizeGroupText(imChatGroup.getNotice(), 1024, "群公告", false);
        boolean hasUpdate = groupName != null
                || avatar != null
                || introduction != null
                || notice != null
                || imChatGroup.getInType() != null
                || transferMaster;
        if (!hasUpdate) {
            return PoetryResult.fail("没有可更新的内容！");
        }

        LambdaUpdateChainWrapper<ImChatGroup> lambdaUpdate = imChatGroupService.lambdaUpdate();
        lambdaUpdate.eq(ImChatGroup::getId, imChatGroup.getId());
        lambdaUpdate.eq(ImChatGroup::getMasterUserId, currentUserId);
        if (groupName != null) {
            lambdaUpdate.set(ImChatGroup::getGroupName, groupName);
        }
        if (avatar != null) {
            lambdaUpdate.set(ImChatGroup::getAvatar, avatar);
        }
        if (introduction != null) {
            lambdaUpdate.set(ImChatGroup::getIntroduction, introduction);
        }
        // 群通知
        if (notice != null) {
            lambdaUpdate.set(ImChatGroup::getNotice, notice);
        }
        // 修改进入方式
        if (imChatGroup.getInType() != null) {
            lambdaUpdate.set(ImChatGroup::getInType, imChatGroup.getInType());
        }
        // 转让群
        if (transferMaster) {
            lambdaUpdate.set(ImChatGroup::getMasterUserId, imChatGroup.getMasterUserId());
        }
        boolean isSuccess = lambdaUpdate.update();
        if (!isSuccess) {
            throw new PoetryRuntimeException("群组更新失败！");
        }
        if (transferMaster) {
            boolean adminUpdated = imChatGroupUserService.lambdaUpdate()
                    .eq(ImChatGroupUser::getGroupId, imChatGroup.getId())
                    .eq(ImChatGroupUser::getUserId, imChatGroup.getMasterUserId())
                    .in(ImChatGroupUser::getUserStatus,
                            ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE)
                    .set(ImChatGroupUser::getAdminFlag, ImConfigConst.ADMIN_FLAG_TRUE)
                    .update();
            if (!adminUpdated) {
                throw new PoetryRuntimeException("新群主管理员状态更新失败！");
            }
        }
        if (isSuccess && notice != null) {
            // todo 发送群公告
        }
        return PoetryResult.success();
    }

    private ImChatGroup buildNewGroup(ImChatGroup request, int groupType, Integer masterUserId) {
        ImChatGroup group = new ImChatGroup();
        group.setGroupName(normalizeGroupText(request.getGroupName(), 32, "群名称", true));
        group.setAvatar(normalizeGroupText(request.getAvatar(), 256, "群头像", false));
        group.setIntroduction(normalizeGroupText(request.getIntroduction(), 128, "群简介", false));
        group.setNotice(normalizeGroupText(request.getNotice(), 1024, "群公告", false));
        group.setInType(request.getInType());
        group.setGroupType(groupType);
        group.setMasterUserId(masterUserId);
        return group;
    }

    private String normalizeGroupText(String value, int maxLength, String fieldName, boolean required) {
        if (!StringUtils.hasText(value)) {
            if (required) {
                throw new PoetryRuntimeException(fieldName + "不能为空！");
            }
            return null;
        }
        String normalized = StringUtil.removeHtml(value.trim());
        if (normalized.length() > maxLength) {
            throw new PoetryRuntimeException(fieldName + "不能超过 " + maxLength + " 个字符！");
        }
        return normalized;
    }

    /**
     * 解散群
     */
    @GetMapping("/deleteGroup")
    @LoginCheck
    @Transactional(rollbackFor = Exception.class)
    public PoetryResult deleteGroup(@RequestParam("id") Integer id) {
        User currentUser = PoetryUtil.getCurrentUser();
        boolean isSuccess;
        if (currentUser.getUserType().intValue() == PoetryEnum.USER_TYPE_ADMIN.getCode()) {
            isSuccess = imChatGroupService.removeById(id);
        } else {
            LambdaUpdateChainWrapper<ImChatGroup> lambdaUpdate = imChatGroupService.lambdaUpdate();
            lambdaUpdate.eq(ImChatGroup::getId, id);
            lambdaUpdate.eq(ImChatGroup::getMasterUserId, PoetryUtil.getUserId());
            isSuccess = lambdaUpdate.remove();
        }
        if (isSuccess) {
            // 删除用户
            LambdaUpdateChainWrapper<ImChatGroupUser> lambdaUpdate = imChatGroupUserService.lambdaUpdate();
            lambdaUpdate.eq(ImChatGroupUser::getGroupId, id).remove();
            // 删除聊天记录
            LambdaUpdateChainWrapper<ImChatUserGroupMessage> messageLambdaUpdateChainWrapper = imChatUserGroupMessageService.lambdaUpdate();
            messageLambdaUpdateChainWrapper.eq(ImChatUserGroupMessage::getGroupId, id).remove();

            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.removeGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(id), "remove group");
            }
        }
        return PoetryResult.success();
    }

    /**
     * 管理员查询所有群
     */
    @PostMapping("/listGroupForAdmin")
    @LoginCheck(0)
    public PoetryResult<BaseRequestVO> listGroupForAdmin(@RequestBody BaseRequestVO baseRequestVO) {
        LambdaQueryChainWrapper<ImChatGroup> lambdaQuery = imChatGroupService.lambdaQuery();
        lambdaQuery.orderByDesc(ImChatGroup::getCreateTime).page(baseRequestVO);
        return PoetryResult.success(baseRequestVO);
    }

    /**
     * 加入话题
     */
    @GetMapping("/addGroupTopic")
    @LoginCheck
    public PoetryResult addGroupTopic(@RequestParam("id") Integer id) {
        LambdaQueryChainWrapper<ImChatGroup> lambdaQuery = imChatGroupService.lambdaQuery();
        long count = lambdaQuery.eq(ImChatGroup::getId, id)
                .eq(ImChatGroup::getGroupType, ImConfigConst.GROUP_TOPIC).count();
        if (count == 1) {
            TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
            if (tioWebsocketStarter != null) {
                Tio.bindGroup(tioWebsocketStarter.getServerTioConfig(), String.valueOf(PoetryUtil.getUserId()), String.valueOf(id));
            }
        }
        return PoetryResult.success();
    }

    /**
     * 用户查询所有群
     * <p>
     * 只查询审核通过和禁言的群
     */
    @GetMapping("/listGroup")
    @LoginCheck
    public PoetryResult<List<GroupVO>> listGroup() {
        Integer userId = PoetryUtil.getUserId();
        LambdaQueryChainWrapper<ImChatGroupUser> lambdaQuery = imChatGroupUserService.lambdaQuery();
        lambdaQuery.eq(ImChatGroupUser::getUserId, userId);
        lambdaQuery.in(ImChatGroupUser::getUserStatus, ImConfigConst.GROUP_USER_STATUS_PASS, ImConfigConst.GROUP_USER_STATUS_SILENCE);
        List<ImChatGroupUser> groupUsers = lambdaQuery.list();

        Map<Integer, ImChatGroupUser> groupUserMap = groupUsers.stream().collect(Collectors.toMap(ImChatGroupUser::getGroupId, Function.identity()));
        LambdaQueryChainWrapper<ImChatGroup> wrapper = imChatGroupService.lambdaQuery();
        wrapper.eq(ImChatGroup::getGroupType, ImConfigConst.GROUP_TOPIC);
        if (!CollectionUtils.isEmpty(groupUserMap.keySet())) {
            wrapper.or(w -> w.in(ImChatGroup::getId, groupUserMap.keySet())
                    .eq(ImChatGroup::getGroupType, ImConfigConst.GROUP_COMMON));
        }
        List<ImChatGroup> imChatGroups = wrapper.list();
        List<GroupVO> groupVOS = imChatGroups.stream().map(imChatGroup -> {
            ImChatGroupUser imChatGroupUser = groupUserMap.get(imChatGroup.getId());
            if (imChatGroupUser == null) {
                imChatGroupUser = new ImChatGroupUser();
                imChatGroupUser.setUserStatus(ImConfigConst.GROUP_USER_STATUS_PASS);
                imChatGroupUser.setCreateTime(LocalDateTime.now());
                imChatGroupUser.setUserId(userId);
                imChatGroupUser.setAdminFlag(userId.intValue() == PoetryUtil.getAdminUser().getId().intValue());
            }
            return getGroupVO(imChatGroup, imChatGroupUser);
        }).collect(Collectors.toList());
        return PoetryResult.success(groupVOS);
    }

    private GroupVO getGroupVO(ImChatGroup imChatGroup, ImChatGroupUser imChatGroupUser) {
        GroupVO groupVO = new GroupVO();
        groupVO.setGroupName(imChatGroup.getGroupName());
        groupVO.setAvatar(imChatGroup.getAvatar());
        groupVO.setIntroduction(imChatGroup.getIntroduction());
        groupVO.setNotice(imChatGroup.getNotice());
        groupVO.setInType(imChatGroup.getInType());
        groupVO.setGroupType(imChatGroup.getGroupType());
        groupVO.setId(imChatGroup.getId());
        groupVO.setCreateTime(imChatGroupUser.getCreateTime());
        groupVO.setUserStatus(imChatGroupUser.getUserStatus());
        groupVO.setAdminFlag(imChatGroupUser.getAdminFlag());
        groupVO.setMasterFlag(imChatGroup.getMasterUserId().intValue() == imChatGroupUser.getUserId().intValue());
        return groupVO;
    }
}
