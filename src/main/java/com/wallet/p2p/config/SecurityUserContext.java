package com.wallet.p2p.config;

public final class SecurityUserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private SecurityUserContext() {
    }

    public static void setCurrentUser(String userId) {
        CURRENT_USER.set(userId);
    }

    public static String getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
