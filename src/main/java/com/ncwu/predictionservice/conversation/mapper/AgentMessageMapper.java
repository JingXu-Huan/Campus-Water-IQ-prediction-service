package com.ncwu.predictionservice.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncwu.predictionservice.conversation.entity.AgentMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/** 消息表 Mapper；通用 CRUD 由 MyBatis-Plus 的 BaseMapper 提供。 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessageEntity> {
}
