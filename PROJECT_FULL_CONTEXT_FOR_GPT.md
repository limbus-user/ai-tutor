# AI Tutor Project Full Context for GPT

작성일: 2026-06-08
작업 위치: `C:\Users\c\Desktop\ai-tutor-github`
원격 저장소: `https://github.com/limbus-user/ai-tutor.git`
현재 기준 커밋: `b5c3fb1 Polish workspace upload and auth UI`

이 문서는 다음 GPT/개발자가 프로젝트를 빠르게 파악하도록 코드와 실행 상태를 확인한 내용을 정리한 인수인계 문서다.

## 1. 이번 대화에서 실제 수행한 작업

1. 기존 로컬 폴더 `C:\Users\c\Desktop\ai-tutor`는 수정하지 않고 확인만 했다.
2. 기존 폴더에는 로컬 변경사항이 많아 덮어쓰지 않았다.
3. GitHub 원격 `https://github.com/limbus-user/ai-tutor.git`를 별도 폴더 `C:\Users\c\Desktop\ai-tutor-github`에 새로 클론했다.
4. 기존 8080 포트를 점유하던 Java 서버 PID `24928`을 종료했다.
5. Docker Desktop을 시작하고 PostgreSQL/Redis 컨테이너를 실행했다.
6. `ai-tutor-github`에서 `gradlew.bat bootRun`을 백그라운드 실행했다.
7. 서버가 `http://localhost:8080` 및 `http://localhost:8080/index.html`에서 HTTP 200으로 응답하는 것을 확인했다.
8. 사용자가 요청한 `AI TUTOR` 왼쪽 문양 제거 작업을 수행했다.
9. 변경 파일은 `src/main/resources/static/index.html`이며, 실행 중 서버에 즉시 반영되도록 `build/resources/main/static/index.html`에도 같은 변경을 적용했다.
10. 브라우저 캐시 회피를 위해 `app.css`와 `app.js` 쿼리 버전을 갱신했다.
11. 워크스페이스 축소 화면에서 3열이 지나치게 좁아지는 문제를 고치기 위해 반응형 CSS를 추가했다.

현재 실행 상태:

- 앱 URL: `http://localhost:8080`
- 앱 Java 프로세스: PID `26720`
- PostgreSQL 컨테이너: `ai-tutor-postgres`, 포트 `5432`
- Redis 컨테이너: `ai-tutor-redis`, 포트 `6379`
- 로그 파일: `C:\Users\c\Desktop\ai-tutor-github\run-logs\bootRun.out.log`

## 2. 프로젝트 한 줄 요약

PDF를 업로드하면 텍스트를 추출하고 청크로 나눈 뒤, RAG 검색 기반 채팅 답변과 PDF 기반 퀴즈 생성을 제공하는 Spring Boot + 정적 SPA 학습 튜터 프로젝트다.

## 3. 기술 스택

- Backend: Java 17, Spring Boot 3.5.13
- Build: Gradle Wrapper
- Web: Spring MVC + 정적 `index.html`, `app.css`, `app.js`
- Persistence: Spring Data JPA, PostgreSQL 운영 설정, H2 테스트 설정
- Cache/Session-like storage: Redis, `RedisTemplate<String, Object>`
- Security: Spring Security, BCrypt, JWT 생성 로직은 있으나 현재 모든 요청 허용
- PDF parsing: Apache PDFBox 3.0.1
- RAG/LLM helper: LangChain4j 0.35.0
- Local LLM: Ollama `/api/generate`
- OpenAI fallback: LangChain4j OpenAI Chat/Embedding 모델

## 4. 실행 방법

현재 GitHub 클론 기준 기본 실행은 PostgreSQL과 Redis가 필요하다.

```powershell
cd C:\Users\c\Desktop\ai-tutor-github

# Docker Desktop 실행 후 PostgreSQL/Redis 준비
docker run -d --name ai-tutor-postgres -e POSTGRES_DB=ai_tutor -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
docker run -d --name ai-tutor-redis -p 6379:6379 redis:7-alpine

# 앱 실행
.\gradlew.bat bootRun
```

현재 DB 설정은 `src/main/resources/application.properties`에 있다.

