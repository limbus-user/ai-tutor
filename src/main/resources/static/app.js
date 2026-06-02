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
  openSessionMenuId: null,
  homeSessionFilter: "recent",
  homeSessionSearch: "",
  homeSessionPage: 1,
  homeQuizCount: 0,
  homeMessageCount: 0,
  homeRecentMessages: [],
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
  state.openSessionMenuId = null;
  state.homeSessionPage = 1;
  state.homeQuizCount = 0;
  state.homeMessageCount = 0;
  state.homeRecentMessages = [];
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
  document.getElementById("dev-mode-wrap")?.classList.add("hidden");
}

function renderSessionList() {
  const container = document.getElementById("session-list");
  container.innerHTML = "";

  const studySessions = state.sessions
    .filter((session) => (session.type || "STUDY") === "STUDY")
    .filter((session) => state.homeSessionFilter !== "active" || (session.status || "ACTIVE") === "ACTIVE")
    .filter((session) => session.title.toLowerCase().includes(state.homeSessionSearch.toLowerCase()))
    .sort((a, b) => state.homeSessionFilter === "recent"
      ? new Date(b.updatedAt) - new Date(a.updatedAt)
      : a.id - b.id);

  document.getElementById("session-visible-count").textContent = String(studySessions.length);
  const pageSize = 7;
  const pageCount = Math.max(1, Math.ceil(studySessions.length / pageSize));
  state.homeSessionPage = Math.min(state.homeSessionPage, pageCount);
  const visibleSessions = studySessions.slice(
    (state.homeSessionPage - 1) * pageSize,
    state.homeSessionPage * pageSize,
  );
  renderSessionPagination(pageCount);

  if (!studySessions.length) {
    container.innerHTML = '<div class="empty-box">조건에 맞는 세션이 없습니다.</div>';
    renderHomeDashboard();
    return;
  }

  visibleSessions.forEach((session) => {
    const fragment = sessionItemTemplate.content.cloneNode(true);
    const status = session.status || "ACTIVE";
    fragment.querySelector(".session-title").textContent = session.title;
    fragment.querySelector(".session-meta").textContent =
      `sessionId ${session.id} · ${new Date(session.updatedAt).toLocaleString("ko-KR")}`;
    const statusElement = fragment.querySelector(".session-status");
    statusElement.textContent = status === "ACTIVE" ? "ACTIVE" : "완료";
    statusElement.classList.toggle("complete", status !== "ACTIVE");
    fragment.querySelector(".open-session-button").addEventListener("click", () => void openExistingSession(session.id));
    const menu = fragment.querySelector(".session-menu");
    menu.classList.toggle("hidden", state.openSessionMenuId !== session.id);
    fragment.querySelector(".session-more-button").addEventListener("click", (event) => {
      event.stopPropagation();
      state.openSessionMenuId = state.openSessionMenuId === session.id ? null : session.id;
      renderSessionList();
    });
    fragment.querySelector(".rename-session-button").addEventListener("click", () => void renameSession(session));
    fragment.querySelector(".delete-session-button").addEventListener("click", () => void deleteSession(session.id));
    container.appendChild(fragment);
  });

  renderHomeDashboard();
}

function renderSessionPagination(pageCount) {
  const container = document.getElementById("session-pagination");
  container.innerHTML = "";

  const appendButton = (label, page, options = {}) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `pagination-button${options.active ? " active" : ""}`;
    button.textContent = label;
    button.disabled = options.disabled || false;
    button.setAttribute("aria-label", options.ariaLabel || `${page}페이지`);
    button.addEventListener("click", () => {
      state.homeSessionPage = page;
      renderSessionList();
    });
    container.appendChild(button);
  };

  appendButton("‹", Math.max(1, state.homeSessionPage - 1), {
    disabled: state.homeSessionPage === 1,
    ariaLabel: "이전 페이지",
  });
  for (let page = 1; page <= pageCount; page += 1) {
    appendButton(String(page), page, { active: page === state.homeSessionPage });
  }
  appendButton("›", Math.min(pageCount, state.homeSessionPage + 1), {
    disabled: state.homeSessionPage === pageCount,
    ariaLabel: "다음 페이지",
  });
}

