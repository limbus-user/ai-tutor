const AUTH_STORAGE_KEY = "ai-tutor-auth";

const state = {
  token: null,
  userId: null,
  userEmail: "",
  userName: "",
  role: "",
  apiLogs: [],
  sessions: [],
  documentsCatalog: [],
  currentSession: null,
  currentMessages: [],
  currentWorkspace: null,
  quizSession: null,
  openQuizSetMenuId: null,
  openDocumentMenuId: null,
};

const views = {
  auth: document.getElementById("auth-view"),
  home: document.getElementById("home-view"),
  workspace: document.getElementById("workspace-view"),
  quiz: document.getElementById("quiz-view"),
};

const serverStatus = document.getElementById("server-status");
const authFeedback = document.getElementById("auth-feedback");
const devModeOutput = document.getElementById("dev-mode-output");
const sessionItemTemplate = document.getElementById("session-item-template");
const documentItemTemplate = document.getElementById("document-item-template");
const messageTemplate = document.getElementById("message-template");
const quizSetTemplate = document.getElementById("quiz-set-template");
const quizSessionTemplate = document.getElementById("quiz-session-template");

function showView(name) {
  Object.entries(views).forEach(([key, element]) => {
    element.classList.toggle("active", key === name);
  });
}

function showAuthFeedback(message, isError = false) {
  authFeedback.textContent = String(message);
  authFeedback.classList.remove("hidden");
  authFeedback.classList.toggle("error", isError);
}

function clearAuthFeedback() {
  authFeedback.textContent = "";
  authFeedback.classList.add("hidden");
  authFeedback.classList.remove("error");
}

function setServerStatus(text, isError = false) {
  serverStatus.textContent = text;
  serverStatus.classList.toggle("error", isError);
}

function isAdmin() {
  return state.role === "ADMIN";
}

function loadAuth() {
  const raw = localStorage.getItem(AUTH_STORAGE_KEY);
  if (!raw) return;
  try {
    const auth = JSON.parse(raw);
    state.token = auth.token || null;
    state.userId = auth.userId || null;
    state.userEmail = auth.userEmail || "";
    state.userName = auth.userName || "";
    state.role = auth.role || "";
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY);
  }
}

function saveAuth() {
  if (!state.token) {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    return;
  }

  localStorage.setItem(
    AUTH_STORAGE_KEY,
    JSON.stringify({
      token: state.token,
      userId: state.userId,
      userEmail: state.userEmail,
      userName: state.userName,
      role: state.role,
    }),
  );
}

function clearAuth() {
  state.token = null;
  state.userId = null;
  state.userEmail = "";
  state.userName = "";
  state.role = "";
  state.apiLogs = [];
  state.sessions = [];
  state.documentsCatalog = [];
  state.currentSession = null;
  state.currentMessages = [];
  state.currentWorkspace = null;
  state.quizSession = null;
  state.openQuizSetMenuId = null;
  state.openDocumentMenuId = null;
  saveAuth();
  renderDevModeLogs();
}

function applyAuthResponse(data) {
  state.token = data.token;
  state.userId = data.id;
  state.userEmail = data.email;
  state.userName = data.name;
  state.role = data.role || "";
  saveAuth();
}

function appendApiLog(log) {
  state.apiLogs.unshift(log);
  state.apiLogs = state.apiLogs.slice(0, 50);
  renderDevModeLogs();
}

function formatErrorMessage(error, fallback) {
  if (!error) {
    return fallback;
  }
  if (typeof error === "string" && error.trim() && error !== "[object Object]") {
    return error;
  }
  if (typeof error.payload === "string" && error.payload.trim()) {
    return error.payload;
  }
  if (error.payload && typeof error.payload === "object") {
    const objectMessage = [
      error.payload.message,
      error.payload.error,
      error.payload.reason,
      error.payload.detail,
      error.payload.title,
    ].find((value) => typeof value === "string" && value.trim() && value !== "[object Object]");

    if (objectMessage) {
      return objectMessage;
    }
    try {
      const serialized = JSON.stringify(error.payload, null, 2);
      return serialized && serialized !== "{}" ? serialized : fallback;
    } catch {
      return fallback;
    }
  }
  if (typeof error.message === "string" && error.message.trim() && error.message !== "[object Object]") {
    return error.message;
  }
  return fallback;
}

