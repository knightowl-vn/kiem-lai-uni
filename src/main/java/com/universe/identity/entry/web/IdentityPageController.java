package com.universe.identity.entry.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IdentityPageController {

    @GetMapping({"/", "/home"})
    public String homePage() {
        return "home";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "identity/register";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "identity/login";
    }
}