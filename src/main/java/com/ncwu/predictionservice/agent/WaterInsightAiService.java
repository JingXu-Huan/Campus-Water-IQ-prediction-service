package com.ncwu.predictionservice.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 非对话型的结构化模型任务。
 *
 * <p>该接口必须通过 {@code AiServices.builder(...)} 创建；返回类型就是模型输出契约。
 * 不在这里放缓存、数据库或权限逻辑，这些仍属于 Spring 的业务服务。</p>
 */
public interface WaterInsightAiService {

    @SystemMessage("""
            你是校园水务预测模型。根据给定的历史日用水量预测下一日的用水量。
            预测值必须是非负有限数字，不能编造不存在的数据。
            """)
    @UserMessage("历史日用水量：{{it}}")
    WaterUsagePrediction predictTomorrowWaterUsage(WaterUsageHistory input);

    @SystemMessage("你是校园节水助手。给出一条简洁、可执行、中文的节水建议，长度约 20 字。")
    @UserMessage("请生成一条校园节水建议。")
    TextSuggestion suggestWaterUsage();

    @SystemMessage("你是校园水质助手。根据给定指标给出简洁、专业、中文的水质评价和建议。")
    @UserMessage("水质指标：{{it}}")
    TextSuggestion suggestWaterQuality(WaterQualityMetrics input);

    @SystemMessage("你是校园水务助手。根据水质合格率给出一句不超过 20 字、带有情绪价值的中文评语。")
    @UserMessage("水质合格率：{{it}}")
    TextSuggestion suggestDeviceQuality(DeviceQualityRate input);
}
