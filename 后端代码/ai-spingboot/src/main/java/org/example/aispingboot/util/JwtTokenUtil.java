package org.example.aispingboot.util;

import ch.qos.logback.core.util.StringUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.example.aispingboot.config.JwtConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;

/**
 * JWT 工具类。
 * 负责 token 的生成、提取、验签和载荷解析。
 * 由于需要在过滤器和控制器中静态调用，这里通过 ApplicationContext 获取 JwtConfig。
 */
@Component
public class JwtTokenUtil implements ApplicationContextAware {

    /** JWT 签发者标识，验签时会一并校验 */
    private static final String ISSUER = "mental-health-assistant";

    private static ApplicationContext applicationContext;
    // 用于再静态工具类中获取Spring容器管理的Bean
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        JwtTokenUtil.applicationContext = applicationContext;
    }

    private static JwtConfig getJwtConfig() {
        return applicationContext.getBean(JwtConfig.class);
    };

    /**
     * 生成 JWT。
     * 载荷包含 userId、username、roleType，并设置签发时间、过期时间和签发者。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param roleType 用户类型，1 普通用户、2 管理员
     * @return 签名后的 token
     */
    public static String generateToken(Long userId, String username, Integer roleType) {
        try {
            // 获取jwt的配置
            JwtConfig jwtConfig = getJwtConfig();
            // 生成签名的算法
            Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
            // 生成过期时间
            Date expiration = new Date(System.currentTimeMillis() + jwtConfig.getExpiration());

            String token = JWT.create()
                    .withClaim("userId", userId)
                    .withClaim("username", username)
                    .withClaim("roleType", roleType)
                    .withExpiresAt(expiration) // 设置过期时间
                    .withIssuedAt(new Date()) // 设置签发时间
                    .withIssuer(ISSUER) // 设置签发者
                    .sign(algorithm);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("生成token 失败: " + e);
        }
    }

    /**
     * 从请求头 token 中提取 JWT。
     *
     * @param request HTTP 请求
     * @return token 字符串，不存在时返回 null
     */
    public static String extractTokenFromRequest(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String tokenHeader = request.getHeader("token");
        if (StringUtils.hasText(tokenHeader)) {
            return tokenHeader;
        }
        return null;
    }

    /**
     * 获取当前请求的 token。
     * 优先读取过滤器写入的 jwtToken 请求属性，取不到时回退到请求头 token。
     *
     * @return 当前 token，无法获取时返回 null
     */
    public static String getCurrentToken() {
        // 优先从请求属性中获取 token（验证通过后的token）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String token = (String) request.getAttribute("jwtToken");
            if (token != null) {
                return token;
            }

            // 备用方案： 从请求头直接获取
            String headerToken =  extractTokenFromRequest(request);
            return headerToken;
        }
        return null;
    }

    /**
     * 校验 token 并解析用户信息（面向业务层的入口方法）。
     *
     * <p>与 {@link #verifyToken(String)} 的联系：本方法不重复实现验签逻辑，
     * 而是把"验签"这一步委托给 {@link #verifyToken(String)}，拿到验签通过的
     * {@link DecodedJWT} 后，再从载荷中读取业务需要的字段，组装成
     * {@link TokenVerificationResult} 返回。即：verifyToken 负责"这个 token 是不是真的、
     * 有没有过期"，validateToken 负责"从真的 token 里取出用户身份"。</p>
     *
     * <p>职责划分：</p>
     * <ul>
     *   <li>{@code verifyToken}：底层校验，只做签名、签发者、过期时间三项校验，
     *       校验失败直接抛出 {@link JWTVerificationException}，不做业务字段解析；</li>
     *   <li>{@code validateToken}：上层封装，只做载荷解析，把 userId、username、roleType
     *       转换为业务对象，并对字段是否齐全做判断。</li>
     * </ul>
     *
     * <p>注意：本方法内部没有 try-catch 包裹 {@code verifyToken} 的调用，
     * 因此 token 为空、签名被篡改或已过期时，异常会继续向上抛出，
     * 由调用方（如 JWT 过滤器或 Controller 的全局异常处理）统一处理。</p>
     *
     * @param token 待校验的 token
     * @return 校验通过且载荷字段齐全时返回用户信息，否则返回 null
     */
    public static TokenVerificationResult validateToken(String token) {
        // 第一步：委托 verifyToken 完成验签，校验不通过会抛出 JWTVerificationException，
        // 所以能执行到下一行，说明签名、签发者、过期时间都是合法的
        DecodedJWT jwt = verifyToken(token);

        // 第二步：验签通过后，从 JWT 载荷中读取用户身份信息
        // 由于载荷经过签名，这里读到的内容可信，不会被客户端篡改
        Long userId = jwt.getClaim("userId").asLong();
        String username = jwt.getClaim("username").asString();

        // 第三步：解析角色类型，1 普通用户、2 管理员
        // 兼容两种写入方式：按数值写入时用 asInt() 直接取，
        // 若 token 中是字符串形式（如 "1"），asInt() 失败后回退为 asString() 再转换
        Integer roleType = null;
        try {
            roleType = jwt.getClaim("roleType").asInt();
        } catch (Exception e) {
           String roleTypeStr = jwt.getClaim("roleType").asString();
           if (StringUtils.hasText(roleTypeStr)) {
               roleType = Integer.valueOf(roleTypeStr);
           }
        }

        // 第四步：三个关键字段齐全才视为有效用户，否则返回 null 表示载荷不完整
        if (userId != null && StringUtils.hasText(username) && roleType != null) {
            return new TokenVerificationResult(userId, username, roleType, true);
        }
        return null;
    }

    /**
     * 校验 token 的签名、签发者和过期时间（面向所有调用方的底层验签方法）。
     *
     * <p>与 {@link #validateToken(String)} 的联系：本方法是"纯验签"的原子能力，
     * 只回答"token 是否合法"，不关心业务字段；{@code validateToken} 则在本方法之上
     * 做一层业务封装。因此过滤器、拦截器、其他工具方法都可以直接复用本方法，
     * 需要用户信息时再调用 {@code validateToken}。</p>
     *
     * <p>校验内容（由 {@link JWTVerifier} 一次性完成）：</p>
     * <ol>
     *   <li>签名是否正确：用 {@link JwtConfig#getSecret()} 重新做 HMAC256 验签，
     *       能防止 token 被篡改；</li>
     *   <li>签发者是否为 {@link #ISSUER}：防止其他系统签发的 token 被拿来冒用；</li>
     *   <li>是否已过期：校验载荷中的 exp（{@code generateToken} 写入的过期时间）。</li>
     * </ol>
     *
     * <p>本方法采用"失败即抛异常"的风格，不做返回 null 的兜底，
     * 便于调用方用一次 catch 统一处理所有非法 token 的情况。</p>
     *
     * @param token 待校验的 token
     * @return 校验通过后解码出的 JWT 对象，可继续读取载荷
     * @throws JWTVerificationException token 为空、签名错误、签发者不符或已过期时抛出
     */
    public static DecodedJWT verifyToken(String token) {
        // 前置校验：空 token 无法进入验签流程，直接抛出异常
        if (!StringUtils.hasText(token)) {
            throw new JWTVerificationException("Token不能为空");
        }
        // 从 Spring 容器取出 JWT 配置（密钥、过期时间等），保证与生成 token 时用的是同一份配置
        JwtConfig jwtConfig = getJwtConfig();
        // token解码
        // 使用与签发时相同的算法和密钥构造校验器，密钥不一致会导致验签失败
        Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
        // 构建校验器：要求签发者必须与生成时写入的 ISSUER 一致
        JWTVerifier verifier =  JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
        // 执行校验：签名、签发者、过期时间任一项不通过都会抛出 JWTVerificationException
        // 校验成功时返回 DecodedJWT，调用方即可读取 userId、username、roleType 等载荷
        return  verifier.verify(token);
    }

    /**
     * token 校验结果封装。
     * 保存从 JWT 中解析出的用户 ID、用户名、角色类型和有效标记。
     */
    @Getter
    public static class TokenVerificationResult {
        private final Long userId;
        private final String username;
        private final Integer roleType;
        private final boolean valid;

        public TokenVerificationResult(Long userId, String username, Integer roleType, boolean valid) {
            this.userId = userId;
            this.username = username;
            this.roleType = roleType;
            this.valid = valid;
        }
    }
}
