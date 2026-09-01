package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.dto.ExamMockQuestionResponse;
import com.gyeongtaekim.ai_tutor.dto.ExamMockResponse;
import com.gyeongtaekim.ai_tutor.repository.ExamQuestionBankRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExamMockService {
    public static final String IT_ENGINEER_CERTIFICATION = "정보처리기사";
    public static final ExamMockDefinition IT_ENGINEER_20220424 = new ExamMockDefinition(
            "it-engineer-20220424",
            IT_ENGINEER_CERTIFICATION,
            "정보처리기사 필기 2022년 04월 24일",
            LocalDate.of(2022, 4, 24),
            "exam-mocks/it-engineer-20220424",
            "정보처리기사20220424(교사용).hwp"
    );
    public static final ExamMockDefinition IT_ENGINEER_20220305 = new ExamMockDefinition(
            "it-engineer-20220305",
            IT_ENGINEER_CERTIFICATION,
            "정보처리기사 필기 2022년 03월 05일",
            LocalDate.of(2022, 3, 5),
            "exam-mocks/it-engineer-20220305",
            "정보처리기사20220305(교사용).hwp"
    );
    public static final ExamMockDefinition IT_ENGINEER_20210814 = new ExamMockDefinition(
            "it-engineer-20210814",
            IT_ENGINEER_CERTIFICATION,
            "정보처리기사 필기 2021년 08월 14일",
            LocalDate.of(2021, 8, 14),
            "exam-mocks/it-engineer-20210814",
            "정보처리기사20210814(교사용).hwp"
    );

    private static final Map<String, ExamMockDefinition> EXAM_MOCKS = Map.of(
            IT_ENGINEER_20220424.quizSetId(), IT_ENGINEER_20220424,
            IT_ENGINEER_20220305.quizSetId(), IT_ENGINEER_20220305,
            IT_ENGINEER_20210814.quizSetId(), IT_ENGINEER_20210814
    );

    private final ExamQuestionBankRepository examQuestionBankRepository;

    public ExamMockResponse getItEngineer20220424MockExam() {
        return getMockExam(IT_ENGINEER_20220424.quizSetId());
    }

    public ExamMockResponse getMockExam(String quizSetId) {
        ExamMockDefinition definition = getDefinition(quizSetId);
        var questions = examQuestionBankRepository
                .findByCertificationAndExamDateOrderByQuestionNoAsc(definition.certification(), definition.examDate())
                .stream()
                .map(question -> new ExamMockQuestionResponse(question, definition.quizSetId()))
                .toList();

        if (questions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mock exam question bank is empty");
        }

        return new ExamMockResponse(
                definition.quizSetId(),
                definition.certification(),
                definition.examName(),
                definition.examDate(),
                questions
        );
    }

    public ExamMockDefinition getDefinition(String quizSetId) {
        ExamMockDefinition definition = EXAM_MOCKS.get(quizSetId);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mock exam not found");
        }
        return definition;
    }

    public static Iterable<ExamMockDefinition> getDefinitions() {
        return EXAM_MOCKS.values();
    }

    public record ExamMockDefinition(
            String quizSetId,
            String certification,
            String examName,
            LocalDate examDate,
            String resourceBase,
            String sourceFile
    ) {
    }
}
