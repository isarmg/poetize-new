package com.ld.poetry.utils.storage;

import com.ld.poetry.entity.Resource;
import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.service.ResourceService;
import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.utils.PoetryUtil;
import com.ld.poetry.vo.FileVO;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.model.BatchStatus;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "qiniu.enable", havingValue = "true")
public class QiniuUtil implements StoreService {

    /**
     * 七牛云
     */
    @Value("${qiniu.accessKey}")
    private String accessKey;

    @Value("${qiniu.secretKey}")
    private String secretKey;

    @Value("${qiniu.bucket}")
    private String bucket;

    @Value("${qiniu.downloadUrl}")
    private String downloadUrl;

    private static final long EXPIRE_SECONDS = 60L;

    @Autowired
    private ResourceService resourceService;

    public String getToken(String key) {
        UploadSecurityValidator.validateQiniuKey(key);
        StringMap putPolicy = new StringMap();
        putPolicy.put("fsizeLimit", UploadSecurityValidator.sizeLimitForQiniuKey(key));
        putPolicy.put("detectMime", 1);
        putPolicy.put("insertOnly", 1);
        Auth auth = Auth.create(accessKey, secretKey);
        return auth.uploadToken(bucket, key, EXPIRE_SECONDS, putPolicy);
    }

    @Override
    public void deleteFile(List<String> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        //构造一个带指定 Region 对象的配置类
        Configuration cfg = Configuration.create(Region.autoRegion());
        Auth auth = Auth.create(accessKey, secretKey);
        BucketManager bucketManager = new BucketManager(auth, cfg);
        try {
            //单次批量请求的文件数量不得超过1000
            String[] keyList = files.stream().map(this::validateAndExtractKey).toArray(String[]::new);
            BucketManager.BatchOperations batchOperations = new BucketManager.BatchOperations();
            batchOperations.addDeleteOp(bucket, keyList);
            Response response = bucketManager.batch(batchOperations);
            BatchStatus[] batchStatusList = response.jsonToObject(BatchStatus[].class);
            boolean hasFailure = batchStatusList == null || batchStatusList.length != keyList.length;
            if (hasFailure) {
                throw new PoetryRuntimeException("七牛云文件删除结果不完整！");
            }
            for (int i = 0; i < keyList.length; i++) {
                BatchStatus status = batchStatusList[i];
                String key = keyList[i];
                if (status == null) {
                    hasFailure = true;
                    log.error("文件删除失败：{}，原因：七牛云未返回状态", key);
                    continue;
                }
                if (status.code == 200) {
                    log.info("文件删除成功：" + key);
                } else if (status.code == 612) {
                    log.warn("七牛云文件已不存在，继续清理资源记录：{}", key);
                } else {
                    hasFailure = true;
                    String error = status.data == null ? "未知错误" : status.data.error;
                    log.error("文件删除失败：{}，原因：{}", key, error);
                }
            }
            if (hasFailure) {
                throw new PoetryRuntimeException("部分七牛云文件删除失败！");
            }
        } catch (QiniuException ex) {
            log.error("七牛云文件删除失败", ex);
            throw new PoetryRuntimeException("七牛云文件删除失败！", ex);
        }
    }

    @Override
    public FileVO saveFile(FileVO fileVO) {
        throw new PoetryRuntimeException("七牛云存储不支持服务端直传，请使用上传凭证直传");
    }

    @Override
    public String getStoreName() {
        return StoreEnum.QINIU.getCode();
    }

    public String validateAndExtractKey(String path) {
        if (!StringUtils.hasText(downloadUrl) || !StringUtils.hasText(path)) {
            throw new PoetryRuntimeException("七牛云下载地址或资源路径未配置！");
        }
        String prefix = downloadUrl.endsWith("/") ? downloadUrl : downloadUrl + "/";
        if (!path.startsWith(prefix)) {
            throw new PoetryRuntimeException("资源路径不属于当前七牛云存储！");
        }
        String key = path.substring(prefix.length());
        UploadSecurityValidator.validateQiniuKey(key);
        return key;
    }

