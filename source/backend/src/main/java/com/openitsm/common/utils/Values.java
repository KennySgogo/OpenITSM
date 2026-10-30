package com.openitsm.common.utils;

import com.openitsm.common.exception.BusinessException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Values {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    private Values() {}

    public static String jsonText(com.fasterxml.jackson.databind.JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw BusinessException.field(field, "字段必须为字符串");
        return value.textValue();
    }

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
        if (count < 6) throw BusinessException.field("password", "密码至少需要 6 个字符");
        if (!password.equals(confirm)) throw BusinessException.field("confirmPassword", "两次密码不一致");
        return password;
    }

}
