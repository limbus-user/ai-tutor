# AI Tutor Project Blueprint

## 1. Project Purpose

This project is not intended to be a generic chatbot.
It is intended to be a `RAG-based AI tutor` that reduces hallucinations by grounding answers in trusted learning materials and preserves student context across sessions.

The core product goals are:

1. `Reduce hallucinations`
   - Do not let the model answer from vague general knowledge when reliable internal learning data exists.
   - Use trusted documents, problem sets, explanations, and concept summaries as the primary evidence source.

2. `Preserve conversation continuity`
   - Unlike ordinary chatbots that lose context between sessions, this tutor should remember the learner's recent conversation, learning progress, weak concepts, and review history.

3. `Support wrong-answer review`
   - When a student gets a question wrong, the system should not stop at showing the answer.
   - It should identify the related concept, explain the mistake, and schedule targeted review.

In short:

`This project is a personalized AI tutor that uses RAG to answer with grounded evidence, remembers ongoing learning context, and builds an automated review loop for wrong answers.`

## 2. Current Implementation Status

The current codebase is only the backend foundation for authentication and user management.

Implemented so far:

- Spring Boot application setup
- PostgreSQL connection
- JPA user persistence
- JWT-based authentication
- Signup and login APIs
- Protected user APIs

Not implemented yet:

- RAG document ingestion
- Vector search
- LLM integration
- Chat session persistence
- Learning memory
- Problem solving flow
- Wrong-answer pipeline
- Review scheduling
- Analytics or weak-concept tracking
- Frontend
- Automated tests

This means the project is currently in the `backend skeleton` stage, not the full AI tutor stage.

## 3. Product Definition

The final product should behave like this:

1. A student asks a question or solves a problem.
2. The system searches trusted academic content and problem explanations through RAG.
3. The AI answers only from retrieved evidence whenever possible.
4. The conversation is saved so the next interaction continues from previous context.
5. If the student gets a problem wrong, the system records the failure, identifies the weak concept, explains the mistake, and schedules follow-up review.

The product is successful only if all three layers work together:

- `Grounded answering`
- `Persistent learning context`
- `Review-driven tutoring`

## 4. High-Level Architecture

Recommended architecture:

`Client -> Spring Boot API -> Domain Services -> RAG Retrieval -> LLM -> Persistence -> Review Pipeline`

Main modules:

- `Auth/User`
  - signup, login, JWT, user identity

- `Content`
  - concepts, documents, problems, explanations, metadata

- `RAG`
  - chunking, embeddings, vector retrieval, evidence selection

- `Chat`
  - chat sessions, chat messages, recent conversation context

- `Learning Memory`
  - long-term learner profile, weak concepts, tutoring preferences

- `Tutoring`
  - question answering, explanation, hints, step-by-step support

- `Review Pipeline`
  - wrong-answer tracking, concept diagnosis, spaced review queue

- `Analytics`
  - accuracy trends, weak areas, review completion, progress summaries

## 5. Why RAG Is Mandatory

The purpose of this project is to reduce hallucinations compared to ordinary generative AI chatbots.
That means the model must not be treated as the primary source of truth.

The required answer flow is:

1. Receive the user question.
2. Retrieve relevant chunks from trusted educational content.
3. Pass only the necessary evidence and user context to the model.
4. Generate an answer constrained by the retrieved evidence.
5. Return the answer with source information or source IDs.

Design rule:

`Retrieved evidence has priority over model prior knowledge.`

If retrieval confidence is low, the system should prefer:

- asking a clarification question
- stating uncertainty clearly
- refusing unsupported claims

It should not confidently invent facts.

## 6. Conversation Continuity Design

To prevent context loss, conversation memory should be separated into two layers.

### Short-term memory

Used for natural ongoing conversation.

- recent messages in the current chat session
- active problem currently being discussed
- latest hints and responses

### Long-term learning memory

Used for personalization across sessions.

- weak concepts
- recent mistakes
- preferred explanation style
- current study unit
- review backlog

Prompt assembly for each tutoring request should combine:

- recent chat context
- long-term learner memory summary
- retrieved RAG evidence

Do not inject the full chat history on every request.
Use summaries and bounded context windows.

## 7. Wrong-Answer Review Pipeline

This is a core differentiator of the project.

Expected flow:

1. Student submits an answer.
2. System grades it as correct or incorrect.
3. If incorrect, save the attempt.
4. Link the failure to one or more concepts.
5. Generate a grounded explanation using trusted content.
6. Add a follow-up review item.
7. Re-serve similar or prerequisite questions later.
8. Repeat until the concept is stabilized.

