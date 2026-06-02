package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2000)
    private String questionText;

    @Column(nullable = false, length = 1000)
    private String answer;

    @Column(nullable = false, length = 2000)
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnderstandingLevel understandingLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProblemType type;

    @ManyToMany
    @JoinTable(
            name = "problem_concepts",
            joinColumns = @JoinColumn(name = "problem_id"),
            inverseJoinColumns = @JoinColumn(name = "concept_id")
    )
    private List<Concept> concepts = new ArrayList<>();

    public Problem(
            String questionText,
            String answer,
            String explanation,
            Difficulty difficulty,
            UnderstandingLevel understandingLevel,
            ProblemType type,
            List<Concept> concepts
    ) {
        this.questionText = questionText;
        this.answer = answer;
        this.explanation = explanation;
        this.difficulty = difficulty;
        this.understandingLevel = understandingLevel;
        this.type = type;
        this.concepts = concepts;
    }

    public enum Difficulty {
        EASY, MEDIUM, HARD
    }

    public enum UnderstandingLevel {
        CONCEPT_UNDERSTANDING,
        CONCEPT_DISTINCTION,
        CONCEPT_APPLICATION
    }

    public enum ProblemType {
        SHORT_ANSWER, MULTIPLE_CHOICE, TRUE_FALSE
    }
}
