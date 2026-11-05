package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User getOrCreateUser(String userId, Jwt jwt) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User ID missing from token");
        }

        return userRepository.findById(userId)
                .orElseGet(() -> createUser(userId, jwt));
    }

    @Transactional
    public User createUser(String userId, Jwt jwt) {
        String email = resolveEmail(jwt, userId);

        User user = User.builder()
                .id(userId)
                .email(email)
                .passwordHash("oauth-user")
                .themePref("light")
                .build();

        return userRepository.save(user);
    }

    private String resolveEmail(Jwt jwt, String userId) {
        if (jwt != null) {
            String email = jwt.getClaimAsString("email");
            if (email != null && !email.isBlank()) {
                return email;
            }

            Object claim = jwt.getClaims().get("email");
            if (claim instanceof String stringEmail && !stringEmail.isBlank()) {
                return stringEmail;
            }
        }

        return userId + "@supabase.local";
    }
}

