package com.yupi.springbootinit.modules.chart.application;

import java.util.Collections;
import java.util.List;

/** 超时 RUNNING 租约回收结果。 */
public class ReclaimResult {
    private final List<Long> requeuedChartIds;
    private final List<Long> failedChartIds;

    public ReclaimResult(List<Long> requeuedChartIds, List<Long> failedChartIds) {
        this.requeuedChartIds = requeuedChartIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(requeuedChartIds);
        this.failedChartIds = failedChartIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(failedChartIds);
    }

    public List<Long> getRequeuedChartIds() {
        return requeuedChartIds;
    }

    public List<Long> getFailedChartIds() {
        return failedChartIds;
    }

    public int getRequeuedCount() {
        return requeuedChartIds.size();
    }

    public int getFailedCount() {
        return failedChartIds.size();
    }
}
