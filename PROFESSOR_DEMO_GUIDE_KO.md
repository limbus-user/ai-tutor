# 교수님 시연 가이드

## 목적

이 문서는 현재 구현된 `AI Tutor` 백엔드 기능을 교수님 앞에서 바로 시연할 수 있도록 정리한 실행 가이드다.

현재 시연 가능한 핵심 기능:

- PDF 업로드
- 문서 기반 질의 응답 (`RAG`)
- 문서 기반 질문 생성
- 채팅 세션 생성
- 튜터 질문/응답 저장
- 학습 메모 반영

## 시연 전 준비

### 1. 서버 실행

프로젝트 루트에서 실행:

```powershell
.\gradlew.bat bootRun
```

정상 기동 기준:

```text
Started AiTutorApplication
```

주의:

- `bootRun`은 서버를 계속 실행 상태로 유지하는 태스크다.
- 멈춘 것이 아니라 정상 대기 상태다.
- 종료는 `Ctrl + C`

### 2. 테스트 터미널 별도 열기

서버를 실행한 터미널은 그대로 두고, 새 PowerShell 창에서 아래 명령을 실행한다.

기본 주소:

```powershell
$base = "http://localhost:8080"
```

## 시연 시나리오

권장 흐름:

1. PDF 업로드
2. PDF 기반 질문 생성
3. 문서 기반 질의 응답
4. 채팅 세션 생성
5. 튜터 질문
6. 채팅 저장 결과 확인

## 1. PDF 업로드

예시 PDF 경로:

```text
C:\Users\AI2-28\Desktop\gyeong tae kim 202104002\testpdf.pdf
```

실행:

```powershell
curl.exe -X POST "$base/api/rag/upload" `
  -F "file=@C:\Users\AI2-28\Desktop\gyeong tae kim 202104002\testpdf.pdf" `
  -F "subject=computer-science" `
  -F "unitName=algorithm" `
  -F "trustLevel=high"
```

기대 결과 예시:

```json
{"chunkCount":2,"documentId":2,"title":"testpdf.pdf","storedFileName":"1775112343375_testpdf.pdf"}
```

설명 포인트:

- PDF를 업로드하면 텍스트를 추출한다.
- 문서를 chunk 단위로 분리해 저장한다.
- 이후 질문 시 이 저장된 chunk를 근거로 검색한다.

## 2. PDF 기반 질문 생성

업로드 응답의 `storedFileName` 값을 사용한다.

실행:

```powershell
curl.exe -X POST "$base/api/rag/generate-questions?fileName=1775112343375_testpdf.pdf"
```

기대 결과:

- 문서 미리보기
- 문서 내용을 바탕으로 생성한 질문 목록

설명 포인트:

- 업로드한 문서를 바탕으로 학습 질문을 자동 생성할 수 있다.
- 현재는 초안 수준의 질문 생성이며, 향후 품질 고도화가 가능하다.

## 3. 문서 기반 질의 응답

실행:

```powershell
curl.exe -X POST "$base/api/rag/query" `
  -H "Content-Type: text/plain; charset=utf-8" `
  --data-binary "업로드한 PDF의 핵심 내용을 요약해줘"
```

기대 결과:

- 문서 근거 기반 답변
- `sources` 목록

설명 포인트:

- 질의가 들어오면 관련 chunk를 검색한다.
- 검색된 문서 근거를 기반으로 응답을 구성한다.

## 4. 채팅 세션 생성

아래는 `userId = 1` 기준 예시다.

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

기대 결과 예시:

```json
{"id":2,"userId":1,"title":"PDF 기반 튜터 데모","status":"ACTIVE",...}
```

설명 포인트:

- 학습 대화는 세션 단위로 저장된다.
- 이후 질문과 응답은 이 세션에 누적된다.

## 5. 튜터 질문

아래 예시는 `sessionId = 2` 기준이다.

```powershell
@'
{"question":"업로드한 PDF를 바탕으로 핵심 개념을 쉽게 설명해줘"}
'@ | Set-Content -Path .\ask.json -Encoding utf8
```

```powershell
curl.exe -X POST "$base/api/tutor/sessions/2/ask" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@ask.json"
```

기대 결과:

- 질문 저장
- 문서 검색 실행
- 학습 메모 반영
- 답변 생성
- 응답 내 `sources` 포함

설명 포인트:

- 튜터는 최근 대화와 학습 메모를 함께 반영한다.
- OpenAI API Key가 없더라도 fallback 기반 답변이 동작한다.

## 6. 채팅 저장 결과 확인

`sessionId = 2` 예시:

```powershell
curl.exe "$base/api/chat/sessions/2/messages"
```

기대 결과:

- USER 메시지 저장
- ASSISTANT 메시지 저장
- assistant 메시지에 source reference 포함

설명 포인트:

- 질문/응답이 단발성이 아니라 누적 저장된다.
- 이후 세션 기반 학습 기억 구조로 확장할 수 있다.

## 교수님께 설명할 핵심 문장

짧게 설명하면:

`현재 구현은 PDF를 업로드해 문서를 저장하고, 그 문서 기반으로 질문 생성과 질의응답을 수행하며, 튜터 질문과 답변을 채팅 세션에 저장하는 단계까지 구현되어 있습니다.`

조금 더 구체적으로 설명하면:

`사용자가 학습 자료 PDF를 올리면 텍스트를 추출하고 chunk로 분할 저장합니다. 이후 질문이 들어오면 관련 문서 근거를 검색하고, 그 근거를 바탕으로 답변을 생성합니다. 질문 생성 기능도 있으며, 채팅 세션과 학습 메모를 함께 반영하는 구조로 확장해 두었습니다.`

## 현재 한계

교수님께 설명할 때 아래는 정직하게 말하는 편이 낫다.

- 현재는 업로드된 전체 문서 풀에서 검색한다.
- 즉, 방금 업로드한 PDF만 강제로 제한하는 문서 필터는 아직 없다.
- 벡터 저장은 영속 벡터 DB가 아니라 메모리 기반이다.
- 자동 문제 생성은 아직 `/api/problems` 직접 생성과 `rag/generate-questions` 수준으로 분리되어 있다.
- 채점 로직은 문자열 동일성 기반이라 단순하다.

권장 표현:

`현재는 MVP 단계라 문서 업로드, 문서 기반 검색, 질문 생성, 튜터 응답 저장까지 구현했고, 다음 단계는 문서별 필터링과 검색 고도화입니다.`

## 시연 중 자주 생기는 문제

### 1. `Port 8080 already in use`

이미 서버가 떠 있는 상태다.

해결:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen
Stop-Process -Id <PID>
```

### 2. PowerShell에서 한글 JSON 전송 시 깨짐

`Invoke-RestMethod` 대신 `curl.exe + json 파일` 사용.

### 3. 업로드는 됐는데 다른 PDF source도 같이 나옴

현재 구조상 전체 문서에서 검색하기 때문이다.

설명:

`현재는 전체 업로드 문서 집합을 대상으로 검색하고 있습니다. 다음 단계에서 문서별 필터링을 추가할 예정입니다.`

## 시연 후 정리 멘트

`현재 구현은 AI 튜터 백엔드의 기본 골격을 넘어서, 문서 업로드, RAG 검색, 질문 생성, 튜터 응답, 대화 저장까지 연결된 상태입니다. 남은 과제는 검색 정확도와 문서별 필터링, 문제 생성/채점 고도화입니다.`
