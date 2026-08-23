package com.ld.poetry.utils.storage;

import com.ld.poetry.constants.CommonConst;
import com.ld.poetry.handle.PoetryRuntimeException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class UploadSecurityValidator {
    private static final long IMAGE_SIZE_LIMIT = 10L * 1024 * 1024;
    private static final long OTHER_SIZE_LIMIT = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of(
            CommonConst.PATH_TYPE_GRAFFITI,
            CommonConst.PATH_TYPE_ARTICLE_PICTURE,
            CommonConst.PATH_TYPE_USER_AVATAR,
            CommonConst.PATH_TYPE_ARTICLE_COVER,
            CommonConst.PATH_TYPE_WEB_BACKGROUND_IMAGE,
            CommonConst.PATH_TYPE_WEB_AVATAR,
            CommonConst.PATH_TYPE_RANDOM_AVATAR,
            CommonConst.PATH_TYPE_RANDOM_COVER,
            CommonConst.PATH_TYPE_COMMENT_PICTURE,
            CommonConst.PATH_TYPE_INTERNET_MEME,
            CommonConst.PATH_TYPE_IM_GROUP_AVATAR,
            CommonConst.PATH_TYPE_IM_GROUP_MESSAGE,
            CommonConst.PATH_TYPE_IM_FRIEND_MESSAGE,
            CommonConst.PATH_TYPE_FUNNY_COVER,
            CommonConst.PATH_TYPE_FAVORITES_COVER,
            CommonConst.PATH_TYPE_LOVE_COVER,
            CommonConst.PATH_TYPE_LOVE_MAN,
            CommonConst.PATH_TYPE_LOVE_WOMAN
    );

    private static final Set<String> ALL_TYPES = Set.of(
            CommonConst.PATH_TYPE_GRAFFITI,
            CommonConst.PATH_TYPE_ARTICLE_PICTURE,
            CommonConst.PATH_TYPE_USER_AVATAR,
            CommonConst.PATH_TYPE_ARTICLE_COVER,
            CommonConst.PATH_TYPE_WEB_BACKGROUND_IMAGE,
            CommonConst.PATH_TYPE_WEB_AVATAR,
            CommonConst.PATH_TYPE_RANDOM_AVATAR,
            CommonConst.PATH_TYPE_RANDOM_COVER,
            CommonConst.PATH_TYPE_COMMENT_PICTURE,
            CommonConst.PATH_TYPE_INTERNET_MEME,
            CommonConst.PATH_TYPE_IM_GROUP_AVATAR,
            CommonConst.PATH_TYPE_IM_GROUP_MESSAGE,
            CommonConst.PATH_TYPE_IM_FRIEND_MESSAGE,
            CommonConst.PATH_TYPE_FUNNY_URL,
            CommonConst.PATH_TYPE_FUNNY_COVER,
            CommonConst.PATH_TYPE_FAVORITES_COVER,
            CommonConst.PATH_TYPE_LOVE_COVER,
            CommonConst.PATH_TYPE_LOVE_MAN,
            CommonConst.PATH_TYPE_LOVE_WOMAN,
            CommonConst.PATH_TYPE_VIDEO_ARTICLE,
            CommonConst.PATH_TYPE_ASSETS
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "ico");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "ogg", "m4a", "aac", "flac");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "webm", "mov", "m4v");
    private static final Set<String> FONT_EXTENSIONS = Set.of(
            "woff", "woff2", "ttf", "otf");
    private static final Set<String> ACTIVE_EXTENSIONS = Set.of(
            "html", "htm", "svg", "js", "mjs", "xml", "xhtml", "shtml");

    private UploadSecurityValidator() {
    }

    public static void validateUpload(String type, String relativePath, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PoetryRuntimeException("上传文件不能为空！");
        }
        validateTypeAndPath(type, relativePath, true);
        long sizeLimit = sizeLimit(type);
        if (file.getSize() > sizeLimit) {
            throw new PoetryRuntimeException("上传文件超过该资源类型的大小限制！");
        }
        String extension = extension(relativePath);
        validateMime(type, extension, file.getContentType());
        validateSignature(type, extension, file);
    }

    public static void validateStoredResource(String type, String path, String mimeType, long size) {
        validateTypeAndPath(type, path, false);
        if (size <= 0 || size > sizeLimit(type)) {
            throw new PoetryRuntimeException("资源大小不合法或超过该类型限制！");
        }
        validateMime(type, extension(path), mimeType);
    }

    public static void validateQiniuKey(String key) {
        typeForQiniuKey(key);
    }

    public static long sizeLimitForQiniuKey(String key) {
        return sizeLimit(typeForQiniuKey(key));
    }

    private static String typeForQiniuKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 256 || key.contains("\\")
                || key.startsWith("/") || key.endsWith("/") || key.contains("//")
                || key.contains("?") || key.contains("#") || key.contains("%")
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new PoetryRuntimeException("文件键不合法！");
        }
        for (String segment : key.split("/", -1)) {
            if (!StringUtils.hasText(segment) || ".".equals(segment) || "..".equals(segment)
                    || segment.length() > 128) {
                throw new PoetryRuntimeException("文件键不合法！");
            }
        }
        String type = ALL_TYPES.stream()
                .filter(candidate -> key.startsWith(candidate + "/"))
                .findFirst()
                .orElseThrow(() -> new PoetryRuntimeException("文件键未包含受支持的资源类型！"));
        validateTypeAndPath(type, key, true);
        return type;
    }

    public static boolean isAssetsType(String type) {
        return CommonConst.PATH_TYPE_ASSETS.equals(type);
    }

    public static boolean isAssetsKey(String key) {
        return StringUtils.hasText(key) && key.startsWith(CommonConst.PATH_TYPE_ASSETS + "/");
    }

    private static void validateTypeAndPath(String type, String path, boolean requireTypePrefix) {
        if (!StringUtils.hasText(type) || !ALL_TYPES.contains(type) || !StringUtils.hasText(path)
                || path.length() > 256 || path.contains("?") || path.contains("#")
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new PoetryRuntimeException("资源类型或文件路径不合法！");
        }
        if (requireTypePrefix && !path.startsWith(type + "/")) {
            throw new PoetryRuntimeException("文件路径与资源类型不匹配！");
        }
        String extension = extension(path);
        if (ACTIVE_EXTENSIONS.contains(extension) || !allowedExtensions(type).contains(extension)) {
            throw new PoetryRuntimeException("不支持该资源类型或文件扩展名！");
        }
    }

    private static Set<String> allowedExtensions(String type) {
        if (IMAGE_TYPES.contains(type)) {
            return IMAGE_EXTENSIONS;
        }
        if (CommonConst.PATH_TYPE_FUNNY_URL.equals(type)) {
            return AUDIO_EXTENSIONS;
        }
        if (CommonConst.PATH_TYPE_VIDEO_ARTICLE.equals(type)) {
            return VIDEO_EXTENSIONS;
        }
        if (CommonConst.PATH_TYPE_ASSETS.equals(type)) {
            return Set.of(
                    "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "ico",
                    "mp3", "wav", "ogg", "m4a", "aac", "flac",
                    "mp4", "webm", "mov", "m4v", "woff", "woff2", "ttf", "otf");
        }
        return Set.of();
    }

    private static long sizeLimit(String type) {
        return IMAGE_TYPES.contains(type) ? IMAGE_SIZE_LIMIT : OTHER_SIZE_LIMIT;
    }

    private static String extension(String path) {
        String withoutQuery = path;
        int queryIndex = withoutQuery.indexOf('?');
        if (queryIndex >= 0) {
            withoutQuery = withoutQuery.substring(0, queryIndex);
        }
        int fragmentIndex = withoutQuery.indexOf('#');
        if (fragmentIndex >= 0) {
            withoutQuery = withoutQuery.substring(0, fragmentIndex);
        }
        int slash = Math.max(withoutQuery.lastIndexOf('/'), withoutQuery.lastIndexOf('\\'));
        int dot = withoutQuery.lastIndexOf('.');
        if (dot <= slash || dot == withoutQuery.length() - 1) {
            throw new PoetryRuntimeException("文件扩展名不能为空！");
        }
        return withoutQuery.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void validateMime(String type, String extension, String contentType) {
        if (!StringUtils.hasText(contentType) || contentType.length() > 128
                || contentType.chars().anyMatch(Character::isISOControl)) {
            throw new PoetryRuntimeException("文件 MIME 类型不能为空！");
        }
        String mime = contentType.toLowerCase(Locale.ROOT);
        boolean valid;
        if (IMAGE_EXTENSIONS.contains(extension)) {
            valid = mime.startsWith("image/") && !"image/svg+xml".equals(mime);
        } else if (AUDIO_EXTENSIONS.contains(extension)) {
            valid = mime.startsWith("audio/");
        } else if (VIDEO_EXTENSIONS.contains(extension)) {
            valid = mime.startsWith("video/");
        } else if (FONT_EXTENSIONS.contains(extension) && CommonConst.PATH_TYPE_ASSETS.equals(type)) {
            valid = mime.startsWith("font/") || mime.contains("font") || mime.contains("woff");
        } else {
            valid = false;
        }
        if (!valid) {
            throw new PoetryRuntimeException("文件扩展名与 MIME 类型不匹配！");
        }
    }

    private static void validateSignature(String type, String extension, MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(16);
            boolean valid;
            if (IMAGE_EXTENSIONS.contains(extension)) {
                valid = validImageSignature(extension, header);
            } else if (AUDIO_EXTENSIONS.contains(extension)) {
                valid = validAudioSignature(extension, header);
            } else if (VIDEO_EXTENSIONS.contains(extension)) {
                valid = validVideoSignature(extension, header);
            } else if (FONT_EXTENSIONS.contains(extension) && CommonConst.PATH_TYPE_ASSETS.equals(type)) {
                valid = validFontSignature(extension, header);
            } else {
                valid = false;
            }
            if (!valid) {
                throw new PoetryRuntimeException("文件内容与扩展名不匹配！");
            }
        } catch (IOException e) {
            throw new PoetryRuntimeException("无法读取上传文件！", e);
        }
    }

    private static boolean validImageSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "jpg", "jpeg" -> startsWith(bytes, 0xff, 0xd8, 0xff);
            case "png" -> startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "gif" -> ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a");
            case "webp" -> ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
            case "avif" -> ascii(bytes, 4, "ftyp") && (ascii(bytes, 8, "avif") || ascii(bytes, 8, "avis"));
            case "bmp" -> ascii(bytes, 0, "BM");
            case "ico" -> startsWith(bytes, 0x00, 0x00, 0x01, 0x00);
            default -> false;
        };
    }

    private static boolean validAudioSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "mp3" -> ascii(bytes, 0, "ID3") || (bytes.length > 1 && unsigned(bytes[0]) == 0xff
                    && (unsigned(bytes[1]) & 0xe0) == 0xe0);
            case "wav" -> ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE");
            case "ogg" -> ascii(bytes, 0, "OggS");
            case "m4a" -> ascii(bytes, 4, "ftyp");
            case "aac" -> bytes.length > 1 && unsigned(bytes[0]) == 0xff && (unsigned(bytes[1]) & 0xf6) == 0xf0;
            case "flac" -> ascii(bytes, 0, "fLaC");
            default -> false;
        };
    }

    private static boolean validVideoSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "mp4", "mov", "m4v" -> ascii(bytes, 4, "ftyp");
            case "webm" -> startsWith(bytes, 0x1a, 0x45, 0xdf, 0xa3);
            default -> false;
        };
    }

    private static boolean validFontSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "woff" -> ascii(bytes, 0, "wOFF");
            case "woff2" -> ascii(bytes, 0, "wOF2");
            case "ttf" -> startsWith(bytes, 0x00, 0x01, 0x00, 0x00);
            case "otf" -> ascii(bytes, 0, "OTTO");
            default -> false;
        };
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expectedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedBytes.length; i++) {
            if (bytes[offset + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (unsigned(bytes[i]) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
