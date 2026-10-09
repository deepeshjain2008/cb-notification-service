package com.igot.cb.notification.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingRequestTest {

    @Test
    void testAllArgsConstructorAndGetters() {
        NotificationSettingRequest req = new NotificationSettingRequest("EMAIL", true);
        assertEquals("EMAIL", req.getNotificationType());
        assertTrue(req.isEnabled());
    }

    @Test
    void testNoArgsConstructorAndSetters() {
        NotificationSettingRequest req = new NotificationSettingRequest();
        req.setNotificationType("SMS");
        req.setEnabled(false);
        assertEquals("SMS", req.getNotificationType());
        assertFalse(req.isEnabled());
    }

    @Test
    void testEqualsAndHashCode_sameObject() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", true);
        assertEquals(r1, r1);
        assertEquals(r1.hashCode(), r1.hashCode());
    }

    @Test
    void testEquals_nullAndDifferentClass() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", true);
        assertNotEquals(null, r1);
        assertNotEquals("some string", r1);
    }

    @Test
    void testEquals_differentFieldValues() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", true);
        NotificationSettingRequest r2 = new NotificationSettingRequest("EMAIL", false); // different boolean
        NotificationSettingRequest r3 = new NotificationSettingRequest("SMS", true);    // different string
        assertNotEquals(r1, r2);
        assertNotEquals(r1, r3);
    }

    @Test
    void testEqualsAndHashCode_equalObjects() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("PUSH", true);
        NotificationSettingRequest r2 = new NotificationSettingRequest("PUSH", true);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testToString() {
        NotificationSettingRequest req = new NotificationSettingRequest("EMAIL", true);
        String str = req.toString();
        assertTrue(str.contains("EMAIL"));
        assertTrue(str.contains("true"));
    }

    @Test
    void testEquals_notificationTypeNullCases() {
        NotificationSettingRequest r1 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest r2 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest r3 = new NotificationSettingRequest("EMAIL", true);
        assertEquals(r1, r2);
        assertNotEquals(r1, r3);
    }

    @Test
    void testHashCode_withNullNotificationType() {
        NotificationSettingRequest r1 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest r2 = new NotificationSettingRequest(null, true);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testEquals_nullVsNonNullSymmetry() {
        NotificationSettingRequest r1 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest r2 = new NotificationSettingRequest("EMAIL", true);
        assertNotEquals(r1, r2);
        assertNotEquals(r2, r1);
    }

    @Test
    void testHashCode_nullVsNonNull() {
        NotificationSettingRequest r1 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest r2 = new NotificationSettingRequest("EMAIL", true);
        assertNotEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testToStringWithNullNotificationType() {
        NotificationSettingRequest req = new NotificationSettingRequest(null, true);
        String str = req.toString();
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("notificationType=null"));
    }

    @Test
    void testToStringWithNull() {
        NotificationSettingRequest req = new NotificationSettingRequest(null, false);
        String str = req.toString();
        assertTrue(str.contains("notificationType=null"));
        assertTrue(str.contains("enabled=false"));
    }

    private static class NonCanEqualSubclass extends NotificationSettingRequest {
        @Override
        public boolean canEqual(Object other) {
            return false;
        }
    }

    @Test
    void testEquals_canEqualFalse() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", true);
        NonCanEqualSubclass r2 = new NonCanEqualSubclass();

        assertNotEquals(r1, r2);
    }

    @Test
    void testEquals_notificationTypeDirectCalls_allBranches() {
        NotificationSettingRequest bothNull1 = new NotificationSettingRequest(null, true);
        NotificationSettingRequest bothNull2 = new NotificationSettingRequest(null, true);
        assertTrue(bothNull1.equals(bothNull2));

        NotificationSettingRequest thisNull = new NotificationSettingRequest(null, true);
        NotificationSettingRequest otherNonNull = new NotificationSettingRequest("EMAIL", true);
        assertFalse(thisNull.equals(otherNonNull));

        NotificationSettingRequest thisNonNull = new NotificationSettingRequest("EMAIL", true);
        NotificationSettingRequest otherNull = new NotificationSettingRequest(null, true);
        assertFalse(thisNonNull.equals(otherNull));

        NotificationSettingRequest thisNonNull2 = new NotificationSettingRequest("EMAIL", true);
        NotificationSettingRequest otherNonNullEqual = new NotificationSettingRequest("EMAIL", true);
        assertTrue(thisNonNull2.equals(otherNonNullEqual));

        NotificationSettingRequest thisNonNull3 = new NotificationSettingRequest("EMAIL", true);
        NotificationSettingRequest otherNonNullDifferent = new NotificationSettingRequest("SMS", true);
        assertFalse(thisNonNull3.equals(otherNonNullDifferent));
    }

    @Test
    void testHashCode_enabledFalse() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", false);
        NotificationSettingRequest r2 = new NotificationSettingRequest("EMAIL", false);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void testCanEqual_directCalls() {
        NotificationSettingRequest r1 = new NotificationSettingRequest("EMAIL", true);

        assertTrue(r1.canEqual(new NotificationSettingRequest("SMS", false)));
        assertFalse(r1.canEqual("some string"));
    }
}
