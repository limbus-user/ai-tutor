# AI Tutor 프로젝트 통합 문서

최종 갱신: 2026-08-30

이 문서는 현재 Spring Boot 기반 RAG 학습 튜터 프로젝트의 최신 구조와 구현 상태를 정리한다. 실행 방법은 [RUN_GUIDE_KO.md](RUN_GUIDE_KO.md)를 기준으로 하며, UML과 데이터베이스 스키마 설명은 [UML_DB_DESIGN_KO.md](UML_DB_DESIGN_KO.md)에 정리되어 있다.

## 관련 문서

- [RUN_GUIDE_KO.md](RUN_GUIDE_KO.md): Windows PowerShell 기준 실행, 종료, 오류 해결 가이드
- [UML_DB_DESIGN_KO.md](UML_DB_DESIGN_KO.md): 객체 구성, UML 클래스 다이어그램, ERD, 테이블별 설명

## 프로젝트 목적

AI Tutor는 사용자가 업로드한 PDF 학습 자료와 기출문제를 바탕으로 질문 답변, 퀴즈 생성, 오답 관리, 복습 큐, 모의고사 풀이 기록을 제공하는 개인 학습 튜터이다.

주요 목표:

- PDF 학습 자료 기반 RAG 질의응답
- 문서 기반 퀴즈 생성과 풀이
- 채팅 세션, 학습 메모리, 오답노트, 복습 큐 관리
- 정보처리기사 기출 모의고사 제공
- PostgreSQL은 관계형 데이터 저장, Qdrant는 PDF 청크 벡터 검색 전용으로 분리

## 현재 아키텍처

```text
Browser UI
  -> Spring Boot REST API
    -> PostgreSQL: 사용자, 문서 원문, 청크 원문, 채팅, 퀴즈, 오답, 복습, 모의고사 기록
    -> Qdrant: PDF 청크 임베딩 벡터 저장 및 유사도 검색
    -> Redis: Redis 기반 오답 저장 및 취약점 분석
    -> OpenAI Embedding: PDF 청크와 질문 임베딩 생성
    -> Ollama: 로컬 LLM 답변 생성
```

Qdrant 전환 후에도 PostgreSQL은 제거하지 않는다. `document_chunk`에는 청크 원문과 메타데이터를 저장하고, 임베딩 벡터는 Qdrant의 `document_chunks` collection에 저장한다. Redis는 `user:{userId}:wrongAnswers` 키로 간단한 오답 기록을 저장하고 취약점 분석에 사용한다.

## 디렉터리 구조

```text
src/main/java/com/gyeongtaekim/ai_tutor
  config        설정, 초기 데이터, DB 스키마 보정
  controller    REST API 컨트롤러
  domain        JPA 엔티티
  dto           요청/응답 DTO
  repository    Spring Data JPA 리포지토리
  security      JWT 인증
  service       비즈니스 로직
  service/question
                RAG 기반 문제 생성/검증/수정 로직

src/main/resources
  application.properties
  exam-mocks    정보처리기사 기출 CSV와 이미지 자료
  sql           과거/보조 SQL 파일
  static        브라우저 단일 페이지 UI

src/test
  H2 기반 통합 테스트

tools
  샘플 PDF 및 기출 데이터 변환 보조 자료
```

## 주요 기술

- Java 17
- Spring Boot 3.5.13
- Spring Web
- Spring Data JPA
- Spring Security
- JWT
- PostgreSQL
- Qdrant
- Redis
- LangChain4j
- OpenAI Embedding
- Ollama
- PDFBox
- H2 테스트 DB

## 데이터 저장 책임

PostgreSQL:

- `users`
- `learning_memory`
- `chat_session`
- `chat_message`
- `chat_session_document`
- `rag_document`
- `document_chunk` 원문 및 메타데이터
- `session_quiz`
- `concept`
- `problem`
- `problem_concepts`
- `user_problem_attempt`
- `wrong_answer_note`
- `review_queue`
- `exam_question_bank`
- `exam_mock_attempt`

