# 교수님 데모 / 테스트 가이드

## 목적

이 문서는 현재 구현된 `AI Tutor`를 교수님 데모와 로컬 테스트용으로 바로 실행할 수 있게 정리한 가이드입니다.

현재 확인 가능한 핵심 기능:

- PDF 업로드
- 문서 기반 문제 생성
- 문서 기반 질문응답
- 채팅 세션 생성
- 튜터 질문/답변
- 학습 메모 반영
- 프론트엔드 데모 페이지 연동

## 권장 모델

- 권장 로컬 모델: `qwen2.5:3b`
- 이유: 16GB RAM 환경에서 `qwen2.5:7b` 보다 안정적으로 동작할 가능성이 높음
- 참고: `qwen2.5:7b` 는 가능하지만 문제 생성 시 느리거나 멈출 수 있음

## 기본 주소

- 백엔드/프론트엔드 기본 주소: `http://localhost:8080`
- 프론트엔드 데모 페이지: `http://localhost:8080/index.html`

## 완전 처음부터 다시 시작하기

### 핵심 원칙

- `userId`, `documentId`, `sessionId` 는 매번 증가하는 것이 정상입니다.
- 숫자가 커졌다고 오류가 아닙니다.
- 가장 안전한 방법은 항상 `바로 앞 응답에서 받은 값`을 다음 요청에 넣는 것입니다.

즉, 완전 초기화를 하지 않아도 테스트는 가능합니다.

### 정말 처음부터 새로 시작하고 싶을 때

1. 서버 종료

```powershell
Ctrl + C
```

2. 테스트용 json 파일 정리

```powershell
Remove-Item .\user.json -ErrorAction SilentlyContinue
Remove-Item .\session.json -ErrorAction SilentlyContinue
Remove-Item .\ask.json -ErrorAction SilentlyContinue
```

3. 업로드 폴더 테스트 파일 정리

주의:

- 이 작업은 기존 업로드 테스트 파일을 지웁니다.
- 제출본을 보존해야 하면 삭제하지 말고 그대로 두고 새 파일만 다시 업로드해도 됩니다.

```powershell
Get-ChildItem .\uploads
```

필요할 때만 직접 삭제:

```powershell
Remove-Item .\uploads\*_testpdf.pdf -ErrorAction SilentlyContinue
```

4. DB는 선택적으로 초기화

가장 안전한 방법은 DB를 굳이 비우지 않고, 새 `userId`, 새 `documentId`, 새 `sessionId` 로 진행하는 것입니다.

## 1. 서버 실행

프로젝트 루트에서 실행:

```powershell
$env:OLLAMA_ENABLED = "true"
$env:OLLAMA_CHAT_MODEL = "qwen2.5:3b"
.\gradlew.bat bootRun
```

정상 기동 기준:

```text
Started AiTutorApplication
Tomcat started on port 8080
```

주의:

- `bootRun` 은 서버가 계속 떠 있는 상태가 정상입니다.
- 종료는 `Ctrl + C`

## 2. 프론트엔드로 빠르게 확인하는 방법

브라우저에서 아래 주소를 엽니다.

```text
http://localhost:8080/index.html
```

화면에서 바로 가능한 흐름:

1. `API 연결 확인`
2. `사용자 생성`
3. `학습 메모 저장`
4. `PDF 업로드`
5. `문제 생성`
6. `생성된 문제 풀이`
7. `세션 생성`
8. `튜터 질문`
9. `채팅 메시지 조회`

프론트엔드에서 자동으로 이어지는 값:

- 사용자 생성 후 `userId`
- PDF 업로드 후 내부 `documentId`
- 세션 생성 후 `sessionId`

프론트엔드 확인 포인트:

- 결과 카드에 각 API 응답이 누적되는지
- PDF 업로드 후 `현재 선택 문서`가 표시되는지
- 문제 생성 결과에 `type`, `correctAnswer`, `explanation` 이 있는지
- 생성된 문제 카드에서 직접 답을 선택하거나 입력할 수 있는지
- 제출 후 정답/오답, 정답, 모범답안, 해설이 표시되는지
- 튜터 답변에 `sources` 가 비어 있지 않은지
- `채팅 메시지 조회`에서 USER/ASSISTANT 메시지가 저장되어 있는지

