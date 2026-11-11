package org.example.aispingboot.service;

import org.example.aispingboot.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储服务。
 *
 * <p>项目没有接对象存储（OSS/MinIO），封面等图片直接落在后端服务器磁盘上：</p>
 * <pre>
 * {upload-dir}/{businessType}/{yyyy-MM}/{uuid}.{ext}
 * 例如：./upload/ARTICLE/2026-11/9f3c...a1.png
 * </pre>
 *
 * <p>数据库里只保存相对路径 {@code /upload/ARTICLE/2026-11/xxx.png}，
 * 由 {@link org.example.aispingboot.config.WebMvcConfig} 把 {@code /upload/**}
 * 映射到磁盘目录对外提供静态访问，这样换域名/换端口时不需要改库。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 允许上传的图片后缀，防止把 jsp/html 等可执行文件传上来 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 单文件大小上限 5MB（与 application.yml 的 multipart 限制配合） */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 按月份分目录，避免单目录文件过多 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 上传根目录的绝对路径 */
    private final Path rootDir;

    /**
     * @param uploadDir 上传根目录，来自 application.yml 的 file.upload-dir，默认 ./upload
     */
    public FileStorageService(@Value("${file.upload-dir:./upload}") String uploadDir) {
        this.rootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件上传目录: " + rootDir, e);
        }
    }

    /**
     * 上传根目录的 URI 形式（带结尾斜杠），供 Spring MVC 静态资源映射使用。
     *
     * @return 形如 file:///E:/project/upload/
     */
    public String getRootLocation() {
        // 目录已在上面的构造方法中创建，toUri() 会保证以 "/" 结尾
        return rootDir.toUri().toString();
    }

    /**
     * 保存上传的图片。
     *
     * @param file         上传文件
     * @param businessType 业务类型（如 ARTICLE），只保留字母数字下划线短横线
     * @return 存储结果，包含可直接入库的相对路径
     */
    public StoredFile store(MultipartFile file, String businessType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("图片不能超过 5MB");
        }

        String ext = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("仅支持 jpg / jpeg / png / gif / webp / bmp 格式的图片");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException("上传文件不是有效的图片");
        }

        // businessType 来自前端，必须过滤掉 / 和 .. 之类字符，防止目录穿越
        String safeType = businessType == null ? "" : businessType.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeType.isBlank()) {
            safeType = "COMMON";
        }

        String month = LocalDate.now().format(MONTH_FORMATTER);
        Path dir = rootDir.resolve(safeType).resolve(month).normalize();
        // 双重保险：规范化后仍必须位于根目录内
        if (!dir.startsWith(rootDir)) {
            throw new BusinessException("非法的业务类型");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            log.error("保存上传文件失败, target={}", target, e);
            throw new BusinessException("文件保存失败，请稍后重试");
        }

        // 统一用正斜杠拼 URL，Windows 的反斜杠在浏览器里会 404
        String relativePath = "/upload/" + safeType + "/" + month + "/" + fileName;
        log.info("文件上传成功: {} ({} bytes) -> {}", file.getOriginalFilename(), file.getSize(), relativePath);
        return new StoredFile(relativePath, file.getOriginalFilename(), file.getSize(),
                contentType == null ? "" : contentType);
    }

    /**
     * 从原始文件名中取小写后缀；没有后缀时返回空串（后续会被白名单拒绝）。
     *
     * @param originalFilename 原始文件名
     * @return 小写后缀，不含点
     */
    private String resolveExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 存储结果。
     *
     * @param filePath    相对访问路径，如 /upload/ARTICLE/2026-11/xxx.png
     * @param fileName    原始文件名
     * @param fileSize    文件大小（字节）
     * @param contentType MIME 类型
     */
    public record StoredFile(String filePath, String fileName, long fileSize, String contentType) {
    }
}
