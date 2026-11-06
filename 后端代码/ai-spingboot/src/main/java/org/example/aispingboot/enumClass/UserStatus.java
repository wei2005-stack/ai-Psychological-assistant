package org.example.aispingboot.enumClass;

import lombok.Getter;

/**
 * 用户状态枚举。
 * 0 表示禁用，1 表示正常；登录和 JWT 过滤器都会校验该状态。
 */
@Getter
public enum UserStatus {

    DISABLED(0, "禁用"),
    NORMAL(1, "正常");

    private final Integer code;
    private final String description;

    UserStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据代码获取枚举
     */
    public static UserStatus fromCode(Integer code) {
        for (UserStatus status : UserStatus.values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的用户状态代码: " + code);
    }
}