# AI Tutor 프로젝트 설계도

## 1. 프로젝트 목적

이 프로젝트는 일반적인 오픈형 챗봇을 만드는 것이 아니다.
목표는 `RAG 기반 AI 튜터`를 만드는 것이다.

이 튜터는 다음 3가지를 동시에 만족해야 한다.

- 신뢰할 수 있는 학습 자료를 근거로 답변하기
- 학생의 학습 맥락과 대화 흐름을 기억하기
- 틀린 문제를 복습으로 연결하기

즉, 이 프로젝트는 다음과 같이 정의할 수 있다.

`문서 기반 근거 답변 + 학습 기억 + 오답 복습 루프를 갖춘 개인화 AI 튜터`

## 2. 현재 구현 상태

현재 프로젝트는 더 이상 인증만 있는 백엔드 뼈대 단계가 아니다.
핵심 튜터 흐름이 동작하는 `MVP 백엔드` 단계까지 올라와 있다.

현재 구현된 것:

- Spring Boot 백엔드
- PostgreSQL 연동
- JWT 회원가입/로그인
- 사용자 API
- 개념 등록/조회
- 문제 생성/조회
- 문제 제출 및 정답/오답 판정
- 오답노트 생성
- 복습 큐 생성 및 완료 처리
- 채팅 세션/메시지 저장
- 학습 메모 저장
- PDF 업로드
- PDF 텍스트 추출 및 chunk 저장
- `rag/query`
- `rag/generate-questions`
- `tutor/ask`
- OpenAI가 없을 때 fallback 기반 응답
- H2 기반 통합 테스트

아직 부족한 것:

- `pgvector` 기반 영속 벡터 저장
- 문서별 검색 필터
- 검색 정확도 고도화
- 튜터 응답 구조 정교화
- 문자열 비교 이상의 채점 로직
- 분석/대시보드
- 넓은 범위의 자동 테스트
- 전반적인 입력 검증과 예외 처리 보강

정리하면 현재 상태는 `백엔드 스켈레톤`이 아니라 `AI 튜터 MVP 백엔드`다.

## 3. 제품 정의

최종 제품은 다음 흐름으로 동작해야 한다.

1. 학생이 질문을 하거나 문제를 푼다.
2. 시스템이 신뢰 가능한 학습 자료에서 관련 근거를 찾는다.
3. AI는 검색된 근거를 바탕으로 답한다.
4. 대화와 학습 상태는 이후 세션을 위해 저장된다.
5. 학생이 틀린 경우 오답이 기록되고 복습으로 이어진다.

이 제품은 아래 3개 층이 함께 동작할 때 의미가 있다.

- `근거 기반 답변`
- `지속되는 학습 기억`
- `복습 중심 튜터링`

## 4. 현재 아키텍처

현재 실질적인 구조는 다음과 같다.

`Client -> Spring Boot API -> Domain Service -> RAG 검색 -> 선택적 LLM -> PostgreSQL/파일 저장 -> 복습 파이프라인`

현재 존재하는 주요 모듈:

- `Auth/User`
  - 회원가입, 로그인, JWT, 사용자 식별

- `Content`
  - 개념, 문제, 업로드 문서, chunk

- `RAG`
  - PDF 업로드, chunk 분할, 키워드/임베딩 검색, 질문 생성

- `Chat`
  - 채팅 세션, 채팅 메시지, 최근 대화 저장

- `Learning Memory`
  - 취약 개념, 학습 이력 요약, 설명 선호

- `Tutoring`
  - 문서 근거 기반 답변, fallback 응답

- `Review Pipeline`
  - 오답 기록, 복습 큐 생성

- `Legacy Session Scaffolding`
  - Redis 기반 옛 세션 코드가 남아 있지만 주 방향은 아님

## 5. 왜 RAG가 필수인가

이 프로젝트의 핵심은 `환각 감소`다.
그러므로 모델 자체를 정답의 1차 출처로 보면 안 된다.

올바른 답변 흐름:

1. 사용자 질문 수신
2. 신뢰 가능한 학습 자료에서 관련 chunk 검색
3. 검색된 근거와 최근 맥락, 학습 메모를 조합
4. 그 근거 범위 안에서 답변 생성
5. 가능하면 source 정보도 함께 반환

핵심 원칙:

`모델의 사전지식보다 검색된 근거가 우선이다.`

근거가 약하면 시스템은 다음 중 하나를 해야 한다.

- 질문을 더 구체화해 달라고 요청
- 불확실하다고 명시
- 근거 없는 단정 회피

## 6. 대화 지속성과 학습 기억

이 프로젝트는 기억을 2층으로 나눠야 한다.

### 단기 기억

현재 대화 흐름을 위한 기억:

- 최근 채팅 메시지
- 현재 질문
- 최근 응답

### 장기 학습 기억

세션을 넘어 유지되는 기억:

- 취약 개념
- 학습 이력 요약
- 설명 선호 방식

각 튜터 요청에는 다음 3개가 함께 들어가야 한다.

- 최근 대화
- 장기 학습 기억
- RAG 검색 근거

전체 채팅 로그를 매번 다 넣는 방식은 피한다.

## 7. 오답 복습 파이프라인

이 파이프라인은 이미 일부 구현되었다.

현재 동작:

1. 학생이 답안을 제출한다.
2. 정답 여부를 판정한다.
3. 시도 기록을 저장한다.
4. 틀리면 `WrongAnswerNote`를 만든다.
5. `ReviewQueue` 항목을 만든다.
6. 복습 완료 시 상태를 갱신할 수 있다.

앞으로 필요한 고도화:

- 개념 기반 오답 진단
- 더 나은 복습 스케줄링
- 후속 문제 추천
- 근거 기반 오답 설명

