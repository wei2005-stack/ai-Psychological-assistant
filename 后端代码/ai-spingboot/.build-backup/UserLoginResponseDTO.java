package org.example.aispingboot.DTO.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 登录响应。
 * 顶层返回 token、角色类型和用户详情，前端据此写入登录态并按角色分流。
 */
@Data
@Builder
public class UserLoginResponseDTO {

    /** JWT 访问令牌 */
    private String token;

    /** 角色类型：1 普通用户、2 管理员 */
    private String roleType;

    /** 登录用户详情 */
    private UserDetailResponseDTO userInfo;

    /**
     * 用户详情。
     * 返回账号资料、角色和状态，并附带前端展示所需的显示名称。
     */
    @Builder
    @Data
    public static class UserDetailResponseDTO {
        private Long id;
        private String username;
        private String email;
        private String nickname;
        private String avatar;
        private String phone;
        private Integer gender;
        private String genderDisplayName;
        private LocalDate birthday;
        private Integer userType;
        private String userTypeDisplayName;
        private Integer status;
        private String statusDisplayName;
        private String displayName;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
