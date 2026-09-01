# AI Tutor 실행/종료 가이드

최종 갱신: 2026-08-31

이 문서는 Windows PowerShell 기준 로컬 실행 가이드이다.

## 관련 문서

- [PROJECT_CONTEXT_KO.md](PROJECT_CONTEXT_KO.md): 프로젝트 목적, 구조, 기능, API 전체 요약
- [UML_DB_DESIGN_KO.md](UML_DB_DESIGN_KO.md): UML 설계도, ERD, 테이블별 스키마 설명

## 필요 프로그램

- Java 17
- Docker Desktop
- 선택 사항: Ollama

Docker Compose로 실행하는 서비스:

- PostgreSQL: `pgvector/pgvector:pg16`
- Redis: `redis:7`
- Qdrant: `qdrant/qdrant:latest`

기본 포트:

- Spring Boot: `8080`
- PostgreSQL: `5432`
- Redis: `6379`
- Qdrant HTTP: `6333`
- Qdrant gRPC: `6334`
- Ollama: `11434`

## 기본 실행

프로젝트 폴더로 이동한다.

```powershell
cd C:\Users\c\Desktop\ai-tutor-github
```

PostgreSQL, Redis, Qdrant를 실행한다.

```powershell
docker compose up -d postgres redis qdrant
```

만약 `Conflict. The container name "/ai-tutor-redis" is already in use` 같은 오류가 나면, 같은 이름의 컨테이너가 이미 다른 폴더의 Docker Compose 프로젝트에서 만들어진 상태이다. 이 프로젝트의 `docker-compose.yml`은 `container_name`을 고정해서 쓰므로, 그 경우에는 새로 만들지 말고 기존 컨테이너를 시작한다.

```powershell
docker start ai-tutor-postgres ai-tutor-redis ai-tutor-qdrant
```

상태를 확인한다.

```powershell
docker ps
Invoke-RestMethod http://localhost:6333/collections
```

Spring Boot 실행 전에 환경 변수를 설정한다.

```powershell
$env:DB_USERNAME="ai_tutor"
$env:DB_PASSWORD="ai_tutor"
$env:OPENAI_API_KEY="sk-..."
$env:OLLAMA_ENABLED="true"
$env:OLLAMA_CHAT_MODEL="qwen2.5:7b"
```

OpenAI API 키는 PDF 청크 임베딩을 생성하고 Qdrant에 벡터를 저장할 때 필요하다. 키가 없으면 PDF 원문은 PostgreSQL에 저장될 수 있지만 Qdrant 벡터 검색은 정상 동작하지 않는다.

Ollama를 쓰지 않을 때만 다음처럼 끈다.

```powershell
$env:OLLAMA_ENABLED="false"
```

필요하면 다음 환경 변수도 조정할 수 있다.

```powershell
$env:OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
$env:QDRANT_URL="http://localhost:6333"
$env:QDRANT_COLLECTION="document_chunks"
$env:QDRANT_VECTOR_SIZE="1536"
$env:OLLAMA_BASE_URL="http://localhost:11434"
```

Spring Boot를 실행한다.

```powershell
.\gradlew.bat bootRun
```

`bootRun`은 웹 서버 실행 명령이므로 터미널이 종료되지 않는 것이 정상이다. `Tomcat started on port 8080` 또는 `Started AiTutorApplication` 로그가 보이면 브라우저에서 접속한다. 이 창을 닫거나 `Ctrl + C`를 누르면 서버도 종료된다.

브라우저에서 접속한다.

```text
http://localhost:8080
```

## 테스트 계정

서버 시작 시 테스트 계정이 자동 생성된다.

```text
email: demo@example.com
password: secret123
role: ADMIN
```

대부분의 API는 JWT 로그인이 필요하다. 브라우저에서 로그인하거나 `/api/auth/login` 응답의 `token`을 `Authorization` 헤더에 넣는다.

```text
Authorization: Bearer <token>
```

## 백그라운드 실행

PowerShell 창을 점유하지 않고 실행하려면 다음 명령을 사용한다.

```powershell
cd C:\Users\c\Desktop\ai-tutor-github
$env:DB_USERNAME="ai_tutor"
$env:DB_PASSWORD="ai_tutor"
$env:OPENAI_API_KEY="sk-..."
$env:OLLAMA_ENABLED="true"
$env:OLLAMA_CHAT_MODEL="qwen2.5:7b"
New-Item -ItemType Directory -Force run-logs | Out-Null
Start-Process -FilePath ".\gradlew.bat" `
  -ArgumentList "bootRun" `
  -WorkingDirectory "C:\Users\c\Desktop\ai-tutor-github" `
  -RedirectStandardOutput "C:\Users\c\Desktop\ai-tutor-github\run-logs\bootRun.out.log" `
  -RedirectStandardError "C:\Users\c\Desktop\ai-tutor-github\run-logs\bootRun.err.log" `
  -WindowStyle Hidden
