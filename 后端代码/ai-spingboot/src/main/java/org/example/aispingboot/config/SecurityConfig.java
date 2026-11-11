package org.example.aispingboot.config;

import cn.hutool.core.text.AntPathMatcher;
import org.example.aispingboot.util.JwtAuthticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 * 使用 JWT 做无状态认证，并放行登录、注册、测试等公开接口。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Ant 风格路径匹配器，用于判断请求是否为公开路径 */
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();

    /** 无需登录即可访问的公开路径 */
    private  static final String[] PUBLIC_PATHS = {
            "/",
            "/api/test",           // 健康检查
            "/api/test/**",        // 所有测试子接口（Redis、ChatMemory 验证等）
            "/api/user/login",
            "/api/user/add"
    };

    /**
     * 判断请求路径是否为公开路径。
     *
     * @param requestUri 请求 URI
     * @return true 表示无需登录即可访问
     */
    public static Boolean isPublicPATH(String requestUri) {
        for (String publicPath : PUBLIC_PATHS) {
            if (antPathMatcher.match(publicPath, requestUri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注册 JWT 认证过滤器。
     * 交给 Spring 管理后，过滤器内部才能注入 UserService 等依赖。
     *
     * @return JWT 认证过滤器
     */
    @Bean
    public JwtAuthticationFilter jwtAuthticationFilter() {
        return new JwtAuthticationFilter();
    }

    /**
     * 配置安全过滤器链。
     * 关闭 CSRF、使用无状态会话，并将 JWT 过滤器放在用户名密码过滤器之前。
     *
     * @param http HttpSecurity 配置对象
     * @return 构建完成的过滤器链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF保护 （API服务通常不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 配置会话管理为无状态（JWT需要）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求的授权规则
                .authorizeHttpRequests(auth -> auth
                        // 公开的路径，无需登录即可访问
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 其他请求都需要认证
                        .anyRequest().authenticated()
                )
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
