package com.xinhe.nexus.management.auth;

public final class PasswordPolicy {
    public static final String REQUIREMENT =
            "密码长度须为6至72位，只能包含大写字母、小写字母、数字和标点符号，且至少包含其中三种";

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new IllegalArgumentException(REQUIREMENT);
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean punctuation = false;
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                upper = true;
            } else if (character >= 'a' && character <= 'z') {
                lower = true;
            } else if (character >= '0' && character <= '9') {
                digit = true;
            } else if (isAsciiPunctuation(character)) {
                punctuation = true;
            } else {
                throw new IllegalArgumentException(REQUIREMENT);
            }
        }
        int categories = (upper ? 1 : 0) + (lower ? 1 : 0)
                + (digit ? 1 : 0) + (punctuation ? 1 : 0);
        if (categories < 3) {
            throw new IllegalArgumentException(REQUIREMENT);
        }
    }

    private static boolean isAsciiPunctuation(char character) {
        return (character >= '!' && character <= '/')
                || (character >= ':' && character <= '@')
                || (character >= '[' && character <= '`')
                || (character >= '{' && character <= '~');
    }
}