```properties
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/ai_tutor
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=update
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

테스트 프로필은 `src/test/resources/application-test.properties`를 사용한다.

- H2 메모리 DB 사용
- `ddl-auto=create-drop`
- `ollama.enabled=false`

## 5. LLM 구성

### 5.1 기본 LLM 우선순위

채팅 답변과 퀴즈 생성에서 LLM 사용 우선순위는 다음과 같다.

1. Ollama가 활성화되어 있으면 Ollama를 먼저 사용한다.
2. Ollama가 실패하거나 비활성화되어 있으면 OpenAI API Key가 있을 때 OpenAI를 사용한다.
3. 둘 다 없거나 실패하면 문서 근거 기반 deterministic/fallback 답변 또는 문제 생성 로직을 사용한다.

### 5.2 현재 설정값

`application.properties` 기준:

```properties
openai.api.key=${OPENAI_API_KEY:}
openai.chat.model=${OPENAI_CHAT_MODEL:gpt-4o-mini}
openai.embedding.model=${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}

ollama.enabled=true
ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
ollama.chat.model=${OLLAMA_CHAT_MODEL:qwen2.5:7b}
ollama.temperature=${OLLAMA_TEMPERATURE:0.1}
```

현재 코드상 기본 LLM 모델:

- Ollama chat/generation model: `qwen2.5:7b`
- OpenAI chat fallback model: `gpt-4o-mini`
- OpenAI embedding model: `text-embedding-3-small`

### 5.3 OllamaService 동작

파일: `src/main/java/com/gyeongtaekim/ai_tutor/service/OllamaService.java`

- `generate(systemPrompt, prompt)`는 일반 텍스트 응답을 요청한다.
- `generateJson(systemPrompt, prompt)`는 Ollama 요청에 `format=json`을 넣어 JSON 응답을 기대한다.
- 엔드포인트는 `POST {ollama.base-url}/api/generate`다.
- 옵션은 `temperature`, `num_ctx`, `num_predict`를 넘긴다.
- 접속 타임아웃 5초, 읽기 타임아웃 180초다.
- 실패하면 예외를 밖으로 던지지 않고 warn 로그 후 `null`을 반환한다.

## 6. 주요 디렉터리 구조

```text
src/main/java/com/gyeongtaekim/ai_tutor
  AiTutorApplication.java
  config/
  controller/
  domain/
  dto/
  repository/
  security/
  service/
    question/
src/main/resources
  application.properties
  sql/alter_pdf_text_columns_to_text.sql
  static/index.html
  static/app.css
  static/app.js
src/test
  java/.../AiTutorIntegrationTest.java
  resources/application-test.properties
```

루트 문서:

- `PROJECT_BLUEPRINT.md`, `PROJECT_BLUEPRINT_KO.md`: 초기 설계/청사진
- `PROJECT_STATUS_KO_SUMMARY.md`: 상태 요약
- `PROFESSOR_DEMO_GUIDE_KO.md`: 데모 가이드
- `HANDOFF_NEXT_AGENT.md`: 이전 인수인계 문서
- `pdf_multi_select_bug_plan.md`: PDF 다중 선택 관련 계획

## 7. 백엔드 아키텍처

전형적인 계층 구조다.

- `controller`: REST API 엔드포인트
- `service`: 비즈니스 로직
- `domain`: JPA 엔티티
- `repository`: Spring Data JPA Repository
- `dto`: 요청/응답 객체
- `security`: JWT, UserDetails, 필터
- `config`: Security, Redis, DB 스키마 보정, 테스트 계정 seed

요청 흐름 예시:

```text
브라우저 app.js
  -> REST Controller
  -> Service
  -> Repository/JPA 또는 Redis
  -> DB/Redis/File/Ollama/OpenAI
  -> DTO 응답
  -> app.js 렌더링
