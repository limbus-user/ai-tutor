package com.gyeongtaekim.ai_tutor.config;

import com.gyeongtaekim.ai_tutor.domain.ExamQuestionBank;
import com.gyeongtaekim.ai_tutor.repository.ExamQuestionBankRepository;
import com.gyeongtaekim.ai_tutor.service.ExamMockService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class ExamQuestionBankInitializer {

    private final ExamQuestionBankRepository examQuestionBankRepository;

    @Bean
    public CommandLineRunner seedExamQuestionBank() {
        return args -> {
            for (ExamMockService.ExamMockDefinition definition : ExamMockService.getDefinitions()) {
                seedExam(definition);
            }
        };
    }

    private void seedExam(ExamMockService.ExamMockDefinition definition) throws IOException {
        ClassPathResource questionsResource = new ClassPathResource(definition.resourceBase() + "/exam_questions.csv");
        ClassPathResource choicesResource = new ClassPathResource(definition.resourceBase() + "/exam_choices.csv");
        if (!questionsResource.exists() || !choicesResource.exists()) {
            return;
        }

        Map<Integer, Map<Integer, String>> choicesByQuestionNo = readChoices(choicesResource);
        List<ExamQuestionBank> questions = readQuestions(definition, questionsResource, choicesByQuestionNo);
        if (!questions.isEmpty()) {
            examQuestionBankRepository.saveAll(questions);
        }
    }

    private List<ExamQuestionBank> readQuestions(
            ExamMockService.ExamMockDefinition definition,
            ClassPathResource questionsResource,
            Map<Integer, Map<Integer, String>> choicesByQuestionNo
    ) throws IOException {
        List<ExamQuestionBank> questions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(questionsResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsvLine(line);
                if (row.size() < 9) {
                    continue;
                }

                int questionNo = Integer.parseInt(row.get(0));
                String explanation = readOptionalExplanation(row);
                var existingQuestion = examQuestionBankRepository.findByCertificationAndExamDateAndQuestionNo(
                        definition.certification(),
                        definition.examDate(),
                        questionNo
                );
                if (existingQuestion.isPresent()) {
                    if (explanation != null && !explanation.equals(existingQuestion.get().getExplanation())) {
                        existingQuestion.get().updateExplanation(explanation);
                        examQuestionBankRepository.save(existingQuestion.get());
                    }
                    continue;
                }

                Map<Integer, String> choices = choicesByQuestionNo.getOrDefault(questionNo, Map.of());
                if (choices.size() < 4) {
                    continue;
                }

                questions.add(new ExamQuestionBank(
                        definition.certification(),
                        definition.examName(),
                        definition.examDate(),
                        Integer.parseInt(row.get(1)),
                        row.get(2),
                        questionNo,
                        row.get(3),
                        row.get(4),
                        choices.get(1),
                        choices.get(2),
                        choices.get(3),
                        choices.get(4),
                        Integer.parseInt(row.get(5)),
                        explanation,
                        row.get(7),
                        readSourceType(row),
                        definition.sourceFile()
                ));
            }
        }
        return questions;
    }

    private String readOptionalExplanation(List<String> row) {
        if (row.size() >= 10) {
            String explanation = sanitizeExplanation(row.get(8));
            return explanation == null || explanation.isBlank() ? null : explanation;
        }
        return null;
    }

    private String sanitizeExplanation(String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return explanation;
        }
        return explanation
                .replaceAll("\\s*\\[해설작성자\\s*:\\s*[^\\]]+\\]\\s*", " ")
                .replaceAll("\\s*\\[해설작성자\\s*:.*$", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String readSourceType(List<String> row) {
        if (row.size() >= 10) {
            return row.get(9);
        }
        return row.get(8);
    }

    private Map<Integer, Map<Integer, String>> readChoices(ClassPathResource choicesResource) throws IOException {
        Map<Integer, Map<Integer, String>> choicesByQuestionNo = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(choicesResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsvLine(line);
                if (row.size() < 4) {
                    continue;
                }
                int questionNo = Integer.parseInt(row.get(0));
                int choiceNo = Integer.parseInt(row.get(1));
                choicesByQuestionNo
                        .computeIfAbsent(questionNo, ignored -> new HashMap<>())
                        .put(choiceNo, row.get(2));
            }
        }
        return choicesByQuestionNo;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index += 1) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index += 1;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values;
    }
}
