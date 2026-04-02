package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class LearningMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(nullable = false, length = 2000)
    private String weakConceptSummary;

    @Column(nullable = false, length = 2000)
    private String historySummary;

    @Column(nullable = false, length = 1000)
    private String preferences;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public LearningMemory(User user, String weakConceptSummary, String historySummary, String preferences) {
        this.user = user;
        this.weakConceptSummary = weakConceptSummary;
        this.historySummary = historySummary;
        this.preferences = preferences;
        this.updatedAt = LocalDateTime.now();
    }

    public void update(String weakConceptSummary, String historySummary, String preferences) {
        this.weakConceptSummary = weakConceptSummary;
        this.historySummary = historySummary;
        this.preferences = preferences;
        this.updatedAt = LocalDateTime.now();
    }
}
