package com.tpp.simulator.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tpp.simulator.config.FieldError;
import java.util.List;

/**
 * Thân lỗi {@code ErrorResponse} của hợp đồng: {@code fieldErrors} chỉ có khi {@code error} là
 * VALIDATION_FAILED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String error, String message, List<FieldError> fieldErrors) {}
