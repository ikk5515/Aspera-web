package com.aspera.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        // /files is valid for both USER and ADMIN accounts. Redirecting every user to
        // the administrator page caused a 403/blank landing page for normal users.
        return "redirect:/files";
    }
}
