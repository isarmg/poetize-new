package com.ld.poetry.controller;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.*;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.im.websocket.TioUtil;
import com.ld.poetry.im.websocket.TioWebsocketStarter;
import com.ld.poetry.service.UserService;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.utils.cache.PoetryCache;
import com.ld.poetry.vo.BaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.tio.core.Tio;

/**
 * <p>
 * 后台用户 前端控制器
 * </p>
 *
 * @author sara
 * @since 2021-08-13
 */
@RestController
@RequestMapping("/admin")
public class AdminUserController {

    @Autowired
    private UserService userService;

    /**
     * 查询用户
     */
    @PostMapping("/user/list")
    @LoginCheck(0)
    public PoetryResult<Page> listUser(@RequestBody BaseRequestVO baseRequestVO) {
        return userService.listUser(baseRequestVO);
    }

    /**
     * 修改用户状态
     * <p>
     * flag = true：解禁
     * flag = false：封禁
     */
    @GetMapping("/user/changeUserStatus")
    @LoginCheck(0)
    public PoetryResult changeUserStatus(@RequestParam("userId") Integer userId, @RequestParam("flag") Boolean flag) {
        if (userId == null || flag == null) {
            return PoetryResult.fail("参数错误！");
        }
        if (userId.equals(PoetryUtil.getAdminUser().getId())) {
            return PoetryResult.fail("站长状态不能修改！");
        }

        LambdaUpdateChainWrapper<User> updateChainWrapper = userService.lambdaUpdate().eq(User::getId, userId);
        if (flag) {
            updateChainWrapper.eq(User::getUserStatus, PoetryEnum.STATUS_DISABLE.getCode()).set(User::getUserStatus, PoetryEnum.STATUS_ENABLE.getCode()).update();
        } else {
            updateChainWrapper.eq(User::getUserStatus, PoetryEnum.STATUS_ENABLE.getCode()).set(User::getUserStatus, PoetryEnum.STATUS_DISABLE.getCode()).update();
        }
        logout(userId);
        return PoetryResult.success();
    }

    /**
     * 修改用户赞赏
     */
    @GetMapping("/user/changeUserAdmire")
    @LoginCheck(0)
    public PoetryResult changeUserAdmire(@RequestParam("userId") Integer userId, @RequestParam("admire") String admire) {
        if (userId == null) {
            return PoetryResult.fail("用户 ID 不能为空！");
        }
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAdmire, admire)
                .update();
        PoetryCache.remove(CommonConst.ADMIRE);
        return PoetryResult.success();
    }

    /**
     * 修改用户类型
     */
    @GetMapping("/user/changeUserType")
    @LoginCheck(0)
    public PoetryResult changeUserType(@RequestParam("userId") Integer userId, @RequestParam("userType") Integer userType) {
        if (userId == null || userType == null) {
            return PoetryResult.fail("参数错误！");
        }
        if (userId.equals(PoetryUtil.getAdminUser().getId())) {
            return PoetryResult.fail("站长类型不能修改！");
        }

        // 0 是唯一站长类型，只能由部署初始化流程维护，后台接口不得授予。
        if (userType != 1 && userType != 2) {
            return PoetryResult.fail("用户类型只能是管理员或普通用户！");
        }
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .ne(User::getUserType, PoetryEnum.USER_TYPE_ADMIN.getCode())
                .set(User::getUserType, userType)
                .update();
        if (!updated) {
            return PoetryResult.fail("用户不存在或类型不能修改！");
        }

        logout(userId);
        return PoetryResult.success();
    }

    private void logout(Integer userId) {
        if (PoetryCache.get(CommonConst.ADMIN_TOKEN + userId) != null) {
            String token = (String) PoetryCache.get(CommonConst.ADMIN_TOKEN + userId);
            PoetryCache.remove(CommonConst.ADMIN_TOKEN + userId);
            PoetryCache.remove(token);
        }

        if (PoetryCache.get(CommonConst.USER_TOKEN + userId) != null) {
            String token = (String) PoetryCache.get(CommonConst.USER_TOKEN + userId);
            PoetryCache.remove(CommonConst.USER_TOKEN + userId);
            PoetryCache.remove(token);
        }
        TioWebsocketStarter tioWebsocketStarter = TioUtil.getTio();
        if (tioWebsocketStarter != null) {
            Tio.removeUser(tioWebsocketStarter.getServerTioConfig(), String.valueOf(userId), "remove user");
        }

    }
}
