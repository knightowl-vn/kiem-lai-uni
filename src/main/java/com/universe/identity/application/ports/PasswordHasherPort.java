package com.universe.identity.application.ports;

/**
 * Port kỹ thuật định nghĩa hợp đồng mã hóa và xác thực mật khẩu.
 * Cách ly thuật toán (BCrypt/Argon2) khỏi tầng Core.
 */
public interface PasswordHasherPort {
    /**
     * Băm mật khẩu gốc thành chuỗi an toàn.
     * @param rawPassword Mật khẩu người dùng nhập (Plain text)
     * @return Mật khẩu đã được mã hóa (Hash)
     */
    String hash(String rawPassword);

    /**
     * Đối chiếu mật khẩu nhập vào với mật khẩu đã lưu trong Database.
     * @param rawPassword Mật khẩu người dùng nhập
     * @param encodedPassword Mật khẩu đã băm
     * @return true nếu khớp, false nếu sai
     */
    boolean verify(String rawPassword, String encodedPassword);
}