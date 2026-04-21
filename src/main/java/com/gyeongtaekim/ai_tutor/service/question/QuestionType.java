package com.gyeongtaekim.ai_tutor.service.question;

import java.util.Arrays;
import java.util.Locale;

public enum QuestionType {
    MULTIPLE_CHOICE("multiple_choice"),
    SHORT_ANSWER("short_answer"),
    FILL_IN_BLANK("fill_in_blank"),
    TRUE_FALSE("true_false"),
    MATCHING("matching"),
    ORDERING("ordering"),
    CODE_READING("code_reading"),
    CODE_COMPLETION("code_completion"),
    ERROR_DETECTION("error_detection"),
    COMPARISON("comparison"),
    APPLICATION("application"),
    MULTI_SELECT("multi_select");

    private final String apiValue;

    QuestionType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static QuestionType fromApiValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.apiValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported question type: " + value));
    }
}
