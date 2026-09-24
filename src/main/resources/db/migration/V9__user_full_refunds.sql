ALTER TABLE et_refund DROP CHECK ck_et_refund_reason;
ALTER TABLE et_refund ADD CONSTRAINT ck_et_refund_reason
    CHECK (reason IN ('LATE_PAYMENT','USER_REQUEST'));
