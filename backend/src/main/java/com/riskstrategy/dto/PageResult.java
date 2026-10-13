package com.riskstrategy.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private List<T> records;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public static <T> PageResult<T> of(IPage<T> pageResult) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(pageResult.getRecords());
        r.setTotal(pageResult.getTotal());
        r.setPage((int) pageResult.getCurrent());
        r.setSize((int) pageResult.getSize());
        r.setTotalPages((int) pageResult.getPages());
        return r;
    }
}
