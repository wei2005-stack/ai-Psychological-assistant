package org.example.aispingboot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.aispingboot.entity.ConsultationSession;

/**
 * 咨询会话表持久层。
 * 继承 BaseMapper 后可直接使用增删改查和条件构造器。
 */
@Mapper
public interface ConsultationSessionMapper extends BaseMapper<ConsultationSession> {
}
