package com.tpp.simulator.mapping;

/** Mã lỗi ánh xạ của từng trường (nằm trong {@link FieldResult}, không phải mã HTTP). */
public enum MappingErrorCode {
  NOT_FOUND,
  EMPTY,
  WRONG_TYPE,
  TOO_LONG,
  PAYLOAD_NOT_OBJECT,
  UNPARSEABLE
}
