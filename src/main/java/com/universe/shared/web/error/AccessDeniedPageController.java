package com.universe.shared.web.error;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AccessDeniedPageController {

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "error/403";
    }
}