function renderHomeDashboard() {
  const studySessions = state.sessions.filter((session) => (session.type || "STUDY") === "STUDY");

  document.getElementById("stat-session-count").textContent = String(studySessions.length);
  document.getElementById("stat-document-count").textContent = String(state.documentsCatalog.length);
  document.getElementById("stat-quiz-count").textContent = String(state.homeQuizCount);
  document.getElementById("stat-message-count").textContent = String(state.homeMessageCount);

  const recentContainer = document.getElementById("recent-session-list");
  recentContainer.innerHTML = "";
  if (!state.homeRecentMessages.length) {
    recentContainer.innerHTML = '<div class="empty-box">최근 대화 기록이 없습니다.</div>';
    return;
  }

  state.homeRecentMessages.forEach((message) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "recent-session-item";
    button.innerHTML = `
      <span class="recent-session-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 4h12a2 2 0 0 1 2 2v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a2 2 0 0 1 2-2Z"/><path d="M8 8h8M8 12h8M8 16h5"/></svg></span>
      <span class="recent-session-copy">
        <strong></strong>
        <small></small>
      </span>
      <span class="recent-session-arrow"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg></span>
    `;
    button.querySelector("strong").textContent = message.sessionTitle;
    button.querySelector("small").textContent =
      `${message.content} · ${formatRelativeTime(message.createdAt)}`;
    button.addEventListener("click", () => void openExistingSession(message.sessionId));
    recentContainer.appendChild(button);
  });
}

function renderWorkspaceHeader() {
  if (!state.currentSession) return;

  document.getElementById("workspace-title").textContent = state.currentSession.title;
  document.getElementById("workspace-subtitle").textContent = `sessionId ${state.currentSession.id} · ${state.userName}`;
  document.getElementById("workspace-status").textContent = state.currentSession.status || "ACTIVE";

  const selectedIds = getSelectedQuizDocumentIds();
  const selectedDocuments = (state.currentWorkspace?.documents || [])
      .filter((document) => selectedIds.includes(document.documentId));

  document.getElementById("current-document-badge").textContent = selectedDocuments.length
      ? `선택 PDF ${selectedDocuments.length}개`
      : "문서 미선택";
  document.getElementById("selected-document-footer").textContent = `선택된 파일 ${selectedDocuments.length}개`;
}

function getSelectedQuizDocumentIds() {
  return state.currentWorkspace?.selectedQuizDocumentIds || [];
}

function renderQuizDocumentSelection() {
  const element = document.getElementById("quiz-document-selection");
  if (!element) return;

  const selectedIds = getSelectedQuizDocumentIds();
  const titles = (state.currentWorkspace?.documents || [])
    .filter((documentInfo) => selectedIds.includes(documentInfo.documentId))
    .map((documentInfo) => documentInfo.title);
  element.innerHTML = "";
  if (!titles.length) {
    element.textContent = "출제 PDF를 선택해 주세요.";
    return;
  }

  const heading = document.createElement("strong");
  heading.textContent = `선택된 PDF (${titles.length}개)`;
  const header = document.createElement("div");
  header.className = "quiz-document-selection-header";
  header.appendChild(heading);
  const editButton = document.createElement("button");
  editButton.type = "button";
  editButton.textContent = "편집";
  editButton.addEventListener("click", () => document.getElementById("document-list").scrollIntoView({ behavior: "smooth" }));
  header.appendChild(editButton);
  element.appendChild(header);
  const chips = document.createElement("div");
  chips.className = "quiz-document-chips";
  titles.forEach((title) => {
    const chip = document.createElement("span");
    chip.className = "quiz-document-chip";
    chip.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M14 3v6h6"/></svg>';
    chip.append(title);
    chips.appendChild(chip);
  });
  element.appendChild(chips);
}

