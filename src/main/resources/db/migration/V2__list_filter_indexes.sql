-- V2: bốn chỉ mục btree cho bộ lọc và thứ tự của hai danh sách (PD-03, BR19.7).
-- Chỉ thêm chỉ mục: không đổi bảng hay cột, không dữ liệu, không cài phần mở rộng nào (NFR6.24).
-- V1 không bao giờ sửa; thay đổi lược đồ mới đi vào V3 trở đi.

-- Danh sách callback không lọc, thứ tự BR19.4.
CREATE INDEX ix_callbacks_received ON callbacks (received_at DESC, record_id DESC);

-- Danh sách callback lọc theo outcome.
CREATE INDEX ix_callbacks_outcome_received ON callbacks (outcome, received_at DESC, record_id DESC);

-- Danh sách callback lọc theo lý do từ chối; chỉ lần nhận bị từ chối có giá trị.
CREATE INDEX ix_callbacks_rejection_received ON callbacks (rejection_reason, received_at DESC, record_id DESC)
    WHERE rejection_reason IS NOT NULL;

-- Danh sách giao dịch lọc theo dịch vụ (kèm khoảng thời gian).
CREATE INDEX ix_transactions_service_latest ON transactions (service_code, latest_received_at DESC, latest_callback_id DESC);
