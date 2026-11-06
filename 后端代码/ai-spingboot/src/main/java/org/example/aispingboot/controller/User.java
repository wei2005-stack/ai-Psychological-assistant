package org.example.aispingboot.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.example.aispingboot.DTO.command.UserLoginCommandDTO;
import org.example.aispingboot.DTO.command.UserRegisterCommandDTO;
import org.example.aispingboot.DTO.response.UserLoginResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.service.UserService;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.web.bind.annotation.*;

/**
 * 用户接口。
 * 提供登录、注册和获取当前用户信息三个入口。
 */
@RestController
@RequestMapping("/api/user")
public class User {
    @Resource
    private UserService userService;

    /**
     * 用户登录。
     * 校验用户名或邮箱、密码和账号状态，成功后返回 JWT 与用户信息。
     *
     * @param commandDTO 登录参数
     * @return token 和用户详情
     */
    @PostMapping("/login")
    public Result<UserLoginResponseDTO> login(@Valid @RequestBody UserLoginCommandDTO commandDTO) {
        // 调用服务层登录方法
        UserLoginResponseDTO result = userService.login(commandDTO);
        return Result.ok(result);
    }

    /**
     * 用户注册。
     * 校验参数和唯一性后创建账号，密码使用 BCrypt 加密存储。
     *
     * @param commandDTO 注册参数
     * @return 新用户详情
     */
    @PostMapping("/add")
    public Result<UserLoginResponseDTO.UserDetailResponseDTO> register(@Valid @RequestBody UserRegisterCommandDTO commandDTO) {
        UserLoginResponseDTO.UserDetailResponseDTO result = userService.register(commandDTO);
        return Result.ok(result);
    }

    /**
     * 获取当前登录用户。
     * 从 JWT 中解析 userId，再查询用户详情。
     *
     * @return 当前用户详情
     */
    @GetMapping("/current")
    public Result<UserLoginResponseDTO.UserDetailResponseDTO> getCurrentUser() {
        // 如何从token中解析出用户的id
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        Long userId = jwt.getClaim("userId").asLong();
        // 调用service层获取用户详情
        UserLoginResponseDTO.UserDetailResponseDTO result = userService.getUserById(userId);
        return Result.ok(result);
    }

}
