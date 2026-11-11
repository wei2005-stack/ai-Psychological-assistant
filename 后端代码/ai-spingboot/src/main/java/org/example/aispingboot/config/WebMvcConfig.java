package org.example.aispingboot.config;

import org.example.aispingboot.service.FileStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置：把本地上传目录映射成静态资源。
 *
 * <p>上传接口只返回相对路径 {@code /upload/xxx/yyy.png}，数据库也存相对路径；
 * 这里把 URL 前缀 {@code /upload/**} 映射到磁盘上的上传根目录，
 * 浏览器就能直接通过 {@code http://localhost:1236/upload/...} 访问图片。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final FileStorageService fileStorageService;

    public WebMvcConfig(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/upload/**")
                // fileStorageService.getRootLocation() 返回 file:///.../upload/ 形式
                .addResourceLocations(fileStorageService.getRootLocation())
                // 图片内容不会变（文件名是 UUID），缓存 1 小时减少重复请求
                .setCachePeriod(3600);
    }
}
