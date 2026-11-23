package com.queueless.dto;

import java.util.ArrayList;
import java.util.List;

public class BusinessResponse {
    private Long id;
    private String name;
    private List<BranchSummaryResponse> branches = new ArrayList<>();

    public BusinessResponse() {}

    public BusinessResponse(Long id, String name, List<BranchSummaryResponse> branches) {
        this.id = id;
        this.name = name;
        this.branches = branches != null ? branches : new ArrayList<>();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<BranchSummaryResponse> getBranches() {
        return branches;
    }

    public void setBranches(List<BranchSummaryResponse> branches) {
        this.branches = branches;
    }
}