function renderDevModeLogs() {
  if (!devModeOutput) return;
  if (!state.apiLogs.length) {
    devModeOutput.textContent = "아직 기록된 API 호출이 없습니다.";
    return;
  }

  devModeOutput.textContent = state.apiLogs.map((log) => {
    const responseBody = typeof log.responseBody === "string"
      ? log.responseBody
      : JSON.stringify(log.responseBody, null, 2);
    return `[${log.timestamp}] ${log.method} ${log.url} -> ${log.status} (${log.elapsedMs}ms)\nrequest: ${log.requestBody || "-"}\nresponse: ${responseBody}`;
  }).join("\n\n");
}

async function apiFetch(url, options = {}) {
  const method = options.method || "GET";
  const requestBody = typeof options.body === "string"
    ? options.body
    : options.body instanceof FormData
      ? "[form-data]"
      : "";
  const startedAt = Date.now();
  const headers = new Headers(options.headers || {});

  if (state.token) {
    headers.set("Authorization", `Bearer ${state.token}`);
  }

  const response = await fetch(url, { ...options, headers });
  const text = await response.text();
  const elapsedMs = Date.now() - startedAt;

  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = text;
  }

  appendApiLog({
    timestamp: new Date().toLocaleTimeString("ko-KR"),
    method,
    url,
    status: response.status,
    elapsedMs,
    requestBody,
    responseBody: data,
  });

  if (!response.ok) {
    const error = new Error(formatErrorMessage({ payload: data }, `API 요청에 실패했습니다. (${response.status})`));
    error.payload = data;
    throw error;
  }

  return data;
}

function renderHomeHeader() {
  const roleLabel = state.role ? ` / ${state.role}` : "";
  document.getElementById("home-user-summary").textContent = `${state.userName} (${state.userEmail})${roleLabel}`;
  document.getElementById("dev-mode-wrap").classList.toggle("hidden", !isAdmin());
}

function renderSessionList() {
  const container = document.getElementById("session-list");
  container.innerHTML = "";

  const studySessions = state.sessions.filter((session) => (session.type || "STUDY") === "STUDY");

  if (!studySessions.length) {
    container.innerHTML = '<div class="empty-box">아직 만든 세션이 없습니다.</div>';
    return;
  }

  studySessions.forEach((session) => {
    const fragment = sessionItemTemplate.content.cloneNode(true);
    fragment.querySelector(".session-title").textContent = session.title;
    fragment.querySelector(".session-meta").textContent =
      `sessionId ${session.id} · ${session.status} · ${new Date(session.updatedAt).toLocaleString("ko-KR")}`;
    fragment.querySelector(".open-session-button").addEventListener("click", () => void openExistingSession(session.id));
    fragment.querySelector(".delete-session-button").addEventListener("click", () => void deleteSession(session.id));
    container.appendChild(fragment);
  });
}

function renderWorkspaceHeader() {
  if (!state.currentSession) return;
  document.getElementById("workspace-title").textContent = state.currentSession.title;
  document.getElementById("workspace-subtitle").textContent = `sessionId ${state.currentSession.id} · ${state.userName}`;
  const currentDocument = state.currentWorkspace?.documents?.find((document) => document.documentId === state.currentWorkspace.currentDocumentId);
  document.getElementById("current-document-badge").textContent = currentDocument ? currentDocument.title : "문서 미선택";
}

