package com.novelweb.identity.auth;

public class GoogleAccountLinkRequiredException extends RuntimeException {

    public static final String CODE = "ACCOUNT_LINK_REQUIRED";

    public GoogleAccountLinkRequiredException() {
        super("The sign-in could not be completed with this account");
    }

    public String code() {
        return CODE;
    }
}
