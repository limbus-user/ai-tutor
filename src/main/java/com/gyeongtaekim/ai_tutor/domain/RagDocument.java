package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String extractedText;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public RagDocument(
            User user,
            String title,
            SourceType sourceType,
            String trustLevel,
            String subject,
            String unitName,
            String storedFileName,
            String extractedText
    ) {
        this.user = user;
        this.title = title;
        this.sourceType = sourceType;
        this.trustLevel = trustLevel;
        this.subject = subject;
        this.unitName = unitName;
        this.storedFileName = storedFileName;
        this.extractedText = extractedText;
        this.createdAt = LocalDateTime.now();
    }

    public RagDocument(
            String title,
            SourceType sourceType,
            String trustLevel,
            String subject,
            String unitName,
            String storedFileName,
            String extractedText
    ) {
        this(null, title, sourceType, trustLevel, subject, unitName, storedFileName, extractedText);
    }

    public void assignUser(User user) {
        this.user = user;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updateMetadata(String subject, String unitName, String trustLevel) {
        this.subject = subject;
        this.unitName = unitName;
        this.trustLevel = trustLevel;
    }

    public enum SourceType {
        PDF, NOTE, PROBLEM_SET
    }
}
