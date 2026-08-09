package com.universe.identity.domain;

import com.universe.identity.contracts.events.UserRegisteredEvent;
import com.universe.identity.domain.exceptions.InvalidBioException;
import com.universe.identity.domain.exceptions.InvalidDisplayNameException;
import com.universe.shared.events.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho tài khoản người dùng.
 *
 * User chịu trách nhiệm bảo vệ các quy tắc nghiệp vụ liên quan đến:
 * - định danh;
 * - hồ sơ cá nhân;
 * - trạng thái tài khoản;
 * - vai trò;
 * - phương thức xác thực.
 */
public class User {

    private final UUID id;
    private final Email email;

    private String passwordHash;
    private String displayName;
    private String avatarUrl;
    private boolean avatarCustomized;
    private String bio;

    private UserStatus status;
    private UserRole role;

    /**
     * Phương thức đăng nhập gốc của tài khoản.
     */
    private AuthProvider authProvider;

    /**
     * Mã định danh do nhà cung cấp OAuth cung cấp.
     *
     * Với Google, đây là claim "sub".
     * Tài khoản LOCAL có giá trị null.
     */
    private String providerSubject;

    /**
     * Version nghiệp vụ của aggregate.
     *
     * Không phải persistence version của Hibernate.
     */
    private long aggregateVersion;

    private final Instant createdAt;

    private final List<DomainEvent> domainEvents =
            new ArrayList<>();

    /**
     * Constructor dùng khi tạo tài khoản LOCAL mới.
     */
    private User(
            UUID id,
            Email email,
            String passwordHash,
            String displayName,
            Instant createdAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "User ID không được để trống."
                );

        this.email =
                Objects.requireNonNull(
                        email,
                        "Email không được để trống."
                );

        this.passwordHash =
                requirePasswordHash(passwordHash);

        this.displayName =
                normalizeDisplayName(displayName);

        this.avatarUrl = null;
        this.avatarCustomized = false;
        this.bio = null;

        this.status = UserStatus.ACTIVE;
        this.role = UserRole.USER;

        this.authProvider = AuthProvider.LOCAL;
        this.providerSubject = null;

