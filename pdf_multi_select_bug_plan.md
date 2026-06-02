# 졸업작품 현재 이슈 정리 및 최소 수정 계획

## 1. 현재 개발 목표

현재 작업 중인 기능은 **업로드한 PDF 파일 여러 개를 선택하여 문제 생성하기**이다.

기존에는 PDF 1개 기준으로 채팅, 퀴즈 생성, RAG 검색이 동작했지만, 여러 PDF 선택 기능을 추가하면서 상태 충돌이 발생하고 있다.

---

## 2. 현재 발생한 주요 문제

### 문제 1. 제거했던 개발자 모드 UI가 다시 나타남

여러 PDF 선택 기능 수정 중 예전 `index.html` 또는 `app.js` 코드가 다시 살아난 것으로 보인다.

확인할 것:

- 개발자 모드 관련 HTML 요소가 다시 추가되었는지
- 개발자 모드 관련 JS 함수가 다시 호출되는지
- CSS로 숨긴 게 아니라 실제로 제거해야 하는지

---

### 문제 2. PDF 선택 방식이 중복됨

현재 PDF 선택 상태가 두 가지로 나뉘어 있다.

1. 체크박스 선택
2. 카드 클릭 시 테두리 선택

이 때문에 실제 코드에서 아래와 같은 상태가 섞였을 가능성이 있다.

```text
checkedDocumentIds
selectedDocumentIds
selectedDocumentId
activeDocumentId
currentDocumentId
```

이 상태들이 서로 다르게 동작하면 채팅, 퀴즈 생성, 퀴즈 목록 표시 기준이 꼬인다.

---

### 문제 3. 퀴즈 목록이 선택한 PDF에 따라 따로 보임

여러 PDF를 선택하여 퀴즈를 생성한 뒤, 한 개 PDF만 선택하면 기존에 만든 퀴즈 목록이 다르게 보인다.

현재 문제의 핵심은 다음과 같다.

```text
PDF 선택 상태가 기존 퀴즈 목록 조회 필터처럼 동작하고 있음
```

하지만 PDF 선택 상태는 기존 퀴즈 목록을 바꾸면 안 된다.

올바른 기준:

```text
PDF 선택 상태 = 새 퀴즈를 만들 때 사용할 입력값
QuizSet = 생성된 퀴즈 묶음
```

기존 퀴즈 목록은 현재 선택된 PDF와 무관하게 전체 QuizSet 목록을 보여주는 것이 안정적이다.

---

### 문제 4. 채팅 세션도 PDF 선택에 따라 달라짐

예시:

```text
자료구조.pdf 선택 상태
→ 자료구조 질문 답변 가능

데이터베이스.pdf 선택 상태
→ 자료구조 질문 답변 불가
```

이 동작은 RAG 검색 범위가 현재 선택된 PDF 하나로 제한되어 있기 때문에 발생할 수 있다.

여러 PDF 기반 채팅을 원한다면 채팅 세션은 단일 PDF가 아니라 여러 PDF를 참조해야 한다.

---

## 3. 핵심 원인

현재 문제는 대부분 **PDF 선택 상태를 앱 전체 기준으로 사용해서 생긴 상태 충돌**이다.

잘못된 구조:

```text
PDF 선택 상태 = 채팅 기준
PDF 선택 상태 = 퀴즈 목록 필터
PDF 선택 상태 = 퀴즈 생성 입력
PDF 선택 상태 = 현재 앱 전체 기준
```

올바른 구조:

```text
Document = 업로드된 PDF 파일
selectedDocumentIds = 현재 화면에서 사용자가 선택한 PDF 목록
ChatSession = 채팅방
QuizSet = 한 번 생성된 퀴즈 묶음
Question = QuizSet 안의 개별 문제
```

---

## 4. 앞으로의 구조 설계 원칙

### 원칙 1. PDF 선택 상태는 하나만 둔다

프론트엔드에서는 PDF 선택 상태를 아래 하나로 통일한다.

```js
selectedDocumentIds = []
```

체크박스 상태와 카드 테두리 상태를 따로 관리하지 않는다.

선택 표시도 반드시 이 기준만 사용한다.

```js
selectedDocumentIds.includes(documentId)
```

---

### 원칙 2. PDF 체크 또는 카드 선택은 기존 데이터를 필터링하지 않는다

PDF 선택은 새 작업을 만들 때만 사용한다.

사용 가능한 작업:

- 새 퀴즈 생성
- 새 채팅 세션 생성
- 현재 질문에서 RAG 검색 범위 지정

하면 안 되는 작업:

- 기존 퀴즈 목록 숨기기
- 기존 채팅 목록 바꾸기
- 앱 전체 상태 바꾸기

---

### 원칙 3. QuizSet은 생성 당시 사용한 PDF 목록을 저장한다

추천 구조:

```text
quiz_set
- id
- title
- type
- question_count
- created_at

quiz_set_document
- quiz_set_id
- document_id

question
- id
- quiz_set_id
- type
- question_text
- answer
- explanation
```

