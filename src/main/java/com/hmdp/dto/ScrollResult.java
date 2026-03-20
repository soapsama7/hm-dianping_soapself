package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    // 返回的相关查询结果集合
    private List<?> list;
    // 本次查询推送的最小时间戳（用于下一次查询前端传lastId）
    private Long minTime;
    // 偏移量
    private Integer offset;
}
