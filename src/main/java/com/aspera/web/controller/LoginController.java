package com.aspera.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    // 로그인 페이지 요청 처리 (Data Flow View)
    // 1. 요청: 사용자가 브라우저 주소창에 '/login'을 입력하거나(GET), 권한이 없는 페이지에 접근하여 리다이렉트될 때 호출
    // 2. 반환: 'login' 뷰 템플릿(login.html)을 렌더링하여 반환
    // (실제 로그인 처리는 POST /login 요청으로 SecurityConfig에서 수행)
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