## 3. PowerShell로 직접 API 테스트하는 방법

서버를 띄운 창은 그대로 두고, 새 PowerShell 창에서 아래 명령을 실행합니다.

```powershell
$base = "http://localhost:8080"
```

## 권장 테스트 순서

1. 사용자 생성
2. PDF 업로드
3. 문서 기반 문제 생성
4. 채팅 세션 생성
5. 튜터 질문
6. 채팅 메시지 확인

## 3-1. 사용자 생성

```powershell
@'
{"email":"demo@example.com","password":"secret123","name":"Demo User"}
'@ | Set-Content -Path .\user.json -Encoding utf8
```

```powershell
curl.exe -X POST "$base/api/users" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@user.json"
```

예시 응답:

```json
{"id":1,"email":"demo@example.com","name":"Demo User"}
```

## 3-2. PDF 업로드

예시 PDF 경로:

```text
C:\Users\USER\Desktop\ai-tutor\uploads\testpdf.pdf
```

```powershell
curl.exe -X POST "$base/api/rag/upload" `
  -F "file=@C:\Users\USER\Desktop\ai-tutor\uploads\testpdf.pdf" `
  -F "subject=컴퓨터과학" `
  -F "unitName=알고리즘" `
  -F "trustLevel=높음"
```

예시 응답:

```json
{"chunkCount":2,"documentId":13,"title":"testpdf.pdf","storedFileName":"1775540174911_testpdf.pdf"}
```

## 3-3. 문서 기반 문제 생성

문제 생성은 `type` 과 `count` 를 받을 수 있습니다.

지원 `type`:

- `mixed`
- `multiple_choice`
- `ox`
- `short_answer`

혼합형:

```powershell
curl.exe -X POST "$base/api/rag/generate-questions?documentId=13&type=mixed&count=5"
```

객관식:

```powershell
curl.exe -X POST "$base/api/rag/generate-questions?documentId=13&type=multiple_choice&count=3"
```

OX:

```powershell
curl.exe -X POST "$base/api/rag/generate-questions?documentId=13&type=ox&count=3"
```

주관식:

```powershell
curl.exe -X POST "$base/api/rag/generate-questions?documentId=13&type=short_answer&count=3"
```

예시 응답 구조:

```json
{
  "documentId": 13,
  "title": "testpdf.pdf",
  "storedFileName": "1775540174911_testpdf.pdf",
  "questions": [
    {
      "order": 1,
      "type": "multiple_choice",
      "question": "...",
      "choices": ["...", "...", "...", "..."],
      "correctAnswer": "...",
      "modelAnswer": "...",
      "explanation": "...",
      "sourceEvidence": "...",
      "difficulty": "medium"
    }
  ]
}
```

확인 포인트:

- `questions` 배열이 내려오는지
- 각 항목에 `type`, `correctAnswer`, `explanation` 이 있는지
- 객관식이면 `choices` 4개가 있는지
- OX면 `choices` 가 `["O","X"]` 인지

## 3-4. 채팅 세션 생성

```powershell
@'
{"userId":1,"title":"PDF 기반 튜터 데모"}
'@ | Set-Content -Path .\session.json -Encoding utf8
```

```powershell
curl.exe -X POST "$base/api/chat/sessions" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@session.json"
```

예시 응답:

```json
{"id":5,"userId":1,"title":"PDF 기반 튜터 데모","status":"ACTIVE","createdAt":"...","updatedAt":"..."}
```

## 3-5. 튜터 질문

```powershell
@'
{"question":"상속이 뭐야?","documentId":13}
'@ | Set-Content -Path .\ask.json -Encoding utf8
```

```powershell
curl.exe -X POST "$base/api/tutor/sessions/5/ask" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@ask.json"
```

후속 질문 예시:

```powershell
@'
{"question":"그럼 다형성이랑 차이점이 뭐야?","documentId":13}
'@ | Set-Content -Path .\ask.json -Encoding utf8
```

```powershell
curl.exe -X POST "$base/api/tutor/sessions/5/ask" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@ask.json"
```

예시 응답:

```json
{
  "sessionId": 5,
  "question": "상속이 뭐야?",
  "answer": "...",
  "sources": ["testpdf.pdf [chunk 0]"]
}
```

확인 포인트:

- `sources` 가 비어 있지 않은지
- 답변이 업로드한 PDF 내용 기준으로 나오는지
- 후속 질문에서도 직전 문맥을 어느 정도 이어받는지

## 3-6. 채팅 메시지 확인

```powershell
curl.exe "$base/api/chat/sessions/5/messages"
```

예시 응답:

```json
[
  {
    "id": 1,
    "sessionId": 5,
    "role": "USER",
    "content": "상속이 뭐야?"
  },
  {
    "id": 2,
    "sessionId": 5,
    "role": "ASSISTANT",
    "content": "...",
    "sourceReferences": "testpdf.pdf [chunk 0]"
  }
]
```

## 빠른 데모 요약

1. 서버 실행

```powershell
$env:OLLAMA_ENABLED = "true"
$env:OLLAMA_CHAT_MODEL = "qwen2.5:3b"
.\gradlew.bat bootRun
```

2. 브라우저에서 열기

```text
http://localhost:8080/index.html
```

3. 화면에서 순서대로 실행

- API 연결 확인
- 사용자 생성
- 학습 메모 저장
- PDF 업로드
- 문제 생성
- 생성된 문제 풀이
- 세션 생성
- 튜터 질문
- 채팅 메시지 조회

## 교수님께 설명할 때 좋은 문장

짧게 설명:

`사용자가 PDF를 업로드하면 서버가 텍스트를 추출하고, 그 문서를 근거로 문제 생성과 튜터 답변을 수행합니다. 생성된 문제는 브라우저에서 바로 풀어볼 수 있고, 질문과 답변은 채팅 세션 단위로 저장되며 문서 근거도 함께 남습니다.`

조금 더 구체적으로 설명:

`현재 구현은 PDF 업로드, 문서 텍스트 추출, 문서 기반 질의응답, 문서 기반 문제 생성, 채팅 세션 저장, 튜터 응답 생성까지 이어지는 구조입니다. 문제 생성은 mixed, 객관식, OX, 주관식 타입을 지원하고, 프론트엔드에서 업로드한 문서를 자동으로 이어받아 바로 문제를 풀고 튜터 질문까지 진행할 수 있습니다.`

## 자주 생기는 문제

### 1. `Port 8080 was already in use`

기존 서버가 이미 떠 있는 상태입니다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen
Stop-Process -Id <PID>
```

### 2. `curl: (3) URL rejected: No host part in the URL`

`$base` 변수가 설정되지 않은 상태입니다.

```powershell
$base = "http://localhost:8080"
```

### 3. 브라우저에서 프론트 화면이 안 열림

먼저 아래 주소를 정확히 확인합니다.

```text
http://localhost:8080/index.html
```

여전히 안 열리면 서버 로그에 `Adding welcome page: class path resource [static/index.html]` 가 보이는지 확인합니다.

### 4. 404 또는 엉뚱한 세션/문서로 테스트됨

예전 예시 숫자를 그대로 넣은 경우입니다.

해결:

- 사용자 생성 응답에서 `id` 확인
- 업로드 응답에서 `documentId` 확인
- 세션 생성 응답에서 `id` 확인
- 다음 요청에 그 값을 그대로 사용

### 5. `generate-questions` 가 오래 걸림

로컬 LLM이 문제를 생성하는 중일 수 있습니다.

```powershell
C:\Users\USER\AppData\Local\Programs\Ollama\ollama.exe ps
```

참고:

- `qwen2.5:3b` 는 `7b` 보다 가볍습니다.
- 그래도 로컬 환경에서는 수 초에서 수십 초 걸릴 수 있습니다.

## 종료 순서

1. 테스트용 PowerShell 창 정리

- `curl.exe` 를 실행하던 창은 닫아도 됩니다.
- 필요 없으면 `user.json`, `session.json`, `ask.json` 도 지워도 됩니다.

2. 서버 실행 창으로 돌아가기

```powershell
Ctrl + C
```

3. 포트가 남아 있는지 확인

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen
```

아직 남아 있으면:

```powershell
Stop-Process -Id <PID>
```
