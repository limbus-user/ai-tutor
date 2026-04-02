# AI Tutor Project Blueprint

## 1. Project Purpose

This project is not meant to be a generic chatbot.
It is intended to be a `RAG-based AI tutor` that:

- answers from trusted learning materials
- preserves learner context across sessions
- turns wrong answers into follow-up review

The core product goals remain:

1. `Reduce hallucinations`
   - The system should prefer trusted internal learning content over vague model prior knowledge.

2. `Preserve conversation continuity`
   - The tutor should remember recent dialogue, learner progress, weak concepts, and preferences.

3. `Support wrong-answer review`
   - Wrong answers should be recorded, explained, and scheduled for later review.

In short:

`This project is a personalized AI tutor that combines RAG, learner memory, and a review loop for wrong answers.`

## 2. Current Implementation Status

The project is no longer just an auth skeleton.
It now has a working backend MVP for the main tutor flow.

Implemented:

- Spring Boot backend
- PostgreSQL persistence
- JWT signup/login flow
- user APIs
- concept APIs
- problem creation and submission
- wrong-answer note creation
- review queue creation and completion
- chat sessions and chat messages
- learner memory persistence
- PDF upload and text extraction
- chunk persistence for RAG
- RAG query API
- document-based question generation API
- tutor ask flow with RAG grounding
- fallback grounded answers when OpenAI is unavailable
- core integration tests with H2

Still missing or incomplete:

- persistent vector storage (`pgvector`)
- document-level retrieval filtering
- high-quality reranking / hybrid retrieval
- richer tutor answer contract
- robust grading beyond string equality
- analytics dashboards
- full validation and exception handling coverage
- broader automated test coverage

This means the project is currently in an `MVP backend` stage rather than a pure skeleton stage.

## 3. Product Definition

The target product behavior is:

1. A student asks a question or solves a problem.
2. The system retrieves relevant evidence from trusted learning materials.
3. The tutor answers from that evidence whenever possible.
4. The system remembers the interaction in chat and learner memory.
5. If the student gets something wrong, the system records it and adds review follow-up.

The product only makes sense when these three layers work together:

- `Grounded answering`
- `Persistent learning context`
- `Review-driven tutoring`

## 4. Current Architecture

Current practical architecture:

`Client -> Spring Boot API -> Domain Services -> RAG Retrieval -> Optional LLM -> PostgreSQL/Files -> Review Pipeline`

Main modules currently present:

- `Auth/User`
  - signup, login, JWT, user identity

- `Content`
  - concepts, problems, uploaded documents, chunks

- `RAG`
  - PDF ingestion, chunking, keyword/embedding retrieval, question generation

- `Chat`
  - chat sessions, chat messages, recent conversation persistence

- `Learning Memory`
  - weak concepts, learning history summary, tutoring preferences

- `Tutoring`
  - grounded tutor answer flow, fallback answer generation

- `Review Pipeline`
  - wrong-answer tracking, review queue scheduling

- `Legacy Session Scaffolding`
  - older Redis-based learning session code still exists but is not the primary direction

## 5. Why RAG Is Mandatory

The product goal is to reduce hallucinations.
That requires retrieval to be structurally central, not optional.

Required answer flow:

1. Receive a learner question.
2. Retrieve relevant chunks from trusted educational content.
3. Pass evidence, recent context, and learner memory into answer generation.
4. Produce an answer grounded in that evidence.
5. Return answer plus source references whenever possible.

Design rule:

`Retrieved evidence has priority over model prior knowledge.`

If evidence is weak, the system should:

- ask for clarification
- state uncertainty clearly
- avoid unsupported claims

## 6. Conversation Continuity Design

The project uses two memory layers.

### Short-term memory

Used for active conversation:

- recent chat messages
- active tutoring exchange
- latest stored assistant responses

### Long-term learning memory

Used across sessions:

- weak concept summary
- history summary
- tutoring preferences

Each tutoring request should combine:

- recent conversation
- long-term learner memory
- retrieved RAG evidence

Do not inject unbounded chat history into every request.

## 7. Wrong-Answer Review Pipeline

This flow is already partially implemented.

Current implemented behavior:

1. Student submits an answer.
2. System checks correctness.
3. Attempt is stored.
4. If incorrect, a `WrongAnswerNote` is created.
5. A `ReviewQueue` item is scheduled.
6. Review completion can mark the queue item and note as reviewed.

Target future behavior:

- concept-based remediation
- generated grounded explanations
- targeted follow-up questions
- spaced repetition refinement

The system should treat wrong answers as a persistent learning signal.