function renderDocuments() {
  const container = document.getElementById("document-list");
  container.innerHTML = "";

  const documents = state.currentWorkspace?.documents || [];
  if (!documents.length) {
    container.innerHTML = '<div class="empty-box">업로드된 PDF가 없습니다.</div>';
    renderWorkspaceHeader();
    return;
  }

  documents.forEach((documentInfo) => {
    const fragment = documentItemTemplate.content.cloneNode(true);
    const card = fragment.querySelector(".document-row");
    const openButton = fragment.querySelector(".document-row-open");
    const moreButton = fragment.querySelector(".document-more-button");
    const menu = fragment.querySelector(".document-menu");
    const downloadButton = fragment.querySelector(".download-document-button");
    const renameButton = fragment.querySelector(".rename-document-button");
    const deleteButton = fragment.querySelector(".delete-document-button");
    const selectedIndicator = fragment.querySelector(".document-selected-indicator");

    card.classList.toggle("selected", documentInfo.documentId === state.currentWorkspace.currentDocumentId);
    selectedIndicator.classList.toggle("active", documentInfo.documentId === state.currentWorkspace.currentDocumentId);
    menu.classList.toggle("hidden", state.openDocumentMenuId !== documentInfo.documentId);
    fragment.querySelector(".document-name").textContent = documentInfo.title;
    fragment.querySelector(".document-meta").textContent =
      `${documentInfo.subject || "-"} / ${documentInfo.unitName || "-"} / ${documentInfo.trustLevel || "-"}`;

    openButton.addEventListener("click", () => {
      state.currentWorkspace.currentDocumentId = documentInfo.documentId;
      state.openDocumentMenuId = null;
      renderDocuments();
      renderQuizSets();
    });

    downloadButton.addEventListener("click", () => {
      state.openDocumentMenuId = null;
      window.open(`/api/rag/documents/${documentInfo.documentId}/download`, "_blank", "noopener,noreferrer");
    });
    renameButton.addEventListener("click", () => void renameDocument(documentInfo));
    deleteButton.addEventListener("click", () => void deleteDocument(documentInfo));
    moreButton.addEventListener("click", (event) => {
      event.stopPropagation();
      state.openDocumentMenuId = state.openDocumentMenuId === documentInfo.documentId ? null : documentInfo.documentId;
      renderDocuments();
    });

    container.appendChild(fragment);
  });

  renderWorkspaceHeader();
}

async function renameDocument(documentInfo) {
  const nextTitle = window.prompt("새 PDF 이름을 입력해 주세요.", documentInfo.title || "");
  if (nextTitle === null) {
    return;
  }

  try {
    const renamed = await apiFetch(`/api/rag/documents/${documentInfo.documentId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: nextTitle }),
    });

    await fetchDocumentsCatalog();
    state.currentWorkspace.documents = (state.currentWorkspace?.documents || []).map((document) =>
      document.documentId === documentInfo.documentId
        ? {
            ...document,
            title: renamed.title,
            storedFileName: renamed.storedFileName,
            subject: renamed.subject,
            unitName: renamed.unitName,
            trustLevel: renamed.trustLevel,
          }
        : document,
    );
    state.openDocumentMenuId = null;
    renderDocuments();
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "PDF 이름을 바꾸지 못했습니다."));
  }
}

async function deleteDocument(documentInfo) {
  const confirmed = window.confirm(`"${documentInfo.title}" 파일을 삭제하시겠습니까?`);
  if (!confirmed) {
    return;
  }

  try {
    await apiFetch(`/api/rag/documents/${documentInfo.documentId}`, {
      method: "DELETE",
    });

    await fetchDocumentsCatalog();
    state.currentWorkspace.documents = (state.currentWorkspace?.documents || [])
      .filter((document) => document.documentId !== documentInfo.documentId);
    state.currentWorkspace.quizzes = (state.currentWorkspace?.quizzes || [])
      .filter((quiz) => quiz.documentId !== documentInfo.documentId);

    if (state.currentWorkspace.currentDocumentId === documentInfo.documentId) {
      state.currentWorkspace.currentDocumentId = state.currentWorkspace.documents[0]?.documentId || null;
    }

    state.openDocumentMenuId = null;
    renderDocuments();
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "PDF를 삭제하지 못했습니다."));
  }
}

function formatRelativeTime(dateValue) {
  const target = new Date(dateValue);
  const diffMs = Date.now() - target.getTime();
  const diffMinutes = Math.max(1, Math.floor(diffMs / 60000));

  if (diffMinutes < 60) {
    return `${diffMinutes}분 전`;
  }

  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours}시간 전`;
  }

  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) {
    return `${diffDays}일 전`;
  }

  return target.toLocaleDateString("ko-KR");
}

function renderMessages() {
  const container = document.getElementById("message-list");
  container.innerHTML = "";

  if (!state.currentMessages.length) {
    container.innerHTML = '<div class="empty-box">기존 대화가 없습니다. 질문을 보내서 시작하세요.</div>';
    return;
  }

  state.currentMessages.forEach((message) => {
    const fragment = messageTemplate.content.cloneNode(true);
    const card = fragment.querySelector(".message-bubble");
    const isUser = message.role === "USER";
    card.classList.add(isUser ? "user" : "assistant");
    fragment.querySelector(".message-role").textContent = isUser ? "나" : "튜터";
    fragment.querySelector(".message-content").textContent = message.content;
    const sourceElement = fragment.querySelector(".message-source");
    if (message.sourceReferences) {
      sourceElement.textContent = message.sourceReferences;
    } else {
      sourceElement.remove();
    }
    container.appendChild(fragment);
  });

  container.scrollTop = container.scrollHeight;
}

