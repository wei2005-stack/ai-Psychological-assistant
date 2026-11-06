package org.example.aispingboot.util;

import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器响应工具。
 * 过滤器阶段无法被 @RestControllerAdvice 捕获异常，
 * 因此在这里手动把错误结果写入 HttpServletResponse。
 */
public class ResponseUtil {

    /**
     * 向响应写出统一格式的错误 JSON。
     * 根据业务码映射为 401、403 或 400 的 HTTP 状态。
     *
     * @param response   HTTP 响应
     * @param resultCode 业务状态码
     */
    public static void writeError(HttpServletResponse response, ResultCode resultCode) {
        // 1. 业务码映射为 HTTP 状态码，让前端既能看业务码，也能按 HTTP 语义统一处理：
        //    认证类问题（未登录、token 无效 / 过期 / 被封禁）统一返回 401，提示前端重新登录；
        //    授权类问题（账号被禁用等）返回 403，表示身份已识别但没有访问权限；
        //    其余未归类的业务码兜底返回 400，避免出现 HTTP 200 却携带错误体的歧义。
        int status = switch (resultCode) {
            case UNAUTHORIZED, ACCESS_UNAUTHORIZED, TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_BLOCKED -> HttpStatus.UNAUTHORIZED.value();
            case TOKEN_ACCESS_FORBIDDEN -> HttpStatus.FORBIDDEN.value();
            default -> HttpStatus.BAD_REQUEST.value();
        };

        // 2. 设置 HTTP 状态行。必须在 getWriter() 之前调用：
        //    一旦开始写出响应体，响应就会被提交，之后 setStatus 不再生效。
        response.setStatus(status);

        // 3. 声明响应体类型与编码。Content-Type 告诉前端按 JSON 解析，
        //    字符集声明为 UTF-8，避免中文错误提示出现乱码。
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // 4. try-with-resources 会在代码块结束时自动关闭 PrintWriter，防止输出流泄漏；
        //    关闭前显式 flush，确保缓冲区中的内容真正写给客户端，而不是停在内存里。
        try (PrintWriter writer = response.getWriter()){
            // 错误体保持与正常接口一致的 { code, msg, data } 结构，data 固定为 null，
            // 这样前端响应拦截器可以用同一套解析逻辑识别所有响应。
            String jsonResponse = JSONUtil.toJsonStr(Result.error(resultCode.getCode(), resultCode.getMsg(), null));
            writer.print(jsonResponse);
            writer.flush(); // 确保将响应内容写入到输出流
        }catch (IOException e) {
            // 走到这里通常说明客户端已断开或输出流被占用，此时响应已提交、无法再修改状态码；
            // 过滤器阶段又没有全局异常处理器兜底，因此只记录错误，不再向上抛出。
            System.out.println("写入响应失败：" + e.getMessage());
        }
    }
}
