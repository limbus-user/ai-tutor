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
        String shortAnswerRules = type == QuestionType.SHORT_ANSWER ? """

                [Short Answer Rules]
                - short_answer question and modelAnswer must focus on the same conceptTag.
                - modelAnswer and correctAnswer must be complete Korean explanatory sentences.
                - Do not copy truncated fragments from evidence.
                - Do not create answers ending with "\uB2E4\uC74C\uACFC \uAC19\uC74C", "\uC8FC\uC694 \uC5ED\uD560\uC740", "\uC544\uB798\uC640 \uAC19\uC74C", or another incomplete phrase.
                - If the question asks to explain, include a definition and one core characteristic.
                - If the question asks to compare, include the difference between two concepts.
                - If the question asks to apply, include a concrete situation or example and how the concept is used there.
                - If the question asks for an example, include the example and why it fits the concept.
                - For application questions, do not use a simple definition as the answer.
                - If the evidence is insufficient to create a good short_answer question, create a simpler concept explanation question instead.
                """ : "";

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
                """.formatted(title, difficulty, mode, type.apiValue(), evidenceBlock, shortAnswerRules);

        return new PromptPayload(system, prompt);
    }

    public record PromptPayload(String systemPrompt, String userPrompt) {
    }
}
