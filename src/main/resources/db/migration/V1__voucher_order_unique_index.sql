-- Existing databases must execute this migration once before enabling the reliable Stream consumer.
-- Remove duplicate historical rows first if this statement reports a duplicate-key error.
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`);
