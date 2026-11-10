package com.novelweb.identity.auth;

import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleAuthRateLimitKeyHasherTests {

    private static final byte[] CLIENT_ADDRESS = new byte[] {(byte) 192, 0, 2, 10};
    private static final byte[] OTHER_CLIENT_ADDRESS = new byte[] {(byte) 192, 0, 2, 11};
    private static final byte[] PEPPER = new byte[32];

    @Test
    void sameAddressAndSecretProduceStableHmacWithoutStoringAddress() {
        String encodedPepper = Base64.getUrlEncoder().withoutPadding().encodeToString(PEPPER);

        byte[] first = GoogleAuthRateLimitKeyHasher.hash(CLIENT_ADDRESS, encodedPepper);
        byte[] second = GoogleAuthRateLimitKeyHasher.hash(CLIENT_ADDRESS, encodedPepper);
        byte[] otherClient = GoogleAuthRateLimitKeyHasher.hash(OTHER_CLIENT_ADDRESS, encodedPepper);
        byte[] otherPepper = new byte[32];
        otherPepper[0] = 1;

        assertArrayEquals(first, second);
        assertFalse(Arrays.equals(CLIENT_ADDRESS, first));
        assertFalse(Arrays.equals(first, otherClient));
        assertFalse(Arrays.equals(first, GoogleAuthRateLimitKeyHasher.hash(CLIENT_ADDRESS,
                Base64.getUrlEncoder().withoutPadding().encodeToString(otherPepper))));
    }

    @Test
    void rejectsMissingWeakOrMalformedHashInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> GoogleAuthRateLimitKeyHasher.hash(CLIENT_ADDRESS, ""));
        assertThrows(IllegalArgumentException.class,
                () -> GoogleAuthRateLimitKeyHasher.hash(CLIENT_ADDRESS,
                        Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class,
                () -> GoogleAuthRateLimitKeyHasher.hash(new byte[] {1, 2, 3},
                        Base64.getUrlEncoder().withoutPadding().encodeToString(PEPPER)));
    }
}