오답은 단순한 채점 결과가 아니라 `학습 신호`로 다뤄야 한다.

## 8. 도메인 모델 상태

### 현재 존재하는 엔티티

- `User`
- `Concept`
- `Problem`
- `UserProblemAttempt`
- `WrongAnswerNote`
- `ReviewQueue`
- `ChatSession`
- `ChatMessage`
- `LearningMemory`
- `RagDocument`
- `DocumentChunk`

### 이후 필요할 수 있는 것

- 분석용 엔티티
- 영속 벡터 저장 구조
- source confidence 구조

## 9. API 상태

### 인증

- `POST /api/auth/signup`
- `POST /api/auth/login`

### 사용자

- `POST /api/users`
- `GET /api/users`

### 채팅

- `POST /api/chat/sessions`
- `GET /api/chat/sessions`
- `GET /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/close`

### 학습 메모

- `GET /api/memory/{userId}`
- `PUT /api/memory/{userId}`

### 문제

- `POST /api/problems`
- `GET /api/problems/{problemId}`
- `POST /api/problems/{problemId}/submit`

### 복습

- `GET /api/reviews/wrong-answers/{userId}`
- `GET /api/reviews/queue/{userId}`
- `POST /api/reviews/{reviewId}/complete`

### RAG

- `POST /api/rag/upload`
- `POST /api/rag/query`
- `POST /api/rag/generate-questions?fileName=...`

### 튜터

- `POST /api/tutor/sessions/{sessionId}/ask`

### 레거시 / 비주력 API

- `/api/session/*`
- 기존 wrong-answer / weakness-analysis 계열

## 10. 기술 방향

현재 스택:

- Java 17
- Spring Boot
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT
- Redis
- LangChain4j
- PDFBox

현재 테스트 스택:

- Spring Boot Test
- H2
- MockMvc

다음으로 권장되는 기술 방향:

- `pgvector`
- 영속 임베딩 저장
- reranking / hybrid retrieval

## 11. 구현 단계별 진행 상태

### 1단계: 백엔드 기초

상태: `완료`

- 인증/사용자 흐름 존재
- signup/login 동작
- build/test 실행 가능

### 2단계: 학습 도메인

상태: `완료`

- `Concept`
- `Problem`
- `UserProblemAttempt`
- 문제 제출/채점

### 3단계: 오답 파이프라인

상태: `부분 완료`

- `WrongAnswerNote`
- `ReviewQueue`
- 복습 완료 처리

남은 것:

- 더 정교한 개념 진단
- 더 나은 복습 정책

### 4단계: 채팅 지속성

상태: `완료`

- `ChatSession`
- `ChatMessage`
- `LearningMemory`

### 5단계: RAG

상태: `부분 완료`

- 문서 업로드
- chunk 분할
- 키워드 검색
- 선택적 임베딩 검색
- 질문 생성

남은 것:

- 영속 벡터
- 문서/과목/단원 필터
- 검색 품질 고도화

### 6단계: 튜터 지능

상태: `부분 완료`

- 튜터 질문 응답 흐름 존재
- fallback 응답 존재
- source 저장 존재

남은 것:

- 구조화된 답변 포맷
- 더 강한 citation 정책
- 근거 부족 상황 처리 개선

### 7단계: 분석 및 고도화

상태: `미착수`

## 12. 현재까지 검증된 상태

수동 테스트로 확인한 항목:

- 회원가입
- 개념 생성
- 문제 생성
- 정답 제출
- 오답 제출
- 오답노트 생성
- 복습 큐 생성
- 채팅 세션 생성
- 학습 메모 업데이트
- PDF 업로드
- `rag/query`
- `rag/generate-questions`
- `tutor/ask`
- 채팅 메시지 저장

자동 테스트로 확인한 항목:

- H2 기반 통합 테스트 추가
- `.\gradlew.bat test` 통과
- 현재 통합 테스트 범위:
  - 회원가입/로그인
  - 오답 제출 -> 복습 큐 생성
  - PDF 업로드 -> RAG 질의 -> tutor ask -> 채팅 저장

검증 중 확인한 중요한 점:

- 이 환경에서 PowerShell `Invoke-RestMethod`는 한글 JSON 본문 전송이 불안정했다.
- `curl.exe` + UTF-8 JSON 파일 전송은 정상 동작했다.

## 13. 현재 확인된 한계

1. 검색이 전체 업로드 문서 풀 기준이다.
   - 최근 업로드한 PDF만 제한 검색하지 않는다.
   - 그래서 이전 문서 source가 함께 응답에 들어갈 수 있다.

2. 임베딩은 메모리 기반이다.
   - 영속 벡터 DB가 없다.

3. 채점이 단순하다.
   - 문자열 동일성 비교 수준이다.

4. 레거시 Redis 기반 흐름이 남아 있다.
   - 현재 주력 구조는 아니다.

5. 자동 테스트 범위가 아직 좁다.
   - `review complete`
   - `rag/generate-questions`
   - 예외 케이스
   - 레거시 모듈

## 14. 다음 우선 작업

1. `pgvector` 기반 영속 벡터 저장 도입
2. 문서별 / 과목별 / 단원별 검색 필터 추가
3. 튜터 응답 구조 개선
4. 채점 로직 고도화
5. 자동 테스트 확대
6. 입력 검증과 예외 처리 보강

## 15. 최종 요약

현재 저장소는 초기 인증 백엔드 단계를 넘어, 다음 흐름이 실제로 연결된 상태다.

`문서 업로드 + RAG 검색 + 튜터 답변 + 채팅 저장 + 학습 기억 + 오답 복습`

다음 단계의 핵심은 기초 구현이 아니라 `검색 품질`, `영속 벡터`, `문서별 필터링`, `튜터 품질` 개선이다.
