package com.ncwu.predictionservice.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncwu.predictionservice.task.entity.ScheduledTaskEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTaskEntity> {
}