```

## 8. 인증과 보안

### 8.1 회원가입/로그인

- `POST /api/auth/signup`
- `POST /api/auth/login`

`AuthService`는 다음을 수행한다.

- 이메일 중복 검사
- BCrypt로 비밀번호 암호화
- `users` 테이블 저장
- JWT 토큰 생성

`AuthResponse` 필드:

- `id`
- `token`
- `email`
- `name`
- `role`

### 8.2 JWT 구현 상태

`JwtTokenProvider`는 JWT 생성/검증/이메일 추출을 구현한다.

- secret: `jwt.secret`
- expiration: `jwt.expiration`, 기본 86400000ms
- subject: email

`JwtAuthenticationFilter`도 존재하지만, 현재 `SecurityConfig`에서는 필터를 실제 체인에 등록하지 않는다.

### 8.3 현재 보안 정책

`SecurityConfig`에서 다음처럼 모든 요청을 허용한다.

```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
```

즉 프론트엔드는 localStorage에 JWT를 저장하고 요청에 `Authorization: Bearer`를 붙이지만, 서버는 현재 인증을 강제하지 않는다.

### 8.4 기본 계정

`TestAccountInitializer`가 실행 시 다음 계정을 생성/관리한다.

- email: `demo@example.com`
- password: `secret123`
- name: `Demo Admin`
- role: `ADMIN`

## 9. 데이터베이스 구성

### 9.1 DB 종류

운영/로컬 기본:

- PostgreSQL
- DB명: `ai_tutor`
- 사용자: `postgres`
- 비밀번호: `postgres`

테스트:

- H2 in-memory
- PostgreSQL compatibility mode

### 9.2 스키마 생성 방식

`spring.jpa.hibernate.ddl-auto=update`로 JPA 엔티티 기준 자동 업데이트한다.

추가로 다음 schema initializer가 있다.

- `ChatSessionSchemaInitializer`: `chat_session.type` 컬럼 추가 및 null 값을 `STUDY`로 보정
- `PdfTextSchemaInitializer`: PostgreSQL에서 긴 텍스트 컬럼을 `text` 타입으로 변경
- `SessionQuizSchemaInitializer`: `session_quiz` 확장 컬럼 추가, legacy 데이터 기본값 보정, text 타입 보정

### 9.3 현재 확인한 테이블 목록

실행 중 PostgreSQL에서 확인한 테이블은 14개다.

- `users`
- `chat_session`
- `chat_message`
- `chat_session_document`
- `rag_document`
- `document_chunk`
- `session_quiz`
- `concept`
- `problem`
- `problem_concepts`
- `user_problem_attempt`
- `wrong_answer_note`
- `review_queue`
- `learning_memory`

### 9.4 주요 테이블 설명

#### users

사용자 계정 테이블.

- `id`
- `email`, unique
- `password`, BCrypt hash
- `name`
- `role`, `USER` 또는 `ADMIN`

#### chat_session

학습 세션/퀴즈 세션 단위.

- `id`
- `user_id`
- `title`
- `status`, 예: `ACTIVE`, `CLOSED`
- `type`, 예: `STUDY`, `QUIZ`
- `created_at`
- `updated_at`

#### chat_message

세션별 대화 기록.

- `id`
- `session_id`
- `role`, `USER` 또는 `ASSISTANT`
- `content`, text
- `source_references`
- `created_at`

#### rag_document

업로드한 PDF 문서 메타데이터와 추출 텍스트.

- `id`
- `title`
- `source_type`, 현재 `PDF`
- `trust_level`
- `subject`
- `unit_name`
- `stored_file_name`, unique
- `extracted_text`, text
- `created_at`

#### document_chunk

PDF에서 추출한 텍스트 청크.

- `id`
- `document_id`
- `chunk_index`
- `chunk_text`, text
- `metadata`

#### chat_session_document

세션과 PDF 문서의 연결 테이블.

- `id`
- `session_id`
- `document_id`

이 테이블 때문에 세션을 다시 열어도 연결된 PDF 목록이 유지된다.

#### session_quiz

생성된 퀴즈와 풀이 상태를 저장한다.

- `id`
- `session_id`
- `document_id`
- `source_document_ids_json`, 다중 PDF 선택 시 원본 문서 id 배열 JSON
- `quiz_set_id`, 한 번에 생성된 퀴즈 묶음 id
- `quiz_set_title`
- `question_order`
- `type`
- `question`
- `choices_json`
- `correct_answer`
- `model_answer`
- `explanation`
- `source_evidence`
- `difficulty`
- `concept_tag`
- `understanding_level`
- `submitted_answer`
- `correct`
- `evaluation_feedback`
- `attempt_count`
- `reset_count`
- `solved`
- `last_solved_at`
- `created_at`

#### concept / problem / problem_concepts

수동 문제 생성과 개념 태깅용.

- `concept`: 과목, 단원, 개념명, 설명
- `problem`: 질문, 정답, 해설, 난이도, 이해 수준, 문제 유형
- `problem_concepts`: 문제와 개념 다대다 연결

#### user_problem_attempt / wrong_answer_note / review_queue

오답 제출과 복습 큐를 위한 테이블.

- `user_problem_attempt`: 제출 답안, 정오답, 피드백, 제출 시각
- `wrong_answer_note`: 오답 개념 태그, 해설, 복습 상태
- `review_queue`: 다음 복습 시각, 우선순위, 상태

#### learning_memory

사용자별 학습 기억.

- `user_id`, unique
- `weak_concept_summary`
- `history_summary`
- `preferences`
- `updated_at`

## 10. Redis 구성

`RedisConfig`는 `RedisTemplate<String, Object>`를 등록한다.

- key serializer: `StringRedisSerializer`
- value/hash value serializer: `Jackson2JsonRedisSerializer<Object>`
- `JavaTimeModule` 등록으로 `LocalDateTime` 직렬화 지원

Redis 사용 위치:

- `WrongAnswerService`: `user:{userId}:wrongAnswers` 리스트에 오답 저장
- `WeaknessAnalysisService`: 위 Redis 리스트를 읽어 단어 빈도 기반 취약점 분석
- `LearningSessionService`: `chapters` key에 챕터 목록 저장, active session은 메모리 `ConcurrentHashMap`도 사용

주의: Redis 기반 학습 세션 기능은 현재 메인 SPA 흐름보다 이전/보조 기능에 가깝다. 현재 사용자 화면의 핵심 흐름은 JPA 기반 chat/session/rag/session_quiz 쪽이다.

## 11. 파일 저장 구조

PDF 업로드 경로는 `upload.path=uploads`다.

업로드 시 파일명은 다음 형태로 저장된다.

```text
{System.currentTimeMillis()}_{originalFilename}
```

DB에는 `rag_document.stored_file_name`으로 저장 파일명을 기록한다.

삭제 시 `RagService.deleteDocument`가 다음을 수행한다.

1. `document_chunk` 삭제
2. `chat_session_document` 연결 삭제
3. `rag_document` 삭제
4. 실제 `uploads` 파일 삭제

## 12. RAG 동작 방식

핵심 파일: `RagService.java`

### 12.1 PDF 업로드 흐름

`POST /api/rag/upload`

요청:

- multipart `file`
- optional `subject`
- optional `unitName`
- optional `trustLevel`

처리 흐름:

1. 빈 파일 검사
2. `uploads` 디렉터리 생성
3. 원본 PDF 파일 저장
4. PDFBox로 텍스트 추출
5. LangChain4j `DocumentSplitters.recursive(900, 140)` 계열 로직으로 텍스트 청크 분할
6. `rag_document` 저장
7. `document_chunk` 저장
8. 가능한 경우 embedding store에 청크 추가
9. `RagDocumentUploadResponse` 반환

응답 필드:

- `documentId`
- `title`
- `storedFileName`
- `chunkCount`

### 12.2 검색/질문 흐름

`POST /api/rag/query`

처리 흐름:

1. 전체 문서 또는 특정 문서의 청크 조회
2. 관련 청크 검색
3. 상위 청크에서 근거 문장 추출
4. `RagQueryResponse(query, answer, sources)` 반환

검색 방식:

- OpenAI API Key가 있으면 embedding 기반 검색을 시도한다.
- embedding store는 `InMemoryEmbeddingStore<DocumentChunk>`라 애플리케이션 메모리에 존재한다.
- OpenAI Key가 없거나 embedding 실패 시 키워드 기반 점수 검색으로 fallback한다.
- 상위 검색 개수는 코드 상수 기준 `RETRIEVAL_TOP_K = 4`다.
- embedding score와 keyword score를 가중 결합하는 코드가 있다.

중요한 제약:

- embedding store는 in-memory라 앱 재시작 후에는 DB의 기존 chunk embedding이 자동 재구성되어야 검색 품질이 유지된다. 코드에는 `embeddingsInitialized`와 초기화 로직이 존재한다.
- OpenAI API Key가 없으면 embedding은 사용되지 않고 fallback 검색에 의존한다.

## 13. 튜터 채팅 동작 방식

핵심 파일: `TutorService.java`

API:

```text
POST /api/tutor/sessions/{sessionId}/ask
```

요청 DTO:

- `question`
- `documentId`, optional. 프론트는 선택 PDF가 1개일 때만 보낸다.

처리 흐름:

1. `chat_session` 조회
2. 사용자 질문을 `chat_message`에 `USER`로 저장
3. 사용자 `learning_memory` 조회
4. 최근 메시지 기반으로 후속 질문/비교 질문을 재작성하려고 시도
5. `RagService.query(groundedQuestion, documentId)` 실행
6. 근거가 없으면 근거 없음 답변 반환
7. 근거가 있으면 Ollama로 grounded answer 생성 시도
8. Ollama 실패 시 OpenAI API Key가 있으면 `gpt-4o-mini`로 답변 생성 시도
9. 둘 다 실패하면 RAG 근거 문장 자체를 답변으로 사용
10. assistant 답변을 `chat_message`에 저장
11. session `updated_at` 갱신
12. `TutorAskResponse` 반환

답변 프롬프트 정책:

- 한국어로 답변
- 제공된 근거만 사용
- 부족한 근거는 부족하다고 말하기
- 설명형 질문은 5~8문장
- 출처 목록은 답변 본문에 넣지 말고 코드가 마지막에 붙임

응답에는 출처가 다음처럼 붙는다.

```text
출처:
파일명.pdf [chunk 0]
```

주의: 일부 소스 문자열은 현재 파일 인코딩 문제로 한글이 깨져 보이는 부분이 있다. 런타임 문자열도 일부 깨진 상태로 응답될 가능성이 있다.

## 14. 퀴즈 생성 동작 방식

퀴즈 생성에는 두 계층의 로직이 있다.

1. `RagService` 내부의 기존 문제 생성 로직
2. `service/question` 패키지의 확장된 문제 생성/검증 로직

현재 프론트엔드의 주 흐름은 다음 API를 사용한다.

```text
POST /api/rag/generate-questions?sessionId=...&type=...&count=...&documentIds=...
POST /api/chat/sessions/{sessionId}/quizzes
```

### 14.1 프론트엔드 퀴즈 생성 흐름

`app.js`의 `generateQuiz`:

1. 현재 세션 확인
2. 선택된 PDF id 목록 확인
3. `/api/rag/generate-questions` 호출
4. 반환된 questions를 `/api/chat/sessions/{sessionId}/quizzes`에 저장
5. `session_quiz`에 저장된 퀴즈 목록을 화면에 렌더링

### 14.2 지원 문제 타입

프론트 선택 UI에는 다음 옵션이 있다.

- `mixed`
- `multiple_choice`
- `ox`
- `short_answer`

`service/question/QuestionType.java`에는 더 많은 타입이 정의되어 있다.

- `multiple_choice`
- `short_answer`
- `fill_in_blank`
- `true_false`
- `matching`
- `ordering`
- `code_reading`
- `code_completion`
- `error_detection`
- `comparison`
- `application`
- `multi_select`

### 14.3 생성 전략

- Ollama가 가능하면 `generateJson`으로 JSON 문제 생성을 시도한다.
- 생성 결과는 `QuestionRepairService`와 `QuestionValidator`에서 보정/검증된다.
- 검증 실패 또는 LLM 비활성화 시 deterministic 문제 생성 로직으로 fallback한다.
- `mixed`는 난이도에 따라 문제 타입 가중치를 달리한다.
- 같은 session/document 조합에서 이전에 생성된 질문과 중복을 피하기 위해 fingerprint 제외 로직이 있다.

### 14.4 퀴즈 저장 구조

`SessionQuizService.saveQuizzes`:

1. `documentId` 필수 검사
2. `documentIds`가 있으면 정렬/중복 제거하여 `source_document_ids_json`에 저장
3. 새 `quiz_set_id` UUID 생성
4. `quiz_set_title` 설정
5. 각 question을 `SessionQuiz` 엔티티로 변환 후 저장

퀴즈 묶음 기능:

- 퀴즈 세트 이름 변경: `PATCH /api/chat/sessions/{sessionId}/quizzes/{quizSetId}`
- 퀴즈 세트 삭제: `DELETE /api/chat/sessions/{sessionId}/quizzes/{quizSetId}`
- 세트 전체 초기화: `POST /api/chat/sessions/{sessionId}/quizzes/sets/{quizSetId}/reset`

### 14.5 퀴즈 채점

`SessionQuizService.submitQuiz`:

- 객관식/OX는 정규화한 답안이 정확히 일치해야 정답
- 주관식은 정확 일치, 포함 관계, model answer 포함, 의미 토큰 overlap을 사용
- 주관식 토큰 overlap은 너무 짧은 답은 정답 처리하지 않음
- 제출하면 `submitted_answer`, `correct`, `evaluation_feedback`, `attempt_count`, `solved`, `last_solved_at` 갱신

## 15. REST API 전체 요약

### Auth

```text
POST /api/auth/signup
POST /api/auth/login
```

### Users

```text
POST /api/users
GET /api/users
```

### Chat Sessions

```text
POST /api/chat/sessions
GET /api/chat/sessions?userId={userId}
GET /api/chat/sessions/{sessionId}
PATCH /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
POST /api/chat/sessions/{sessionId}/close
```

### Chat Messages

```text
GET /api/chat/sessions/{sessionId}/messages
POST /api/chat/sessions/{sessionId}/messages
POST /api/tutor/sessions/{sessionId}/ask
```

### Session Documents

```text
GET /api/chat/sessions/{sessionId}/documents
POST /api/chat/sessions/{sessionId}/documents/{documentId}
```

### RAG Documents

```text
GET /api/rag/documents
GET /api/rag/documents/{documentId}/download
PATCH /api/rag/documents/{documentId}
DELETE /api/rag/documents/{documentId}
POST /api/rag/upload
POST /api/rag/query
POST /api/rag/generate-questions
```

### Session Quizzes

```text
GET /api/chat/sessions/{sessionId}/quizzes
POST /api/chat/sessions/{sessionId}/quizzes
PATCH /api/chat/sessions/{sessionId}/quizzes/{quizSetId}
DELETE /api/chat/sessions/{sessionId}/quizzes/{quizSetId}
POST /api/chat/sessions/{sessionId}/quizzes/{quizId}/submit
POST /api/chat/sessions/{sessionId}/quizzes/{quizId}/reset
POST /api/chat/sessions/{sessionId}/quizzes/sets/{quizSetId}/reset
```

### Concepts / Problems / Reviews

```text
POST /api/concepts
GET /api/concepts
POST /api/problems
GET /api/problems/{problemId}
POST /api/problems/{problemId}/submit
GET /api/reviews/wrong-answers/{userId}
GET /api/reviews/queue/{userId}
POST /api/reviews/{reviewId}/complete
```

### Learning Memory / Legacy Session / Weakness

```text
GET /api/memory/{userId}
PUT /api/memory/{userId}
GET /api/session/chapters
POST /api/session/start
GET /api/session/questions/{userId}
POST /api/session/answer
POST /api/session/end/{userId}
POST /api/session/chapters
POST /api/wrong-answers/save
GET /api/wrong-answers/{userId}
DELETE /api/wrong-answers/{userId}
GET /api/analysis/{userId}
```

### Feedback

```text
POST /api/feedback
```

피드백은 DB가 아니라 `run-logs/feedback-submissions.log`에 JSON line 형태로 append된다.

## 16. 프론트엔드 구성

정적 SPA다.

- `src/main/resources/static/index.html`: 화면 구조와 `<template>` 정의
- `src/main/resources/static/app.css`: 전체 스타일
- `src/main/resources/static/app.js`: 상태 관리, API 호출, 렌더링, 이벤트 핸들러

### 16.1 View 구성

`index.html`의 주요 view:

1. `auth-view`
2. `home-view`
3. `workspace-view`
4. `quiz-view`

`app.js`의 `showView(name)`가 active view를 바꾼다.

### 16.2 Auth View

구성:

- 로그인 폼
- 회원가입 폼
- 피드백 폼
- 서버 상태 pill

기본 입력값:

- email: `demo@example.com`
- password: `secret123`

로그인 성공 후:

1. `localStorage`의 `ai-tutor-auth`에 auth 정보 저장
2. `loadHome()` 호출
3. home view 표시

### 16.3 Home View

구성:

- 학습 현황 카드: 전체 세션, 업로드 문서, 생성 퀴즈, 총 대화 수
- 세션 목록/검색/필터/페이지네이션
- 새 공부 시작 카드
- 최근 대화 기록

새 공부 시작 흐름:

1. PDF 선택
2. 과목/단원/신뢰도 입력
3. `/api/rag/upload`
4. `/api/chat/sessions`
5. `/api/chat/sessions/{sessionId}/documents/{documentId}`
6. workspace view로 이동

### 16.4 Workspace View

구성:

- 상단 브랜드: 현재는 `AI TUTOR` 텍스트만 남김. 이전 SVG 문양은 제거됨.
- 왼쪽 PDF 패널: PDF 업로드, 문서 목록, 다중 선택
- 가운데 채팅 패널: 메시지 목록, 질문 입력, 첨부 버튼
- 오른쪽 퀴즈 패널: 선택 PDF 표시, 문제 타입/개수 선택, 최근 생성 퀴즈 목록

문서 선택 방식:

- 문서 row 또는 체크 버튼을 클릭하면 `selectedQuizDocumentIds` 배열에 추가/제거된다.
- 선택 PDF가 1개일 때 채팅 질문에는 `documentId`를 보내 특정 문서로 scope한다.
- 퀴즈 생성은 선택된 여러 PDF id를 `documentIds` query param으로 모두 보낸다.

### 16.5 Quiz View

구성:

- 퀴즈 전용 세션 화면
- 현재 퀴즈 카드
- 정답 확인
- 이전/다음 이동
- 문제별 reset
- 세트 전체 reset
- 결과 요약/비교 화면 관련 렌더링 코드 존재

### 16.6 Templates

`index.html`에 다음 template이 있다.

- `session-item-template`
- `document-item-template`
- `message-template`
- `quiz-template`
- `quiz-set-template`
- `quiz-session-template`

### 16.7 API 호출 래퍼

`app.js`의 `apiFetch(url, options)`가 모든 API 호출을 담당한다.

- `Authorization: Bearer {token}` 자동 추가
- JSON body면 `Content-Type: application/json` 처리
- 응답 text를 JSON parse 시도
- 실패 응답은 `message`를 추출해 Error 발생
- admin/dev mode용 API log를 메모리에 쌓음

## 17. 주요 사용자 시나리오

### 17.1 로그인 후 PDF 업로드 학습

```text
로그인
  -> Home
  -> 새 공부 시작
  -> PDF 업로드
  -> chat_session 생성
  -> session-document 연결
  -> Workspace 진입
