package com.uply.coupon.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean existsById(Long userId) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE user_id = ?", Long.class, userId);
        return count != null && count > 0;
    }
}
