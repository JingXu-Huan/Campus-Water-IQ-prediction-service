package com.ncwu.common.domain.dto;

import lombok.Data;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;

/** Repair-report payload shared with the repair service. */
@Data
public class UserReportDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String deviceCode;
    private String uid;
    @Size(max = 64, message = "报修人姓名长度不能超过64个字符") private String reportName;
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入合法的中国大陆手机号") private String contactInfo;
    @Size(max = 500, message = "故障描述长度不能超过500个字符") private String desc;
    @Min(1) @Max(3) private int severity;
    @Pattern(regexp = "DRAFT|CONFIRMED|PROCESSING|DONE|CANCELLED", message = "status 只能是 DRAFT、CONFIRMED、PROCESSING、DONE 或 CANCELLED") private String status;
    @Size(max = 255, message = "备注长度不能超过255个字符") private String remark;
}
