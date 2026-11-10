package com.novelweb.identity.auth;

public class UnavailableGoogleIdentityException extends RuntimeException {

    public UnavailableGoogleIdentityException() {
        super("The sign-in could not be completed with this account");
    }
}