## 8. Domain Model Status

### Already present

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

### Still needed later

- richer analytics entities if dashboarding is added
- persistent vector representation / vector index integration
- optional source confidence model

## 9. API Status

### Auth

- `POST /api/auth/signup`
- `POST /api/auth/login`

### User

- `POST /api/users`
- `GET /api/users`

### Chat

- `POST /api/chat/sessions`
- `GET /api/chat/sessions`
- `GET /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/close`

### Learning Memory

- `GET /api/memory/{userId}`
- `PUT /api/memory/{userId}`

### Problems

- `POST /api/problems`
- `GET /api/problems/{problemId}`
- `POST /api/problems/{problemId}/submit`

### Review

- `GET /api/reviews/wrong-answers/{userId}`
- `GET /api/reviews/queue/{userId}`
- `POST /api/reviews/{reviewId}/complete`

### RAG

- `POST /api/rag/upload`
- `POST /api/rag/query`
- `POST /api/rag/generate-questions?fileName=...`

### Tutor

- `POST /api/tutor/sessions/{sessionId}/ask`

### Legacy / non-primary APIs still present

- `/api/session/*`
- older wrong-answer / weakness-analysis endpoints

## 10. Technology Direction

Current stack:

- Java 17
- Spring Boot
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT
- Redis
- LangChain4j
- PDFBox

Current test stack:

- Spring Boot Test
- H2
- MockMvc

Recommended next additions:

- `pgvector`
- persistent embedding storage
- reranking / hybrid retrieval strategy

## 11. Implementation Progress by Phase

### Phase 1: backend foundation

Status: `done`

- auth/user base exists
- signup/login works
- build and test run

### Phase 2: learning domain

Status: `done`

- `Concept`
- `Problem`
- `UserProblemAttempt`
- problem submission and grading

### Phase 3: wrong-answer pipeline

Status: `partially done`

- `WrongAnswerNote`
- `ReviewQueue`
- review completion

Still needed:

- smarter concept diagnosis
- stronger review scheduling strategy

### Phase 4: chat continuity

Status: `done`

- `ChatSession`
- `ChatMessage`
- `LearningMemory`

### Phase 5: RAG

Status: `partially done`

- PDF ingestion
- chunking
- keyword retrieval
- optional embedding retrieval
- grounded query API

Still needed:

- persistent vectors
- filtering by document / subject / unit
- retrieval quality improvements

### Phase 6: tutoring intelligence

Status: `partially done`

- grounded tutor ask flow exists
- fallback answer generation exists
- sources are stored with tutor messages

Still needed:

- structured answer format
- stronger citation policy
- safer evidence sufficiency handling

### Phase 7: analytics and refinement

Status: `not started`

## 12. Verification Status

Verified manually:

- signup
- concept creation
- problem creation
- correct answer submission
- wrong answer submission
- wrong-answer note creation
- review queue creation
- chat session creation
- learning memory update
- PDF upload
- `rag/query`
- `rag/generate-questions`
- `tutor/ask`
- chat message persistence

Verified automatically:

- H2-based integration tests added
- `./gradlew.bat test` passes
- current integration suite covers:
  - auth signup/login
  - wrong-answer review creation
  - PDF upload -> RAG query -> tutor ask -> chat persistence

Important verification note:

- in this environment, PowerShell `Invoke-RestMethod` produced unreliable Korean JSON payload encoding
- `curl.exe` with UTF-8 JSON files worked correctly

## 13. Known Gaps

1. Retrieval searches across the full uploaded document pool.
   - responses may cite older uploaded PDFs alongside the newest one
   - document-specific filtering is still needed

2. Embeddings are stored in memory only.
   - no persistent vector DB

3. Grading is simplistic.
   - direct normalized string equality only

4. Legacy Redis-backed modules still exist.
   - they are not the main architecture direction

5. Test coverage is still limited.
   - no review-complete test
   - no `rag/generate-questions` test
   - no broad negative-path coverage

## 14. Recommended Next Work

1. Introduce persistent vector storage with `pgvector`
2. Add retrieval filters by uploaded document / subject / unit
3. Improve tutor answer structure and evidence policy
4. Upgrade grading logic beyond exact string equality
5. Expand automated test coverage
6. Add validation and exception handling cleanup

## 15. Final Summary

This repository has progressed beyond the initial auth foundation and now contains a functioning backend MVP for:

`document upload + RAG retrieval + tutor answering + chat persistence + learner memory + wrong-answer review`

The next stage is not basic scaffolding anymore.
The next stage is `retrieval quality, persistence quality, and tutoring quality`.
