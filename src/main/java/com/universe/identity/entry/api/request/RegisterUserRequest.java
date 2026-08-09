package com.universe.identity.entry.api.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO nhận dữ liệu đăng ký từ Client (Frontend/Mobile).
 */
public record RegisterUserRequest(
		@NotBlank(message = "Email không được để trống") @Email(message = "Email không đúng định dạng") String email,

		@NotBlank(message = "Mật khẩu không được để trống") @Size(min = 8, max = 64, message = "Mật khẩu phải từ 8 đến 64 ký tự") @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).*$", message = "Mật khẩu phải chứa ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ số") String password,

		@NotBlank(message = "Tên hiển thị không được để trống") @Size(min = 3, max = 50, message = "Tên hiển thị phải từ 3 đến 50 ký tự") String displayName) {
}