package org.example.aispingboot.controller;

import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.service.FileStorageService;
import org.example.aispingboot.util.CurrentUserUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件上传接口。
 *
 * <p>与前端 {@code api/admin.js} 的 uploadFile 对齐：</p>
 * <pre>
 * POST /api/file/upload
 * Content-Type: multipart/form-data
 *   file           图片文件
 *   businessType   ARTICLE
 *   businessId     前端生成的 UUID（会作为文章 id 一起提交）
 *   businessField  cover
 * </pre>
 *
 * <p>返回 data.filePath，前端拼上 fileBaseUrl 显示图片，并把 filePath 存进文章 coverImage。</p>
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 上传图片（仅管理员）。
     *
     * @param file          图片文件
     * @param businessType  业务类型，默认 ARTICLE
     * @param businessId    业务主键，如文章 UUID
     * @param businessField 业务字段，如 cover
     * @return filePath / fileName / fileSize / contentType + 业务信息回显
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "businessType", required = false, defaultValue = "ARTICLE") String businessType,
            @RequestParam(value = "businessId", required = false) String businessId,
            @RequestParam(value = "businessField", required = false) String businessField) {

        // 上传进服务器磁盘属于管理动作，普通用户不允许
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }

        FileStorageService.StoredFile stored = fileStorageService.store(file, businessType);

        Map<String, Object> data = new LinkedHashMap<>();
        // 前端读取的是 filePath（相对路径），这里字段名必须保持一致
        data.put("filePath", stored.filePath());
        data.put("fileName", stored.fileName());
        data.put("fileSize", stored.fileSize());
        data.put("contentType", stored.contentType());
        data.put("businessType", businessType);
        data.put("businessId", businessId);
        data.put("businessField", businessField);
        return Result.ok(data);
    }
}
