package com.universe.identity.entry.api;

import com.universe.identity.application.registration.RegisterUserCommand;
import com.universe.identity.application.registration.RegisterUserUseCase;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.entry.api.request.RegisterUserRequest;
import com.universe.shared.web.ApiResponse;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;

    public AuthController(RegisterUserUseCase registerUserUseCase) {
        this.registerUserUseCase = registerUserUseCase;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserDTO> register(@Valid @RequestBody RegisterUserRequest request) {
        RegisterUserCommand command = new RegisterUserCommand(
                request.email(),
                request.password(),
                request.displayName()
        );
        
        UserDTO result = registerUserUseCase.execute(command);
        return ApiResponse.success(result);
    }
}