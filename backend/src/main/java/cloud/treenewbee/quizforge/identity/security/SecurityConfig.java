package cloud.treenewbee.quizforge.identity.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import cloud.treenewbee.quizforge.identity.infrastructure.SessionMapper;

@Configuration
public class SecurityConfig {

    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain metricsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator", "/actuator/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${qf.security.allowed-origin}") String allowedOrigin,
            SessionMapper sessions,
            Clock clock) throws Exception {
        http
                // 会话由 auth_session 持久化。
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 认证与退出由应用接口处理。
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                // 写请求通过 Origin 校验防护（ADR-005）。
                .csrf(c -> c.disable())
                // 健康检查和认证入口允许匿名访问。
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/health",
                                "/api/auth/register", "/api/auth/login", "/api/auth/logout").permitAll()
                        .anyRequest().authenticated())
                // 认证失败也使用统一 JSON 错误格式。
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated()))
                .addFilterBefore(new OriginCheckFilter(allowedOrigin), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new SessionAuthenticationFilter(sessions, clock),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static AuthenticationEntryPoint unauthenticated() {
        return (request, response, exception) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}}");
        };
    }
}
