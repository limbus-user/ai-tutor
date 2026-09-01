package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "exam_question_bank",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_exam_question_bank_cert_date_no",
                columnNames = {"certification", "exam_date", "question_no"}
        )
)
@Getter
@NoArgsConstructor
public class ExamQuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String certification;

    @Column(nullable = false, length = 200)
    private String examName;

    @Column(nullable = false)
    private LocalDate examDate;

    @Column(nullable = false)
    private Integer subjectNo;

    @Column(nullable = false, length = 100)
    private String subjectName;

    @Column(nullable = false)
    private Integer questionNo;

    @Column(nullable = false, length = 50)
    private String questionType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String choice1;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String choice2;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String choice3;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String choice4;

    @Column(nullable = false)
    private Integer correctChoiceNo;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(columnDefinition = "TEXT")
    private String mediaPaths;

    @Column(nullable = false, length = 50)
    private String sourceType;

    @Column(length = 255)
    private String sourceFile;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ExamQuestionBank(
            String certification,
            String examName,
            LocalDate examDate,
            Integer subjectNo,
            String subjectName,
            Integer questionNo,
            String questionType,
            String questionText,
            String choice1,
            String choice2,
            String choice3,
            String choice4,
            Integer correctChoiceNo,
            String explanation,
            String mediaPaths,
            String sourceType,
            String sourceFile
    ) {
        this.certification = certification;
        this.examName = examName;
        this.examDate = examDate;
        this.subjectNo = subjectNo;
        this.subjectName = subjectName;
        this.questionNo = questionNo;
        this.questionType = questionType;
        this.questionText = questionText;
        this.choice1 = choice1;
        this.choice2 = choice2;
        this.choice3 = choice3;
        this.choice4 = choice4;
        this.correctChoiceNo = correctChoiceNo;
        this.explanation = explanation;
        this.mediaPaths = mediaPaths;
        this.sourceType = sourceType;
        this.sourceFile = sourceFile;
        this.createdAt = LocalDateTime.now();
    }

    public void updateExplanation(String explanation) {
        this.explanation = explanation;
    }
}