Qdrant:

- collection: `document_chunks`
- vector size: `1536`
- distance: `Cosine`
- payload:
  - `chunkId`
  - `userId`
  - `documentId`
  - `sessionId`
  - `pageNumber`
  - `chunkIndex`

Redis:

- key pattern: `user:{userId}:wrongAnswers`
- value type: List
- 용도: `WrongAnswerService`가 저장한 간단한 오답 기록을 `WeaknessAnalysisService`가 읽어 취약 키워드 빈도를 계산한다.

## RAG 처리 흐름

PDF 업로드:

```text
PDF 업로드
-> PDFBox 텍스트 추출
-> 청크 분할
-> PostgreSQL document_chunk에 청크 원문과 메타데이터 저장
-> OpenAI Embedding으로 청크 임베딩 생성
-> Qdrant document_chunks collection에 chunkId를 point id로 upsert
```

질문:

```text
사용자 질문
-> OpenAI Embedding으로 질문 임베딩 생성
-> Qdrant에서 userId 및 documentId/documentIds filter로 유사 chunkId 검색
-> PostgreSQL document_chunk에서 chunkId 목록으로 원문 조회
-> 기존 RAG 프롬프트/근거 구성
-> Ollama LLM 호출
-> 답변 반환
```

회원별 데이터 분리는 필수다. Qdrant 검색은 `userId` 필터를 요구하며, 특정 문서 기반 질문은 `documentId` 또는 `documentIds` 필터도 함께 적용한다.

## UML 및 DB 설계 요약

자세한 다이어그램은 [UML_DB_DESIGN_KO.md](UML_DB_DESIGN_KO.md)를 기준으로 한다.

- `User`: 사용자 계정 정보 저장
  - 관련 테이블: `users`, `learning_memory`
- `Chat`: AI 튜터 대화 저장
  - 관련 테이블: `chat_session`, `chat_message`, `chat_session_document`
- `RAG Document`: PDF 문서와 검색 청크 저장
  - 관련 테이블: `rag_document`, `document_chunk`
  - 관련 벡터 저장소: Qdrant `document_chunks`
- `Problem`: 개념 기반 문제와 풀이 기록 저장
  - 관련 테이블: `concept`, `problem`, `problem_concepts`, `user_problem_attempt`
- `Review`: 오답노트와 복습 일정 저장
  - 관련 테이블: `wrong_answer_note`, `review_queue`
- `Session Quiz`: 문서 기반 생성 퀴즈와 풀이 상태 저장
  - 관련 테이블: `session_quiz`
- `Exam Mock`: 정보처리기사 기출 문제와 모의고사 풀이 기록 저장
  - 관련 테이블: `exam_question_bank`, `exam_mock_attempt`

## 인증과 계정

대부분의 API는 JWT 인증이 필요하다.

공개 API:

- `GET /`
- `GET /index.html`
- `GET /app.css`
- `GET /app.js`
- `GET /api/auth/health`
- `POST /api/auth/signup`
- `POST /api/auth/login`
- `GET /api/exam-mocks/**`
- `POST /api/feedback`

테스트 계정은 서버 시작 시 자동 생성된다.

```text
email: demo@example.com
password: secret123
role: ADMIN
```

인증 API를 직접 호출할 때는 로그인 응답의 token을 사용한다.

```text
Authorization: Bearer <token>
```

## 구현된 기능

사용자/인증:

- 회원가입
- 로그인
- JWT 발급
- 현재 인증 사용자 기준 데이터 접근 제어

문서/RAG:

- PDF 업로드
- PDF 텍스트 추출
- 청크 원문 및 메타데이터 PostgreSQL 저장
- 청크 임베딩 Qdrant 저장
- 문서 목록 조회
- 문서 다운로드
- 문서 이름 변경
- 문서 메타데이터 수정
- 문서 삭제 시 PostgreSQL 청크와 Qdrant point 정리
- 문서 분석 조회
- 문서 기반 질의응답
- 단일/다중 문서 기반 문제 생성

