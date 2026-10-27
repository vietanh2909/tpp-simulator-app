-- V1: lược đồ nền của U1 (xem entities.md và contract-summary.md C6).
-- Không bao giờ sửa bản chuyển này sau khi đã áp dụng; thay đổi mới đi vào V2, V3, ...
-- Các cột của U2 (deleted, rejection_reason, payload_raw_text, api_key_secret...) có sẵn ở đây
-- để U2 không phải sửa bản đã áp dụng; U1 chỉ ghi giá trị mặc định.

CREATE TABLE services (
    service_code           varchar(50)  NOT NULL,
    name                   varchar(100) NOT NULL,
    description            varchar(500),
    enabled                boolean      NOT NULL DEFAULT true,
    deleted                boolean      NOT NULL DEFAULT false,
    auth_type              varchar(20)  NOT NULL,
    api_key_header_name    varchar(100),
    -- Lưu nguyên văn theo NFR3.5; U1 luôn để rỗng (từ chối API_KEY) và không câu truy vấn nào chọn cột này.
    api_key_secret         text,
    current_version_number integer      NOT NULL,
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    CONSTRAINT pk_services PRIMARY KEY (service_code),
    CONSTRAINT ck_services_auth_type CHECK (auth_type IN ('NONE', 'API_KEY')),
    CONSTRAINT ck_services_version CHECK (current_version_number >= 1)
);

-- Mã dịch vụ duy nhất không phân biệt hoa thường (BR1.2); mọi truy vấn tìm theo lower(service_code) = lower(:code).
CREATE UNIQUE INDEX ux_services_service_code_lower ON services (lower(service_code));

CREATE TABLE service_config_versions (
    version_id            bigint GENERATED ALWAYS AS IDENTITY,
    service_code          varchar(50)  NOT NULL,
    version_number        integer      NOT NULL,
    transaction_id_path   varchar(200) NOT NULL,
    status_path           varchar(200) NOT NULL,
    timestamp_path        varchar(200),
    timestamp_format      varchar(20)  NOT NULL,
    timestamp_pattern     varchar(100),
    default_time_zone     varchar(64)  NOT NULL,
    response_http_status  integer      NOT NULL,
    -- Văn bản JSON cố định của response đã cấu hình (tối đa 64 KB, kiểm tra ở ServiceCatalog).
    response_body         text         NOT NULL,
    created_at            timestamptz  NOT NULL,
    CONSTRAINT pk_service_config_versions PRIMARY KEY (version_id),
    CONSTRAINT fk_config_versions_service FOREIGN KEY (service_code) REFERENCES services (service_code),
    CONSTRAINT uq_config_versions_service_version UNIQUE (service_code, version_number),
    CONSTRAINT ck_config_versions_version CHECK (version_number >= 1),
    CONSTRAINT ck_config_versions_http CHECK (response_http_status BETWEEN 200 AND 599),
    CONSTRAINT ck_config_versions_ts_format CHECK (timestamp_format IN ('ISO_8601', 'UNIX_SECONDS', 'UNIX_MILLIS', 'PATTERN'))
);

CREATE TABLE callbacks (
    record_id               bigint GENERATED ALWAYS AS IDENTITY,
    service_code            varchar(50)  NOT NULL,
    config_version_id       bigint       NOT NULL,
    received_at             timestamptz  NOT NULL,
    outcome                 varchar(20)  NOT NULL,
    rejection_reason        varchar(40),
    http_status_returned    integer      NOT NULL,
    request_headers_masked  jsonb        NOT NULL,
    payload_json            jsonb,
    payload_raw_text        text,
    transaction_id          varchar(255),
    status                  varchar(255),
    business_timestamp_utc  timestamptz,
    mapping_field_results   jsonb        NOT NULL,
    response_status_returned integer,
    response_body_returned  jsonb,
    CONSTRAINT pk_callbacks PRIMARY KEY (record_id),
    CONSTRAINT fk_callbacks_service FOREIGN KEY (service_code) REFERENCES services (service_code),
    CONSTRAINT fk_callbacks_config_version FOREIGN KEY (config_version_id) REFERENCES service_config_versions (version_id),
    CONSTRAINT ck_callbacks_outcome CHECK (outcome IN ('SUCCESS', 'MAPPING_ERROR', 'REJECTED'))
    -- KHÔNG có khóa ngoại từ callbacks sang transactions: Callback được ghi trước để có record_id,
    -- rồi mới thêm hoặc cập nhật Transaction (entities.md).
);

-- Đọc các lần nhận của một dịch vụ theo thứ tự nhận (U2 dùng cho danh sách callback).
CREATE INDEX ix_callbacks_service_received ON callbacks (service_code, received_at, record_id);

-- Đọc các lần nhận của một giao dịch (BR5.3) theo thứ tự nhận mà không quét toàn bảng.
CREATE INDEX ix_callbacks_service_txn_received ON callbacks (service_code, transaction_id, received_at, record_id)
    WHERE transaction_id IS NOT NULL;

CREATE TABLE transactions (
    service_code             varchar(50)  NOT NULL,
    transaction_id           varchar(255) NOT NULL,
    latest_status            varchar(255),
    latest_business_ts       timestamptz,
    latest_received_at       timestamptz  NOT NULL,
    latest_callback_id       bigint       NOT NULL,
    latest_has_mapping_error boolean      NOT NULL,
    callback_count           integer      NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (service_code, transaction_id),
    CONSTRAINT fk_transactions_service FOREIGN KEY (service_code) REFERENCES services (service_code),
    CONSTRAINT fk_transactions_latest_callback FOREIGN KEY (latest_callback_id) REFERENCES callbacks (record_id),
    CONSTRAINT ck_transactions_count CHECK (callback_count >= 1)
);

-- Danh sách giao dịch: thứ tự cố định khớp BR5.1, nên phân trang không cần sắp xếp bù.
CREATE INDEX ix_transactions_latest ON transactions (latest_received_at DESC, latest_callback_id DESC);
