package com.gyeongtaekim.ai_tutor.service.question;

import org.springframework.stereotype.Component;

@Component
public class QuestionPromptFactory {

    public PromptPayload buildPrompt(QuestionType type, String title, String evidenceBlock, String difficulty, String mode) {
        String system = """
                You create grounded study questions from source evidence.
                Return strict JSON only.
                Do not leak the answer in the question stem.
                Respect the requested question type exactly.
                """;

        String prompt = """
                [Document]
                %s

                [Difficulty]
                %s

                [Mode]
                %s

                [Question Type]
                %s

                [Evidence]
                %s

                [Required JSON Shape]
                {
                  "type":"...",
                  "question":"...",
                  "choices":["..."],
                  "correctAnswer":"...",
                  "acceptableAnswers":["..."],
                  "modelAnswer":"...",
                  "explanation":"...",
                  "sourceEvidence":"...",
                  "difficulty":"...",
                  "tags":["..."],
                  "format":{},
                  "meta":{}
                }
                """.formatted(title, difficulty, mode, type.apiValue(), evidenceBlock);

        return new PromptPayload(system, prompt);
    }

    public record PromptPayload(String systemPrompt, String userPrompt) {
    }
}
