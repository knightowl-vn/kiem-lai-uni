package com.universe.admin.application;

import com.universe.admin.contracts.dto.AdminDashboardStats;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.contracts.admin.dto.IdentityDashboardSnapshot;
import com.universe.identity.contracts.interfaces.IdentityDashboardContract;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private static final long RECENT_PERIOD_DAYS =
            7L;

    private final IdentityDashboardContract
            identityDashboardContract;

    private final ClockPort clock;

    public AdminDashboardService(
            IdentityDashboardContract identityDashboardContract,
            ClockPort clock
    ) {
        this.identityDashboardContract =
                Objects.requireNonNull(
                        identityDashboardContract,
                        "Identity dashboard contract không được để trống."
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "Clock port không được để trống."
                );
    }

    /**
     * Lấy toàn bộ thống kê tài khoản dùng cho
     * trang Admin Dashboard.
     */
    public AdminDashboardStats getStatistics() {
        IdentityDashboardSnapshot snapshot =
                loadSnapshot();

        return new AdminDashboardStats(
                snapshot.totalUsers(),
                snapshot.activeUsers(),
                snapshot.blockedUsers(),
                snapshot.unverifiedUsers(),
                snapshot.adminUsers(),
                snapshot.localUsers(),
                snapshot.googleUsers(),
                snapshot.newUsersSince()
        );
    }

    /**
     * Lấy 5 tài khoản mới được tạo gần nhất.
     */
    public List<AdminUserView> getRecentUsers() {
        return loadSnapshot().recentUsers();
    }

    private IdentityDashboardSnapshot loadSnapshot() {
        Instant sevenDaysAgo =
                clock.now()
                        .minus(
                                RECENT_PERIOD_DAYS,
                                ChronoUnit.DAYS
                        );

        return identityDashboardContract
                .getSnapshot(
                        sevenDaysAgo
                );
    }
}