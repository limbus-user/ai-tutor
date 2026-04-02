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

    // 메모리에 세션 저장 (실제로는 Redis나 DB 사용 권장)
    private final Map<Long, LearningSessionDto> activeSessions = new ConcurrentHashMap<>();

    private static final String CHAPTERS_KEY = "chapters";

    // 단원 리스트 설정 (수동으로 추가하거나 PDF 파싱으로 자동화 가능)
    public void setChapters(List<ChapterDto> chapters) {
        redisTemplate.opsForValue().set(CHAPTERS_KEY, chapters);
    }

    public List<ChapterDto> getChapters() {
        return (List<ChapterDto>) redisTemplate.opsForValue().get(CHAPTERS_KEY);
    }

    // 세션 시작
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

    // 단원별 문제 생성 (취약 단원 우선)
    public List<String> generateQuestionsForSession(Long userId) {
        try {
            LearningSessionDto session = activeSessions.get(userId);
            if (session == null) return List.of("Mock Question: Session not found");

            WeaknessAnalysisDto analysis = weaknessAnalysisService.analyzeWeakness(userId);
            if (analysis == null) {
                List<String> mockQuestions = new ArrayList<>();
                mockQuestions.add("Mock Question 1: Default question 1?");
                mockQuestions.add("Mock Question 2: Default question 2?");
                return mockQuestions;
            }

            String chapter = session.getSelectedChapter();
            if (chapter == null || chapter.isEmpty()) {
                chapter = "Default Chapter";
            }

            // 취약 단원이 선택된 단원과 관련 있으면 우선 출제
            String query;
            if (analysis.getWeaknessTopics() == null || analysis.getWeaknessTopics().isEmpty()) {
                query = "단원 '" + chapter + "'에 대한 3개의 객관식 문제를 생성해 주세요.";
            } else {
                query = "취약 단원 " + analysis.getWeaknessTopics().keySet() + "과 관련된 3개의 문제를 생성해 주세요.";
            }

            // 실제로는 단원별 청크로 필터링된 RAG 사용
            String response = ragService.query(query);
            if (response == null || response.isEmpty()) {
                return List.of("Mock Question 1: Sample question?");
            }
            List<String> questions = new ArrayList<>(List.of(response.split("\n\n")));
            if (questions.isEmpty()) {
                questions.add("Mock Question 1: Sample question?");
            }
            session.getGeneratedQuestions().addAll(questions);
            return questions;
        } catch (Exception e) {
            List<String> mockQuestions = new ArrayList<>();
            mockQuestions.add("Mock Question 1: What is the main topic?");
            mockQuestions.add("Mock Question 2: How does it relate to other concepts?");
            return mockQuestions;
        }
    }

    // 오답 제출 및 피드백
    public String submitAnswer(Long userId, WrongAnswerDto wrongAnswer) {
        LearningSessionDto session = activeSessions.get(userId);
        if (session != null) {
            session.getWrongAnswers().add(wrongAnswer);
        }

        wrongAnswerService.saveWrongAnswer(wrongAnswer);

        // 오답 누적 시 피드백
        List<Object> allWrong = wrongAnswerService.getWrongAnswers(userId);
        if (allWrong.size() >= 3) { // 예: 3회 이상 오답
            return "오답 패턴이 발견되었습니다. 관련 개념 설명: " + generateFeedback(wrongAnswer.getQuestion());
        }

        return "오답이 기록되었습니다. 다시 시도하세요.";
    }

    // 세션 종료
    public void endSession(Long userId) {
        LearningSessionDto session = activeSessions.remove(userId);
        if (session != null) {
            session.setEndTime(LocalDateTime.now());
            // 세션 데이터 저장 (필요 시)
        }
    }

    private String generateFeedback(String question) {
        // 간단한 피드백 생성 (실제로는 RAG로 관련 설명 검색)
        return ragService.query("질문 '" + question + "'에 대한 관련 개념을 설명해 주세요.");
    }
}