```

### 17.2 채팅 질문

```text
Workspace에서 질문 입력
  -> /api/tutor/sessions/{sessionId}/ask
  -> USER 메시지 저장
  -> RAG 검색
  -> Ollama/OpenAI/fallback 답변 생성
  -> ASSISTANT 메시지 저장
  -> 메시지 목록 다시 조회
  -> 화면 렌더링
```

### 17.3 퀴즈 생성과 풀이

```text
Workspace에서 PDF 선택
  -> 문제 타입/개수 선택
  -> /api/rag/generate-questions
  -> /api/chat/sessions/{sessionId}/quizzes 저장
  -> Quiz set 목록 렌더링
  -> Quiz view 진입
  -> 답안 제출
  -> /submit API
  -> 정오답/피드백 저장 및 렌더링
```

### 17.4 오답 복습 흐름

수동 `Problem` API 기반 문제 제출에서 오답이면:

```text
/problem/{id}/submit
  -> UserProblemAttempt 저장
  -> ReviewService.recordWrongAnswer
  -> WrongAnswerNote 저장
  -> ReviewQueue 저장, nextReviewAt = now + 1 day
```

이 흐름은 현재 메인 PDF 퀴즈 UI보다 별도/보조 기능에 가깝다.

## 18. 테스트 구성

테스트 파일: `src/test/java/com/gyeongtaekim/ai_tutor/AiTutorIntegrationTest.java`

검증하는 주요 내용:

- 회원가입/로그인 JWT 응답
- `/index.html` 정적 페이지 제공
- 세션 문서 연결 유지
- 세션 간 문서 연결 격리
- 오답 제출 시 복습 artifact 생성
- 문제 조회 시 정답 미노출
- OX 문제 생성/제출
- PDF 업로드, RAG query, tutor ask flow
- 특정 documentId로 tutor ask scope 제한
- 한국어 PDF 기반 질문/퀴즈 생성
- 세션 삭제 시 메시지/퀴즈 삭제
- 다중 PDF 선택 기반 퀴즈 생성
- 주관식 application 답변 품질 검증

테스트 프로필에서는 Ollama가 꺼져 있어 deterministic/fallback 경로를 많이 검증한다.

## 19. 현재 코드 품질/주의사항

### 19.1 인코딩 문제

여러 Java 파일의 한글 문자열/주석이 깨져 있다. 예:

- `TutorService`
- `RagService`
- `SessionQuizService`
- `ReviewService`
- 일부 테스트 기대 문자열

정적 HTML은 현재 정상 한글로 보인다. 그러나 Java 런타임 응답 문자열 중 일부는 깨진 한글이 사용자에게 보일 수 있다. 후속 작업 시 UTF-8로 문자열을 복구하는 작업이 필요하다.

### 19.2 Security는 개발용 허용 상태

JWT는 구현되어 있지만 모든 API가 `permitAll`이다. 실제 배포라면 반드시 보호해야 한다.

### 19.3 OpenAI embedding은 선택적이다

OpenAI API Key가 없으면 embedding 검색이 아닌 fallback 검색을 사용한다. 현재 로컬 실행에서 `OPENAI_API_KEY`가 비어 있으면 LLM 답변은 Ollama에 의존하고, Ollama 실패 시 fallback 답변으로 간다.

### 19.4 Ollama 모델 필요

`ollama.enabled=true`이므로 로컬에 Ollama 서버와 `qwen2.5:7b` 모델이 있어야 LLM 품질이 나온다. Ollama가 실패해도 서버는 죽지 않고 fallback한다.

### 19.5 Build output 직접 수정 주의

이번 대화에서는 실행 중 서버에 즉시 반영하려고 `build/resources/main/static/index.html`도 수정했다. 영구 수정 기준 파일은 `src/main/resources/static/index.html`이다. 재빌드하면 build output은 소스 기준으로 다시 생성된다.

### 19.6 별도 클론 폴더 사용 중

현재 실행/수정 기준은 `C:\Users\c\Desktop\ai-tutor-github`다. 기존 `C:\Users\c\Desktop\ai-tutor`는 로컬 변경사항이 많아 덮어쓰지 않았다.

## 20. 최근 UI 변경 상세

사용자 요청: `ai tutor 옆에 있는 이상한 문양 지워줘`

변경 전:

```html
<div class="workspace-brand">
  <span class="brand-mark"><svg ...></svg></span>
  <strong>AI TUTOR</strong>
