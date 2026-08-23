package com.ld.poetry.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.Resource;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.service.ResourceService;
import com.ld.poetry.utils.storage.LocalUtil;
import com.ld.poetry.utils.storage.UploadSecurityValidator;
import com.ld.poetry.utils.*;
import com.ld.poetry.vo.BaseRequestVO;
import com.ld.poetry.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 资源信息 前端控制器
 * </p>
 *
 * @author sara
 * @since 2022-03-06
 */
@RestController
@RequestMapping("/resource")
@Slf4j
public class ResourceController {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private LocalUtil localUtil;

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    @LoginCheck
    public PoetryResult<String> upload(@RequestParam("file") MultipartFile file, FileVO fileVO) {
        if (file == null || fileVO == null || !StringUtils.hasText(fileVO.getType())
                || !StringUtils.hasText(fileVO.getRelativePath())) {
            return PoetryResult.fail("文件和资源类型和资源路径不能为空！");
        }
        UploadSecurityValidator.validateUpload(fileVO.getType(), fileVO.getRelativePath(), file);
        if (UploadSecurityValidator.isAssetsType(fileVO.getType())
                && !PoetryUtil.getAdminUser().getId().equals(PoetryUtil.getUserId())) {
            return PoetryResult.fail("公共静态资源仅允许站长上传！");
        }

        fileVO.setFile(file);
        FileVO result = localUtil.saveFile(fileVO);

        try {
            Resource re = new Resource();
            re.setPath(result.getVisitPath());
            re.setType(fileVO.getType());
            re.setSize(Math.toIntExact(file.getSize()));
            re.setMimeType(file.getContentType());
            re.setStoreType(CommonConst.STORE_TYPE_LOCAL);
            String originalName = StringUtils.hasText(fileVO.getOriginalName())
                    ? fileVO.getOriginalName() : file.getOriginalFilename();
            if (StringUtils.hasText(originalName)) {
                originalName = StringUtil.removeHtml(originalName);
                re.setOriginalName(originalName.substring(0, Math.min(originalName.length(), 128)));
            }
            re.setUserId(PoetryUtil.getUserId());
            if (!resourceService.save(re)) {
                throw new PoetryRuntimeException("资源信息保存失败！");
            }
        } catch (RuntimeException e) {
            try {
                localUtil.deleteFile(Collections.singletonList(result.getVisitPath()));
            } catch (RuntimeException cleanupException) {
                log.error("上传资源记录保存失败，且补偿删除文件失败：{}", result.getVisitPath(), cleanupException);
            }
            throw e;
        }
        return PoetryResult.success(result.getVisitPath());
    }

    /**
     * 删除
     */
    @PostMapping("/deleteResource")
    @LoginCheck(0)
    public PoetryResult deleteResource(@RequestParam("path") String path) {
        if (!StringUtils.hasText(path)) {
            return PoetryResult.fail("资源路径不能为空！");
        }
        Resource resource = resourceService.lambdaQuery().select(Resource::getStoreType).eq(Resource::getPath, path).one();
        if (resource == null) {
            return PoetryResult.fail("文件不存在：" + path);
        }
        if (!CommonConst.STORE_TYPE_LOCAL.equals(resource.getStoreType())) {
            return PoetryResult.fail("仅支持删除服务器本地资源！");
        }

        localUtil.deleteFile(Collections.singletonList(path));
        if (!resourceService.lambdaUpdate().eq(Resource::getPath, path).remove()) {
            return PoetryResult.fail("文件已删除，但资源记录清理失败，请重试！");
        }
        return PoetryResult.success();
    }

    /**
     * 查询表情包
     */
    @GetMapping("/getImageList")
    @LoginCheck
    public PoetryResult<List<String>> getImageList() {
        List<Resource> list = resourceService.lambdaQuery().select(Resource::getPath)
                .eq(Resource::getType, CommonConst.PATH_TYPE_INTERNET_MEME)
                .eq(Resource::getStatus, PoetryEnum.STATUS_ENABLE.getCode())
                .eq(Resource::getUserId, PoetryUtil.getAdminUser().getId())
                .orderByDesc(Resource::getCreateTime)
                .list();
        List<String> paths = list.stream().map(Resource::getPath).collect(Collectors.toList());
        return PoetryResult.success(paths);
    }

    /**
     * 查询资源
     */
    @PostMapping("/listResource")
    @LoginCheck(0)
    public PoetryResult<Page> listResource(@RequestBody BaseRequestVO baseRequestVO) {
        if (baseRequestVO == null) {
            return PoetryResult.fail("分页参数不能为空！");
        }
        resourceService.lambdaQuery()
                .eq(StringUtils.hasText(baseRequestVO.getResourceType()), Resource::getType, baseRequestVO.getResourceType())
                .orderByDesc(Resource::getCreateTime).page(baseRequestVO);
        return PoetryResult.success(baseRequestVO);
    }

    /**
     * 修改资源状态
     */
    @GetMapping("/changeResourceStatus")
    @LoginCheck(0)
    public PoetryResult changeResourceStatus(@RequestParam("id") Integer id, @RequestParam("flag") Boolean flag) {
        if (id == null || flag == null) {
            return PoetryResult.fail("资源 ID 和状态不能为空！");
        }
        if (!resourceService.lambdaUpdate().eq(Resource::getId, id).set(Resource::getStatus, flag).update()) {
            return PoetryResult.fail("资源不存在或状态修改失败！");
        }
        return PoetryResult.success();
    }
}
