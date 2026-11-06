package org.example.aispingboot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.aispingboot.entity.ConsultationMessage;

/**
 * 咨询消息表持久层。
 * 继承 BaseMapper 后可直接使用增删改查和条件构造器。
 */
@Mapper
public interface ConsultationMessageMapper extends BaseMapper<ConsultationMessage> {
}
