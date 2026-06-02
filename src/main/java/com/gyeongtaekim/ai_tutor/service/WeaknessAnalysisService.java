package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.dto.WeaknessAnalysisDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WeaknessAnalysisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String WRONG_ANSWERS_KEY_PREFIX = "user:%d:wrongAnswers";

    public WeaknessAnalysisDto analyzeWeakness(Long userId) {
        String key = String.format(WRONG_ANSWERS_KEY_PREFIX, userId);
        List<Object> wrongAnswers = redisTemplate.opsForList().range(key, 0, -1);

        Map<String, Integer> topicFrequency = new HashMap<>();

        if (wrongAnswers == null || wrongAnswers.isEmpty()) {
            return new WeaknessAnalysisDto(userId, new HashMap<>(), "아직 오답이 없습니다.");
        }

        for (Object obj : wrongAnswers) {
            try {
                // Redis에서 가져온 객체를 Map으로 변환 (Jackson으로 역직렬화)
                Map<String, Object> wrongAnswerMap = objectMapper.convertValue(obj, Map.class);
                if (wrongAnswerMap == null) continue;

                Object questionObj = wrongAnswerMap.get("question");
                if (questionObj == null) continue;

                String question = questionObj.toString();

                // 간단한 키워드 추출 (질문에서 명사 추출 시뮬레이션)
                String[] words = question.split("\\s+");
                for (String word : words) {
                    if (word.length() > 2) { // 짧은 단어 제외
                        topicFrequency.put(word, topicFrequency.getOrDefault(word, 0) + 1);
                    }
                }
            } catch (Exception e) {
                // 변환 실패 시 무시
                continue;
            }
        }

        // 빈도가 높은 상위 5개 단원 추출
        Map<String, Integer> topTopics = topicFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);

        String summary = "취약 단원 분석: " + topTopics.keySet().toString();

        return new WeaknessAnalysisDto(userId, topTopics, summary);
    }
}