        this.aggregateVersion = 1L;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo không được để trống."
                );
    }

    /**
     * Constructor dùng để:
     * - tái tạo aggregate từ database;
     * - tạo tài khoản OAuth.
     *
     * Constructor này không phát domain event.
     */
    private User(
            UUID id,
            Email email,
            String passwordHash,
            String displayName,
            String avatarUrl,
            boolean avatarCustomized,
            String bio,
            UserStatus status,
            UserRole role,
            AuthProvider authProvider,
            String providerSubject,
            long aggregateVersion,
            Instant createdAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "User ID không được để trống."
                );

        this.email =
                Objects.requireNonNull(
                        email,
                        "Email không được để trống."
                );

        /*
         * Có thể null với tài khoản Google
         * chưa tạo mật khẩu local.
         */
        this.passwordHash =
                normalizeNullablePasswordHash(
                        passwordHash
                );

        this.displayName =
                normalizeDisplayName(displayName);

        this.avatarUrl =
                normalizeNullableAvatarUrl(
                        avatarUrl
                );

        this.avatarCustomized =
                avatarCustomized;

        this.bio =
                normalizeNullableBio(bio);

        this.status =
                Objects.requireNonNull(
                        status,
                        "Trạng thái tài khoản không được để trống."
                );

        this.role =
                role == null
                        ? UserRole.USER
                        : role;

        this.authProvider =
                authProvider == null
                        ? AuthProvider.LOCAL
                        : authProvider;

        this.providerSubject =
                normalizeProviderSubject(
                        this.authProvider,
                        providerSubject
                );

        if (aggregateVersion < 1L) {
            throw new IllegalArgumentException(
                    "Aggregate version phải lớn hơn hoặc bằng 1."
            );
        }

        this.aggregateVersion =
                aggregateVersion;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo không được để trống."
                );
    }

    /**
     * Tạo tài khoản LOCAL mới.
     *
     * passwordHash phải được Application layer
     * kiểm tra và hash trước khi truyền vào Domain.
     */
    public static User createLocal(
            UUID id,
            Email email,
            String passwordHash,
            String displayName,
            Instant now
    ) {
        User user =
                new User(
                        id,
                        email,
                        passwordHash,
                        displayName,
                        now
                );

        user.addRegisteredEvent();

        return user;
    }

    /**
     * Tạo tài khoản Google mới.
     *
     * Tài khoản Google mới:
     * - chưa có mật khẩu local;
     * - chưa có bio;
     * - có thể có avatar lấy từ Google.
     */
    public static User createGoogle(
            UUID id,
            Email email,
            String displayName,
            String avatarUrl,
            String providerSubject,
            Instant now
    ) {
        String normalizedSubject =
                requireProviderSubject(
                        providerSubject,
                        AuthProvider.GOOGLE
                );

        User user =
                new User(
                        id,
                        email,
                        null,
                        displayName,
                        avatarUrl,
                        false,
                        null,
                        UserStatus.ACTIVE,
                        UserRole.USER,
                        AuthProvider.GOOGLE,
                        normalizedSubject,
                        1L,
                        now
                );

        user.addRegisteredEvent();

        return user;
    }

    /**
     * Tái tạo User từ dữ liệu persistence.
     *
     * Không phát domain event trong quá trình rehydrate.
     */
    public static User rehydrate(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            String avatarUrl,
            boolean avatarCustomized,
            String bio,
            UserStatus status,
            UserRole role,
            AuthProvider authProvider,
            String providerSubject,
            long aggregateVersion,
            Instant createdAt
    ) {
        return new User(
                id,
                new Email(email),
                passwordHash,
                displayName,
                avatarUrl,
                avatarCustomized,
                bio,
                status,
                role,
                authProvider,
                providerSubject,
                aggregateVersion,
                createdAt
        );
    }

    /**
     * Cập nhật tên hiển thị.
     */
    public void updateDisplayName(
            String newDisplayName
    ) {
        String normalizedName =
                normalizeDisplayName(
                        newDisplayName
                );

        if (this.displayName.equals(
                normalizedName
        )) {
            return;
        }

        this.displayName =
                normalizedName;

        increaseAggregateVersion();
    }

    /**
     * Cập nhật phần giới thiệu cá nhân.
     *
     * Truyền null hoặc chuỗi trắng
     * sẽ xóa bio hiện tại.
     */
    public void updateBio(
            String newBio
    ) {
        String normalizedBio =
                normalizeNullableBio(
                        newBio
                );

        if (Objects.equals(
                this.bio,
                normalizedBio
        )) {
            return;
        }

        this.bio =
                normalizedBio;

        increaseAggregateVersion();
    }

    /**
     * Cập nhật URL ảnh đại diện.
     *
     * Truyền null hoặc chuỗi trắng
     * sẽ đưa avatar về null.
     */
    public void updateAvatarUrl(
            String newAvatarUrl
    ) {
        String normalizedAvatarUrl =
                normalizeNullableAvatarUrl(
                        newAvatarUrl
                );

        boolean avatarChanged =
                !Objects.equals(
                        this.avatarUrl,
                        normalizedAvatarUrl
                );

        boolean customizationChanged =
                !this.avatarCustomized;

        if (!avatarChanged
                && !customizationChanged) {
            return;
        }

        this.avatarUrl =
                normalizedAvatarUrl;

        /*
         * User đã chủ động tải avatar lên.
         * Google không được ghi đè avatar này
         * trong các lần đăng nhập sau.
         */
        this.avatarCustomized = true;

        increaseAggregateVersion();
    }

    /**
     * Xóa avatar hiện tại.
     */
    public void removeAvatar() {
        boolean avatarChanged =
                avatarUrl != null;

        boolean customizationChanged =
                !avatarCustomized;

        if (!avatarChanged
                && !customizationChanged) {
            return;
        }

        avatarUrl = null;

        /*
         * User đã chủ động xóa avatar.
         * Vì vậy lần đăng nhập Google sau
         * không được tự khôi phục ảnh Google.
         */
        avatarCustomized = true;

        increaseAggregateVersion();
    }

    /**
     * Cập nhật mật khẩu đã được hash.
     */
    public void updatePasswordHash(
            String newPasswordHash
    ) {
        String normalizedHash =
                requirePasswordHash(
                        newPasswordHash
                );

        if (normalizedHash.equals(
                this.passwordHash
        )) {
            return;
        }

        this.passwordHash =
                normalizedHash;

        increaseAggregateVersion();
    }

    /**
     * Tạo mật khẩu cho tài khoản OAuth
     * chưa có mật khẩu local.
     */
    public void createPasswordHash(
            String newPasswordHash
    ) {
        if (hasPassword()) {
            throw new IllegalStateException(
                    "Tài khoản đã có mật khẩu."
            );
        }

        this.passwordHash =
                requirePasswordHash(
                        newPasswordHash
                );

        increaseAggregateVersion();
    }

    public boolean hasPassword() {
        return passwordHash != null
                && !passwordHash.isBlank();
    }

    public boolean isGoogleAccount() {
        return authProvider
                == AuthProvider.GOOGLE;
    }

    /**
     * Liên kết tài khoản hiện tại với Google.
     *
     * Sau khi liên kết, tài khoản vẫn giữ mật khẩu local
     * nếu trước đó đã có mật khẩu.
     */
    public void linkGoogleAccount(
            String googleSubject
    ) {
        String normalizedSubject =
                requireProviderSubject(
                        googleSubject,
                        AuthProvider.GOOGLE
                );

        if (authProvider
                == AuthProvider.GOOGLE) {

            if (!Objects.equals(
                    providerSubject,
                    normalizedSubject
            )) {
                throw new IllegalStateException(
                        "Tài khoản đã liên kết với "
                                + "một tài khoản Google khác."
                );
            }

            return;
        }

        this.authProvider =
                AuthProvider.GOOGLE;

        this.providerSubject =
                normalizedSubject;

        increaseAggregateVersion();
    }

    /**
     * Chỉ bổ sung thông tin Google
     * khi hồ sơ hiện tại chưa có giá trị tương ứng.
     *
     * Không ghi đè tên hoặc avatar
     * mà người dùng đã tự cập nhật.
     */
    public void updateOAuthProfileIfMissing(
            String oauthDisplayName,
            String oauthAvatarUrl
    ) {
        boolean changed = false;

        /*
         * Chỉ lấy tên Google khi profile hiện tại
         * chưa có display name.
         */
        if ((displayName == null
                || displayName.isBlank())
                && oauthDisplayName != null
                && !oauthDisplayName.isBlank()) {

            this.displayName =
                    normalizeDisplayName(
                            oauthDisplayName
                    );

            changed = true;
        }

        /*
         * Chỉ lấy avatar Google khi:
         * 1. User chưa từng tự đổi/xóa avatar.
         * 2. Profile hiện tại chưa có avatar.
         * 3. Google thực sự trả về avatar.
         */
        if (!avatarCustomized
                && (avatarUrl == null
                || avatarUrl.isBlank())
                && oauthAvatarUrl != null
                && !oauthAvatarUrl.isBlank()) {

            this.avatarUrl =
                    normalizeNullableAvatarUrl(
                            oauthAvatarUrl
                    );

            changed = true;
        }

        if (changed) {
            increaseAggregateVersion();
        }
    }

    /**
     * Khóa tài khoản.
     */
    public void block() {
        if (status
                == UserStatus.BANNED) {

            throw new IllegalStateException(
                    "Tài khoản đã bị cấm vĩnh viễn."
            );
        }

        if (status
                == UserStatus.BLOCKED) {
            return;
        }

        status =
                UserStatus.BLOCKED;

        increaseAggregateVersion();
    }

    /**
     * Kích hoạt hoặc mở khóa tài khoản.
     */
    public void activate() {
        if (status
                == UserStatus.ACTIVE) {
            return;
        }

        status =
                UserStatus.ACTIVE;

        increaseAggregateVersion();
    }

    /**
     * Cấm tài khoản.
     */
    public void ban() {
        if (status
                == UserStatus.BANNED) {
            return;
        }

        status =
                UserStatus.BANNED;

        increaseAggregateVersion();
    }

    /**
     * Đánh dấu tài khoản chưa xác minh.
     */
    public void markUnverified() {
        if (status
                == UserStatus.UNVERIFIED) {
            return;
        }

        status =
                UserStatus.UNVERIFIED;

        increaseAggregateVersion();
    }

    /**
     * Thay đổi vai trò tài khoản.
     */
    public void changeRole(
            UserRole newRole
    ) {
        Objects.requireNonNull(
                newRole,
                "Vai trò mới không được để trống."
        );

        if (this.role == newRole) {
            return;
        }

        this.role = newRole;

        increaseAggregateVersion();
    }

    /**
     * Thêm sự kiện đăng ký tài khoản.
     */
    private void addRegisteredEvent() {
        domainEvents.add(
                UserRegisteredEvent.create(
                        id,
                        email.value(),
                        displayName,
                        createdAt
                )
        );
    }

    /**
     * Chuẩn hóa và kiểm tra tên hiển thị.
     */
    private static String normalizeDisplayName(
            String displayName
    ) {
        if (displayName == null) {
            throw new InvalidDisplayNameException(
                    "Tên hiển thị không được để trống."
            );
        }

        String normalizedName =
                displayName.trim();

        if (normalizedName.length() < 3
                || normalizedName.length() > 50) {

            throw new InvalidDisplayNameException(
                    "Tên hiển thị phải từ 3 đến 50 ký tự."
            );
        }

        if (!normalizedName.matches(
                "^[\\p{L}0-9_\\s]+$"
        )) {
            throw new InvalidDisplayNameException(
                    "Tên hiển thị chứa ký tự không hợp lệ."
            );
        }

        return normalizedName;
    }

    /**
     * Chuẩn hóa bio.
     *
     * Bio:
     * - có thể null;
     * - chuỗi trắng được xem là null;
     * - tối đa 500 ký tự.
     */
    private static String normalizeNullableBio(
            String bio
    ) {
        if (bio == null
                || bio.isBlank()) {
            return null;
        }

        String normalizedBio =
                bio.trim();

        if (normalizedBio.length() > 500) {
            throw new InvalidBioException(
                    "Phần giới thiệu không được vượt quá 500 ký tự."
            );
        }

        return normalizedBio;
    }

    /**
     * Yêu cầu password hash phải có giá trị.
     */
    private static String requirePasswordHash(
            String passwordHash
    ) {
        if (passwordHash == null
                || passwordHash.isBlank()) {

            throw new IllegalArgumentException(
                    "Password hash không được để trống."
            );
        }

        return passwordHash.trim();
    }

    /**
     * Chuẩn hóa password hash có thể null.
     *
     * Dùng cho tài khoản OAuth
     * chưa tạo mật khẩu local.
     */
    private static String
    normalizeNullablePasswordHash(
            String passwordHash
    ) {
        if (passwordHash == null
                || passwordHash.isBlank()) {
            return null;
        }

        return passwordHash.trim();
    }

    /**
     * Chuẩn hóa URL avatar.
     */
    private static String normalizeNullableAvatarUrl(
            String avatarUrl
    ) {
        if (avatarUrl == null
                || avatarUrl.isBlank()) {
            return null;
        }

        return avatarUrl.trim();
    }

    /**
     * Chuẩn hóa provider subject
     * theo auth provider.
     */
    private static String normalizeProviderSubject(
            AuthProvider authProvider,
            String providerSubject
    ) {
        if (authProvider
                == AuthProvider.LOCAL) {
            return null;
        }

        return requireProviderSubject(
                providerSubject,
                authProvider
        );
    }

    /**
     * Yêu cầu provider subject phải tồn tại
     * đối với tài khoản OAuth.
     */
    private static String requireProviderSubject(
            String providerSubject,
            AuthProvider authProvider
    ) {
        if (providerSubject == null
                || providerSubject.isBlank()) {

            throw new IllegalArgumentException(
                    "Provider subject không được để trống "
                            + "đối với tài khoản "
                            + authProvider.name()
                            + "."
            );
        }

        return providerSubject.trim();
    }

    private void increaseAggregateVersion() {
        aggregateVersion++;
    }

    /**
     * Trả về bản sao không thể chỉnh sửa
     * của danh sách domain event hiện tại.
     */
    public List<DomainEvent>
    domainEventsSnapshot() {
        return Collections.unmodifiableList(
                new ArrayList<>(domainEvents)
        );
    }

    /**
     * Xóa các event sau khi đã được ghi vào outbox.
     */
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    public UUID getId() {
        return id;
    }

    public Email getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public boolean isAvatarCustomized() {
        return avatarCustomized;
    }

    public String getBio() {
        return bio;
    }

    public UserStatus getStatus() {
        return status;
    }

    public UserRole getRole() {
        return role;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}