</div>
```

변경 후:

```html
<div class="workspace-brand"><strong>AI TUTOR</strong></div>
```

수정 위치:

- `src/main/resources/static/index.html`
- `build/resources/main/static/index.html`, 실행 중 즉시 반영용

CSS의 `.brand-mark` 규칙은 남아 있지만 HTML에서 더 이상 사용하지 않는다. 삭제해도 되지만 현재는 영향이 없다.

추가 반응형 수정:

- `src/main/resources/static/app.css`의 `.app-shell { zoom: 1.08; }`을 `zoom: 1`로 낮춰 브라우저 폭 계산이 왜곡되지 않게 했다.
- `#workspace-view .workspace-grid`에 `grid-template-areas`를 추가했다.
- 861px에서 1240px 사이에서는 기존 3열 대신 `문서/퀴즈` 왼쪽, `채팅` 오른쪽의 2열 구조로 바뀐다.
- 860px 이하에서는 문서, 채팅, 퀴즈가 1열로 쌓인다.
- 큰 높이 화면에서 워크스페이스가 과도하게 늘어나지 않도록 `height: clamp(...)`로 최대 높이를 제한했다.
- 현재 정적 리소스 버전 문자열은 `20260608-workspace-responsive-1`이다.

## 21. 후속 개발자가 먼저 보면 좋은 파일 순서

