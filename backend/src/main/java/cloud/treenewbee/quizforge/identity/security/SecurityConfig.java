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
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${qf.security.allowed-origin}")String allowedOrigin, SessionMapper sessions, Clock clock) throws Exception {
        http
                // ① 不使用 Spring 的 HttpSession：会话存在我们自己的 auth_session 表里（ADR-005）
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ② 关闭 Spring 自带的表单登录、HTTP Basic 登录和退出页面：我们有自己的接口
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                // ③ 关闭 Spring 自带的 CSRF Token：改用 SameSite Cookie + Origin 校验（ADR-005），
                .csrf(c -> c.disable())
                // ④ 哪些接口不需要登录
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/health",
                                "/api/auth/register", "/api/auth/login", "/api/auth/logout").permitAll()
                        .anyRequest().authenticated())
                // ⑤ 未登录时，返回我们统一的错误格式，而不是 Spring 默认的页面
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