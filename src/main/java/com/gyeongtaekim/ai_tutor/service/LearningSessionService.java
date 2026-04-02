package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.dto.ChapterDto;
import com.gyeongtaekim.ai_tutor.dto.LearningSessionDto;
import com.gyeongtaekim.ai_tutor.dto.WeaknessAnalysisDto;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class LearningSessionService {

    private final RagService ragService;
    private final WrongAnswerService wrongAnswerService;
    private final WeaknessAnalysisService weaknessAnalysisService;
    private final RedisTemplate<String, Object> redisTemplate;

    private final Map<Long, LearningSessionDto> activeSessions = new ConcurrentHashMap<>();

    private static final String CHAPTERS_KEY = "chapters";

    public void setChapters(List<ChapterDto> chapters) {
        redisTemplate.opsForValue().set(CHAPTERS_KEY, chapters);
    }

    @SuppressWarnings("unchecked")
    public List<ChapterDto> getChapters() {
        return (List<ChapterDto>) redisTemplate.opsForValue().get(CHAPTERS_KEY);
    }

    public LearningSessionDto startSession(Long userId, String chapterName) {
        LearningSessionDto session = new LearningSessionDto();
        session.setUserId(userId);
        session.setSelectedChapter(chapterName);
        session.setGeneratedQuestions(new ArrayList<>());
        session.setWrongAnswers(new ArrayList<>());
        session.setStartTime(LocalDateTime.now());

        activeSessions.put(userId, session);
        return session;
    }

    public List<String> generateQuestionsForSession(Long userId) {
        try {
            LearningSessionDto session = activeSessions.get(userId);
            if (session == null) {
                return List.of("Mock Question: Session not found");
            }

            WeaknessAnalysisDto analysis = weaknessAnalysisService.analyzeWeakness(userId);
            if (analysis == null) {
                return List.of("Mock Question 1: Default question 1?", "Mock Question 2: Default question 2?");
            }

            String chapter = session.getSelectedChapter();
            if (chapter == null || chapter.isBlank()) {
                chapter = "Default Chapter";
            }

            String query;
            if (analysis.getWeaknessTopics() == null || analysis.getWeaknessTopics().isEmpty()) {
                query = "단원 '" + chapter + "'에 대한 3개의 핵심 문제를 생성해 주세요.";
            } else {
                query = "취약 단원 " + analysis.getWeaknessTopics().keySet() + "과 관련된 3개의 문제를 생성해 주세요.";
            }

            String response = ragService.query(query).getAnswer();
            if (response == null || response.isBlank()) {
                return List.of("Mock Question 1: Sample question?");
            }

            List<String> questions = new ArrayList<>(List.of(response.split("\n\n")));
            if (questions.isEmpty()) {
                questions.add("Mock Question 1: Sample question?");
            }
            session.getGeneratedQuestions().addAll(questions);
            return questions;
        } catch (Exception e) {
            return List.of(
                    "Mock Question 1: What is the main topic?",
                    "Mock Question 2: How does it relate to other concepts?"
            );
        }
    }

    public String submitAnswer(Long userId, WrongAnswerDto wrongAnswer) {
        LearningSessionDto session = activeSessions.get(userId);
        if (session != null) {
            session.getWrongAnswers().add(wrongAnswer);
        }

        wrongAnswerService.saveWrongAnswer(wrongAnswer);

        List<Object> allWrong = wrongAnswerService.getWrongAnswers(userId);
        if (allWrong.size() >= 3) {
            return "오답 패턴이 발견되었습니다. 관련 개념 설명: " + generateFeedback(wrongAnswer.getQuestion());
        }

        return "오답을 기록했습니다. 다시 시도해 보세요.";
    }

    public void endSession(Long userId) {
        LearningSessionDto session = activeSessions.remove(userId);
        if (session != null) {
            session.setEndTime(LocalDateTime.now());
        }
    }

    private String generateFeedback(String question) {
        return ragService.query("질문 '" + question + "'에 대한 관련 개념을 설명해 주세요.").getAnswer();
    }
}
