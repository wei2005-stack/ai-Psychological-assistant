package org.example.aispingboot.util;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * 当前登录用户工具类。
 *
 * <p>为什么需要它：JWT 过滤器（{@link JwtAuthticationFilter}）遇到公开路径会直接跳过，
 * 此时 Spring Security 上下文里没有认证信息，但请求头里可能仍然带着 token
 * （例如知识库列表：游客可访问，管理员访问时需要看到草稿）。
 * 本工具类统一做「从 token 里取用户身份」这件事，并对公开接口提供不抛异常的版本。</p>
 */
public final class CurrentUserUtil {

    private CurrentUserUtil() {
    }

    /**
     * 获取当前登录用户 ID，未登录或 token 非法时抛出 {@link JWTVerificationException}。
     * 只能用于必须登录的接口（过滤器已保证请求带合法 token）。
     *
     * @return 用户 ID
     */
    public static Long currentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    /**
     * 获取当前登录用户 ID，未登录时返回 null，不抛异常。
     * 用于「游客可访问、登录后行为不同」的公开接口。
     *
     * @return 用户 ID，未登录返回 null
     */
    public static Long currentUserIdOrNull() {
        try {
            return currentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前用户角色：1 普通用户、2 管理员，未登录或解析失败返回 null。
     *
     * @return 角色类型
     */
    public static Integer currentRoleTypeOrNull() {
        try {
            String token = JwtTokenUtil.getCurrentToken();
            DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
            return jwt.getClaim("roleType").asInt();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前用户是否为管理员（token 中 roleType == 2）。
     * 公开接口里用它决定是否放开草稿/下线文章。
     *
     * @return true 表示管理员
     */
    public static boolean isAdmin() {
        Integer role = currentRoleTypeOrNull();
        return role != null && role == 2;
    }
}
