package com.tpp.simulator.ledger;

import java.util.List;

/**
 * Hình dạng {@code Page} của hợp đồng: items, page, size, totalItems, totalPages. {@code page} là
 * {@code long} vì người gọi có thể gửi số trang rất lớn (PD-04).
 */
public record PageResult<T>(List<T> items, long page, int size, long totalItems, int totalPages) {}
