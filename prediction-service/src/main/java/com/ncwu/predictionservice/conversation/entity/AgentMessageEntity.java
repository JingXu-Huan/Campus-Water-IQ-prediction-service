package com.ncwu.predictionservice.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** agent_message 表对应的 MyBatis-Plus 实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName("agent_message")
public class AgentMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private UUID conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