This pipeline should support:

- immediate feedback
- concept-based remediation
- repeated practice
- spaced review scheduling

The system should not treat a wrong answer as a one-time event.
It should treat it as a learning signal.

## 8. Recommended Domain Model

Minimum backend entities for the real product:

### Already present

- `User`

### Needed next

- `Concept`
  - subject, unit, concept name, description

- `Document`
  - source title, source type, trust level, subject, unit

- `DocumentChunk`
  - document reference, chunk text, embedding reference, metadata

- `Problem`
  - question text, answer, explanation, difficulty, type, related concepts

- `ChatSession`
  - user, title, status, created time, updated time

- `ChatMessage`
  - session, role, content, source references, created time

- `UserProblemAttempt`
  - user, problem, submitted answer, correctness, feedback, timestamp

- `WrongAnswerNote`
  - user, attempt, concept tags, explanation, review status

- `ReviewQueue`
  - user, concept or problem reference, next review time, priority, status

- `LearningMemory`
  - user, summarized weak concepts, history summary, preferences

## 9. API Direction

Suggested MVP APIs:

### Auth

- `POST /api/auth/signup`
- `POST /api/auth/login`

### Chat

- `POST /api/chat/sessions`
- `GET /api/chat/sessions/{sessionId}`
- `GET /api/chat/sessions/{sessionId}/messages`
- `POST /api/chat/sessions/{sessionId}/messages`

### Problems

- `POST /api/problems`
- `GET /api/problems/{problemId}`
- `POST /api/problems/{problemId}/submit`

### Review

- `GET /api/reviews`
- `POST /api/reviews/{reviewId}/solve`

### RAG

- `POST /api/rag/documents`
- `POST /api/rag/query`

### Analytics

- `GET /api/analytics/weaknesses`

## 10. Recommended Technology Direction

Current backend stack is already aligned with:

- Java 17
- Spring Boot
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT

Recommended additions:

- `pgvector` for vector storage in PostgreSQL
- LLM API integration for grounded tutoring
- document parsing and embedding ingestion pipeline

Recommended practical first version:

`Spring Boot + PostgreSQL + pgvector + external LLM API`

This keeps the architecture simple while supporting RAG in the same database environment.

## 11. Implementation Priority

The project should not jump straight into a full AI platform.
Build it in this order.

### Phase 1: stabilize backend foundation

- keep current auth and user flow
- add validation and error handling
- add tests for signup and login

### Phase 2: learning domain

- add `Concept`
- add `Problem`
- add `UserProblemAttempt`
- add problem submission and grading APIs

### Phase 3: wrong-answer pipeline

- add `WrongAnswerNote`
- add `ReviewQueue`
- connect incorrect attempts to review scheduling

### Phase 4: chat continuity

- add `ChatSession`
- add `ChatMessage`
- add learner memory summary model

### Phase 5: RAG

- document ingestion
- chunking
- embeddings
- vector retrieval
- evidence-based prompt assembly

### Phase 6: tutoring intelligence

- grounded answer generation
- hint generation
- concept-based remediation
- answer source references

### Phase 7: analytics and refinement

- weak concept dashboards
- review completion tracking
- prompt quality tuning
- hallucination reduction policy improvements

## 12. Non-Negotiable Design Rules

Any future agent continuing this project should follow these rules:

1. Do not turn this into a generic open-ended chatbot.
2. Keep RAG grounded in trusted educational content.
3. Preserve chat continuity across sessions.
4. Treat wrong answers as review signals, not just grading results.
5. Prefer explicit source-linked answers over fluent unsupported answers.
6. Keep the architecture modular so auth, tutoring, RAG, and review can evolve separately.

## 13. What the Next Agent Should Do

If an agent reads this file and continues development, the recommended immediate next tasks are:

1. Review the current auth-based backend structure.
2. Add the first real tutoring domain entities:
   - `Concept`
   - `Problem`
   - `UserProblemAttempt`
3. Implement problem submission and correctness evaluation.
4. Add wrong-answer persistence and a simple review queue.
5. After that, begin the first RAG ingestion and retrieval module.

## 14. Final Summary

This repository should evolve into:

`A RAG-based personalized AI tutoring system that minimizes hallucinations, remembers student learning context across sessions, and automates review for wrong answers.`

The current implementation is only the authentication and user-management foundation.
Future work must build the tutoring, memory, RAG, and review layers on top of that base.
