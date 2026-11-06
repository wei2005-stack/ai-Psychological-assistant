package org.example.aispingboot.common;

import lombok.Data;

/**
 * 统一响应结果封装。
 * 所有接口都返回 code、msg、data 三个字段，便于前端统一处理。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {
    private String code;
    private String msg;
    private T data;

    /**
     * 构造无数据的成功响应。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok() {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMsg(ResultCode.SUCCESS.getMsg());
        return result;
    }

    /**
     * 构造携带业务数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok(T data) {
        Result<T> result = ok();
        result.setData(data);
        return result;
    }

    /**
     * 构造默认失败响应。
     *
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error() {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.ERROR.getCode());
        result.setMsg(ResultCode.ERROR.getMsg());
        return result;
    }

    /**
     * 构造自定义错误码和提示的失败响应。
     *
     * @param code 错误码
     * @param msg  错误提示
     * @param data 附加数据，可为 null
     * @param <T>  数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(String code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
}
