package com.breathAI.ttobagi_server.global.config;

import com.breathAI.ttobagi_server.global.security.AuditLogFilter;
import com.breathAI.ttobagi_server.global.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

// JWT 기반 무상태 인증 및 경로별 접근 권한 설정
// 컨트롤러의 @PreAuthorize가 동작하도록 메서드 보안도 켠다
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // 허용 오리진 목록 (환경변수 CORS_ALLOWED_ORIGINS)
    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuditLogFilter auditLogFilter;

    // 비밀번호 단방향 암호화 인코더
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 보안 필터 체인 구성
    // CSRF 비활성화, CORS 허용, 무상태 세션, 경로별 권한, JWT 필터 등록 순
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(allowedOrigins);
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                // 다운로드 파일 이름을 프론트엔드가 읽을 수 있게 한다
                config.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
                config.setAllowCredentials(true);
                return config;
            }))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                // 토큰이 없거나 유효하지 않은 요청은 401로 응답한다 (권한 부족 403과 구분)
                .authenticationEntryPoint((request, response, authException) -> {
                       if (response.isCommitted()) return;
                       response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                       response.setContentType("application/json;charset=UTF-8");
                       response.getWriter().write("{\"success\":false,\"message\":\"로그인이 필요합니다.\",\"status\":401}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                       if (response.isCommitted()) return;
                       response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                       response.setContentType("application/json;charset=UTF-8");
                       response.getWriter().write("{\"success\":false,\"message\":\"ADMIN 계정만 접근 가능합니다.\",\"status\":403}");
                })
            )
            .authorizeHttpRequests(authz -> authz
                // SSE 응답을 마무리하는 내부 재요청은 최초 요청에서 이미 인증을 거쳤으므로 다시 검사하지 않는다
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // 로그인 전에 쓰는 인증 API만 연다 (내 정보·탈퇴·승격은 로그인 필요)
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/signup",
                        "/api/v1/auth/signup/code",
                        "/api/v1/auth/login",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/reissue",
                        "/api/v1/auth/password/reset",
                        "/api/v1/auth/password/reset/confirm").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/dashboard/analyze/callback/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/faq/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/faq/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/faq/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/faq/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 토큰의 사용자를 알 수 있고 401·403으로 막힌 요청도 남도록 JWT 필터 바로 뒤에 둔다
            .addFilterAfter(auditLogFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
