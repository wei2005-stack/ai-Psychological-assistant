package org.example.aispingboot.exception;

import lombok.Getter;

/**
 * 业务异常。
 * 用于表达用户不存在、密码错误、用户名重复等可预期的业务失败，
 * 由全局异常处理器统一转换为 Result 响应。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String code;
    private final String message;
    private final Object data;

    public BusinessException(String message) {
        super(message);
        this.code = "BUSINESS_ERROR";
        this.message = message;
        this.data = null;
    }

}