function renderDocuments() {
  const container = document.getElementById("document-list");
  container.innerHTML = "";

  const documents = state.currentWorkspace?.documents || [];
  document.getElementById("uploaded-document-count").textContent = `업로드된 파일 ${documents.length}개`;
  if (!documents.length) {
    container.innerHTML = '<div class="empty-box">업로드된 PDF가 없습니다.</div>';
    renderWorkspaceHeader();
    renderQuizDocumentSelection();
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

    const selectedForQuiz = getSelectedQuizDocumentIds().includes(documentInfo.documentId);
    card.classList.toggle("selected", selectedForQuiz);
    selectedIndicator.classList.toggle("active", selectedForQuiz);
    selectedIndicator.setAttribute("aria-pressed", String(selectedForQuiz));
    menu.classList.toggle("hidden", state.openDocumentMenuId !== documentInfo.documentId);
    fragment.querySelector(".document-name").textContent = documentInfo.title;
    const metaTags = fragment.querySelector(".document-meta-tags");
    [documentInfo.subject, documentInfo.unitName, documentInfo.trustLevel]
      .filter(Boolean)
      .forEach((value) => {
        const tag = document.createElement("span");
        tag.textContent = value;
        metaTags.appendChild(tag);
      });
    fragment.querySelector(".document-uploaded-at").textContent = documentInfo.createdAt
      ? `업로드 ${new Date(documentInfo.createdAt).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" })}`
      : "";

    openButton.addEventListener("click", () => {
      const selectedIds = getSelectedQuizDocumentIds();
      state.currentWorkspace.selectedQuizDocumentIds = selectedIds.includes(documentInfo.documentId)
          ? selectedIds.filter((documentId) => documentId !== documentInfo.documentId)
          : [...selectedIds, documentInfo.documentId];

      state.openDocumentMenuId = null;
      renderDocuments();
    });

    selectedIndicator.addEventListener("click", (event) => {
      event.stopPropagation();

      const selectedIds = getSelectedQuizDocumentIds();
      state.currentWorkspace.selectedQuizDocumentIds = selectedIds.includes(documentInfo.documentId)
          ? selectedIds.filter((documentId) => documentId !== documentInfo.documentId)
          : [...selectedIds, documentInfo.documentId];

      renderDocuments();
      renderWorkspaceHeader();
      renderQuizDocumentSelection();
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
  renderQuizDocumentSelection();
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
    state.currentWorkspace.selectedQuizDocumentIds = getSelectedQuizDocumentIds()
      .filter((documentId) => documentId !== documentInfo.documentId);

    state.openDocumentMenuId = null;
    renderDocuments();
    renderWorkspaceHeader();
    renderQuizDocumentSelection();
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
    fragment.querySelector(".message-avatar").innerHTML = isUser
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="6" width="14" height="12" rx="4"/><path d="M12 3v3M9 11h.01M15 11h.01M9 15h6"/></svg>';
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
  const map = new Map();

  quizzes.forEach((quiz) => {
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
    fragment.querySelector(".quiz-set-icon").classList.add(`quiz-set-icon-${index % 4}`);

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

function buildQuizSessionState(quizSet) {
  const questions = [...(quizSet.questions || [])].sort((a, b) => (a.order || 0) - (b.order || 0));
  const answers = {};
  const revealed = {};
  const results = {};

  questions.forEach((quiz, index) => {
    const key = String(index);
    answers[key] = quiz.submittedAnswer || "";
    revealed[key] = Boolean(quiz.solved);
    if (quiz.solved) {
      results[key] = {
        submittedAnswer: quiz.submittedAnswer || "",
        correct: Boolean(quiz.correct),
        evaluationFeedback: quiz.evaluationFeedback || "",
      };
    }
  });

  const firstUnsolvedIndex = questions.findIndex((quiz) => !quiz.solved);
  return {
    sessionId: state.currentSession.id,
    quizSetId: quizSet.quizSetId,
    documentId: quizSet.documentId,
    title: quizSet.quizSetTitle,
    questions,
    currentIndex: firstUnsolvedIndex >= 0 ? firstUnsolvedIndex : 0,
    answers,
    revealed,
    results,
    completed: questions.length > 0 && questions.every((quiz) => quiz.solved),
  };
}

function syncQuizInState(updatedQuiz) {
  state.currentWorkspace.quizzes = (state.currentWorkspace?.quizzes || []).map((quiz) =>
    quiz.id === updatedQuiz.id ? updatedQuiz : quiz,
  );

  if (state.quizSession) {
    state.quizSession.questions = state.quizSession.questions.map((quiz) =>
      quiz.id === updatedQuiz.id ? updatedQuiz : quiz,
    );
  }
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

  if (state.quizSession?.completed) {
    renderQuizSummary(board, quizzes);
    return;
  }

  const currentIndex = Math.min(state.quizSession?.currentIndex || 0, quizzes.length - 1);
  const quiz = quizzes[currentIndex];
  const answerKey = String(currentIndex);
  const selectedAnswer = state.quizSession?.answers?.[answerKey] || "";
  const revealed = Boolean(state.quizSession?.revealed?.[answerKey]);
  const savedResult = state.quizSession?.results?.[answerKey];
  const fragment = quizSessionTemplate.content.cloneNode(true);
  const card = fragment.querySelector(".quiz-solve-card");
  const answerArea = fragment.querySelector(".quiz-solve-answer-area");
  const feedback = fragment.querySelector(".quiz-feedback");
  const submitButton = fragment.querySelector(".quiz-check-button");
  const prevButton = fragment.querySelector(".quiz-prev-button");
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
    const correct = savedResult?.correct ?? isAnswerCorrect(selectedAnswer, quiz.correctAnswer);
    feedback.innerHTML = `
      <div class="feedback-badge ${correct ? "correct" : "wrong"}">${correct ? "정답입니다." : "오답입니다."}</div>
      <div class="feedback-detail"><strong>정답:</strong> ${quiz.correctAnswer}</div>
      <div class="feedback-detail"><strong>해설:</strong> ${quiz.explanation || quiz.modelAnswer || ""}</div>
      <div class="feedback-detail"><strong>개념 태그:</strong> ${quiz.conceptTag || "핵심 개념"}</div>
      <div class="feedback-detail"><strong>이해 단계:</strong> ${formatUnderstandingLevelLabel(quiz.understandingLevel)}</div>
      ${savedResult?.evaluationFeedback ? `<div class="feedback-detail"><strong>답안 비교:</strong> ${savedResult.evaluationFeedback}</div>` : ""}
    `;
  }

  submitButton.addEventListener("click", async () => {
    const currentAnswer = quiz.choices?.length
      ? (state.quizSession.answers[answerKey] || "")
      : (card.querySelector(".quiz-text-answer")?.value?.trim() || "");

    if (!currentAnswer) {
      feedback.innerHTML = '<div class="feedback-badge wrong">답을 먼저 입력해 주세요.</div>';
      return;
    }

    submitButton.disabled = true;
    try {
      const updatedQuiz = await apiFetch(`/api/chat/sessions/${state.quizSession.sessionId}/quizzes/${quiz.id}/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ submittedAnswer: currentAnswer }),
      });
      syncQuizInState(updatedQuiz);
      state.quizSession.answers[answerKey] = updatedQuiz.submittedAnswer || currentAnswer;
      state.quizSession.revealed[answerKey] = true;
      state.quizSession.results[answerKey] = {
        submittedAnswer: updatedQuiz.submittedAnswer || currentAnswer,
        correct: Boolean(updatedQuiz.correct),
        evaluationFeedback: updatedQuiz.evaluationFeedback || "",
      };
      renderQuizSession();
    } catch (error) {
      feedback.innerHTML = `<div class="feedback-badge wrong">${formatErrorMessage(error, "답안을 저장하지 못했습니다.")}</div>`;
      submitButton.disabled = false;
    }
  });

  prevButton.disabled = currentIndex <= 0;
  prevButton.addEventListener("click", () => {
    if (currentIndex <= 0) {
      return;
    }
    state.quizSession.currentIndex -= 1;
    renderQuizSession();
  });

  nextButton.textContent = currentIndex >= quizzes.length - 1 ? "완료" : "다음";
  nextButton.addEventListener("click", () => {
    if (currentIndex < quizzes.length - 1) {
      state.quizSession.currentIndex += 1;
      renderQuizSession();
      return;
    }
    state.quizSession.completed = true;
    renderQuizSession();
  });

  board.appendChild(fragment);
}

function formatQuizTypeLabel(type) {
  if (type === "ox") return "O/X";
  if (type === "multiple_choice") return "객관식";
  if (type === "short_answer") return "주관식";
  return type || "퀴즈";
}

function formatUnderstandingLevelLabel(level) {
  if (level === "CONCEPT_UNDERSTANDING") return "개념 이해";
  if (level === "CONCEPT_DISTINCTION") return "개념 구분";
  if (level === "CONCEPT_APPLICATION") return "개념 적용";
  return "개념 이해";
}

function isAnswerCorrect(submittedAnswer, correctAnswer) {
  return (submittedAnswer || "").trim().toLowerCase() === (correctAnswer || "").trim().toLowerCase();
}

function renderQuizSummary(board, quizzes) {
  const analysis = buildQuizAnalysis(quizzes);
  const comparison = buildSameDocumentFeedbackComparison(analysis);
  const radarSvg = buildRadarChartSvg(analysis.stageResults);
  const wrongQuestions = analysis.wrongQuestions.map((item) => `
    <li>
      <div>
        <strong>${item.conceptTag}</strong>
        <p>${item.question}</p>
      </div>
      <span>관련 문제 다시 풀기</span>
    </li>
  `).join("");

  board.innerHTML = `
    <article class="quiz-summary-card">
      <div class="quiz-summary-header">
        <div>
          <h2>이해 단계 분석 결과</h2>
          <p class="summary-copy">사용자의 문제 풀이 결과를 바탕으로 이해 단계를 분석했습니다.</p>
        </div>
      </div>

      <section class="summary-top-metrics">
        <div class="summary-metric-card primary">
          <div class="summary-metric-icon">◎</div>
          <div>
            <p>전체 정답률</p>
            <strong>${analysis.accuracy}%</strong>
          </div>
        </div>
        <div class="summary-metric-card">
          <div class="summary-metric-icon neutral">≣</div>
          <div>
            <p>총 ${analysis.totalCount}문제 중 ${analysis.correctCount}문제 정답</p>
          </div>
        </div>
      </section>

      <div class="quiz-summary-grid">
        <section class="summary-panel summary-panel-emphasis">
          <h3>이해 단계 삼각형 분석</h3>
          <div class="summary-radar-wrap">
            <div class="summary-radar-chart">${radarSvg}</div>
            <div class="summary-radar-label top">
              <span>개념 이해</span>
              <strong>${findStageRate(analysis.stageResults, "CONCEPT_UNDERSTANDING")}%</strong>
            </div>
            <div class="summary-radar-label left">
              <span>개념 구분</span>
              <strong>${findStageRate(analysis.stageResults, "CONCEPT_DISTINCTION")}%</strong>
            </div>
            <div class="summary-radar-label right">
              <span>개념 적용</span>
              <strong>${findStageRate(analysis.stageResults, "CONCEPT_APPLICATION")}%</strong>
            </div>
          </div>
          <div class="summary-stage-legend">
            <span><i class="legend-dot good"></i>양호 (70% 이상)</span>
            <span><i class="legend-dot mid"></i>보통 (40% ~ 69%)</span>
            <span><i class="legend-dot low"></i>부족 (40% 미만)</span>
          </div>
        </section>

        <section class="summary-column-stack">
          <section class="summary-panel">
          <h3>부족 개념 TOP 3</h3>
          <ol class="summary-concept-list">
            ${analysis.topConcepts.map((item, index) => `<li><span class="rank-badge rank-${index + 1}">${index + 1}</span><span>${item.name}</span><strong>오답 ${item.wrongCount}회</strong></li>`).join("") || "<li><span class=\"rank-badge rank-1\">1</span><span>반복 오답 개념 없음</span><strong>오답 0회</strong></li>"}
          </ol>
        </section>

          <section class="summary-panel">
            <h3>이해 단계 결과</h3>
            <div class="summary-progress-list">
              ${analysis.stageResults.map((stage) => `
                <div class="summary-progress-item">
                  <div class="summary-progress-head">
                    <div>
                      <strong>${formatUnderstandingLevelLabel(stage.level)}</strong>
                      <p>${describeUnderstandingLevel(stage.level)}</p>
                    </div>
                    <span>${findStageRate(analysis.stageResults, stage.level)}%</span>
                  </div>
                  <div class="summary-progress-bar">
                    <div class="summary-progress-fill ${progressClassName(stage.status)}" style="width: ${findStageRate(analysis.stageResults, stage.level)}%"></div>
                  </div>
                </div>
              `).join("")}
            </div>
          </section>
        </section>
      </div>

      <section class="summary-panel">
        <h3>AI 최종 피드백</h3>
        <p class="summary-copy summary-feedback-copy">${analysis.feedback}</p>
      </section>

      <section class="summary-panel">
        <h3>같은 PDF 풀이 비교</h3>
        ${renderSameDocumentFeedbackComparison(comparison)}
      </section>

      <section class="summary-panel">
        <h3>추천 복습 목록</h3>
        ${wrongQuestions ? `<ul class="summary-review-list">${wrongQuestions}</ul>` : '<p class="summary-copy">틀린 문제가 없습니다.</p>'}
      </section>

      <div class="quiz-summary-actions">
        <button type="button" class="secondary-button quiz-review-wrong-button">틀린 문제 다시 풀기</button>
        <button type="button" class="quiz-back-workspace-button">학습 화면으로</button>
      </div>
    </article>
  `;

  board.querySelector(".quiz-back-workspace-button").addEventListener("click", () => {
    showView("workspace");
  });

  board.querySelector(".quiz-review-wrong-button").addEventListener("click", () => {
    restartWrongQuestions(analysis.wrongQuestions);
  });
}

function buildSameDocumentFeedbackComparison(currentAnalysis) {
  const currentDocumentId = state.quizSession?.documentId;
  const currentQuizSetId = state.quizSession?.quizSetId;
  const previousQuizzes = (state.currentWorkspace?.quizzes || []).filter((quiz) =>
    quiz.documentId === currentDocumentId
      && quiz.quizSetId !== currentQuizSetId
      && quiz.solved,
  );

  if (!currentDocumentId || !previousQuizzes.length) {
    return null;
  }

  const previousCorrectCount = previousQuizzes.filter((quiz) => quiz.correct).length;
  const previousAccuracy = Math.round((previousCorrectCount / previousQuizzes.length) * 100);
  const previousResults = Object.fromEntries(previousQuizzes.map((quiz, index) => [
    String(index),
    {
      submittedAnswer: quiz.submittedAnswer || "",
      correct: Boolean(quiz.correct),
      evaluationFeedback: quiz.evaluationFeedback || "",
    },
  ]));
  const previousAnalysis = buildQuizAnalysis(previousQuizzes, previousResults);
  return {
    previousCount: previousQuizzes.length,
    previousAccuracy,
    currentAccuracy: currentAnalysis.accuracy,
    difference: currentAnalysis.accuracy - previousAccuracy,
    previousFeedback: previousAnalysis.feedback,
    currentFeedback: currentAnalysis.feedback,
  };
}

function renderSameDocumentFeedbackComparison(comparison) {
  if (!comparison) {
    return '<p class="summary-copy">같은 PDF로 완료한 이전 풀이가 없습니다. 다음 풀이부터 이전 결과와 비교합니다.</p>';
  }

  const differenceText = comparison.difference === 0
    ? "변화 없음"
    : `${comparison.difference > 0 ? "+" : ""}${comparison.difference}%p`;
  const comparisonClass = comparison.difference > 0 ? "good" : comparison.difference < 0 ? "low" : "mid";

  return `
    <div class="summary-comparison-grid">
      <div><span>이전 동일 PDF 풀이</span><strong>${comparison.previousAccuracy}%</strong><small>${comparison.previousCount}문제 기준</small></div>
      <div><span>현재 풀이</span><strong>${comparison.currentAccuracy}%</strong><small>현재 퀴즈 세트</small></div>
      <div class="${comparisonClass}"><span>정답률 변화</span><strong>${differenceText}</strong><small>동일 PDF 결과만 비교</small></div>
    </div>
    <div class="summary-feedback-comparison">
      <div><strong>이전 최종 피드백</strong><p>${comparison.previousFeedback}</p></div>
      <div><strong>현재 최종 피드백</strong><p>${comparison.currentFeedback}</p></div>
    </div>
  `;
}

function buildQuizAnalysis(quizzes, results = state.quizSession?.results || {}) {
  const totalCount = quizzes.length;
  const wrongQuestions = [];
  const conceptStats = new Map();
  const stageStats = new Map();
  let correctCount = 0;

  quizzes.forEach((quiz, index) => {
    const result = results[String(index)] || { submittedAnswer: "", correct: false };
    const conceptTag = quiz.conceptTag || "핵심 개념";
    const understandingLevel = quiz.understandingLevel || "CONCEPT_UNDERSTANDING";

    if (result.correct) {
      correctCount += 1;
    } else {
      wrongQuestions.push({
        index,
        question: quiz.question,
        conceptTag,
        understandingLevel,
      });
      conceptStats.set(conceptTag, (conceptStats.get(conceptTag) || 0) + 1);
    }

    const stage = stageStats.get(understandingLevel) || { level: understandingLevel, totalCount: 0, correctCount: 0 };
    stage.totalCount += 1;
    if (result.correct) {
      stage.correctCount += 1;
    }
    stageStats.set(understandingLevel, stage);
  });

  const accuracy = totalCount ? Math.round((correctCount / totalCount) * 100) : 0;
  const topConcepts = Array.from(conceptStats.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([name, wrongCount]) => ({ name, wrongCount }));

  const orderedLevels = ["CONCEPT_UNDERSTANDING", "CONCEPT_DISTINCTION", "CONCEPT_APPLICATION"];
  const stageResults = orderedLevels.map((level) => {
    const stage = stageStats.get(level) || { level, totalCount: 0, correctCount: 0 };
    const rate = stage.totalCount ? stage.correctCount / stage.totalCount : 0;
    return {
      ...stage,
      status: classifyStageStatus(rate, stage.totalCount),
    };
  });

  return {
    totalCount,
    correctCount,
    accuracy,
    topConcepts,
    stageResults,
    wrongQuestions,
    feedback: buildRecommendationText(topConcepts, stageResults),
  };
}

function classifyStageStatus(rate, totalCount) {
  if (!totalCount) return "문제 없음";
  if (rate >= 0.8) return "양호";
  if (rate >= 0.5) return "보통";
  return "부족";
}

function buildRecommendationText(topConcepts, stageResults) {
  const weakestStage = stageResults
    .filter((stage) => stage.totalCount > 0)
    .sort((a, b) => {
      const aRate = a.totalCount ? a.correctCount / a.totalCount : 0;
      const bRate = b.totalCount ? b.correctCount / b.totalCount : 0;
      return aRate - bRate;
    })[0];
  const conceptText = topConcepts.length
    ? topConcepts.map((item) => item.name).join(", ")
    : "반복 오답 개념";

  if (!weakestStage) {
    return "모든 문제를 안정적으로 해결했습니다. 현재 이해 흐름을 유지하면서 새로운 개념으로 확장해도 됩니다.";
  }

  if (weakestStage.level === "CONCEPT_UNDERSTANDING") {
    return `${conceptText}에서 정의와 핵심 특징을 다시 정리할 필요가 있습니다. 용어의 의미를 짧은 문장으로 직접 설명하는 복습이 먼저입니다.`;
  }
  if (weakestStage.level === "CONCEPT_DISTINCTION") {
    return `${conceptText}처럼 비슷한 개념을 구분하는 문제에서 흔들렸습니다. 차이점 비교표나 반례 중심으로 복습하는 편이 맞습니다.`;
  }
  return `${conceptText}의 기본 의미는 알고 있지만 실제 상황에 적용하는 단계가 약합니다. 코드 예시나 사례 문제로 다시 연결하는 복습이 필요합니다.`;
}

function findStageRate(stageResults, level) {
  const stage = stageResults.find((item) => item.level === level);
  if (!stage || !stage.totalCount) {
    return 0;
  }
  return Math.round((stage.correctCount / stage.totalCount) * 100);
}

function describeUnderstandingLevel(level) {
  if (level === "CONCEPT_UNDERSTANDING") return "정의, 특징 이해";
  if (level === "CONCEPT_DISTINCTION") return "유사 개념 구분";
  if (level === "CONCEPT_APPLICATION") return "상황, 예시 적용";
  return "기본 이해";
}

function progressClassName(status) {
  if (status === "양호") return "good";
  if (status === "보통") return "mid";
  return "low";
}

function buildRadarChartSvg(stageResults) {
  const values = [
    findStageRate(stageResults, "CONCEPT_UNDERSTANDING"),
    findStageRate(stageResults, "CONCEPT_APPLICATION"),
    findStageRate(stageResults, "CONCEPT_DISTINCTION"),
  ];
  const centerX = 140;
  const centerY = 130;
  const radius = 92;
  const levels = [0.25, 0.5, 0.75, 1];
  const baseAngles = [-90, 30, 150];

  const polygons = levels.map((ratio) => {
    const points = baseAngles.map((angle) => formatPoint(polarPoint(centerX, centerY, radius * ratio, angle))).join(" ");
    return `<polygon points="${points}" class="radar-grid" />`;
  }).join("");

  const axes = baseAngles.map((angle) => {
    const point = polarPoint(centerX, centerY, radius, angle);
    return `<line x1="${centerX}" y1="${centerY}" x2="${point.x}" y2="${point.y}" class="radar-axis" />`;
  }).join("");

  const dataPoints = values.map((value, index) => polarPoint(centerX, centerY, radius * (value / 100), baseAngles[index]));
  const dataPolygon = dataPoints.map((point) => formatPoint(point)).join(" ");
  const pointDots = dataPoints.map((point) => `<circle cx="${point.x}" cy="${point.y}" r="3.5" class="radar-point" />`).join("");

  return `
    <svg viewBox="0 0 280 240" class="radar-svg" aria-hidden="true">
      ${polygons}
      ${axes}
      <polygon points="${dataPolygon}" class="radar-shape" />
      ${pointDots}
    </svg>
  `;
}

function polarPoint(cx, cy, radius, angleDeg) {
  const angleRad = (Math.PI / 180) * angleDeg;
  const x = cx + (Math.cos(angleRad) * radius);
  const y = cy + (Math.sin(angleRad) * radius);
  return { x: x.toFixed(2), y: y.toFixed(2) };
}

function formatPoint(point) {
  return `${point.x},${point.y}`;
}

function restartWrongQuestions(wrongQuestions) {
  if (!wrongQuestions.length) {
    showView("workspace");
    return;
  }

  wrongQuestions.forEach((item) => {
    const key = String(item.index);
    delete state.quizSession.answers[key];
    delete state.quizSession.revealed[key];
    delete state.quizSession.results[key];
  });

  state.quizSession.currentIndex = wrongQuestions[0].index;
  state.quizSession.completed = false;
  renderQuizSession();
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
      createdAt: matched.createdAt,
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
      createdAt: matched.createdAt,
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
  renderHomeDashboard();
}

async function fetchSessions() {
  state.sessions = await apiFetch(`/api/chat/sessions?userId=${state.userId}`);
  renderSessionList();
}

async function fetchHomeActivityMetrics() {
  const studySessions = state.sessions.filter((session) => (session.type || "STUDY") === "STUDY");
  const results = await Promise.allSettled(
    studySessions.map(async (session) => {
      const [messages, quizzes] = await Promise.all([
        apiFetch(`/api/chat/sessions/${session.id}/messages`),
        apiFetch(`/api/chat/sessions/${session.id}/quizzes`),
      ]);
      return {
        messageCount: messages.length,
        quizCount: quizzes.length,
        messages,
        session,
      };
    }),
  );

  state.homeMessageCount = 0;
  state.homeQuizCount = 0;
  state.homeRecentMessages = [];
  results.forEach((result) => {
    if (result.status !== "fulfilled") return;
    state.homeMessageCount += result.value.messageCount;
    state.homeQuizCount += result.value.quizCount;
    result.value.messages
      .filter((message) => message.role === "USER")
      .forEach((message) => {
        state.homeRecentMessages.push({
          ...message,
          sessionTitle: result.value.session.title,
        });
      });
  });
  state.homeRecentMessages = state.homeRecentMessages
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    .slice(0, 3);
  renderHomeDashboard();
}

async function loadHome() {
  renderHomeHeader();
  await Promise.all([fetchDocumentsCatalog(), fetchSessions()]);
  renderHomeDashboard();
  showView("home");
  void fetchHomeActivityMetrics();
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
      selectedQuizDocumentIds: documents.map((document) => document.documentId),
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
    state.openSessionMenuId = null;
    await fetchSessions();
    await fetchHomeActivityMetrics();
  } catch (error) {
    alert(formatErrorMessage(error, "세션을 삭제하지 못했습니다."));
  }
}

async function renameSession(session) {
  const nextTitle = window.prompt("새 세션 이름을 입력해 주세요.", session.title || "");
  if (nextTitle === null) return;

  try {
    await apiFetch(`/api/chat/sessions/${session.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: nextTitle }),
    });
    state.openSessionMenuId = null;
    await fetchSessions();
    await fetchHomeActivityMetrics();
  } catch (error) {
    alert(formatErrorMessage(error, "세션 이름을 바꾸지 못했습니다."));
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
        createdAt: state.documentsCatalog.find((documentInfo) => documentInfo.id === uploadResponse.documentId)?.createdAt,
      }],
      quizzes: [],
      currentDocumentId: uploadResponse.documentId,
      selectedQuizDocumentIds: [uploadResponse.documentId],
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
      createdAt: new Date().toISOString(),
    });

    state.currentWorkspace.selectedQuizDocumentIds = [
      ...new Set([...getSelectedQuizDocumentIds(), uploadResponse.documentId]),
    ];

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

    const selectedDocumentIds = getSelectedQuizDocumentIds();
    if (selectedDocumentIds.length === 1) {
      payload.documentId = selectedDocumentIds[0];
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

    const documentIds = getSelectedQuizDocumentIds();
    if (!documentIds.length) throw new Error("문제 생성에 사용할 PDF를 하나 이상 체크해 주세요.");
    const documentId = documentIds[0];
    const params = new URLSearchParams({
      type: document.getElementById("quiz-type").value,
      count: document.getElementById("quiz-count").value,
    });
    documentIds.forEach((selectedDocumentId) => params.append("documentIds", selectedDocumentId));

    const response = await apiFetch(
      `/api/rag/generate-questions?${params.toString()}`,
      { method: "POST" },
    );

    const questions = response.questions || [];
    if (!questions.length) throw new Error("생성된 퀴즈가 없습니다.");

    const selectedDocuments = state.currentWorkspace.documents
      .filter((documentInfo) => documentIds.includes(documentInfo.documentId));
    const quizSourceTitle = selectedDocuments.map((documentInfo) => documentInfo.title).join(", ");
    const savedQuizzes = await apiFetch(`/api/chat/sessions/${state.currentSession.id}/quizzes`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        documentId,
        quizSetTitle: `${quizSourceTitle || state.currentSession.title} 퀴즈 ${new Date().toLocaleTimeString("ko-KR")}`,
        questions,
      }),
    });

    state.currentWorkspace.quizzes = [...(state.currentWorkspace.quizzes || []), ...savedQuizzes];
    //state.currentWorkspace.currentDocumentId = documentId;
    renderDocuments();
    renderQuizSets();
  } catch (error) {
    alert(formatErrorMessage(error, "퀴즈를 생성하지 못했습니다."));
  } finally {
    button.disabled = false;
  }
}

