package com.ncwu.common.domain.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Seven-day usage data for the three campuses. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ToAIBO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private List<Double> HY = new ArrayList<>(7);
    private List<Double> LH = new ArrayList<>(7);
    private List<Double> JH = new ArrayList<>(7);
}
