package com.ncwu.predictionservice.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** agent_conversation 表对应的 MyBatis-Plus 实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName("agent_conversation")
public class AgentConversationEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private String userId;
    private String title;
    private String summary;
    private Integer summarizedMessageCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
    private LocalDateTime deletedAt;
}
