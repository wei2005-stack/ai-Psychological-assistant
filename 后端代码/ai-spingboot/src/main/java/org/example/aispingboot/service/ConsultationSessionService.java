package org.example.aispingboot.service;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.mapper.ConsultationSessionMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 咨询会话业务服务。
 * 负责校验用户并创建会话记录。
 */
@Service
public class ConsultationSessionService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    /**
     * 创建咨询会话。
     * 用户不存在时返回 null；标题为空时自动生成带当前时间的默认标题。
     *
     * @param userId    用户 ID
     * @param createDTO 会话创建参数
     * @return 新建的会话，用户不存在时返回 null
     */
    public ConsultationSession createSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 验证用户是否存在
        User user =userMapper.selectById(userId);
        if (user != null) {
            // 创建会话记录
             ConsultationSession session = ConsultationSession.builder()
                    .userId(userId)
                    .sessionTitle(createDTO.getSessionTitle())
                    .startedAt(LocalDateTime.now())
                    .build();
            // 如果未提供标题
            if (StrUtil.isBlank(createDTO.getSessionTitle())) {
                session.setSessionTitle(String.format("AI助手 - " + DateUtil.format(LocalDateTime.now(), "MM-dd HH:mm")));
            }

            // 插入记录
            consultationSessionMapper.insert(session);
            return session;
        }

        return null;
    }

    /**
     * 按主键查询会话。
     * 情绪分析接口用它做归属校验：只能看自己创建的会话，防止越权读取他人会话内容。
     *
     * @param sessionId 数据库会话 ID
     * @return 会话实体，不存在时返回 null
     */
    public ConsultationSession getSessionById(Long sessionId) {
        return consultationSessionMapper.selectById(sessionId);
    }

    /**
     * 分页查询某个用户的会话列表，按开始时间倒序（最新的在最上面）。
     *
     * <p>项目没有配置 MyBatis-Plus 分页插件，这里用 limit 手写分页，
     * 返回当前页数据；总数由 {@link #countByUser(Long)} 单独查。</p>
     *
     * @param userId   用户 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 当前页会话列表
     */
    public List<ConsultationSession> listByUser(Long userId, int pageNum, int pageSize) {
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        LambdaQueryWrapper<ConsultationSession> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationSession::getUserId, userId)
                .orderByDesc(ConsultationSession::getStartedAt)
                .last("limit " + offset + "," + pageSize);
        return consultationSessionMapper.selectList(w);
    }

    /**
     * 统计某个用户的会话总数，供分页返回。
     *
     * @param userId 用户 ID
     * @return 会话条数
     */
    public long countByUser(Long userId) {
        LambdaQueryWrapper<ConsultationSession> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationSession::getUserId, userId);
        return consultationSessionMapper.selectCount(w);
    }

    /**
     * 删除会话记录本身（消息记录由 ConsultationMessageService 负责删除）。
     *
     * @param sessionId 会话 ID
     */
    public void deleteById(Long sessionId) {
        consultationSessionMapper.deleteById(sessionId);
    }
}