1. `src/main/resources/static/app.js`
2. `src/main/resources/static/index.html`
3. `src/main/java/com/gyeongtaekim/ai_tutor/controller/RagController.java`
4. `src/main/java/com/gyeongtaekim/ai_tutor/service/RagService.java`
5. `src/main/java/com/gyeongtaekim/ai_tutor/service/TutorService.java`
6. `src/main/java/com/gyeongtaekim/ai_tutor/service/SessionQuizService.java`
7. `src/main/java/com/gyeongtaekim/ai_tutor/domain/SessionQuiz.java`
8. `src/main/java/com/gyeongtaekim/ai_tutor/domain/RagDocument.java`
9. `src/main/java/com/gyeongtaekim/ai_tutor/domain/DocumentChunk.java`
10. `src/test/java/com/gyeongtaekim/ai_tutor/AiTutorIntegrationTest.java`

## 22. 빠른 점검 명령어

```powershell
# 앱 응답 확인
Invoke-WebRequest -UseBasicParsing http://localhost:8080/index.html

# 서버 로그 tail
Get-Content C:\Users\c\Desktop\ai-tutor-github\run-logs\bootRun.out.log -Tail 80

# 컨테이너 상태
docker ps --filter "name=ai-tutor"

# DB 테이블 확인
docker exec ai-tutor-postgres psql -U postgres -d ai_tutor -c "\dt"

# 현재 소스 변경 확인
git status --short
```

