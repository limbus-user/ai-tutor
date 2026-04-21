package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.SessionQuiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SessionQuizRepository extends JpaRepository<SessionQuiz, Long> {
    List<SessionQuiz> findBySessionIdOrderByCreatedAtAscQuestionOrderAsc(Long sessionId);
    List<SessionQuiz> findBySessionIdAndQuizSetIdOrderByCreatedAtAscQuestionOrderAsc(Long sessionId, String quizSetId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SessionQuiz quiz where quiz.session.id = :sessionId")
    void deleteAllBySessionId(@Param("sessionId") Long sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SessionQuiz quiz where quiz.session.id = :sessionId and quiz.quizSetId = :quizSetId")
    void deleteQuizSet(@Param("sessionId") Long sessionId, @Param("quizSetId") String quizSetId);
}
