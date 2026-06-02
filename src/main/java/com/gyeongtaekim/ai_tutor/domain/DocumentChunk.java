package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    private RagDocument document;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(nullable = false, length = 1000)
    private String metadata;

    public DocumentChunk(RagDocument document, int chunkIndex, String chunkText, String metadata) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.metadata = metadata;
    }
}