function getCurrentDocumentQuizSets() {
  const quizzes = state.currentWorkspace?.quizzes || [];
  const documentId = state.currentWorkspace?.currentDocumentId;
  const filtered = quizzes.filter((quiz) => (documentId ? quiz.documentId === documentId : true));
  const map = new Map();

  filtered.forEach((quiz) => {
    if (!map.has(quiz.quizSetId)) {
      map.set(quiz.quizSetId, {
        quizSetId: quiz.quizSetId,
        quizSetTitle: quiz.quizSetTitle,
        documentId: quiz.documentId,
        createdAt: quiz.createdAt,
        questions: [],
      });
    }
    map.get(quiz.quizSetId).questions.push(quiz);
  });

  return Array.from(map.values()).sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
}

function renderQuizSets() {
  const container = document.getElementById("quiz-list");
  container.innerHTML = "";

  const quizSets = getCurrentDocumentQuizSets();
  if (!quizSets.length) {
    container.innerHTML = '<div class="empty-box">만들어진 퀴즈 세트가 없습니다.</div>';
    return;
  }

  quizSets.forEach((quizSet, index) => {
    const fragment = quizSetTemplate.content.cloneNode(true);
    const openButton = fragment.querySelector(".quiz-set-open");
    const moreButton = fragment.querySelector(".quiz-set-more");
    const menu = fragment.querySelector(".quiz-set-menu");
    const renameButton = fragment.querySelector(".rename-quiz-set-button");
    const deleteButton = fragment.querySelector(".delete-quiz-set-button");

    fragment.querySelector(".quiz-set-title").textContent = quizSet.quizSetTitle || `퀴즈 세트 ${index + 1}`;
    fragment.querySelector(".quiz-set-meta").textContent =
      `문제 ${quizSet.questions.length}개 · ${formatRelativeTime(quizSet.createdAt)}`;
    menu.classList.toggle("hidden", state.openQuizSetMenuId !== quizSet.quizSetId);

    openButton.addEventListener("click", () => void openQuizSession(quizSet));
    moreButton.addEventListener("click", (event) => {
      event.stopPropagation();
      state.openQuizSetMenuId = state.openQuizSetMenuId === quizSet.quizSetId ? null : quizSet.quizSetId;
      renderQuizSets();
    });
    renameButton.addEventListener("click", () => void renameQuizSet(quizSet));
    deleteButton.addEventListener("click", () => void deleteQuizSet(quizSet));
    container.appendChild(fragment);
  });
}