```

로그 확인:

```powershell
Get-Content run-logs\bootRun.out.log -Tail 80
Get-Content run-logs\bootRun.err.log -Tail 80
```

## Ollama 실행

로컬 LLM 답변을 사용하려면 Ollama를 실행한다.

```powershell
ollama serve
ollama pull qwen2.5:7b
```

Windows에서 Ollama가 설치되어 있으면 백그라운드 서비스로 이미 떠 있을 수 있다. 먼저 아래 명령으로 확인한다.

```powershell
Get-Command ollama
Invoke-RestMethod http://localhost:11434/api/tags
```

Ollama 없이 서버를 실행하려면 Spring Boot 실행 전에 다음 값을 설정한다.

```powershell
$env:OLLAMA_ENABLED="false"
```

## Qdrant 확인

Qdrant 서버 확인:

```powershell
Invoke-RestMethod http://localhost:6333/collections
```

PDF 업로드 후 collection 확인:

```powershell
Invoke-RestMethod http://localhost:6333/collections/document_chunks
```

저장된 point 일부 확인:

```powershell
$body = @{
  limit = 3
  with_payload = $true
  with_vector = $false
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri http://localhost:6333/collections/document_chunks/points/scroll `
  -Method Post `
  -Body $body `
  -ContentType "application/json"
```

정상 payload 예시:

```json
{
  "chunkId": 1379,
  "userId": 2,
  "documentId": 47,
  "sessionId": null,
  "pageNumber": null,
  "chunkIndex": 0
}
```

## PostgreSQL 스키마 확인

현재 프로젝트는 `spring.jpa.hibernate.ddl-auto=update`로 JPA 엔티티 기반 테이블을 자동 갱신한다. 추가로 다음 초기화 클래스가 기존 DB의 누락 컬럼이나 타입을 보정한다.

- `ChatSessionSchemaInitializer`: `chat_session.type`
- `PdfTextSchemaInitializer`: 긴 텍스트 컬럼을 `TEXT` 타입으로 보정
- `SessionQuizSchemaInitializer`: `session_quiz` 추가 컬럼과 기본값 보정
- `RagDocumentOwnerSchemaInitializer`: `rag_document.user_id` 및 소유자 인덱스 보정
- `ExamQuestionBankInitializer`: 기출 CSV를 `exam_question_bank`에 적재

주요 테이블 확인:

```powershell
docker exec -e PGPASSWORD=ai_tutor ai-tutor-postgres `
  psql -U ai_tutor -d ai_tutor `
  -c "\dt"
```

모의고사 문제 적재 확인:

```powershell
docker exec -e PGPASSWORD=ai_tutor ai-tutor-postgres `
  psql -U ai_tutor -d ai_tutor `
  -c "select certification, exam_date, count(*) from exam_question_bank group by certification, exam_date order by exam_date;"
```

사용자별 문서/청크 확인:

```powershell
docker exec -e PGPASSWORD=ai_tutor ai-tutor-postgres `
  psql -U ai_tutor -d ai_tutor `
  -c "select d.id, d.user_id, d.title, count(c.id) as chunks from rag_document d left join document_chunk c on c.document_id = d.id group by d.id order by d.id desc limit 10;"
```

## 오류 제보 확인

브라우저 로그인 화면의 피드백 폼 또는 모의고사 문항의 `오류 제보` 버튼으로 제보를 남길 수 있다.

서버에 저장된 제보 확인:

```powershell
Get-Content run-logs\feedback-submissions.log -Tail 20
```

현재 방식은 이메일 발송이 아니라 로컬 로그 파일 저장이다.

## 상태 확인

컨테이너 상태:

```powershell
docker ps
```

포트 상태:

```powershell
Get-NetTCPConnection -LocalPort 5432,6379,6333,6334,8080,11434 -State Listen
```

Spring Boot health:

```powershell
Invoke-RestMethod http://localhost:8080/api/auth/health
```

정상 응답은 `OK`이다.

## 종료

터미널에서 직접 `.\gradlew.bat bootRun`으로 실행했다면 해당 창에서 `Ctrl + C`를 누른다.

백그라운드로 실행한 8080 서버 종료:

```powershell
$server = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($server) { Stop-Process -Id $server.OwningProcess -Force }
```

Docker 서비스 종료:

```powershell
docker compose stop
```

`docker compose stop`이 일부 컨테이너만 멈추거나 Compose 프로젝트가 다르게 잡힌 경우에는 아래 개별 종료 명령을 사용한다. 특히 `ai-tutor-postgres`, `ai-tutor-redis`가 예전 `C:\Users\c\Desktop\ai-tutor` 폴더에서 만들어진 컨테이너라면 현재 `ai-tutor-github`의 `docker compose stop`에 잡히지 않을 수 있다.

개별 종료:

```powershell
docker stop ai-tutor-postgres
docker stop ai-tutor-redis
docker stop ai-tutor-qdrant
```

Ollama를 직접 `ollama serve`로 실행했다면 해당 창에서 `Ctrl + C`를 누른다.

Ollama 포트 프로세스 강제 종료:

```powershell
$ollama = Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue
if ($ollama) { Stop-Process -Id $ollama.OwningProcess -Force }
```

## 자주 나는 오류

`Port 8080 was already in use`

이미 Spring Boot 서버가 실행 중이다.

```powershell
$server = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($server) { Stop-Process -Id $server.OwningProcess -Force }
```

`FATAL: password authentication failed for user "ai_tutor"`

Spring Boot 실행 환경의 DB 비밀번호가 Docker PostgreSQL 비밀번호와 다르다.

```powershell
$env:DB_USERNAME="ai_tutor"
$env:DB_PASSWORD="ai_tutor"
```

그래도 실패하면 컨테이너 내부 비밀번호를 확인하거나 재설정한다.

```powershell
docker exec -e PGPASSWORD=ai_tutor ai-tutor-postgres psql -U ai_tutor -d ai_tutor -c "select 1"
```

`Connection to localhost:5432 refused`

PostgreSQL 컨테이너가 꺼져 있거나 포트가 열리지 않았다.

```powershell
docker compose up -d postgres
docker ps
```

위 명령이 컨테이너 이름 충돌로 실패하면 기존 컨테이너를 시작한다.

```powershell
docker start ai-tutor-postgres
```

`Qdrant is not available. Start it with docker compose up -d qdrant.`

Qdrant 컨테이너가 꺼져 있다.

```powershell
docker compose up -d qdrant
Invoke-RestMethod http://localhost:6333/collections
```

`The container name "/ai-tutor-redis" is already in use`

현재 폴더의 Compose 프로젝트가 새 컨테이너를 만들려고 했지만, 같은 이름의 컨테이너가 이미 존재한다. 현재 PC에서는 기존 컨테이너를 그대로 시작하면 된다.

```powershell
docker start ai-tutor-postgres ai-tutor-redis ai-tutor-qdrant
docker ps
```

어느 폴더에서 만들어진 컨테이너인지 확인:

```powershell
docker inspect ai-tutor-postgres --format "{{ index .Config.Labels \"com.docker.compose.project.working_dir\" }}"
docker inspect ai-tutor-redis --format "{{ index .Config.Labels \"com.docker.compose.project.working_dir\" }}"
docker inspect ai-tutor-qdrant --format "{{ index .Config.Labels \"com.docker.compose.project.working_dir\" }}"
```

`Execution failed for task ':bootRun'` 또는 `non-zero exit value -1`

`bootRun`은 서버 프로세스라 실행 중인 동안 명령이 끝나지 않는다. 터미널이나 실행 도구가 강제로 끊으면 Gradle에는 실패처럼 남을 수 있다. 먼저 실제 서버가 떠 있는지 확인한다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
Invoke-WebRequest http://localhost:8080 -UseBasicParsing
```

8080 포트가 열려 있지 않으면 로그의 마지막 오류를 확인한다.

```powershell
Get-Content run-logs\bootRun.out.log -Tail 120
Get-Content run-logs\bootRun.err.log -Tail 120
```

`Embedding vector size ... does not match Qdrant collection size 1536`

현재 Qdrant collection은 1536차원 임베딩을 기대한다. `text-embedding-3-small`이 아닌 다른 임베딩 모델을 쓰면 vector size가 다를 수 있다. 이 경우 `qdrant.vector-size`와 collection을 함께 맞춰야 한다.

Qdrant collection을 삭제하고 다시 만들 때는 기존 벡터가 사라진다.

```powershell
Invoke-RestMethod `
  -Uri http://localhost:6333/collections/document_chunks `
  -Method Delete
```

이후 PDF를 다시 업로드하면 collection과 point가 다시 생성된다.

## 검증

코드 변경 후 최소 검증:

```powershell
.\gradlew.bat test
```

문서만 수정한 경우에는 테스트 실행이 필수는 아니지만, 코드 변경 후에는 위 명령을 최소 검증 기준으로 사용한다.

Qdrant까지 수동 검증:

1. `docker compose up -d postgres redis qdrant` 또는 충돌 시 `docker start ai-tutor-postgres ai-tutor-redis ai-tutor-qdrant`
2. `OPENAI_API_KEY`, `DB_USERNAME`, `DB_PASSWORD` 설정
3. `.\gradlew.bat bootRun`
4. 브라우저에서 PDF 업로드
5. `Invoke-RestMethod http://localhost:6333/collections/document_chunks`
6. `points_count`가 1 이상인지 확인

전체 문서 기준 설계 확인은 [UML_DB_DESIGN_KO.md](UML_DB_DESIGN_KO.md)의 ERD와 테이블별 구성 설명을 참고한다.
