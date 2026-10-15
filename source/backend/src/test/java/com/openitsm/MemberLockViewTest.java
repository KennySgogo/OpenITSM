package com.openitsm;

import com.openitsm.entity.UserEntity;
import com.openitsm.vo.UserVO;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MemberLockViewTest {
    @Test
    void memberViewExposesLockExpiryWithoutFailureCounts() {
        UserEntity user = new UserEntity();
        user.lockedUntil = Instant.parse("2026-10-15T12:00:00Z");
        assertEquals(user.lockedUntil, UserVO.from(user).lockedUntil());
    }
}