async function renameQuizSet(quizSet) {
  const nextTitle = window.prompt("새 퀴즈 이름을 입력해 주세요.", quizSet.quizSetTitle || "");
  if (nextTitle === null) {
    return;
  }

  try {
    const renamed = await apiFetch(`/api/chat/sessions/${state.currentSession.id}/quizzes/${quizSet.quizSetId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ quizSetTitle: nextTitle }),
    });

    const otherQuizzes = (state.currentWorkspace?.quizzes || []).filter((quiz) => quiz.quizSetId !== quizSet.quizSetId);
    state.currentWorkspace.quizzes = [...otherQuizzes, ...renamed];
    state.openQuizSetMenuId = null;
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "퀴즈 이름을 바꾸지 못했습니다."));
  }
}

async function deleteQuizSet(quizSet) {
  const confirmed = window.confirm(`"${quizSet.quizSetTitle}" 퀴즈를 삭제하시겠습니까?`);
  if (!confirmed) {
    return;
  }

  try {
    await apiFetch(`/api/chat/sessions/${state.currentSession.id}/quizzes/${quizSet.quizSetId}`, {
      method: "DELETE",
    });
    state.currentWorkspace.quizzes = (state.currentWorkspace?.quizzes || [])
      .filter((quiz) => quiz.quizSetId !== quizSet.quizSetId);
    state.openQuizSetMenuId = null;
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "퀴즈를 삭제하지 못했습니다."));
  }
}

function renderQuizSession() {
  const board = document.getElementById("quiz-session-board");
  board.innerHTML = "";

  const quizzes = state.quizSession?.questions || [];
  document.getElementById("quiz-session-title").textContent = state.quizSession?.title || "퀴즈 세션";
  document.getElementById("quiz-session-subtitle").textContent = `sessionId ${state.quizSession?.sessionId || "-"} · ${quizzes.length}문제`;

  if (!quizzes.length) {
    board.innerHTML = '<div class="empty-box">선택된 퀴즈가 없습니다.</div>';
    return;
  }

  const currentIndex = Math.min(state.quizSession?.currentIndex || 0, quizzes.length - 1);
  const quiz = quizzes[currentIndex];
  const answerKey = String(currentIndex);
  const selectedAnswer = state.quizSession?.answers?.[answerKey] || "";
  const revealed = Boolean(state.quizSession?.revealed?.[answerKey]);
  const fragment = quizSessionTemplate.content.cloneNode(true);
  const card = fragment.querySelector(".quiz-solve-card");
  const answerArea = fragment.querySelector(".quiz-solve-answer-area");
  const feedback = fragment.querySelector(".quiz-feedback");
  const submitButton = fragment.querySelector(".quiz-check-button");
  const nextButton = fragment.querySelector(".quiz-next-button");
  const hintContent = fragment.querySelector(".quiz-hint-content");

  fragment.querySelector(".quiz-type").textContent = formatQuizTypeLabel(quiz.type);
  fragment.querySelector(".quiz-order").textContent = `${currentIndex + 1} / ${quizzes.length}`;
  fragment.querySelector(".quiz-question").textContent = quiz.question;
  hintContent.textContent = quiz.sourceEvidence || quiz.modelAnswer || "힌트가 없습니다.";

  if (quiz.choices?.length) {
    answerArea.innerHTML = quiz.choices.map((choice, choiceIndex) => `
      <label class="quiz-option-card ${selectedAnswer === choice ? "selected" : ""}">
        <input type="radio" name="quiz-current" value="${choice}" ${selectedAnswer === choice ? "checked" : ""}>
        <span class="quiz-option-index">${String.fromCharCode(65 + choiceIndex)}.</span>
        <span>${choice}</span>
      </label>
    `).join("");

    card.querySelectorAll('input[name="quiz-current"]').forEach((input) => {
      input.addEventListener("change", () => {
        state.quizSession.answers[answerKey] = input.value;
        renderQuizSession();
      });
    });
  } else {
    answerArea.innerHTML = '<textarea rows="4" class="quiz-text-answer" placeholder="답을 입력하세요."></textarea>';
    const textarea = answerArea.querySelector(".quiz-text-answer");
    textarea.value = selectedAnswer;
    textarea.addEventListener("input", () => {
      state.quizSession.answers[answerKey] = textarea.value;
    });
  }

  if (revealed) {
    const correct = (selectedAnswer || "").trim().toLowerCase() === (quiz.correctAnswer || "").trim().toLowerCase();
    feedback.innerHTML = `
      <div class="feedback-badge ${correct ? "correct" : "wrong"}">${correct ? "정답입니다." : "오답입니다."}</div>
      <div class="feedback-detail"><strong>정답:</strong> ${quiz.correctAnswer}</div>
      <div class="feedback-detail"><strong>해설:</strong> ${quiz.explanation || quiz.modelAnswer || ""}</div>
    `;
  }

  submitButton.addEventListener("click", () => {
    const currentAnswer = quiz.choices?.length
      ? (state.quizSession.answers[answerKey] || "")
      : (card.querySelector(".quiz-text-answer")?.value?.trim() || "");

    if (!currentAnswer) {
      feedback.innerHTML = '<div class="feedback-badge wrong">답을 먼저 입력해 주세요.</div>';
      return;
    }

    state.quizSession.answers[answerKey] = currentAnswer;
    state.quizSession.revealed[answerKey] = true;
    renderQuizSession();
  });

  nextButton.textContent = currentIndex >= quizzes.length - 1 ? "완료" : "다음";
  nextButton.addEventListener("click", () => {
    if (currentIndex < quizzes.length - 1) {
      state.quizSession.currentIndex += 1;
      renderQuizSession();
      return;
    }
    showView("workspace");
  });

  board.appendChild(fragment);
}

function formatQuizTypeLabel(type) {
  if (type === "ox") return "O/X";
  if (type === "multiple_choice") return "객관식";
  if (type === "short_answer") return "주관식";
  return type || "퀴즈";
}

function parseDocumentTitlesFromMessages(messages) {
  const titles = new Set();
  const pattern = /([^,\n]+?) \[chunk \d+\]/g;

  messages.forEach((message) => {
    if (!message.sourceReferences) return;
    for (const match of message.sourceReferences.matchAll(pattern)) {
      titles.add(match[1].trim());
    }
  });

  return Array.from(titles);
}

function inferDocumentsForSession(messages, quizzes) {
  const documents = [];
  const knownIds = new Set();

  parseDocumentTitlesFromMessages(messages).forEach((title) => {
    const matched = state.documentsCatalog.find((documentInfo) => documentInfo.title === title || documentInfo.storedFileName === title);
    if (!matched || knownIds.has(matched.id)) return;
    documents.push({
      documentId: matched.id,
      title: matched.title,
      storedFileName: matched.storedFileName,
      subject: matched.subject,
      unitName: matched.unitName,
      trustLevel: matched.trustLevel,
    });
    knownIds.add(matched.id);
  });

  quizzes.forEach((quiz) => {
    const matched = state.documentsCatalog.find((documentInfo) => documentInfo.id === quiz.documentId);
    if (!matched || knownIds.has(matched.id)) return;
    documents.push({
      documentId: matched.id,
      title: matched.title,
      storedFileName: matched.storedFileName,
      subject: matched.subject,
      unitName: matched.unitName,
      trustLevel: matched.trustLevel,
    });
    knownIds.add(matched.id);
  });

  return documents;
}

async function pingServer() {
  try {
    await apiFetch("/api/users");
    setServerStatus("서버 연결됨");
  } catch {
    setServerStatus("서버 연결 실패", true);
  }
}

async function fetchDocumentsCatalog() {
  state.documentsCatalog = await apiFetch("/api/rag/documents");
}

async function fetchSessions() {
  state.sessions = await apiFetch(`/api/chat/sessions?userId=${state.userId}`);
  renderSessionList();
}

async function loadHome() {
  renderHomeHeader();
  document.getElementById("history-panel").classList.add("hidden");
  await Promise.all([fetchDocumentsCatalog(), fetchSessions()]);
  showView("home");
}

async function openExistingSession(sessionId) {
  try {
    await fetchDocumentsCatalog();

    const [session, messages, quizzes] = await Promise.all([
      apiFetch(`/api/chat/sessions/${sessionId}`),
      apiFetch(`/api/chat/sessions/${sessionId}/messages`),
      apiFetch(`/api/chat/sessions/${sessionId}/quizzes`),
    ]);

    const documents = inferDocumentsForSession(messages, quizzes);
    const currentDocumentId = documents[0]?.documentId || quizzes[0]?.documentId || null;

    state.currentSession = session;
    state.currentMessages = messages;
    state.currentWorkspace = {
      documents,
      quizzes,
      currentDocumentId,
    };

    renderWorkspaceHeader();
    renderDocuments();
    renderMessages();
    renderQuizSets();
    showView("workspace");
  } catch (error) {
    alert(formatErrorMessage(error, "세션을 열지 못했습니다."));
  }
}

async function deleteSession(sessionId) {
  const confirmed = window.confirm("이 세션을 삭제하시겠습니까?");
  if (!confirmed) {
    return;
  }

  try {
    await apiFetch(`/api/chat/sessions/${sessionId}`, { method: "DELETE" });
    if (state.currentSession?.id === sessionId) {
      state.currentSession = null;
      state.currentMessages = [];
      state.currentWorkspace = null;
    }
    await fetchSessions();
  } catch (error) {
    alert(formatErrorMessage(error, "세션을 삭제하지 못했습니다."));
  }
}

async function createNewStudy(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;

  try {
    const fileInput = document.getElementById("new-study-file");
    if (!fileInput.files[0]) {
      throw new Error("PDF 파일을 선택해 주세요.");
    }

    const uploadForm = new FormData();
    uploadForm.append("file", fileInput.files[0]);
    uploadForm.append("subject", document.getElementById("new-study-subject").value);
    uploadForm.append("unitName", document.getElementById("new-study-unit").value);
    uploadForm.append("trustLevel", document.getElementById("new-study-trust").value);

    const uploadResponse = await apiFetch("/api/rag/upload", {
      method: "POST",
      body: uploadForm,
    });

    const session = await apiFetch("/api/chat/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: state.userId,
        title: document.getElementById("new-study-title").value.trim() || `${uploadResponse.title} 학습`,
      }),
    });

    await fetchDocumentsCatalog();

    state.currentSession = session;
    state.currentMessages = [];
    state.currentWorkspace = {
      documents: [{
        documentId: uploadResponse.documentId,
        title: uploadResponse.title,
        storedFileName: uploadResponse.storedFileName,
        subject: document.getElementById("new-study-subject").value,
        unitName: document.getElementById("new-study-unit").value,
        trustLevel: document.getElementById("new-study-trust").value,
      }],
      quizzes: [],
      currentDocumentId: uploadResponse.documentId,
    };

    await fetchSessions();
    renderWorkspaceHeader();
    renderDocuments();
    renderMessages();
    renderQuizSets();
    form.reset();
    showView("workspace");
  } catch (error) {
    alert(formatErrorMessage(error, "새 학습 세션을 만들지 못했습니다."));
  } finally {
    button.disabled = false;
  }
}

async function uploadWorkspacePdf(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;

  try {
    if (!state.currentSession) throw new Error("세션이 먼저 필요합니다.");

    const fileInput = document.getElementById("workspace-file");
    if (!fileInput.files[0]) throw new Error("PDF 파일을 선택해 주세요.");

    const uploadForm = new FormData();
    uploadForm.append("file", fileInput.files[0]);
    uploadForm.append("subject", document.getElementById("workspace-subject").value);
    uploadForm.append("unitName", document.getElementById("workspace-unit").value);
    uploadForm.append("trustLevel", document.getElementById("workspace-trust").value);

    const uploadResponse = await apiFetch("/api/rag/upload", { method: "POST", body: uploadForm });

    state.currentWorkspace.documents.unshift({
      documentId: uploadResponse.documentId,
      title: uploadResponse.title,
      storedFileName: uploadResponse.storedFileName,
      subject: document.getElementById("workspace-subject").value,
      unitName: document.getElementById("workspace-unit").value,
      trustLevel: document.getElementById("workspace-trust").value,
    });
    state.currentWorkspace.currentDocumentId = uploadResponse.documentId;

    await fetchDocumentsCatalog();
    renderDocuments();
    renderQuizSets();
    form.reset();
  } catch (error) {
    alert(formatErrorMessage(error, "PDF를 업로드하지 못했습니다."));
  } finally {
    button.disabled = false;
  }
}

async function sendChat(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;

  try {
    if (!state.currentSession) throw new Error("세션이 필요합니다.");

    const questionInput = document.getElementById("chat-question");
    const question = questionInput.value.trim();
    if (!question) throw new Error("질문을 입력해 주세요.");

    const payload = { question };
    if (state.currentWorkspace?.currentDocumentId) {
      payload.documentId = state.currentWorkspace.currentDocumentId;
    }

    await apiFetch(`/api/tutor/sessions/${state.currentSession.id}/ask`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    state.currentMessages = await apiFetch(`/api/chat/sessions/${state.currentSession.id}/messages`);
    renderMessages();
    questionInput.value = "";
  } catch (error) {
    alert(formatErrorMessage(error, "질문을 처리하지 못했습니다."));
  } finally {
    button.disabled = false;
  }
}

async function generateQuiz(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;

  try {
    if (!state.currentSession) throw new Error("세션이 필요합니다.");

    const documentId = state.currentWorkspace?.currentDocumentId;
    if (!documentId) throw new Error("먼저 PDF를 선택해 주세요.");

    const response = await apiFetch(
      `/api/rag/generate-questions?documentId=${documentId}&type=${document.getElementById("quiz-type").value}&count=${document.getElementById("quiz-count").value}`,
      { method: "POST" },
    );

    const questions = response.questions || [];
    if (!questions.length) throw new Error("생성된 퀴즈가 없습니다.");

    const currentDocument = state.currentWorkspace.documents.find((documentInfo) => documentInfo.documentId === documentId);
    const savedQuizzes = await apiFetch(`/api/chat/sessions/${state.currentSession.id}/quizzes`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        documentId,
        quizSetTitle: `${currentDocument?.title || state.currentSession.title} 퀴즈 ${new Date().toLocaleTimeString("ko-KR")}`,
        questions,
      }),
    });

    state.currentWorkspace.quizzes = [...(state.currentWorkspace.quizzes || []), ...savedQuizzes];
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "퀴즈를 생성하지 못했습니다."));
  } finally {
    button.disabled = false;
  }
}

async function openQuizSession(quizSet) {
  const quizSession = await apiFetch("/api/chat/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      userId: state.userId,
      title: `${quizSet.quizSetTitle} 풀이`,
      type: "QUIZ",
    }),
  });

  await fetchSessions();
  state.quizSession = {
    sessionId: quizSession.id,
    title: quizSession.title,
    questions: quizSet.questions,
    currentIndex: 0,
    answers: {},
    revealed: {},
  };
  renderQuizSession();
  showView("quiz");
}

async function onLogin(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  clearAuthFeedback();

  try {
    const data = await apiFetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: document.getElementById("login-email").value,
        password: document.getElementById("login-password").value,
      }),
    });
    applyAuthResponse(data);
    await loadHome();
  } catch (error) {
    showAuthFeedback(formatErrorMessage(error, "로그인에 실패했습니다."), true);
  } finally {
    button.disabled = false;
  }
}

async function onSignup(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  clearAuthFeedback();

  try {
    const data = await apiFetch("/api/auth/signup", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: document.getElementById("signup-email").value,
        password: document.getElementById("signup-password").value,
        name: document.getElementById("signup-name").value,
      }),
    });
    applyAuthResponse(data);
    await loadHome();
  } catch (error) {
    showAuthFeedback(formatErrorMessage(error, "회원가입에 실패했습니다."), true);
  } finally {
    button.disabled = false;
  }
}

function showSignupForm() {
  document.getElementById("login-form").classList.add("hidden");
  document.getElementById("show-signup-button").classList.add("hidden");
  document.getElementById("signup-form").classList.remove("hidden");
}

function hideSignupForm() {
  document.getElementById("signup-form").classList.add("hidden");
  document.getElementById("login-form").classList.remove("hidden");
  document.getElementById("show-signup-button").classList.remove("hidden");
}

function logoutToAuth() {
  clearAuth();
  hideSignupForm();
  showView("auth");
}

async function initializeApp() {
  loadAuth();
  renderDevModeLogs();
  await pingServer();

  if (state.token && state.userId) {
    try {
      await loadHome();
      return;
    } catch {
      clearAuth();
    }
  }

  showView("auth");
}

document.getElementById("login-form").addEventListener("submit", (event) => void onLogin(event));
document.getElementById("signup-form").addEventListener("submit", (event) => void onSignup(event));
document.getElementById("show-signup-button").addEventListener("click", showSignupForm);
document.getElementById("hide-signup-button").addEventListener("click", hideSignupForm);
document.getElementById("logout-button").addEventListener("click", logoutToAuth);
document.getElementById("workspace-logout-button").addEventListener("click", logoutToAuth);
document.getElementById("quiz-logout-button").addEventListener("click", logoutToAuth);
document.getElementById("back-home-button").addEventListener("click", async () => void loadHome());
document.getElementById("back-workspace-button").addEventListener("click", () => {
  renderWorkspaceHeader();
  renderDocuments();
  renderMessages();
  renderQuizSets();
  showView("workspace");
});
document.getElementById("start-new-study-button").addEventListener("click", () => {
  document.getElementById("new-study-form").classList.toggle("hidden");
});
document.getElementById("show-history-button").addEventListener("click", async () => {
  document.getElementById("history-panel").classList.remove("hidden");
  await fetchSessions();
});
document.getElementById("refresh-sessions-button").addEventListener("click", async () => void fetchSessions());
document.getElementById("new-study-form").addEventListener("submit", (event) => void createNewStudy(event));
document.getElementById("workspace-upload-form").addEventListener("submit", (event) => void uploadWorkspacePdf(event));
document.getElementById("chat-form").addEventListener("submit", (event) => void sendChat(event));
document.getElementById("quiz-form").addEventListener("submit", (event) => void generateQuiz(event));
document.getElementById("dev-mode-toggle").addEventListener("click", () => {
  document.getElementById("dev-mode-panel").classList.toggle("hidden");
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".quiz-set-card") && state.openQuizSetMenuId) {
    state.openQuizSetMenuId = null;
    renderQuizSets();
  }
  if (!event.target.closest(".document-row") && state.openDocumentMenuId) {
    state.openDocumentMenuId = null;
    renderDocuments();
  }
});

void initializeApp();
