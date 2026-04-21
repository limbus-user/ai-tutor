package com.gyeongtaekim.ai_tutor.service.question;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class QuestionTypeSelector {

    public QuestionGenerationPlan createPlan(QuestionGenerationRequest request) {
        String difficulty = normalizeDifficulty(request.difficulty());
        String mode = normalizeMode(request.mode());
        int count = normalizeCount(request.count());

        if (request.distribution() != null && !request.distribution().isBlank()) {
            return new QuestionGenerationPlan(expandDistribution(request.distribution(), count), difficulty, mode);
        }

        String normalizedType = normalizeType(request.type());
        if (!"mixed".equals(normalizedType)) {
            return new QuestionGenerationPlan(repeat(QuestionType.fromApiValue(normalizedType), count), difficulty, mode);
        }

        return new QuestionGenerationPlan(buildMixedPlan(difficulty, count), difficulty, mode);
    }

    public String normalizeDifficulty(String difficulty) {
        String normalized = difficulty == null ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        if (!List.of("easy", "medium", "hard").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "difficulty must be easy, medium, or hard");
        }
        return normalized;
    }

    public String normalizeMode(String mode) {
        String normalized = mode == null ? "mixed" : mode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("concept", "example", "application", "mixed").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be concept, example, application, or mixed");
        }
        return normalized;
    }

    public int normalizeCount(Integer count) {
        int normalized = count == null ? 5 : count;
        if (normalized < 1 || normalized > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 10");
        }
        return normalized;
    }

    public String normalizeType(String type) {
        String normalized = type == null ? "mixed" : type.trim().toLowerCase(Locale.ROOT);
        if ("mixed".equals(normalized)) {
            return normalized;
        }
        try {
            QuestionType.fromApiValue(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported type: " + normalized);
        }
    }

    private List<QuestionType> expandDistribution(String distribution, int count) {
        List<QuestionType> result = new ArrayList<>();
        for (String part : distribution.split(",")) {
            String[] pair = part.trim().split(":");
            if (pair.length != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "distribution must look like type:count,type:count");
            }
            QuestionType type = QuestionType.fromApiValue(pair[0].trim().toLowerCase(Locale.ROOT));
            int repetitions = Integer.parseInt(pair[1].trim());
            result.addAll(repeat(type, repetitions));
        }
        if (result.size() != count) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "distribution count must match count parameter");
        }
        return result;
    }

    private List<QuestionType> buildMixedPlan(String difficulty, int count) {
        Map<QuestionType, Integer> weights = new EnumMap<>(QuestionType.class);
        if ("easy".equals(difficulty)) {
            weights.put(QuestionType.MULTIPLE_CHOICE, 40);
            weights.put(QuestionType.TRUE_FALSE, 20);
            weights.put(QuestionType.FILL_IN_BLANK, 20);
            weights.put(QuestionType.SHORT_ANSWER, 20);
        } else if ("hard".equals(difficulty)) {
            weights.put(QuestionType.SHORT_ANSWER, 20);
            weights.put(QuestionType.COMPARISON, 20);
            weights.put(QuestionType.APPLICATION, 20);
            weights.put(QuestionType.CODE_READING, 20);
            weights.put(QuestionType.ERROR_DETECTION, 20);
        } else {
            weights.put(QuestionType.MULTIPLE_CHOICE, 30);
            weights.put(QuestionType.FILL_IN_BLANK, 20);
            weights.put(QuestionType.SHORT_ANSWER, 20);
            weights.put(QuestionType.APPLICATION, 15);
            weights.put(QuestionType.COMPARISON, 15);
        }

        List<QuestionType> ordered = new ArrayList<>();
        List<QuestionType> cycle = new ArrayList<>(weights.keySet());
        int index = 0;
        while (ordered.size() < count) {
            ordered.add(cycle.get(index % cycle.size()));
            index++;
        }
        return ordered;
    }

    private List<QuestionType> repeat(QuestionType type, int count) {
        List<QuestionType> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(type);
        }
        return list;
    }
}