튜터 채팅:

- 채팅 세션 생성/조회/종료/삭제
- 세션 제목 변경
- 세션별 메시지 저장/조회
- 세션과 문서 연결
- RAG 검색 결과, 최근 대화, 학습 메모리를 조합한 튜터 답변
- Ollama 비활성 또는 실패 시 근거 기반 fallback 답변

학습 메모리:

- 사용자별 취약 개념 요약
- 학습 이력 요약
- 설명 선호 방식 저장

문제/복습:

- 개념 등록/조회
- 문제 생성/조회/제출
- 정답/오답 판정
- 오답노트 생성
- 복습 큐 생성 및 완료 처리

세션 퀴즈:

- 채팅 세션별 퀴즈 세트 저장
- 퀴즈 제출
- 퀴즈 초기화
- 세트 제목 변경
- 세트 삭제

정보처리기사 모의고사:

- 2021년 08월 14일 필기 100문항
- 2022년 03월 05일 필기 100문항
- 2022년 04월 24일 필기 100문항
- 회차별 문제/해설 제공
- 문제 이미지 및 선택지 이미지 표시
- 이미지형 선택지는 번호 기준으로 선택/채점
- OMR 화면
- 답안 제출, 해설 보기, 틀린 문제 다시 풀기
- 풀이 기록 저장/조회/삭제
- 모의고사 결과 비교
- 오류 제보 버튼 활성화

오류 제보:

- `POST /api/feedback`
- 로그인 화면의 피드백 폼
- 모의고사 문항별 오류 제보 버튼
- 제보 내용은 `run-logs/feedback-submissions.log`에 JSON Lines 형식으로 저장
- 모의고사 제보에는 시험 ID, 시험명, 문항 번호, 과목, 선택 답, 정답, 문제, 선택지, 이미지 URL, 페이지 URL, 시각이 자동 포함된다.

## 주요 API

인증:

- `POST /api/auth/signup`
- `POST /api/auth/login`
- `GET /api/auth/health`

문서/RAG:

- `GET /api/rag/documents`
- `GET /api/rag/documents/{documentId}/download`
- `GET /api/rag/documents/{documentId}/analysis`
- `PATCH /api/rag/documents/{documentId}`
- `PATCH /api/rag/documents/{documentId}/metadata`
- `DELETE /api/rag/documents/{documentId}`
- `POST /api/rag/upload`
- `POST /api/rag/analyze-preview`
- `POST /api/rag/query`
- `POST /api/rag/generate-questions`

채팅/튜터:

- `POST /api/chat/sessions`
- `GET /api/chat/sessions`
- `GET /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/close`
- `PATCH /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/documents`
- `POST /api/chat/sessions/{sessionId}/documents/{documentId}`
- `DELETE /api/chat/sessions/{sessionId}`
- `POST /api/tutor/sessions/{sessionId}/ask`

세션 퀴즈:

- `GET /api/chat/sessions/{sessionId}/quizzes`
- `POST /api/chat/sessions/{sessionId}/quizzes`
- `PATCH /api/chat/sessions/{sessionId}/quizzes/{quizSetId}`
- `DELETE /api/chat/sessions/{sessionId}/quizzes/{quizSetId}`
- `POST /api/chat/sessions/{sessionId}/quizzes/{quizId}/submit`
- `POST /api/chat/sessions/{sessionId}/quizzes/{quizId}/reset`
- `POST /api/chat/sessions/{sessionId}/quizzes/sets/{quizSetId}/reset`

학습/복습:

- `POST /api/concepts`
- `GET /api/concepts`
- `POST /api/problems`
- `GET /api/problems/{problemId}`
- `POST /api/problems/{problemId}/submit`
- `GET /api/memory/{userId}`
- `PUT /api/memory/{userId}`
- `GET /api/reviews/wrong-answers/{userId}`
- `GET /api/reviews/queue/{userId}`
- `POST /api/reviews/{reviewId}/complete`

