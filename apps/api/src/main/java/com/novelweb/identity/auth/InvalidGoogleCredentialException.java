package com.novelweb.identity.auth;

public class InvalidGoogleCredentialException extends RuntimeException {

    public InvalidGoogleCredentialException() {
        super("Google credential could not be verified");
    }

    public InvalidGoogleCredentialException(Throwable cause) {
        super("Google credential could not be verified", cause);
    }
}
