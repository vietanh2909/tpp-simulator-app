package com.tpp.simulator.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Đọc JSON nghiêm ngặt ở ranh giới: số, boolean hoặc object/mảng cho trường chuỗi (ví dụ {@code
 * "name": 123}) bị từ chối thành 400 VALIDATION_FAILED thay vì bị ép kiểu lặng lẽ.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictTextCoercion() {
    return builder ->
        builder.postConfigurer(
            mapper -> {
              var text = mapper.coercionConfigFor(LogicalType.Textual);
              text.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
              text.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
              text.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
  }
}
