package com.example.webdavgallery.dto;

import java.util.List;

/** 分页结果：items 当前页数据，hasMore 是否还有下一页，total 目录总条数 */
public class PageResult<T> {

    private List<T> items;
    private boolean hasMore;
    private long total;

    public PageResult() {
    }

    public PageResult(List<T> items, boolean hasMore, long total) {
        this.items = items;
        this.hasMore = hasMore;
        this.total = total;
    }

    public List<T> getItems() { return items; }

    public void setItems(List<T> items) { this.items = items; }

    public boolean isHasMore() { return hasMore; }

    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }

    public long getTotal() { return total; }

    public void setTotal(long total) { this.total = total; }
}
