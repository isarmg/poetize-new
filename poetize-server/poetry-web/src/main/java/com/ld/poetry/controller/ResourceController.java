package com.ld.poetry.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ld.poetry.aop.LoginCheck;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.entity.Resource;
import com.ld.poetry.enums.PoetryEnum;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.service.ResourceService;
import com.ld.poetry.utils.storage.StoreService;
import com.ld.poetry.utils.storage.QiniuUtil;
import com.ld.poetry.utils.storage.UploadSecurityValidator;
import com.ld.poetry.utils.*;
import com.ld.poetry.utils.storage.FileStorageService;
import com.ld.poetry.vo.BaseRequestVO;
import com.ld.poetry.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private FileStorageService fileStorageService;

    /**
     * 保存
     */
    @PostMapping("/saveResource")
    @LoginCheck
    public PoetryResult saveResource(@RequestBody Resource resource) {
        if (resource == null || !StringUtils.hasText(resource.getType()) || !StringUtils.hasText(resource.getPath())) {
            return PoetryResult.fail("资源类型和资源路径不能为空！");
        }
        StoreService storeService = fileStorageService.getFileStorageByStoreType(resource.getStoreType());
        if (!(storeService instanceof QiniuUtil qiniuUtil)) {
            return PoetryResult.fail("本地资源必须通过服务端上传接口登记！");
        }
        String key = qiniuUtil.validateAndExtractKey(resource.getPath());
        if (!key.startsWith(resource.getType() + "/")) {
            return PoetryResult.fail("资源路径与资源类型不匹配！");
        }
        if (UploadSecurityValidator.isAssetsType(resource.getType())
                && !PoetryUtil.getAdminUser().getId().equals(PoetryUtil.getUserId())) {
            return PoetryResult.fail("公共静态资源仅允许站长保存！");
        }
        Map<String, String> fileInfo = qiniuUtil.getFileInfo(Collections.singletonList(key)).get(key);
        if (fileInfo == null) {
            return PoetryResult.fail("七牛云资源不存在或暂时无法验证！");
        }
        String actualMimeType = fileInfo.get("mimeType");
        long actualSize;
        try {
            actualSize = Long.parseLong(fileInfo.get("size"));
        } catch (NumberFormatException e) {
            return PoetryResult.fail("七牛云资源大小不合法！");
        }
        UploadSecurityValidator.validateStoredResource(
                resource.getType(), resource.getPath(), actualMimeType, actualSize);
        Resource re = new Resource();
        re.setPath(resource.getPath());
        re.setType(resource.getType());
        try {
            re.setSize(Math.toIntExact(actualSize));
        } catch (ArithmeticException e) {
            return PoetryResult.fail("七牛云资源大小不合法！");
        }
        if (StringUtils.hasText(resource.getOriginalName())) {
            String originalName = StringUtil.removeHtml(resource.getOriginalName().trim());
            re.setOriginalName(originalName.substring(0, Math.min(originalName.length(), 128)));
        }
        re.setMimeType(actualMimeType);
        re.setStoreType(storeService.getStoreName());
        re.setUserId(PoetryUtil.getUserId());
        if (!resourceService.save(re)) {
            return PoetryResult.fail("资源信息保存失败！");
        }
        return PoetryResult.success();
    }

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
        StoreService storeService = fileStorageService.getFileStorage(fileVO.getStoreType());
        fileVO.setStoreType(storeService.getStoreName());
        FileVO result = storeService.saveFile(fileVO);

        try {
            Resource re = new Resource();
            re.setPath(result.getVisitPath());
            re.setType(fileVO.getType());
            re.setSize(Math.toIntExact(file.getSize()));
            re.setMimeType(file.getContentType());
            re.setStoreType(fileVO.getStoreType());
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
                storeService.deleteFile(Collections.singletonList(result.getVisitPath()));
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

        StoreService storeService = fileStorageService.getFileStorageByStoreType(resource.getStoreType());
        storeService.deleteFile(Collections.singletonList(path));
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
