package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WrongAnswerService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String WRONG_ANSWERS_KEY_PREFIX = "user:%d:wrongAnswers";

    public void saveWrongAnswer(WrongAnswerDto wrongAnswer) {
        wrongAnswer.setTimestamp(LocalDateTime.now());
        String key = String.format(WRONG_ANSWERS_KEY_PREFIX, wrongAnswer.getUserId());
        redisTemplate.opsForList().rightPush(key, wrongAnswer);
    }

    public List<Object> getWrongAnswers(Long userId) {
        String key = String.format(WRONG_ANSWERS_KEY_PREFIX, userId);
        return redisTemplate.opsForList().range(key, 0, -1);
    }

    public void clearWrongAnswers(Long userId) {
        String key = String.format(WRONG_ANSWERS_KEY_PREFIX, userId);
        redisTemplate.delete(key);
    }
}