## 23. 현재 git 변경 상태

현재 문서 작성 전 기준으로 추적 변경은 다음이 있었다.

- `src/main/resources/static/index.html`: workspace brand SVG 제거 및 cache-busting query 변경
- `src/main/resources/static/app.css`: workspace 반응형 레이아웃 보정

이 문서를 만들면 추가로 다음 파일이 생긴다.

- `PROJECT_FULL_CONTEXT_FOR_GPT.md`

## 24. 요약

이 프로젝트는 PDF 학습 자료를 중심으로 한 AI 튜터 웹앱이다. Spring Boot 서버가 PDF 업로드, 텍스트 추출, RAG 검색, LLM 답변 생성, 퀴즈 생성/저장/채점을 담당한다. 프론트엔드는 정적 SPA이며 로그인, 홈 대시보드, 워크스페이스, 퀴즈 풀이 화면으로 구성된다. LLM은 기본적으로 Ollama `qwen2.5:7b`를 우선 사용하고, OpenAI API Key가 있으면 `gpt-4o-mini`와 `text-embedding-3-small`을 fallback/embedding 용도로 사용한다. DB는 PostgreSQL이고 핵심 테이블은 `users`, `chat_session`, `chat_message`, `rag_document`, `document_chunk`, `session_quiz`다.
