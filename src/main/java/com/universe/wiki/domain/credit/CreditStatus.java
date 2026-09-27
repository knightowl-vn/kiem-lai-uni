package com.universe.wiki.domain.credit;

/**
 * Trạng thái ghi nhận công trạng người đóng góp (Contributor Credit Status).
 */
public enum CreditStatus {

    /**
     * Công trạng đang có hiệu lực.
     */
    ACTIVE,

    /**
     * Công trạng đã bị thu hồi bởi Quản trị viên cấp cao (SUPER_ADMIN).
     */
    REVOKED
}
