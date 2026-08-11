package com.aspera.web.service;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 사용자 정보 로드 메서드 (Data Flow Validation)
    // 1. 호출: SecurityConfig에서 설정된 AuthenticationManager가 인증 도중 이 메서드를 호출
    // 2. 입력: 사용자가 로그인 폼에 입력한 username이 전달됨
    // 3. 처리: UserRepository를 통해 DB에서 해당 username을 가진 사용자(User 엔티티)를 조회
    // 4. 변환: DB의 User 엔티티를 Spring Security가 이해할 수 있는 UserDetails 객체로 변환하여 반환
    // 이때 암호화된 비밀번호(BCrypt)와 권한(Role) 정보가 포함됨
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password."));

        if (!"USER".equals(user.getRole()) && !"ADMIN".equals(user.getRole())) {
            throw new UsernameNotFoundException("Invalid username or password.");
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .roles(user.getRole())
                .build();
    }
}
