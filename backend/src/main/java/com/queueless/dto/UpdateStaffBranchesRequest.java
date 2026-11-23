package com.queueless.dto;

import java.util.ArrayList;
import java.util.List;

public class UpdateStaffBranchesRequest {
    private List<Long> branchIds = new ArrayList<>();

    public UpdateStaffBranchesRequest() {}

    public UpdateStaffBranchesRequest(List<Long> branchIds) {
        this.branchIds = branchIds != null ? branchIds : new ArrayList<>();
    }

    public List<Long> getBranchIds() {
        return branchIds;
    }

    public void setBranchIds(List<Long> branchIds) {
        this.branchIds = branchIds;
    }
}
