package org.example.aispingboot.service.convert;

import org.example.aispingboot.DTO.command.UserRegisterCommandDTO;
import org.example.aispingboot.DTO.response.UserLoginResponseDTO;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.enumClass.UserStatus;

import java.time.LocalDateTime;

/**
 * 用户对象转换工具。
 * 负责实体与登录响应、用户详情 DTO 之间的手工映射。
 */
public class UserConvert {

    /**
     * 把用户实体转换为用户详情 DTO。
     * 同时补充性别、用户类型和账号状态的显示名称。
     *
     * @param user 用户实体
     * @return 用户详情 DTO
     */
    public static UserLoginResponseDTO.UserDetailResponseDTO entityToDetailResponse(User user) {
        return UserLoginResponseDTO.UserDetailResponseDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .gender(user.getGender())
                .genderDisplayName(getGenderDisplayName(user.getGender()))
                .birthday(user.getBirthday())
                .userType(user.getUserType())
                .userTypeDisplayName(user.getUserTypeDisplayName())
                .status(user.getStatus())
                .statusDisplayName(user.getStatusDisplayName())
                .displayName(user.getDisplayName())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * 组装登录响应。
     *
     * @param token    生成的 JWT
     * @param userInfo 用户详情
     * @return 登录响应，包含 token、角色类型和用户信息
     */
    public static UserLoginResponseDTO entityToLoginResponse(String token, UserLoginResponseDTO.UserDetailResponseDTO userInfo) {
        return UserLoginResponseDTO.builder()
                .userInfo(userInfo)
                .token(token)
                .roleType(userInfo.getUserType().toString())
                .build();
    }

    /**
     * 把注册参数转换为用户实体。
     * 密码使用调用方传入的加密结果，新用户默认状态为正常。
     *
     * @param commandDTO      注册参数
     * @param encodedPassword BCrypt 加密后的密码
     * @return 待入库的用户实体
     */
    public static User registerCommandToEntity(UserRegisterCommandDTO commandDTO, String encodedPassword) {
        return User.builder()
                .username(commandDTO.getUsername())
                .email(commandDTO.getEmail())
                .password(encodedPassword)
                .nickname(commandDTO.getNickname())
                .phone(commandDTO.getPhone())
                .gender(commandDTO.getGender())
                .birthday(commandDTO.getBirthday())
                .userType(commandDTO.getUserType())
                .status(UserStatus.NORMAL.getCode())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 获取性别显示名称
     * @param gender 性别代码
     * @return 性别显示名称
     */
    private static String getGenderDisplayName(Integer gender) {
        if (gender == null) {
            return "未知";
        }
        switch (gender) {
            case 1:
                return "男";
            case 2:
                return "女";
            default:
                return "未知";
        }
    }
}