async function openQuizSession(quizSet) {
  state.quizSession = buildQuizSessionState(quizSet);
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
document.getElementById("session-search").addEventListener("input", (event) => {
  state.homeSessionSearch = event.currentTarget.value.trim();
  state.homeSessionPage = 1;
  renderSessionList();
});
document.querySelectorAll(".session-filter").forEach((button) => {
  button.addEventListener("click", () => {
    state.homeSessionFilter = button.dataset.sessionFilter;
    state.homeSessionPage = 1;
    document.querySelectorAll(".session-filter").forEach((filterButton) => {
      filterButton.classList.toggle("active", filterButton === button);
    });
    renderSessionList();
  });
});
document.getElementById("show-history-button").addEventListener("click", async () => {
  state.homeSessionFilter = "all";
  state.homeSessionPage = 1;
  document.querySelectorAll(".session-filter").forEach((button) => {
    button.classList.toggle("active", button.dataset.sessionFilter === "all");
  });
  await fetchSessions();
  await fetchHomeActivityMetrics();
});
document.getElementById("refresh-sessions-button").addEventListener("click", async () => {
  await fetchSessions();
  await fetchHomeActivityMetrics();
});
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
  if (!event.target.closest(".session-card") && state.openSessionMenuId) {
    state.openSessionMenuId = null;
    renderSessionList();
  }
});

void initializeApp();
