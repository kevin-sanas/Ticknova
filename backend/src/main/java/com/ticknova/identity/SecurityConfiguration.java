package com.ticknova.identity;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableAutoConfiguration(exclude=UserDetailsServiceAutoConfiguration.class)
public class SecurityConfiguration {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean public SecurityFilterChain apiSecurity(HttpSecurity http,AuthService auth,SecurityProblems problems) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> cors.disable())
            .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rules -> rules.requestMatchers("/api/v1/auth/login","/actuator/health","/actuator/health/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error) -> problems.write(request,response,401,"UNAUTHENTICATED","请重新登录"))
                .accessDeniedHandler((request,response,error) -> problems.write(request,response,403,"FORBIDDEN","无权执行此操作")))
            .addFilterBefore(new BearerSessionFilter(auth,problems),UsernamePasswordAuthenticationFilter.class).build();
    }
}
