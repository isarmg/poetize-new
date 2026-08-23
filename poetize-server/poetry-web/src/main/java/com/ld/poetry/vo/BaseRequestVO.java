package com.ld.poetry.vo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(value = {
        "orders", "maxLimit", "countId", "searchCount", "optimizeCountSql", "optimizeJoinOfCountSql"
}, allowGetters = true)
public class BaseRequestVO extends Page {

    private static final long MAX_PAGE_SIZE = 100L;

    private static final long MAX_PAGE_NUMBER = 100_000L;

    private String order;

    private boolean desc = true;

    private Integer source;

    private String commentType;

    private Integer floorCommentId;

    private String searchKey;

    private String articleSearch;

    // 是否推荐[0:否，1:是]
    private Boolean recommendStatus;

    private Integer sortId;

    private Integer labelId;

    private Boolean userStatus;

    private Integer userType;

    private Integer userId;

    private String resourceType;

    private Boolean status;

    private String classify;

    @Override
    public BaseRequestVO setSize(long size) {
        super.setSize(Math.min(Math.max(size, 1L), MAX_PAGE_SIZE));
        return this;
    }

    @Override
    public BaseRequestVO setCurrent(long current) {
        super.setCurrent(Math.min(Math.max(current, 1L), MAX_PAGE_NUMBER));
        return this;
    }
}
