package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    @Column(nullable = false)
    private String trustLevel;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String unitName;

    @Column(nullable = false, unique = true)
    private String storedFileName;

    @Column(nullable = false, length = 4000)
    private String extractedText;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public RagDocument(
            String title,
            SourceType sourceType,
            String trustLevel,
            String subject,
            String unitName,
            String storedFileName,
            String extractedText
    ) {
        this.title = title;
        this.sourceType = sourceType;
        this.trustLevel = trustLevel;
        this.subject = subject;
        this.unitName = unitName;
        this.storedFileName = storedFileName;
        this.extractedText = extractedText;
        this.createdAt = LocalDateTime.now();
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public enum SourceType {
        PDF, NOTE, PROBLEM_SET
    }
}