    public Map<String, Map<String, String>> getFileInfo(List<String> files) {
        Map<String, Map<String, String>> result = new HashMap<>();
        if (CollectionUtils.isEmpty(files) || files.size() > 1000) {
            return result;
        }
        files.forEach(UploadSecurityValidator::validateQiniuKey);

        //构造一个带指定 Region 对象的配置类
        Configuration cfg = Configuration.create(Region.autoRegion());
        Auth auth = Auth.create(accessKey, secretKey);
        BucketManager bucketManager = new BucketManager(auth, cfg);
        try {
            //单次批量请求的文件数量不得超过1000
            String[] keyList = files.toArray(new String[0]);
            BucketManager.BatchOperations batchOperations = new BucketManager.BatchOperations();
            batchOperations.addStatOps(bucket, keyList);
            Response response = bucketManager.batch(batchOperations);
            BatchStatus[] batchStatusList = response.jsonToObject(BatchStatus[].class);
            if (batchStatusList == null || batchStatusList.length != keyList.length) {
                log.error("七牛云文件信息查询结果不完整");
                return result;
            }
            for (int i = 0; i < keyList.length; i++) {
                BatchStatus status = batchStatusList[i];
                String key = keyList[i];
                if (status != null && status.code == 200 && status.data != null) {
                    //文件存在
                    Map<String, String> info = new HashMap<>();
                    info.put("size", String.valueOf(status.data.fsize));
                    info.put("mimeType", status.data.mimeType);
                    result.put(key, info);
                } else {
                    String error = status == null || status.data == null
                            ? "七牛云未返回完整状态" : status.data.error;
                    log.error("文件信息查询失败：{}，原因：{}", key, error);
                }
            }
        } catch (QiniuException ex) {
            log.error("七牛云文件信息查询失败", ex);
        }

        return result;
    }

    public void saveFileInfo() {
        List<Resource> resourceList = resourceService.lambdaQuery().select(Resource::getPath).list();
        Set<String> paths = resourceList.stream().map(Resource::getPath).collect(Collectors.toSet());
        String downloadPrefix = downloadUrl.endsWith("/") ? downloadUrl : downloadUrl + "/";

        //构造一个带指定 Region 对象的配置类
        Configuration cfg = Configuration.create(Region.autoRegion());
        Auth auth = Auth.create(accessKey, secretKey);
        BucketManager bucketManager = new BucketManager(auth, cfg);
        //文件名前缀
        String prefix = "";
        //每次迭代的长度限制，最大1000，推荐值 1000
        int limit = 1000;
        //指定目录分隔符，列出所有公共前缀（模拟列出目录效果）。缺省值为空字符串
        String delimiter = "";
        //列举空间文件列表
        BucketManager.FileListIterator fileListIterator = bucketManager.createFileListIterator(bucket, prefix, limit, delimiter);

        List<Resource> resources = new ArrayList<>();

        while (fileListIterator.hasNext()) {
            FileInfo[] items = fileListIterator.next();
            for (FileInfo item : items) {
                String path = downloadPrefix + item.key;
                if (item.fsize != 0L && !paths.contains(path)
                        && UploadSecurityValidator.isAssetsKey(item.key)) {
                    try {
                        UploadSecurityValidator.validateQiniuKey(item.key);
                        UploadSecurityValidator.validateStoredResource(
                                CommonConst.PATH_TYPE_ASSETS, path, item.mimeType, item.fsize);
                    } catch (PoetryRuntimeException e) {
                        log.warn("跳过不安全或不受支持的七牛云对象：{}", item.key);
                        continue;
                    }
                    Resource re = new Resource();
                    re.setPath(path);
                    re.setType(CommonConst.PATH_TYPE_ASSETS);
                    try {
                        re.setSize(Math.toIntExact(item.fsize));
                    } catch (ArithmeticException e) {
                        log.warn("跳过大小超出数据库范围的七牛云对象：{}", item.key);
                        continue;
                    }
                    re.setMimeType(item.mimeType);
                    re.setStoreType(StoreEnum.QINIU.getCode());
                    re.setUserId(PoetryUtil.getAdminUser().getId());
                    resources.add(re);
                }
            }
        }

        if (!CollectionUtils.isEmpty(resources)) {
            if (!resourceService.saveBatch(resources)) {
                throw new PoetryRuntimeException("七牛云资源同步记录保存失败！");
            }
            log.info("七牛云资源同步保存数量：{}", resources.size());
        }
        log.info("七牛云资源同步完成");
    }
}
