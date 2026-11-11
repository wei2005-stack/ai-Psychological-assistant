package org.example.aispingboot.util;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.aispingboot.DTO.response.UserLoginResponseDTO;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.config.SecurityConfig;
import org.example.aispingboot.enumClass.UserStatus;
import org.example.aispingboot.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * JWT 认证过滤器。
 * 每个请求只执行一次：提取并校验 token，查询用户状态，
 * 校验通过后把认证信息写入 Spring Security 上下文。
 */
public class JwtAuthticationFilter extends OncePerRequestFilter {
    @Resource
    private UserService userService;
    /**
     * 判断当前请求是否跳过 JWT 校验。
     * 登录、注册、测试等公开路径直接放行。
     *
     * @param request HTTP 请求
     * @return true 表示跳过过滤器
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        // 检查是否为公开路径（知识库读接口仅 GET 放行，因此还要带上请求方法）
        return SecurityConfig.isPublicPATH(requestUri, request.getMethod());
    }

    /**
     * 执行 JWT 认证逻辑。
     * token 缺失、无效或用户被禁用时直接写回错误响应，不再进入后续过滤器。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param chain    过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // 获取请求的URI和方法
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        System.out.println(requestUri);
        System.out.println(method);
        // 1. 提取 JWT token
        String token = JwtTokenUtil.extractTokenFromRequest(request);
        if (StringUtils.hasText(token)) {
            // 2. 验证token并获取用户信息
            // 注意：verifyToken 在签名不匹配、token 过期时是直接抛异常的（文档约定「失败即抛」），
            // 必须在这里捕获。否则异常会穿透过滤器，被 Servlet 容器当成 500 返回，
            // 前端只能看到「服务器错误」，既不会清 token 也不会跳登录页
            // （联调时浏览器的旧 token 就是踩了这个坑：POST /api/emotion-diary 返回 500）。
            JwtTokenUtil.TokenVerificationResult validationResult;
            try {
                validationResult = JwtTokenUtil.validateToken(token);
            } catch (JWTVerificationException e) {
                clearSecurityContext();
                ResponseUtil.writeError(response, ResultCode.TOKEN_INVALID);
                return;
            }
            if (validationResult != null && validationResult.isValid()) {
                // 3. 调用service层服务-》查询用户信息
                UserLoginResponseDTO.UserDetailResponseDTO user = userService.getUserById(validationResult.getUserId());
                System.out.println(JSONUtil.parseObj(user));
                // 4. 校验用户状态是否正常
                // 如果用户状态异常，直接写回错误响应
                if (user != null && UserStatus.NORMAL.getCode().equals(user.getStatus())) {
                    // 4. 创建Spring Security认证对象
                    List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority("ROLE_" + validationResult.getRoleType())
                    );

                    // 创建UsernamePasswordAuthenticationToken对象
                     UsernamePasswordAuthenticationToken authcation = new UsernamePasswordAuthenticationToken(
                            validationResult.getUsername(), // 用户名作为主体
                            null,// 密码为空
                            authorities// 角色信息
                    );

                     // 设置认证信息到Spring Securtity上下文
                    SecurityContextHolder.getContext().setAuthentication(authcation);

                    // 将token（验证通过后的token）存储到请求属性中
                    request.setAttribute("jwtToken", token);
                } else {
                    clearSecurityContext();
                    ResponseUtil.writeError(response, ResultCode.TOKEN_ACCESS_FORBIDDEN);
                    return;
                }
            } else {
                clearSecurityContext();
                ResponseUtil.writeError(response, ResultCode.TOKEN_INVALID);
                return;
            }
        } else {
            // 清理上下文
            clearSecurityContext();
            ResponseUtil.writeError(response, ResultCode.ACCESS_UNAUTHORIZED);
            return;
        }
        // 继续过滤器链
        chain.doFilter(request, response);
    }

    /**
     * 清理 Spring Security 上下文，避免认证信息残留。
     */
    private void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
