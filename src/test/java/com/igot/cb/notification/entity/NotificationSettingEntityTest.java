package com.igot.cb.notification.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingEntityTest {

    @Test
    void testNoArgsConstructorAndSetters() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        entity.setId(1L);
        entity.setUserId("user123");
        entity.setNotificationType("EMAIL");
        entity.setEnabled(false);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(true);

        assertEquals(1L, entity.getId());
        assertEquals("user123", entity.getUserId());
        assertEquals("EMAIL", entity.getNotificationType());
        assertFalse(entity.isEnabled());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
        assertTrue(entity.isDeleted());
    }

    @Test
    void testAllArgsConstructor() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity entity = new NotificationSettingEntity(
                10L, "user456", "SMS", true, now, now, false
        );

        assertEquals(10L, entity.getId());
        assertEquals("user456", entity.getUserId());
        assertEquals("SMS", entity.getNotificationType());
        assertTrue(entity.isEnabled());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
        assertFalse(entity.isDeleted());
    }

    @Test
    void testBuilder() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity entity = NotificationSettingEntity.builder()
                .id(99L)
                .userId("builderUser")
                .notificationType("PUSH")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .isDeleted(false)
                .build();

        assertEquals(99L, entity.getId());
        assertEquals("builderUser", entity.getUserId());
        assertEquals("PUSH", entity.getNotificationType());
        assertTrue(entity.isEnabled());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
        assertFalse(entity.isDeleted());
    }

    @Test
    void testEqualsAndHashCode_equalObjects() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testEquals_differentValues() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(2L, "u2", "SMS", false, now, now, true);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_nullAndDifferentClass() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        assertNotEquals(null, entity);
        assertNotEquals("some string", entity);
    }

    @Test
    void testEquals_selfReference() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        assertEquals(entity, entity);
    }

    @Test
    void testEquals_handlesNullFields() {
        LocalDateTime now = LocalDateTime.now();

        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, null, null, true, null, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, null, null, true, null, now, false);
        NotificationSettingEntity e3 = new NotificationSettingEntity(1L, "notNull", null, true, null, now, false);

        assertEquals(e1, e2);
        assertNotEquals(e1, e3);
    }

    @Test
    void testHashCode_nullFields() {
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, null, null, true, null, null, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, null, null, true, null, null, false);

        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testToString() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        entity.setUserId("userX");
        entity.setNotificationType("EMAIL");
        entity.setEnabled(true);

        String str = entity.toString();
        assertTrue(str.contains("userX"));
        assertTrue(str.contains("EMAIL"));
        assertTrue(str.contains("true"));
    }

    @Test
    void testDefaultValues() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        assertTrue(entity.isEnabled());
        assertFalse(entity.isDeleted());
    }

    @Test
    void testEquals_differentDates() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime later = now.plusDays(1);

        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, later, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentUserId() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "user1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "user2", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentNotificationType() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "SMS", true, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentCreatedAt() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime later = now.plusHours(2);
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, later, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentUpdatedAt() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime later = now.plusHours(2);
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, later, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentIsDeleted() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, true);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_differentId() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(2L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_idNullVsNonNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(null, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_enabledMismatch() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", false, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_bothIdsNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(null, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(null, "u1", "EMAIL", true, now, now, false);

        assertEquals(e1, e2);
    }

    @Test
    void testEquals_updatedAtNullVsNonNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, null, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
    }

    @Test
    void testEquals_bothUpdatedAtNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, null, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, null, false);

        assertEquals(e1, e2);
    }

    @Test
    void testEquals_notificationTypeNullVsNonNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", null, true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
        assertNotEquals(e2, e1);
    }

    @Test
    void testEquals_notificationTypeBothNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", null, true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", null, true, now, now, false);

        assertEquals(e1, e2);
    }

    @Test
    void testEquals_createdAtNullVsNonNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, null, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
        assertNotEquals(e2, e1);
    }

    @Test
    void testEquals_userIdNullVsNonNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, null, "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);

        assertNotEquals(e1, e2);
        assertNotEquals(e2, e1);
    }

    @Test
    void testHashCode_enabledFalseAndDeletedTrue() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", false, now, now, true);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", "EMAIL", false, now, now, true);

        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testHashCode_idNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(null, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(null, "u1", "EMAIL", true, now, now, false);

        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testHashCode_notificationTypeNonNullVsNull() {
        LocalDateTime now = LocalDateTime.now();
        NotificationSettingEntity e1 = new NotificationSettingEntity(1L, "u1", "EMAIL", true, now, now, false);
        NotificationSettingEntity e2 = new NotificationSettingEntity(1L, "u1", null, true, now, now, false);

        assertNotEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testBuilderToString() {
        String str = NotificationSettingEntity.builder()
                .id(1L)
                .userId("u1")
                .notificationType("EMAIL")
                .toString();

        assertTrue(str.contains("NotificationSettingEntity"));
    }

    private static class NonCanEqualSubclass extends NotificationSettingEntity {
        @Override
        public boolean canEqual(Object other) {
            return false;
        }
    }

    @Test
    void testEquals_canEqualFalse() {
        NotificationSettingEntity entity = new NotificationSettingEntity();
        NonCanEqualSubclass other = new NonCanEqualSubclass();

        assertNotEquals(entity, other);
    }

    @Test
    void testCanEqual_directCalls() {
        NotificationSettingEntity entity = new NotificationSettingEntity();

        assertTrue(entity.canEqual(new NotificationSettingEntity()));
        assertFalse(entity.canEqual("some string"));
    }
}
