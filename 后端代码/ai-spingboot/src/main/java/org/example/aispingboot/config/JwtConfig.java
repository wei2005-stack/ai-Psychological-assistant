package org.example.aispingboot.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置类。
 * 把 application.yml 中 jwt 前缀的配置绑定到当前对象。
 */
@Data
// 将类设置为 Spring Bean，用于在其他地方注入
@Component
@ConfigurationProperties(prefix="jwt")
public class JwtConfig {

    /** JWT 签名密钥，使用 HMAC256 算法 */
    private String secret;

    /** 访问 token 有效期，单位毫秒 */
    private long expiration;

    /** 刷新 token 有效期，单位毫秒（当前业务暂未使用） */
    private long refreshExpiration;

    /** token 所在请求头名称（当前过滤器实际读取 token 头） */
    private String header;

    /** token 前缀，例如 Bearer （当前业务暂未使用） */
    private String tokenPrefix;
}