모의고사:

- `GET /api/exam-mocks/it-engineer-20220424`
- `GET /api/exam-mocks/it-engineer-20220305`
- `GET /api/exam-mocks/it-engineer-20210814`
- `GET /api/exam-mocks/{quizSetId}`
- `GET /api/exam-mocks/{quizSetId}/attempts`
- `POST /api/exam-mocks/{quizSetId}/attempts`
- `DELETE /api/exam-mocks/{quizSetId}/attempts/{attemptId}`
- `GET /api/exam-mocks/{quizSetId}/media/{fileName}`

피드백:

- `POST /api/feedback`

## 브라우저 UI

브라우저에서 `http://localhost:8080`으로 접속한다.

현재 UI에서 가능한 흐름:

- 로그인/회원가입
- 피드백 제출
- 학습 메모리 관리
- PDF 업로드
- 문서 목록 관리
- 문서 분석 확인
- 문서 기반 질문
- 채팅 세션 생성 및 튜터 질문
- 문서 기반 퀴즈 생성/풀이
- 정보처리기사 모의고사 풀이
- 모의고사 이미지형 선택지 풀이
- 모의고사 오류 제보
- 모의고사 풀이 기록 비교

## 현재 실행 상태 확인 기준

필수 컨테이너:

- `ai-tutor-postgres`
- `ai-tutor-redis`
- `ai-tutor-qdrant`

포트:

- Spring Boot: `8080`
- PostgreSQL: `5432`
- Redis: `6379`
- Qdrant HTTP: `6333`
- Qdrant gRPC: `6334`
- Ollama: `11434`

Qdrant collection 확인:

```powershell
Invoke-RestMethod http://localhost:6333/collections
Invoke-RestMethod http://localhost:6333/collections/document_chunks
```

## 현재 제한 사항

- OpenAI API 키가 없으면 Qdrant에 저장할 임베딩 벡터를 생성할 수 없다.
- Ollama가 없거나 꺼져 있으면 로컬 LLM 답변 대신 근거 기반 fallback 답변을 사용한다.
- 피드백/오류 제보는 이메일 발송이 아니라 로컬 로그 파일 저장 방식이다.
- 운영 배포용 보안 설정과 비밀값 관리는 별도 정리가 필요하다.
- `src/main/resources/sql/pgvector_document_chunk_embedding.sql`에는 과거 pgvector 보조 SQL이 남아 있다. 현재 주요 Java 검색/저장 경로는 Qdrant를 사용한다.
- `spring.jpa.hibernate.ddl-auto=update`와 여러 `*SchemaInitializer`가 기존 DB 스키마를 보정한다. 운영 환경에서는 명시적인 마이그레이션 도구 도입이 필요하다.

## 검증 상태

2026-08-30 기준 다음 명령이 통과했다.

```powershell
.\gradlew.bat test
```

확인된 사항:

- 컴파일 성공
- 통합 테스트 성공
- 로그인/채팅/퀴즈/모의고사 주요 흐름 유지
- Qdrant collection `document_chunks` 생성 확인
- PDF 업로드 후 Qdrant point 저장 확인
- 모의고사 이미지 선택지 렌더링 및 번호 기반 선택/채점 수정
- 오류 제보 API 저장 확인

## 다음 우선순위

1. 운영/개발 프로필 분리
2. OpenAI API 키와 DB 비밀번호를 `.env` 또는 Secret 관리로 이동
3. Qdrant 검색 품질 평가용 테스트 데이터셋 추가
4. 오류 제보를 이메일/Slack/GitHub Issue 등 외부 알림으로 연결
5. pgvector 관련 과거 SQL 파일 정리 또는 명확한 legacy 표시
6. 모의고사 회차 추가
7. RAG 답변 품질 벤치마크 추가
