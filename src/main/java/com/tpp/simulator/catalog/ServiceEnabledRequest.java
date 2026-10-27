package com.tpp.simulator.catalog;

/** Thân yêu cầu {@code PUT /api/services/{serviceCode}/enabled} (BR13.2). */
public record ServiceEnabledRequest(Boolean enabled) {}
