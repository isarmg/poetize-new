package com.ld.poetry.utils.storage;

import com.ld.poetry.handle.PoetryRuntimeException;
import com.ld.poetry.utils.StringUtil;
import com.ld.poetry.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
public class LocalUtil {

    @Value("${local.uploadUrl}")
    private String uploadUrl;

    @Value("${local.downloadUrl}")
    private String downloadUrl;

    public void deleteFile(List<String> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        String downloadPrefix = getDownloadPrefix();
        for (String filePath : files) {
            if (!StringUtils.hasText(filePath) || !filePath.startsWith(downloadPrefix)) {
                throw new PoetryRuntimeException("拒绝删除不属于本地下载地址的文件！");
            }

            String relativePath = filePath.substring(downloadPrefix.length());
            Path targetPath;
            try {
                targetPath = resolveUploadPath(validateRelativePath(relativePath));
            } catch (PoetryRuntimeException e) {
                throw new PoetryRuntimeException("拒绝删除非法路径对应的文件！", e);
            }

            File file = targetPath.toFile();
            if (file.exists() && file.isFile()) {
                if (file.delete()) {
                    log.info("文件删除成功：{}", filePath);
                } else {
                    throw new PoetryRuntimeException("文件删除失败！");
                }
            } else {
                log.warn("本地文件已不存在，继续清理资源记录：{}", filePath);
            }
        }
    }

    public FileVO saveFile(FileVO fileVO) {
        if (fileVO == null || fileVO.getFile() == null || fileVO.getFile().isEmpty()) {
            throw new PoetryRuntimeException("上传文件不能为空！");
        }
        String path = validateRelativePath(fileVO.getRelativePath());
        Path absolutePath = resolveUploadPath(path);
        boolean created = false;
        try {
            Files.createDirectories(absolutePath.getParent());
            try {
                Files.createFile(absolutePath);
                created = true;
            } catch (FileAlreadyExistsException e) {
                throw new PoetryRuntimeException("文件已存在！");
            }
            fileVO.getFile().transferTo(absolutePath.toFile());
            FileVO result = new FileVO();
            result.setAbsolutePath(absolutePath.toString());
            result.setVisitPath(getDownloadPrefix() + path);
            return result;
        } catch (IOException e) {
            log.error("文件上传失败：", e);
            if (created) {
                deleteCreatedFile(absolutePath);
            }
            throw new PoetryRuntimeException("文件上传失败！");
        } catch (RuntimeException e) {
            if (created) {
                deleteCreatedFile(absolutePath);
            }
            if (e instanceof PoetryRuntimeException poetryRuntimeException) {
                throw poetryRuntimeException;
            }
            log.error("文件上传失败：", e);
            throw new PoetryRuntimeException("文件上传失败！");
        }
    }

    private void deleteCreatedFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupException) {
            log.error("上传失败后的文件清理失败：{}", path, cleanupException);
        }
    }

    private String validateRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)
                || relativePath.startsWith("/")
                || relativePath.endsWith("/")
                || relativePath.contains("\\")) {
            throw new PoetryRuntimeException("文件路径不合法！");
        }

        String[] segments = relativePath.split("/", -1);
        if (segments.length > 5) {
            throw new PoetryRuntimeException("文件路径不合法！");
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (!StringUtil.isValidDirectoryName(segments[i])) {
                throw new PoetryRuntimeException("文件路径不合法！");
            }
        }
        if (!StringUtil.isValidFileName(segments[segments.length - 1])) {
            throw new PoetryRuntimeException("文件路径不合法！");
        }
        return relativePath;
    }

    private Path resolveUploadPath(String relativePath) {
        if (!StringUtils.hasText(uploadUrl)) {
            throw new PoetryRuntimeException("本地上传目录未配置！");
        }

        try {
            Path root = Path.of(uploadUrl).toAbsolutePath().normalize();
            Path target = root.resolve(relativePath).normalize();
            if (target.equals(root) || !target.startsWith(root)) {
                throw new PoetryRuntimeException("文件路径不合法！");
            }
            return target;
        } catch (InvalidPathException e) {
            throw new PoetryRuntimeException("文件路径不合法！");
        }
    }

    private String getDownloadPrefix() {
        if (!StringUtils.hasText(downloadUrl)) {
            throw new PoetryRuntimeException("本地下载地址未配置！");
        }
        return downloadUrl.endsWith("/") ? downloadUrl : downloadUrl + "/";
    }
}
