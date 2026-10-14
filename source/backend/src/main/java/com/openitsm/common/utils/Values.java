package com.openitsm.common.utils;

import com.openitsm.common.exception.BusinessException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Values {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    private Values() {}

    public static String email(String input) {
        if (input == null || input.isBlank()) throw BusinessException.field("email", "请输入邮箱");
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) throw BusinessException.field("email", "请输入有效邮箱");
        return value;
    }

    public static String text(String input, String field, int min, int max) {
        String value = input == null ? "" : input.strip();
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) throw BusinessException.field(field, field + " 长度须为 " + min + "～" + max + " 字");
        return value;
    }

    public static String password(String password, String confirm) {
        if (password == null || password.isEmpty()) throw BusinessException.field("password", "请输入密码");
        int count = password.codePointCount(0, password.length());
        if (count < 12 || count > 64 || password.codePoints().noneMatch(Character::isLetter)
            || password.codePoints().noneMatch(Character::isDigit))
            throw BusinessException.field("password", "密码须为 12～64 位，且至少包含字母和数字");
        if (!password.equals(confirm)) throw BusinessException.field("confirmPassword", "两次密码不一致");
        return password;
    }

    public static long version(String header) {
        if (header == null) throw new BusinessException(428, "PRECONDITION_REQUIRED", "请提供资源版本");
        if (!header.matches("\"[0-9]{1,18}\"")) throw BusinessException.field("If-Match", "版本格式错误");
        return Long.parseLong(header.substring(1, header.length() - 1));
    }
}
