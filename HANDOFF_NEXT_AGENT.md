# AI Tutor Handoff

## Purpose

This repository is evolving from a backend skeleton into a `RAG-based AI tutor` that:

- answers from trusted study materials
- preserves learner context across sessions
- records wrong answers and schedules review

This file is a practical handoff for the next agent so work can continue without re-discovering the current state.

## What Has Been Implemented

### 1. Auth and user foundation

Already present before recent work:

- Spring Boot application
- PostgreSQL integration
- JWT auth flow
- signup/login
- basic user APIs

Key files:

- [AuthController.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/controller/AuthController.java)
- [AuthService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/AuthService.java)
- [UserController.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/controller/UserController.java)

### 2. Learning domain

Added:

- `Concept`
- `Problem`
- `UserProblemAttempt`

Supported APIs:

- `POST /api/concepts`
- `GET /api/concepts`
- `POST /api/problems`
- `GET /api/problems/{problemId}`
- `POST /api/problems/{problemId}/submit`

Current behavior:

- problems can be created and retrieved
- answers are graded by normalized string equality
- attempts are persisted

Key files:

- [Concept.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/Concept.java)
- [Problem.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/Problem.java)
- [UserProblemAttempt.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/UserProblemAttempt.java)
- [ProblemService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/ProblemService.java)

### 3. Wrong-answer review pipeline

Added:

- `WrongAnswerNote`
- `ReviewQueue`

Supported APIs:

- `GET /api/reviews/wrong-answers/{userId}`
- `GET /api/reviews/queue/{userId}`
- `POST /api/reviews/{reviewId}/complete`

Current behavior:

- if a submitted answer is wrong, a `WrongAnswerNote` is created
- a `ReviewQueue` item is created for follow-up review
- completing a review updates queue status and wrong-answer note status

Key files:

- [WrongAnswerNote.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/WrongAnswerNote.java)
- [ReviewQueue.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/ReviewQueue.java)
- [ReviewService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/ReviewService.java)

### 4. Chat continuity and long-term memory

Added:

- `ChatSession`
- `ChatMessage`
- `LearningMemory`

Supported APIs:

- `POST /api/chat/sessions`
- `GET /api/chat/sessions?userId=...`
- `GET /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/close`
- `GET /api/memory/{userId}`
- `PUT /api/memory/{userId}`

Current behavior:

- users can have persistent chat sessions
- messages are stored in the database
- learning memory stores weak concept summary, history summary, and preferences

Key files:

- [ChatSession.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/ChatSession.java)
- [ChatMessage.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/ChatMessage.java)
- [LearningMemory.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/LearningMemory.java)
- [ChatService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/ChatService.java)
- [LearningMemoryService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/LearningMemoryService.java)

### 5. RAG ingestion and retrieval

Added:

- `RagDocument`
- `DocumentChunk`

Supported APIs:

- `POST /api/rag/upload`
- `POST /api/rag/query`
- `POST /api/rag/generate-questions?fileName=...`

Current behavior:

- PDF upload stores file on disk
- extracted text is stored in `RagDocument`
- chunks are stored in `DocumentChunk`
- retrieval first tries embedding search if `OPENAI_API_KEY` is present
- if embeddings are unavailable, it falls back to keyword-based matching

Important implementation detail:

- embeddings are stored in `InMemoryEmbeddingStore`, not a persistent vector DB
- chunk metadata is persisted, but vector data is not
- after restart, the service rebuilds the in-memory store from persisted chunks when needed

Key files:

- [RagDocument.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/RagDocument.java)
- [DocumentChunk.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/domain/DocumentChunk.java)
- [RagService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/RagService.java)
- [RagController.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/controller/RagController.java)

### 6. Grounded tutor flow

Added:

- `POST /api/tutor/sessions/{sessionId}/ask`

Current behavior:

- user question is saved as a chat message
- RAG retrieval is executed
- recent conversation and learning memory are included in the answer flow
- if `OPENAI_API_KEY` exists, an OpenAI chat model is used for grounded answer generation
- if the LLM call fails or no key exists, a fallback evidence-based answer is returned
- assistant response is also saved as a chat message

Key files:

- [TutorController.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/controller/TutorController.java)
- [TutorService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/TutorService.java)

### 7. Verification and automated tests

Recent verification work confirmed that the main backend flow is wired end-to-end:

- signup works
- concepts and problems can be created
- wrong submissions create `WrongAnswerNote` and `ReviewQueue`
- chat sessions and messages persist
- learning memory persists
- PDF upload works
- `rag/query` returns grounded results
- tutor answers are stored back into chat history

Important implementation note from manual verification:

- Korean payloads sent through PowerShell `Invoke-RestMethod` were unreliable in this environment
- using `curl.exe` with UTF-8 JSON files worked correctly
- the server-side persistence itself was fine; the issue was request encoding on the client side

Automated backend tests were also added:

- H2-based Spring Boot test profile
- integration tests for auth
- integration tests for wrong-answer review creation
- integration tests for PDF upload -> RAG query -> tutor ask -> chat message persistence

Key files:

- [AiTutorIntegrationTest.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/test/java/com/gyeongtaekim/ai_tutor/AiTutorIntegrationTest.java)
- [application-test.properties](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/test/resources/application-test.properties)
- [build.gradle](/C:/Users/AI2-28/IdeaProjects/ai-tutor/build.gradle)

## Runtime Requirements

### Minimum local requirements

- PostgreSQL running
- database `ai_tutor` exists

Current datasource:

- URL: `jdbc:postgresql://localhost:5432/ai_tutor`
- username: `postgres`
- password: `postgres`

### Optional but important

- Redis running on `localhost:6379`
  used by legacy `LearningSessionService`, `WrongAnswerService`, and `WeaknessAnalysisService`

### OpenAI integration

To enable LLM and embedding retrieval:

```bat
set OPENAI_API_KEY=your_api_key
set OPENAI_CHAT_MODEL=gpt-4o-mini
set OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

Relevant properties:

- [application.properties](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/resources/application.properties)

## Important Caveats

### 1. Retrieval is not production-grade yet

Even after recent improvements, retrieval is still transitional:

- embeddings are not persisted
- no `pgvector`
- no reranking
- no hybrid retrieval policy
- no source confidence scoring

### 2. Tutor answer quality is still prompt-based

The tutor flow is now structurally correct, but:

- prompt format is simple
- no dedicated answer schema
- no citation validation
- no hallucination guard beyond prompt instructions and source inclusion

### 3. Problem grading is naive

`ProblemService` currently uses direct normalized string equality. That is acceptable for scaffolding only.

### 4. Legacy session features still exist

There is an older `LearningSessionController`/`LearningSessionService` flow that uses Redis and mock behavior.
It was minimally kept compatible, but it is not the main architecture direction.

Files:

- [LearningSessionController.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/controller/LearningSessionController.java)
- [LearningSessionService.java](/C:/Users/AI2-28/IdeaProjects/ai-tutor/src/main/java/com/gyeongtaekim/ai_tutor/service/LearningSessionService.java)

Treat that module as legacy scaffolding unless explicitly chosen for reuse.

## Recommended Next Work

### Highest priority

1. Replace in-memory embedding store with persistent vector storage
   target: PostgreSQL + `pgvector`

2. Make retrieval more reliable
   add:
   - persistent embeddings
   - top-k tuning
   - reranking
   - chunk metadata filters by subject/unit

3. Improve tutor output contract
   define a structured answer format such as:
   - short answer
   - evidence summary
   - explanation
   - follow-up question
   - sources

### Next after that

4. Connect tutor answers to the review pipeline
   if a learner is weak on a concept, the tutor should:
   - simplify explanation
   - mention prerequisite concepts
   - propose a targeted follow-up problem

5. Improve problem grading
   add support for:
   - multiple choice
   - partial matching
   - rubric-based short answer grading

6. Add analytics
   build weakness summaries from persisted attempts and review completion

### Testing and quality

7. Add more automated tests
   current project now has core integration tests, but coverage is still limited
   missing areas include:
   - review completion
   - `rag/generate-questions`
   - controller validation failures
   - legacy Redis-backed session flows

8. Add input validation and exception handling
   many controllers currently rely on minimal validation

## Suggested Immediate Task For The Next Agent

If continuing immediately, do this next:

1. Introduce persistent vector storage with `pgvector`
2. Move embedding persistence out of `InMemoryEmbeddingStore`
3. Update `RagService.query()` to retrieve from persistent vectors
4. Keep keyword retrieval as a fallback only
5. Add document-level filtering so retrieval can be constrained to a specific uploaded file or subject/unit
6. Then test the full flow:
   `rag upload -> tutor ask -> grounded answer -> source verification`

## Verification Status

Recent code changes were verified with:

```bat
./gradlew.bat compileJava
./gradlew.bat build
./gradlew.bat test
```

Verification status:

- compile passed
- build passed
- integration tests passed (`AiTutorIntegrationTest`: 3 tests, 0 failures)
- manual API checks also covered signup, problem submission, review queue creation, PDF upload, RAG query, tutor ask, and chat persistence

Known behavior observed during verification:

- retrieval currently searches across the full uploaded document pool
- because of that, tutor responses may cite earlier uploaded PDFs alongside the most recent one
- this is expected with the current implementation and should be fixed by adding retrieval filters