이렇게 하면 여러 PDF를 기반으로 만든 퀴즈가 하나의 세트로 유지된다.

---

### 원칙 4. ChatSession도 여러 PDF를 참조할 수 있게 한다

추천 구조:

```text
chat_session
- id
- title
- created_at

chat_session_document
- chat_session_id
- document_id
```

이렇게 하면 하나의 채팅방에서 데이터베이스 PDF와 자료구조 PDF를 함께 검색할 수 있다.

---

## 5. 지금 당장 살짝 할 수 있는 최소 작업

시간이 부족하면 DB 구조까지 크게 바꾸지 말고, 먼저 프론트 상태 충돌부터 줄인다.

### 1단계. PDF 선택 상태 하나로 통일

`app.js`에서 PDF 선택 관련 변수를 검색한다.

검색 키워드:

```text
selectedDocument
selectedDocumentId
selectedDocumentIds
activeDocument
activeDocumentId
currentDocument
checked
checkbox
```

할 일:

- 여러 선택 상태가 있으면 `selectedDocumentIds` 하나로 통일
- 카드 클릭 시 `selectedDocumentIds`에 추가/제거
- 체크박스와 테두리 표시도 `selectedDocumentIds.includes(id)` 기준으로만 처리

---

### 2단계. 퀴즈 목록 조회에서 selectedDocumentIds 필터 제거

현재 선택된 PDF에 따라 기존 퀴즈 목록이 달라지면 안 된다.

확인할 것:

```js
loadQuizzes(selectedDocumentId)
loadQuizSets(selectedDocumentIds)
fetch(`/api/quizzes?documentId=${...}`)
```

임시 수정 방향:

```text
기존 퀴즈 목록은 전체 조회
새 퀴즈 생성 요청에만 selectedDocumentIds 전달
```

---

### 3단계. 개발자 모드 UI 재등장 제거

검색 키워드:

```text
developer
dev
debug
개발자
```

할 일:

- `index.html`에서 개발자 모드 UI 제거 또는 숨김
- `app.js`에서 개발자 모드 렌더링 함수 호출 제거
- CSS만으로 숨기기보다 JS에서 다시 생성되는지 확인

---

### 4단계. 채팅은 당장 완전 개편하지 말고 기준만 명확히 한다

당장 DB 구조 변경이 부담되면 우선 이렇게 정한다.

```text
현재 채팅 질문은 selectedDocumentIds 전체를 RAG 검색 범위로 사용한다.
선택된 PDF가 없으면 모든 문서 또는 최근 선택 문서를 사용하지 말고 사용자에게 PDF 선택을 요구한다.
```

장기적으로는 `chat_session_document` 매핑을 추가한다.

---

## 6. AI에게 보낼 짧은 프롬프트

```text
현재 여러 PDF 선택으로 퀴즈 생성 기능을 수정 중인데 PDF 선택 상태 충돌이 생겼습니다.

버그:
1. 제거했던 개발자 모드 UI가 다시 나타남
2. PDF 선택 방식이 체크박스와 카드 테두리 선택으로 중복됨
3. 여러 PDF로 만든 퀴즈 목록이 현재 선택한 PDF에 따라 따로 보임
4. 채팅도 현재 선택 PDF에 묶여 다른 PDF 내용 질문을 못함

수정 방향:
PDF 선택 상태를 selectedDocumentIds 하나로 통일하고, PDF 선택을 앱 전체 필터가 아니라 새 퀴즈/새 채팅/RAG 검색 입력값으로만 사용해주세요.

요구:
- 체크박스 상태와 카드 테두리 상태를 따로 관리하지 않기
- 선택 표시는 selectedDocumentIds.includes(documentId) 기준으로만 처리
- activeDocumentId/currentDocumentId/selectedDocumentId가 채팅·퀴즈·목록 필터에 섞여 있으면 제거하거나 용도 분리
- 기존 퀴즈 목록은 selectedDocumentIds로 숨기지 말고 전체 QuizSet 표시
- 퀴즈 생성 요청에만 selectedDocumentIds 전체 전달
- 채팅/RAG 검색은 선택된 여러 documentIds 범위에서 검색
- 개발자 모드 UI 재등장 방지
- LLM 호출부, PDF 업로드, chunk 저장은 직접 관련 없으면 수정 금지

결과로 원인, 통합한 상태 변수, 수정 파일, 테스트 방법만 알려주세요.
```

---

## 7. 개발할 때 기억할 기준

```text
Document = 업로드된 PDF
selectedDocumentIds = 지금 선택한 PDF 목록
ChatSession = 대화방
QuizSet = 생성된 퀴즈 묶음
Question = 개별 문제
```

가장 중요한 원칙:

```text
PDF 선택 상태가 기존 채팅/기존 퀴즈 목록을 바꾸면 안 된다.
선택 상태는 새 작업을 만들 때만 사용한다.
```
