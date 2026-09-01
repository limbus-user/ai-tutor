package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.ExamQuestionBank;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ExamMockQuestionResponse {
    private final Long id;
    private final Integer subjectNo;
    private final String subjectName;
    private final Integer questionNo;
    private final String type;
    private final String question;
    private final List<String> choices;
    private final Integer correctChoiceNo;
    private final String correctAnswer;
    private final String explanation;
    private final List<String> mediaUrls;
    private final String conceptTag;
    private final String understandingLevel;

    public ExamMockQuestionResponse(ExamQuestionBank question, String quizSetId) {
        this.id = question.getId();
        this.subjectNo = question.getSubjectNo();
        this.subjectName = question.getSubjectName();
        this.questionNo = question.getQuestionNo();
        this.type = "multiple_choice";
        this.question = question.getQuestionText();
        this.choices = List.of(
                question.getChoice1(),
                question.getChoice2(),
                question.getChoice3(),
                question.getChoice4()
        );
        this.correctChoiceNo = question.getCorrectChoiceNo();
        this.correctAnswer = this.choices.get(Math.max(0, Math.min(3, question.getCorrectChoiceNo() - 1)));
        this.explanation = question.getExplanation();
        this.mediaUrls = buildMediaUrls(question.getMediaPaths(), quizSetId);
        this.conceptTag = question.getSubjectName();
        this.understandingLevel = "CONCEPT_APPLICATION";
    }

    private List<String> buildMediaUrls(String mediaPaths, String quizSetId) {
        if (mediaPaths == null || mediaPaths.isBlank()) {
            return List.of();
        }

        List<String> urls = new ArrayList<>();
        for (String mediaPath : mediaPaths.split(",")) {
            String fileName = mediaPath.trim();
            if (fileName.isBlank()) {
                continue;
            }
            int slashIndex = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
            if (slashIndex >= 0) {
                fileName = fileName.substring(slashIndex + 1);
            }
            urls.add("/api/exam-mocks/" + quizSetId + "/media/" + fileName);
        }
        return urls;
    }
}
