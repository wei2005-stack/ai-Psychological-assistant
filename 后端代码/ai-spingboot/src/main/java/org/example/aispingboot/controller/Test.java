package org.example.aispingboot.controller;


import org.example.aispingboot.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性测试接口。
 * 用于确认后端服务已启动，且无需登录即可访问。
 */
@RestController
@RequestMapping("/api")
public class Test {

    /**
     * 返回固定字符串，供健康检查或前后端联调使用。
     *
     * @return hello world
     */
    @GetMapping("/test")
    public Result<String> test() {
//        Result result = new Result();
//        result.setCode("200");
//        System.out.println(result.getCode());
        return Result.ok("hello world");
    }
}
