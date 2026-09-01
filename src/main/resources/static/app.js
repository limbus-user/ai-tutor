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
  examMock: null,
  examMockAttempts: [],
  examMockTimerId: null,
  openQuizSetMenuId: null,
  openDocumentMenuId: null,
  openSessionMenuId: null,
  homeSessionFilter: "recent",
  homeSessionSearch: "",
  homeSessionPage: 1,
  homeQuizCount: 0,
  homeMessageCount: 0,
  homeRecentMessages: [],
  newStudyAnalysis: null,
  newStudyAnalysisOpen: false,
};

const views = {
  auth: document.getElementById("auth-view"),
  home: document.getElementById("home-view"),
  examList: document.getElementById("exam-list-view"),
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
  state.examMock = null;
  state.examMockAttempts = [];
  stopExamMockTimer();
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

async function downloadDocument(documentInfo) {
  try {
    const headers = new Headers();
    if (state.token) {
      headers.set("Authorization", `Bearer ${state.token}`);
    }

    const response = await fetch(`/api/rag/documents/${documentInfo.documentId}/download`, { headers });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `Download failed (${response.status})`);
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = documentInfo.title || documentInfo.storedFileName || "document.pdf";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch (error) {
    alert(formatErrorMessage(error, "PDF download failed."));
  }
}

function renderHomeHeader() {
  document.getElementById("home-user-summary").textContent = state.userName || "사용자";
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
      `${new Date(session.updatedAt).toLocaleString("ko-KR")} 업데이트`;
    const statusElement = fragment.querySelector(".session-status");
    statusElement.textContent = status === "ACTIVE" ? "진행 중" : "완료";
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

  state.homeRecentMessages.slice(0, 3).forEach((message) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "recent-session-item";
    button.innerHTML = `
      <span class="recent-session-icon"><img src="/assets/icons/chat-icon.png" alt="" aria-hidden="true"></span>
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
  document.getElementById("workspace-subtitle").textContent = `${state.userName || "사용자"}님의 학습 워크스페이스`;
  document.getElementById("workspace-status").textContent = (state.currentSession.status || "ACTIVE") === "ACTIVE" ? "진행 중" : "완료";

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

function buildUploadAnalysis(uploadResponse) {
  return {
    documentName: uploadResponse.title || "-",
    chunkCount: uploadResponse.chunkCount ?? 0,
    inferredSubject: uploadResponse.inferredSubject || "-",
    inferredUnit: uploadResponse.inferredUnit || "-",
    recommendedTags: Array.isArray(uploadResponse.recommendedTags) ? uploadResponse.recommendedTags : [],
    documentDifficulty: uploadResponse.documentDifficulty || "-",
    confidence: uploadResponse.confidence || "-",
  };
}

function renderUploadAnalysisCard(documentInfo) {
  const analysis = documentInfo.uploadAnalysis;
  if (!analysis) return null;

  const card = document.createElement("div");
  card.className = "upload-analysis-card";

  const header = document.createElement("div");
  header.className = "upload-analysis-card-header";
  header.innerHTML = "<strong>문서 분석 결과</strong>";
  const headerActions = document.createElement("div");
  headerActions.className = "upload-analysis-header-actions";
  const applyButton = document.createElement("button");
  applyButton.type = "button";
  applyButton.textContent = "입력값 반영";
  applyButton.addEventListener("click", (event) => {
    event.stopPropagation();
    void applyUploadAnalysis(documentInfo, applyButton);
  });
  const status = document.createElement("span");
  status.textContent = documentInfo.analysisStatus || "업로드 완료";
  const closeButton = document.createElement("button");
  closeButton.type = "button";
  closeButton.className = "upload-analysis-close-button";
  closeButton.textContent = "닫기";
  closeButton.addEventListener("click", (event) => {
    event.stopPropagation();
    closeUploadAnalysis(documentInfo.analysisInputScope || "workspace");
  });

  const saveMetadataButton = document.createElement("button");
  saveMetadataButton.type = "button";
  saveMetadataButton.textContent = "정보 수정";
  saveMetadataButton.addEventListener("click", (event) => {
    event.stopPropagation();
    void editDocumentMetadata(documentInfo);
  });

  headerActions.append(applyButton, saveMetadataButton, status, closeButton);
  header.appendChild(headerActions);
  card.appendChild(header);

  const grid = document.createElement("div");
  grid.className = "upload-analysis-grid";
  [
    ["문서명", analysis.documentName || documentInfo.title || "-"],
    ["chunk 수", `${analysis.chunkCount ?? 0}개`],
    ["추정 과목", analysis.inferredSubject || "-"],
    ["추정 단원", analysis.inferredUnit || "-"],
    ["문서 난이도", analysis.documentDifficulty || "-"],
    ["신뢰도", analysis.confidence || "-"],
  ].forEach(([label, value]) => {
    const item = document.createElement("div");
    item.className = "upload-analysis-item";
    const labelElement = document.createElement("span");
    labelElement.textContent = label;
    const valueElement = document.createElement("strong");
    valueElement.textContent = value;
    item.append(labelElement, valueElement);
    grid.appendChild(item);
  });
  card.appendChild(grid);

  const tags = document.createElement("div");
  tags.className = "upload-analysis-tags";
  const tagValues = analysis.recommendedTags?.length ? analysis.recommendedTags : ["추천 태그 없음"];
  tagValues.forEach((value) => {
    const tagChip = document.createElement("span");
    tagChip.className = "upload-analysis-tag-chip";

    const editButton = document.createElement("button");
    editButton.type = "button";
    editButton.className = "upload-analysis-tag-edit";
    editButton.textContent = value;
    editButton.title = "클릭하면 태그를 수정합니다.";
    editButton.addEventListener("click", (event) => {
      event.stopPropagation();
      editUploadAnalysisTag(documentInfo.documentId, value);
    });

    tagChip.appendChild(editButton);
    if (value !== "추천 태그 없음") {
      const deleteButton = document.createElement("button");
      deleteButton.type = "button";
      deleteButton.className = "upload-analysis-tag-delete";
      deleteButton.textContent = "×";
      deleteButton.setAttribute("aria-label", `${value} 태그 삭제`);
      deleteButton.addEventListener("click", (event) => {
        event.stopPropagation();
        removeUploadAnalysisTag(documentInfo.documentId, value);
      });
      tagChip.appendChild(deleteButton);
    }
    tags.appendChild(tagChip);
  });
  card.appendChild(tags);

  const editor = document.createElement("form");
  editor.className = "upload-analysis-tag-editor";
  editor.innerHTML = `
    <input type="text" aria-label="추천 태그 추가" placeholder="태그 추가 또는 수정">
    <button type="submit">추가</button>
  `;
  editor.addEventListener("submit", (event) => {
    event.preventDefault();
    event.stopPropagation();
    const input = editor.querySelector("input");
    addUploadAnalysisTag(documentInfo.documentId, input.value);
    input.value = "";
  });
  card.appendChild(editor);

  return card;
}

function closeUploadAnalysis(scope = "workspace") {
  if (scope === "new-study") {
    state.newStudyAnalysisOpen = false;
    renderNewStudyAnalysis();
    return;
  }

  if (!state.currentWorkspace) return;
  state.currentWorkspace.activeAnalysisDocumentId = null;
  renderLatestUploadAnalysis();
}

function applyUploadAnalysisToInputs(analysis, scope = "workspace") {
  const inputPrefix = scope === "new-study" ? "new-study" : "workspace";
  const subjectInput = document.getElementById(`${inputPrefix}-subject`);
  const unitInput = document.getElementById(`${inputPrefix}-unit`);
  const trustInput = document.getElementById(`${inputPrefix}-trust`);
  if (subjectInput && analysis.inferredSubject && analysis.inferredSubject !== "분류 필요") {
    subjectInput.value = analysis.inferredSubject;
  }
  if (unitInput && analysis.inferredUnit && analysis.inferredUnit !== "-") {
    unitInput.value = analysis.inferredUnit;
  }
  if (trustInput && analysis.confidence && analysis.confidence !== "-") {
    trustInput.value = analysis.confidence;
  }
}

function isUsableAnalysisValue(value, ignoredValues = []) {
  const normalized = String(value || "").trim();
  return normalized && normalized !== "-" && !ignoredValues.includes(normalized);
}

async function applyUploadAnalysis(documentInfo, button) {
  const analysis = documentInfo?.uploadAnalysis;
  if (!analysis) return;

  const scope = documentInfo.analysisInputScope || "workspace";
  applyUploadAnalysisToInputs(analysis, scope);

  if (scope === "new-study" || !documentInfo.documentId || String(documentInfo.documentId).includes("preview")) {
    return;
  }

  const subject = isUsableAnalysisValue(analysis.inferredSubject, ["분류 필요"])
      ? analysis.inferredSubject
      : documentInfo.subject;
  const unitName = isUsableAnalysisValue(analysis.inferredUnit)
      ? analysis.inferredUnit
      : documentInfo.unitName;
  const trustLevel = isUsableAnalysisValue(analysis.confidence)
      ? analysis.confidence
      : documentInfo.trustLevel;

  const previousText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = "반영 중";
  }

  try {
    const updated = await apiFetch(`/api/rag/documents/${documentInfo.documentId}/metadata`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ subject, unitName, trustLevel }),
    });

    await fetchDocumentsCatalog();
    updateCurrentWorkspaceDocumentMetadata(documentInfo.documentId, updated);
    renderDocuments();
  } catch (error) {
    if (button) {
      button.disabled = false;
      button.textContent = previousText;
    }
    alert(formatErrorMessage(error, "분석 결과를 문서 정보에 반영하지 못했습니다."));
  }
}

function updateCurrentWorkspaceDocumentMetadata(documentId, updated) {
  state.currentWorkspace.documents = (state.currentWorkspace?.documents || []).map((document) =>
      document.documentId === documentId
          ? {
            ...document,
            subject: updated.subject,
            unitName: updated.unitName,
            trustLevel: updated.trustLevel,
            uploadAnalysis: document.uploadAnalysis
                ? {
                  ...document.uploadAnalysis,
                  inferredSubject: updated.subject,
                  inferredUnit: updated.unitName,
                  confidence: updated.trustLevel,
                }
                : document.uploadAnalysis,
          }
          : document,
  );
}

function renderNewStudyAnalysis() {
  const panel = document.getElementById("new-study-analysis-panel");
  const button = document.getElementById("new-study-analysis-button");
  if (!panel) return;

  panel.innerHTML = "";
  if (!state.newStudyAnalysis || !state.newStudyAnalysisOpen) {
    panel.classList.add("hidden");
    if (button) button.textContent = state.newStudyAnalysis ? "분석 결과" : "PDF 분석";
    return;
  }

  const card = renderUploadAnalysisCard(state.newStudyAnalysis);
  if (!card) {
    panel.classList.add("hidden");
    if (button) button.textContent = "PDF 분석";
    return;
  }

  panel.classList.remove("hidden");
  panel.appendChild(card);
  if (button) button.textContent = "분석 닫기";
}

function resetNewStudyAnalysis() {
  state.newStudyAnalysis = null;
  state.newStudyAnalysisOpen = false;
  renderNewStudyAnalysis();
}

async function analyzeNewStudyPdf() {
  const button = document.getElementById("new-study-analysis-button");
  const fileInput = document.getElementById("new-study-file");
  if (!fileInput?.files?.[0]) {
    alert("분석할 PDF 파일을 먼저 선택해 주세요.");
    return;
  }

  const analysisForm = new FormData();
  analysisForm.append("file", fileInput.files[0]);
  analysisForm.append("subject", document.getElementById("new-study-subject").value);
  analysisForm.append("unitName", document.getElementById("new-study-unit").value);
  analysisForm.append("trustLevel", document.getElementById("new-study-trust").value);

  if (button) {
    button.disabled = true;
    button.textContent = "분석 중";
  }

  try {
    const response = await apiFetch("/api/rag/analyze-preview", {
      method: "POST",
      body: analysisForm,
    });
    state.newStudyAnalysis = {
      documentId: "new-study-preview",
      title: response.title || fileInput.files[0].name,
      uploadAnalysis: buildUploadAnalysis(response),
      analysisInputScope: "new-study",
      analysisStatus: "미리보기",
    };
    state.newStudyAnalysisOpen = true;
    renderNewStudyAnalysis();
  } catch (error) {
    alert(formatErrorMessage(error, "PDF 분석을 완료하지 못했습니다."));
    renderNewStudyAnalysis();
  } finally {
    if (button) button.disabled = false;
  }
}

function renderLatestUploadAnalysis() {
  const panel = document.getElementById("upload-analysis-panel");
  if (!panel) return;

  const documents = state.currentWorkspace?.documents || [];
  const activeId = state.currentWorkspace?.activeAnalysisDocumentId;
  const documentInfo = documents.find((document) => document.documentId === activeId && document.uploadAnalysis);

  panel.innerHTML = "";
  if (!documentInfo) {
    panel.classList.add("hidden");
    updateAnalysisToggleButton();
    return;
  }

  const card = renderUploadAnalysisCard(documentInfo);
  if (!card) {
    panel.classList.add("hidden");
    updateAnalysisToggleButton();
    return;
  }

  panel.classList.remove("hidden");
  panel.appendChild(card);
  updateAnalysisToggleButton();
}

function updateAnalysisToggleButton() {
  const button = document.getElementById("toggle-upload-analysis-button");
  if (!button) return;

  button.classList.add("hidden");
}

async function showDocumentAnalysis(documentId) {
  if (!state.currentWorkspace) return;

  if (state.currentWorkspace.activeAnalysisDocumentId === documentId) {
    closeUploadAnalysis("workspace");
    return;
  }

  const existing = state.currentWorkspace.documents.find((documentInfo) => documentInfo.documentId === documentId);
  if (!existing) return;

  if (!existing.uploadAnalysis) {
    const response = await apiFetch(`/api/rag/documents/${documentId}/analysis`);
    state.currentWorkspace.documents = state.currentWorkspace.documents.map((documentInfo) =>
      documentInfo.documentId === documentId
        ? { ...documentInfo, uploadAnalysis: buildUploadAnalysis(response) }
        : documentInfo,
    );
  }

  state.currentWorkspace.activeAnalysisDocumentId = documentId;
  state.currentWorkspace.latestUploadAnalysisDocumentId = documentId;
  renderLatestUploadAnalysis();
  document.getElementById("upload-analysis-panel")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function updateUploadAnalysisTags(documentId, updater) {
  if (state.newStudyAnalysis?.documentId === documentId && state.newStudyAnalysis.uploadAnalysis) {
    const currentTags = Array.isArray(state.newStudyAnalysis.uploadAnalysis.recommendedTags)
      ? state.newStudyAnalysis.uploadAnalysis.recommendedTags
      : [];
    state.newStudyAnalysis = {
      ...state.newStudyAnalysis,
      uploadAnalysis: {
        ...state.newStudyAnalysis.uploadAnalysis,
        recommendedTags: updater(currentTags),
      },
    };
    renderNewStudyAnalysis();
    return;
  }

  if (!state.currentWorkspace?.documents) return;
  state.currentWorkspace.documents = state.currentWorkspace.documents.map((documentInfo) => {
    if (documentInfo.documentId !== documentId || !documentInfo.uploadAnalysis) {
      return documentInfo;
    }

    const currentTags = Array.isArray(documentInfo.uploadAnalysis.recommendedTags)
      ? documentInfo.uploadAnalysis.recommendedTags
      : [];
    return {
      ...documentInfo,
      uploadAnalysis: {
        ...documentInfo.uploadAnalysis,
        recommendedTags: updater(currentTags),
      },
    };
  });
  renderDocuments();
  renderLatestUploadAnalysis();
}

function normalizeUploadTag(value) {
  return String(value || "").trim().replace(/\s+/g, " ");
}

function addUploadAnalysisTag(documentId, value) {
  const tag = normalizeUploadTag(value);
  if (!tag) return;

  updateUploadAnalysisTags(documentId, (tags) => [...new Set([...tags, tag])]);
}

function removeUploadAnalysisTag(documentId, value) {
  const tag = normalizeUploadTag(value);
  if (!tag) return;

  updateUploadAnalysisTags(documentId, (tags) => tags.filter((currentTag) => currentTag !== tag));
}

function editUploadAnalysisTag(documentId, currentValue) {
  const normalizedCurrent = normalizeUploadTag(currentValue);
  if (!normalizedCurrent || normalizedCurrent === "추천 태그 없음") return;

  const nextValue = window.prompt("추천 태그를 수정하세요. 비워두면 삭제됩니다.", normalizedCurrent);
  if (nextValue === null) return;

  const normalizedNext = normalizeUploadTag(nextValue);
  updateUploadAnalysisTags(documentId, (tags) => {
    const nextTags = tags.filter((tag) => tag !== normalizedCurrent);
    if (normalizedNext) {
      nextTags.push(normalizedNext);
    }
    return [...new Set(nextTags)];
  });
}

function renderDocuments() {
  const container = document.getElementById("document-list");
  container.innerHTML = "";

  const documents = state.currentWorkspace?.documents || [];
  document.getElementById("uploaded-document-count").textContent = `업로드된 파일 ${documents.length}개`;
  if (!documents.length) {
    container.innerHTML = '<div class="empty-box">업로드된 PDF가 없습니다.</div>';
    renderWorkspaceHeader();
    renderLatestUploadAnalysis();
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
    const trailing = fragment.querySelector(".document-row-trailing");
    const analysisButton = document.createElement("button");
    analysisButton.type = "button";
    analysisButton.className = "document-analysis-button";
    analysisButton.textContent = "분석";
    analysisButton.addEventListener("click", (event) => {
      event.stopPropagation();
      void showDocumentAnalysis(documentInfo.documentId);
    });
    trailing.insertBefore(analysisButton, moreButton);

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
      void downloadDocument(documentInfo);
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
  renderLatestUploadAnalysis();
  renderQuizDocumentSelection();
}

async function editDocumentMetadata(documentInfo) {
  if (!documentInfo?.documentId || String(documentInfo.documentId).includes("preview")) {
    alert("미리보기 분석 결과는 저장할 수 없습니다. PDF를 업로드한 뒤 수정해 주세요.");
    return;
  }

  const currentAnalysis = documentInfo.uploadAnalysis || {};

  const subject = window.prompt(
      "과목을 입력해 주세요.",
      documentInfo.subject || currentAnalysis.inferredSubject || "",
  );
  if (subject === null) return;

  const unitName = window.prompt(
      "단원을 입력해 주세요.",
      documentInfo.unitName || currentAnalysis.inferredUnit || "",
  );
  if (unitName === null) return;

  const trustLevel = window.prompt(
      "신뢰도를 입력해 주세요.",
      documentInfo.trustLevel || currentAnalysis.confidence || "",
  );
  if (trustLevel === null) return;

  try {
    const updated = await apiFetch(`/api/rag/documents/${documentInfo.documentId}/metadata`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        subject,
        unitName,
        trustLevel,
      }),
    });

    await fetchDocumentsCatalog();

    updateCurrentWorkspaceDocumentMetadata(documentInfo.documentId, updated);

    alert("문서 정보가 수정되었습니다.");
    renderDocuments();
    renderLatestUploadAnalysis();
  } catch (error) {
    alert(formatErrorMessage(error, "문서 정보를 수정하지 못했습니다."));
  }
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
            uploadAnalysis: document.uploadAnalysis,
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
    container.innerHTML = '<div class="empty-box chat-empty-space" aria-hidden="true"></div>';
    return;
  }

  state.currentMessages.forEach((message) => {
    const fragment = messageTemplate.content.cloneNode(true);
    const card = fragment.querySelector(".message-bubble");
    const isUser = message.role === "USER";
    card.classList.add(isUser ? "user" : "assistant");
    fragment.querySelector(".message-role").textContent = isUser ? "나" : "학습 도우미";
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
        sourceDocumentIds: getQuizSourceDocumentIds(quiz),
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
    sourceDocumentIds: normalizeDocumentIdSet(quizSet.sourceDocumentIds || getQuizSourceDocumentIds(questions[0])),
    title: quizSet.quizSetTitle,
    questions,
    currentIndex: firstUnsolvedIndex >= 0 ? firstUnsolvedIndex : 0,
    answers,
    revealed,
    results,
    retryQuestionIndexes: [],
    completed: questions.length > 0 && questions.every((quiz) => quiz.solved),
  };
}

const EXAM_MOCK_IDS = ["it-engineer-20220424", "it-engineer-20220305", "it-engineer-20210814"];

function buildExamMockSessionState(exam) {
  const quizSetId = exam.quizSetId || "it-engineer-20220424";
  const questions = (exam.questions || []).map((question, index) => ({
    id: `exam-${question.questionNo || index + 1}`,
    order: question.questionNo || index + 1,
    type: question.type || "multiple_choice",
    question: question.question,
    choices: question.choices || [],
    correctAnswer: question.correctAnswer,
    correctChoiceNo: question.correctChoiceNo,
    modelAnswer: question.correctAnswer,
    explanation: question.explanation || `${question.correctChoiceNo}번이 정답입니다.`,
    sourceEvidence: question.subjectName || exam.examName,
    difficulty: "medium",
    conceptTag: question.subjectName || "정보처리기사",
    understandingLevel: question.understandingLevel || "CONCEPT_APPLICATION",
    mediaUrls: question.mediaUrls || [],
  }));

  return {
    sessionId: "mock",
    quizSetId,
    documentId: null,
    sourceDocumentIds: [],
    title: exam.examName || "정보처리기사 모의고사",
    questions,
    currentIndex: 0,
    answers: {},
    revealed: {},
    results: {},
    retryQuestionIndexes: [],
    completed: false,
    examMock: true,
    flagged: {},
    startedAt: Date.now(),
    durationSeconds: 150 * 60,
  };
}

function buildExamMockResult(quiz, submittedAnswer) {
  const submittedChoiceNo = getExamSubmittedChoiceNo(quiz, submittedAnswer);
  const correctChoiceNo = Number(quiz.correctChoiceNo) || getExamSubmittedChoiceNo(quiz, quiz.correctAnswer);
  const correct = Boolean(submittedChoiceNo && correctChoiceNo && submittedChoiceNo === correctChoiceNo);
  const submittedChoiceText = submittedChoiceNo ? quiz.choices?.[submittedChoiceNo - 1] : submittedAnswer;
  return {
    submittedAnswer: submittedChoiceNo ? String(submittedChoiceNo) : submittedAnswer,
    correct,
    evaluationFeedback: correct
      ? "정답입니다."
      : `오답입니다. 내 답은 ${submittedChoiceNo ? `${submittedChoiceNo}번` : submittedChoiceText || "미응답"}이고, 정답은 ${correctChoiceNo ? `${correctChoiceNo}번, ` : ""}${quiz.correctAnswer}입니다.`,
  };
}

function getExamSubmittedChoiceNo(quiz, submittedAnswer) {
  const answer = String(submittedAnswer || "").trim();
  if (!answer) {
    return 0;
  }
  if (/^[1-4]$/.test(answer)) {
    return Number(answer);
  }
  const index = (quiz.choices || []).findIndex((choice) => String(choice || "").trim() === answer);
  return index >= 0 ? index + 1 : 0;
}

function isExamImageChoice(choice) {
  return /^\[\s*이미지\s*보기\s*\]$/.test(String(choice || "").trim());
}

function getExamMediaLayout(quiz) {
  const mediaUrls = Array.isArray(quiz.mediaUrls) ? quiz.mediaUrls : [];
  const choices = Array.isArray(quiz.choices) ? quiz.choices : [];
  const imageChoiceCount = choices.filter(isExamImageChoice).length;
  if (!imageChoiceCount || mediaUrls.length <= 1) {
    return { questionMediaUrls: mediaUrls, choiceMediaUrls: [] };
  }

  const choiceMediaUrls = new Array(choices.length).fill(null);
  const firstChoiceMediaIndex = Math.max(0, mediaUrls.length - imageChoiceCount);
  let mediaIndex = firstChoiceMediaIndex;
  choices.forEach((choice, choiceIndex) => {
    if (isExamImageChoice(choice) && mediaIndex < mediaUrls.length) {
      choiceMediaUrls[choiceIndex] = mediaUrls[mediaIndex];
      mediaIndex += 1;
    }
  });
  return {
    questionMediaUrls: mediaUrls.slice(0, firstChoiceMediaIndex),
    choiceMediaUrls,
  };
}

function appendExamImages(container, mediaUrls, altPrefix) {
  (mediaUrls || []).filter(Boolean).forEach((mediaUrl, index) => {
    const image = document.createElement("img");
    image.src = mediaUrl;
    image.alt = `${altPrefix} ${index + 1}`;
    image.loading = "lazy";
    container.appendChild(image);
  });
}

function renderExamOptionContent(container, choice, mediaUrl, choiceNo) {
  container.textContent = "";
  if (mediaUrl) {
    const image = document.createElement("img");
    image.src = mediaUrl;
    image.alt = `${choiceNo}번 선택지`;
    image.loading = "lazy";
    image.className = "exam-option-image";
    container.appendChild(image);
    return;
  }
  container.textContent = choice;
}

async function reportExamQuestionIssue(quiz, currentIndex, mode = "solving") {
  const promptMessage = [
    "어떤 오류인지 적어주세요.",
    "예: 이미지가 안 보임, 정답이 틀림, 해설이 이상함, 문제 문구가 깨짐",
  ].join("\n");
  const detail = window.prompt(promptMessage, "");
  if (detail === null) {
    return;
  }

  const normalizedDetail = detail.trim();
  if (normalizedDetail.length < 5) {
    alert("오류 내용을 5자 이상 입력해 주세요.");
    return;
  }

  const answerKey = String(currentIndex);
  const selectedAnswer = state.quizSession?.answers?.[answerKey] || "";
  const selectedChoiceNo = getExamSubmittedChoiceNo(quiz, selectedAnswer);
  const message = [
    normalizedDetail,
    "",
    "[자동 수집 정보]",
    `화면: 모의고사 ${mode}`,
    `시험 ID: ${state.quizSession?.quizSetId || "-"}`,
    `시험명: ${state.quizSession?.title || "-"}`,
    `문항: ${quiz.questionNo || quiz.order || currentIndex + 1}번`,
    `과목: ${quiz.subjectNo || "-"} / ${quiz.subjectName || "-"}`,
    `선택 답: ${selectedChoiceNo ? `${selectedChoiceNo}번` : "미선택"}`,
    `정답: ${quiz.correctChoiceNo || "-"}번`,
    `문제: ${quiz.question || ""}`,
    `선택지: ${(quiz.choices || []).map((choice, index) => `${index + 1}. ${choice}`).join(" | ")}`,
    `이미지: ${(quiz.mediaUrls || []).join(", ") || "-"}`,
    `URL: ${window.location.href}`,
    `시각: ${new Date().toISOString()}`,
  ].join("\n");

  try {
    await apiFetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: state.currentUser?.email || "",
        category: "exam-question-bug",
        message,
      }),
    });
    alert("오류 제보가 접수되었습니다.");
  } catch (error) {
    alert(formatErrorMessage(error, "오류 제보를 보내지 못했습니다."));
  }
}

function loadExamMockAttempts() {
  return Array.isArray(state.examMockAttempts) ? state.examMockAttempts : [];
}

async function fetchExamMockAttempts() {
  if (!state.token) {
    state.examMockAttempts = [];
    return [];
  }
  const attemptGroups = await Promise.all(
    EXAM_MOCK_IDS.map((quizSetId) => apiFetch(`/api/exam-mocks/${quizSetId}/attempts`).catch(() => [])),
  );
  state.examMockAttempts = attemptGroups.flat().filter(Boolean);
  return state.examMockAttempts;
}

async function saveExamMockAttempt() {
  const session = state.quizSession;
  if (!session?.examMock) {
    return;
  }

  const attemptId = session.examAttemptId || `${session.quizSetId || "exam"}-${Date.now()}`;
  const attempt = {
    attemptId,
    quizSetId: session.quizSetId,
    quizSetTitle: session.title,
    createdAt: new Date().toISOString(),
    questions: (session.questions || []).map((quiz, index) => ({
      id: quiz.id || index,
      question: quiz.question || "",
      conceptTag: quiz.conceptTag || quiz.subjectName || "정보처리기사",
      understandingLevel: quiz.understandingLevel || "CONCEPT_APPLICATION",
    })),
      results: session.results || {},
    };
  const savedAttempt = await apiFetch(`/api/exam-mocks/${session.quizSetId}/attempts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(attempt),
  });
  const attempts = loadExamMockAttempts().filter((item) => item.attemptId !== savedAttempt.attemptId);
  attempts.unshift(savedAttempt);
  state.examMockAttempts = attempts.slice(0, 10);
  session.examAttemptId = attemptId;
}

async function openItEngineerMockExam(quizSetId = "it-engineer-20220424") {
  try {
    const [exam] = await Promise.all([
      apiFetch(`/api/exam-mocks/${quizSetId}`),
      fetchExamMockAttempts(),
    ]);
    state.examMock = exam;
    state.quizSession = buildExamMockSessionState(exam);
    startExamMockTimer();
    renderQuizSession();
    showView("quiz");
  } catch (error) {
    alert(formatErrorMessage(error, "정보처리기사 모의고사를 불러오지 못했습니다."));
  }
}

async function openExamList() {
  stopExamMockTimer();
  state.quizSession = null;
  document.getElementById("quiz-view")?.classList.remove("exam-mock-active");
  restoreQuizTopActions();
  try {
    await fetchExamMockAttempts();
  } catch (error) {
    state.examMockAttempts = [];
  }
  renderExamListStats();
  showView("examList");
}

function renderExamListStats() {
  EXAM_MOCK_IDS.forEach((quizSetId) => {
    const shortId = quizSetId.replace("it-engineer-", "");
    const attempts = loadExamMockAttempts().filter((attempt) => attempt.quizSetId === quizSetId);
    const countTarget = document.getElementById(`exam-${shortId}-attempt-count`);
    const scoreTarget = document.getElementById(`exam-${shortId}-last-score`);
    if (countTarget) {
      countTarget.textContent = `풀이 기록 ${attempts.length}회`;
    }
    if (scoreTarget) {
      if (!attempts.length) {
        scoreTarget.textContent = "최근 점수 없음";
        return;
      }
      const latest = buildQuizSetSummary(attempts[0].questions || [], {
        results: attempts[0].results || {},
      });
      scoreTarget.textContent = `최근 ${latest.accuracy}% (${latest.correctCount}/${latest.totalCount})`;
    }
  });
}

function startExamMockTimer() {
  stopExamMockTimer();
  state.examMockTimerId = window.setInterval(updateExamMockTimer, 1000);
}

function stopExamMockTimer() {
  if (state.examMockTimerId) {
    window.clearInterval(state.examMockTimerId);
    state.examMockTimerId = null;
  }
}

function getExamAnsweredCount() {
  return Object.values(state.quizSession?.answers || {}).filter((answer) => String(answer || "").trim()).length;
}

function getExamRemainingSeconds() {
  const session = state.quizSession;
  if (!session?.startedAt || !session?.durationSeconds) {
    return 0;
  }
  const elapsedSeconds = Math.floor((Date.now() - session.startedAt) / 1000);
  return Math.max(0, session.durationSeconds - elapsedSeconds);
}

function formatExamTime(seconds) {
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(remainingSeconds).padStart(2, "0")}`;
}

function updateExamMockTimer() {
  const timer = document.getElementById("exam-mock-timer");
  if (!timer || !state.quizSession?.examMock) {
    return;
  }
  timer.textContent = formatExamTime(getExamRemainingSeconds());
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

function renderExamMockSession(board, quizzes) {
  document.getElementById("quiz-view")?.classList.add("exam-mock-active");
  const currentIndex = Math.min(state.quizSession?.currentIndex || 0, quizzes.length - 1);
  const quiz = quizzes[currentIndex];
  const answerKey = String(currentIndex);
  const selectedAnswer = state.quizSession.answers[answerKey] || "";
  const answeredCount = getExamAnsweredCount();
  const retryIndexes = state.quizSession.retryQuestionIndexes || [];
  const retryPosition = retryIndexes.indexOf(currentIndex);
  const isExamRetryMode = retryIndexes.length > 0;
  const disablePrev = isExamRetryMode ? retryPosition <= 0 : currentIndex <= 0;
  const disableNext = isExamRetryMode ? retryPosition < 0 || retryPosition >= retryIndexes.length - 1 : currentIndex >= quizzes.length - 1;

  document.getElementById("quiz-session-title").textContent = state.quizSession.title || "정보처리기사 모의고사";
  document.getElementById("quiz-session-subtitle").textContent = `CBT · ${quizzes.length}문항`;

  board.innerHTML = `
    <div class="exam-mock-shell">
      <section class="exam-mock-main">
        <article class="exam-question-card">
          <div class="exam-question-head">
            <div>
              <span class="exam-subject-pill">${quiz.subjectName || `${quiz.subjectNo || ""}과목`}</span>
              <span class="exam-question-no">${quiz.order || currentIndex + 1}번</span>
            </div>
            <button type="button" class="exam-report-button">오류 제보</button>
          </div>
          <h2 class="exam-question-title"></h2>
          <div class="exam-media-slot"></div>
          <div class="exam-options"></div>
        </article>
        <div class="exam-bottom-bar">
          <button type="button" class="exam-check-toggle ${state.quizSession.flagged?.[answerKey] ? "active" : ""}">
            체크
          </button>
          <span>${isExamRetryMode ? `복습 ${retryPosition + 1} / ${retryIndexes.length}` : `문항 ${currentIndex + 1} / ${quizzes.length}`}</span>
          <div class="exam-nav-actions">
            <button type="button" class="secondary-button exam-prev-button" ${disablePrev ? "disabled" : ""}>이전</button>
            <button type="button" class="exam-next-button" ${disableNext ? "disabled" : ""}>다음</button>
          </div>
        </div>
      </section>
      <aside class="exam-omr-panel">
        <div class="exam-omr-head">
          <strong>답안지 · OMR</strong>
          <span>${answeredCount}/${quizzes.length}</span>
        </div>
        <div class="exam-legend">
          <span><i class="answered"></i>선택</span>
          <span><i class="flagged"></i>체크</span>
          <span><i></i>미응답</span>
        </div>
        <div class="exam-omr-list"></div>
        <button type="button" class="exam-submit-bottom">${isExamRetryMode ? "복습 답안 제출" : "답안 제출"}</button>
      </aside>
    </div>
  `;

  board.querySelector(".exam-question-title").textContent = quiz.question || "";

  const mediaLayout = getExamMediaLayout(quiz);
  const mediaSlot = board.querySelector(".exam-media-slot");
  appendExamImages(mediaSlot, mediaLayout.questionMediaUrls, `${quiz.order || currentIndex + 1}번 문제 자료`);

  const options = board.querySelector(".exam-options");
  (quiz.choices || []).forEach((choice, choiceIndex) => {
    const choiceNo = choiceIndex + 1;
    const optionValue = String(choiceNo);
    const selected = getExamSubmittedChoiceNo(quiz, selectedAnswer) === choiceNo;
    const label = document.createElement("label");
    label.className = `exam-option-card${selected ? " selected" : ""}`;
    label.innerHTML = `
      <input type="radio" name="exam-current" value="${optionValue}">
      <span class="exam-option-no">${choiceNo}</span>
      <span class="exam-option-text"></span>
    `;
    label.querySelector("input").checked = selected;
    renderExamOptionContent(label.querySelector(".exam-option-text"), choice, mediaLayout.choiceMediaUrls[choiceIndex], choiceNo);
    label.addEventListener("click", () => {
      state.quizSession.answers[answerKey] = optionValue;
      renderQuizSession();
    });
    options.appendChild(label);
  });

  renderExamOmr(board.querySelector(".exam-omr-list"), quizzes);
  bindExamMockControls(board, currentIndex, quizzes);
  renderExamTopActions();
  updateExamMockTimer();
}

function renderExamMockReviewSession(board, quizzes) {
  document.getElementById("quiz-view")?.classList.add("exam-mock-active");
  const currentIndex = Math.min(state.quizSession?.currentIndex || 0, quizzes.length - 1);
  const quiz = quizzes[currentIndex];
  const answerKey = String(currentIndex);
  const result = state.quizSession.results[answerKey] || {};
  const selectedAnswer = result.submittedAnswer || state.quizSession.answers[answerKey] || "";
  const correctAnswer = quiz.correctAnswer || "";
  const selectedChoiceNo = getExamSubmittedChoiceNo(quiz, selectedAnswer);
  const correctChoiceNo = Number(quiz.correctChoiceNo) || getExamSubmittedChoiceNo(quiz, correctAnswer);
  const correctCount = quizzes.filter((item, index) => state.quizSession.results[String(index)]?.correct).length;

  document.getElementById("quiz-session-title").textContent = state.quizSession.title || "정보처리기사 모의고사";
  document.getElementById("quiz-session-subtitle").textContent = `해설 보기 · ${correctCount}/${quizzes.length} 정답`;

  board.innerHTML = `
    <div class="exam-mock-shell exam-review-shell">
      <section class="exam-mock-main">
        <article class="exam-question-card exam-review-card">
          <div class="exam-question-head">
            <div>
              <span class="exam-subject-pill">${quiz.subjectName || `${quiz.subjectNo || ""}과목`}</span>
              <span class="exam-question-no">${quiz.order || currentIndex + 1}번</span>
            </div>
            <button type="button" class="exam-report-button">오류 제보</button>
          </div>
          <h2 class="exam-question-title"></h2>
          <div class="exam-media-slot"></div>
          <div class="exam-options"></div>
          <div class="exam-review-feedback ${result.correct ? "correct" : "wrong"}">
            <strong>${result.correct ? "정답" : "오답"}${selectedChoiceNo ? ` · 내 답 ${selectedChoiceNo}번` : " · 미응답"} · 정답 ${correctChoiceNo || "-"}번</strong>
            <p>${result.evaluationFeedback || ""}</p>
            <p><b>해설</b> ${quiz.explanation || `${correctChoiceNo || "-"}번 선택지가 정답입니다. 선택지와 문제 조건을 다시 비교해 보세요.`}</p>
          </div>
        </article>
        <div class="exam-bottom-bar">
          <button type="button" class="secondary-button exam-summary-button">결과 요약</button>
          <span>문항 ${currentIndex + 1} / ${quizzes.length}</span>
          <div class="exam-nav-actions">
            <button type="button" class="secondary-button exam-prev-button" ${currentIndex <= 0 ? "disabled" : ""}>이전</button>
            <button type="button" class="exam-next-button" ${currentIndex >= quizzes.length - 1 ? "disabled" : ""}>다음</button>
          </div>
        </div>
      </section>
      <aside class="exam-omr-panel">
        <div class="exam-omr-head">
          <strong>채점표 · OMR</strong>
          <span>${correctCount}/${quizzes.length}</span>
        </div>
        <div class="exam-legend">
          <span><i class="correct"></i>정답</span>
          <span><i class="wrong"></i>오답</span>
          <span><i></i>현재</span>
        </div>
        <div class="exam-omr-list"></div>
        <button type="button" class="exam-submit-bottom exam-summary-button">결과 요약 보기</button>
      </aside>
    </div>
  `;

  board.querySelector(".exam-question-title").textContent = quiz.question || "";

  const mediaLayout = getExamMediaLayout(quiz);
  const mediaSlot = board.querySelector(".exam-media-slot");
  appendExamImages(mediaSlot, mediaLayout.questionMediaUrls, `${quiz.order || currentIndex + 1}번 문제 자료`);

  const options = board.querySelector(".exam-options");
  (quiz.choices || []).forEach((choice, choiceIndex) => {
    const choiceNo = choiceIndex + 1;
    const selected = getExamSubmittedChoiceNo(quiz, selectedAnswer) === choiceNo;
    const correct = correctChoiceNo === choiceNo;
    const label = document.createElement("div");
    label.className = [
      "exam-option-card",
      "exam-review-option",
      selected ? "selected" : "",
      correct ? "correct-answer" : "",
      selected && !correct ? "wrong-answer" : "",
    ].filter(Boolean).join(" ");
    label.innerHTML = `
      <span class="exam-option-no">${choiceNo}</span>
      <span class="exam-option-text"></span>
    `;
    renderExamOptionContent(label.querySelector(".exam-option-text"), choice, mediaLayout.choiceMediaUrls[choiceIndex], choiceNo);
    options.appendChild(label);
  });

  renderExamReviewOmr(board.querySelector(".exam-omr-list"), quizzes);
  bindExamReviewControls(board, currentIndex, quizzes);
  renderExamReviewTopActions();
}

function renderExamOmr(container, quizzes) {
  container.innerHTML = "";
  const retryIndexes = state.quizSession?.retryQuestionIndexes || [];
  const groups = new Map();
  quizzes.forEach((quiz, index) => {
    const key = `${quiz.subjectNo || 0}|${quiz.subjectName || "기타"}`;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push({ quiz, index });
  });

  groups.forEach((items, key) => {
    const [, subjectName] = key.split("|");
    const section = document.createElement("section");
    section.className = "exam-omr-subject";
    section.innerHTML = `<h3>${items[0].quiz.subjectNo || ""}과목 · ${subjectName}</h3><div class="exam-omr-grid"></div>`;
    const grid = section.querySelector(".exam-omr-grid");
    items.forEach(({ quiz, index }) => {
      const answer = state.quizSession.answers[String(index)] || "";
      const flagged = Boolean(state.quizSession.flagged?.[String(index)]);
      const disabledByRetry = retryIndexes.length > 0 && !retryIndexes.includes(index);
      const button = document.createElement("button");
      button.type = "button";
      button.disabled = disabledByRetry;
      button.className = [
        "exam-omr-button",
        index === state.quizSession.currentIndex ? "current" : "",
        answer ? "answered" : "",
        flagged ? "flagged" : "",
        disabledByRetry ? "disabled" : "",
      ].filter(Boolean).join(" ");
      button.innerHTML = `<span>${quiz.order || index + 1}</span><strong>${answer ? (getExamSubmittedChoiceNo(quiz, answer) || "-") : "-"}</strong>`;
      button.addEventListener("click", () => {
        if (disabledByRetry) {
          return;
        }
        state.quizSession.currentIndex = index;
        renderQuizSession();
      });
      grid.appendChild(button);
    });
    container.appendChild(section);
  });
}

function renderExamReviewOmr(container, quizzes) {
  container.innerHTML = "";
  const groups = new Map();
  quizzes.forEach((quiz, index) => {
    const key = `${quiz.subjectNo || 0}|${quiz.subjectName || "기타"}`;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push({ quiz, index });
  });

  groups.forEach((items, key) => {
    const [, subjectName] = key.split("|");
    const section = document.createElement("section");
    section.className = "exam-omr-subject";
    section.innerHTML = `<h3>${items[0].quiz.subjectNo || ""}과목 · ${subjectName}</h3><div class="exam-omr-grid"></div>`;
    const grid = section.querySelector(".exam-omr-grid");
    items.forEach(({ quiz, index }) => {
      const result = state.quizSession.results[String(index)] || {};
      const button = document.createElement("button");
      button.type = "button";
      button.className = [
        "exam-omr-button",
        "reviewed",
        index === state.quizSession.currentIndex ? "current" : "",
        result.correct ? "correct" : "wrong",
      ].filter(Boolean).join(" ");
      button.innerHTML = `<span>${quiz.order || index + 1}</span><strong>${result.correct ? "O" : "X"}</strong>`;
      button.addEventListener("click", () => {
        state.quizSession.currentIndex = index;
        renderQuizSession();
      });
      grid.appendChild(button);
    });
    container.appendChild(section);
  });
}

function bindExamMockControls(board, currentIndex, quizzes) {
  board.querySelector(".exam-prev-button")?.addEventListener("click", () => {
    const retryIndexes = state.quizSession?.retryQuestionIndexes || [];
    if (retryIndexes.length) {
      const retryPosition = retryIndexes.indexOf(currentIndex);
      if (retryPosition > 0) {
        state.quizSession.currentIndex = retryIndexes[retryPosition - 1];
        renderQuizSession();
      }
      return;
    }
    if (currentIndex > 0) {
      state.quizSession.currentIndex = currentIndex - 1;
      renderQuizSession();
    }
  });
  board.querySelector(".exam-next-button")?.addEventListener("click", () => {
    const retryIndexes = state.quizSession?.retryQuestionIndexes || [];
    if (retryIndexes.length) {
      const retryPosition = retryIndexes.indexOf(currentIndex);
      if (retryPosition >= 0 && retryPosition < retryIndexes.length - 1) {
        state.quizSession.currentIndex = retryIndexes[retryPosition + 1];
        renderQuizSession();
      }
      return;
    }
    if (currentIndex < quizzes.length - 1) {
      state.quizSession.currentIndex = currentIndex + 1;
      renderQuizSession();
    }
  });
  board.querySelector(".exam-check-toggle")?.addEventListener("click", () => {
    const key = String(currentIndex);
    state.quizSession.flagged[key] = !state.quizSession.flagged[key];
    renderQuizSession();
  });
  board.querySelector(".exam-report-button")?.addEventListener("click", () => {
    void reportExamQuestionIssue(quizzes[currentIndex], currentIndex, "풀이 중");
  });
  board.querySelector(".exam-submit-bottom")?.addEventListener("click", submitExamMock);
}

function bindExamReviewControls(board, currentIndex, quizzes) {
  board.querySelector(".exam-prev-button")?.addEventListener("click", () => {
    if (currentIndex > 0) {
      state.quizSession.currentIndex = currentIndex - 1;
      renderQuizSession();
    }
  });
  board.querySelector(".exam-next-button")?.addEventListener("click", () => {
    if (currentIndex < quizzes.length - 1) {
      state.quizSession.currentIndex = currentIndex + 1;
      renderQuizSession();
    }
  });
  board.querySelectorAll(".exam-summary-button").forEach((button) => {
    button.addEventListener("click", () => renderQuizSummary(board, quizzes));
  });
  board.querySelector(".exam-report-button")?.addEventListener("click", () => {
    void reportExamQuestionIssue(quizzes[currentIndex], currentIndex, "해설 보기");
  });
}

function renderExamTopActions() {
  const actions = document.querySelector("#quiz-view .topbar-actions");
  if (!actions || !state.quizSession?.examMock) {
    return;
  }
  actions.innerHTML = `
    <button id="exam-mock-exit-button" type="button" class="secondary-button">나가기</button>
    <span class="exam-top-timer" id="exam-mock-timer">--:--</span>
    <span class="exam-top-progress">진행 ${getExamAnsweredCount()} / ${state.quizSession.questions.length}</span>
    <button id="exam-mock-submit-button" type="button">제출하기</button>
  `;
  document.getElementById("exam-mock-submit-button")?.addEventListener("click", submitExamMock);
  document.getElementById("exam-mock-exit-button")?.addEventListener("click", handleBackFromQuiz);
}

function renderExamReviewTopActions() {
  const actions = document.querySelector("#quiz-view .topbar-actions");
  if (!actions || !state.quizSession?.examMock) {
    return;
  }
  actions.innerHTML = `
    <button id="exam-mock-exit-button" type="button" class="secondary-button">나가기</button>
    <span class="exam-top-progress">해설 보기 ${state.quizSession.currentIndex + 1} / ${state.quizSession.questions.length}</span>
    <button id="exam-summary-top-button" type="button">결과 요약</button>
  `;
  document.getElementById("exam-summary-top-button")?.addEventListener("click", () => {
    renderQuizSummary(document.getElementById("quiz-session-board"), state.quizSession?.questions || []);
  });
  document.getElementById("exam-mock-exit-button")?.addEventListener("click", handleBackFromQuiz);
}

function restoreQuizTopActions() {
  const actions = document.querySelector("#quiz-view .topbar-actions");
  if (!actions || actions.querySelector("#back-workspace-button")) {
    return;
  }
  actions.innerHTML = `
    <button id="back-workspace-button" type="button" class="secondary-button">학습 화면으로</button>
    <button id="quiz-logout-button" type="button" class="secondary-button">로그아웃</button>
  `;
  document.getElementById("quiz-logout-button").addEventListener("click", logoutToAuth);
  document.getElementById("back-workspace-button").addEventListener("click", handleBackFromQuiz);
}

function handleBackFromQuiz() {
  if (state.quizSession?.examMock) {
    stopExamMockTimer();
    state.quizSession = null;
    document.getElementById("quiz-view")?.classList.remove("exam-mock-active");
    restoreQuizTopActions();
    renderExamListStats();
    showView("examList");
    return;
  }

  renderWorkspaceHeader();
  renderDocuments();
  renderMessages();
  renderQuizSets();
  showView("workspace");
}

async function submitExamMock() {
  const quizzes = state.quizSession?.questions || [];
  const retryIndexes = state.quizSession?.retryQuestionIndexes || [];
  const targetIndexes = retryIndexes.length ? retryIndexes : quizzes.map((_, index) => index);
  const unanswered = targetIndexes.filter((index) => !String(state.quizSession.answers[String(index)] || "").trim()).length;
  if (unanswered > 0 && !window.confirm(`미응답 ${unanswered}문항이 있습니다. 그대로 제출할까요?`)) {
    return;
  }

  targetIndexes.forEach((index) => {
    const quiz = quizzes[index];
    const key = String(index);
    const submittedAnswer = state.quizSession.answers[key] || "";
    state.quizSession.revealed[key] = true;
    state.quizSession.results[key] = submittedAnswer
      ? buildExamMockResult(quiz, submittedAnswer)
      : {
          submittedAnswer: "",
          correct: false,
          evaluationFeedback: `미응답입니다. 정답은 ${quiz.correctChoiceNo ? `${quiz.correctChoiceNo}번, ` : ""}${quiz.correctAnswer}입니다.`,
        };
  });
  try {
    await saveExamMockAttempt();
  } catch (error) {
    alert(formatErrorMessage(error, "모의고사 풀이 기록을 저장하지 못했습니다."));
    return;
  }
  state.quizSession.completed = true;
  state.quizSession.retryQuestionIndexes = [];
  state.quizSession.currentIndex = quizzes.findIndex((quiz, index) => !state.quizSession.results[String(index)]?.correct);
  if (state.quizSession.currentIndex < 0) {
    state.quizSession.currentIndex = 0;
  }
  stopExamMockTimer();
  renderQuizSession();
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
  if (!state.quizSession?.examMock) {
    document.getElementById("quiz-view")?.classList.remove("exam-mock-active");
    restoreQuizTopActions();
  }
  document.getElementById("quiz-session-title").textContent = state.quizSession?.title || "퀴즈 세션";
  document.getElementById("quiz-session-subtitle").textContent = state.quizSession?.examMock
    ? `정보처리기사 필기 · ${quizzes.length}문제`
    : `학습 퀴즈 · ${quizzes.length}문제`;

  if (!quizzes.length) {
    board.innerHTML = '<div class="empty-box">선택된 퀴즈가 없습니다.</div>';
    return;
  }

  if (state.quizSession?.completed) {
    if (state.quizSession?.examMock) {
      renderExamMockReviewSession(board, quizzes);
      return;
    }
    document.getElementById("quiz-view")?.classList.remove("exam-mock-active");
    restoreQuizTopActions();
    renderQuizSummary(board, quizzes);
    return;
  }

  if (state.quizSession?.examMock) {
    renderExamMockSession(board, quizzes);
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
  const mediaUrls = Array.isArray(quiz.mediaUrls) ? quiz.mediaUrls : [];
  if (mediaUrls.length) {
    const mediaWrap = document.createElement("div");
    mediaWrap.className = "exam-question-media";
    mediaUrls.forEach((mediaUrl) => {
      const image = document.createElement("img");
      image.src = mediaUrl;
      image.alt = `${quiz.order || currentIndex + 1}번 문제 자료`;
      image.loading = "lazy";
      mediaWrap.appendChild(image);
    });
    fragment.querySelector(".quiz-question").after(mediaWrap);
  }
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
      ${savedResult?.evaluationFeedback ? `<div class="feedback-detail evaluation-feedback">${savedResult.evaluationFeedback}</div>` : ""}
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
      if (state.quizSession?.examMock) {
        const result = buildExamMockResult(quiz, currentAnswer);
        state.quizSession.answers[answerKey] = result.submittedAnswer;
        state.quizSession.revealed[answerKey] = true;
        state.quizSession.results[answerKey] = result;
        renderQuizSession();
        return;
      }

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
    if (!state.quizSession.revealed?.[answerKey]) {
      feedback.innerHTML = '<div class="feedback-badge wrong">정답 확인 후 다음 문제로 이동할 수 있습니다.</div>';
      return;
    }
    const nextRetryIndex = findNextRetryQuestionIndex(currentIndex);
    if (nextRetryIndex !== null) {
      state.quizSession.currentIndex = nextRetryIndex;
      renderQuizSession();
      return;
    }
    if (isRetryModeActive()) {
      state.quizSession.completed = true;
      state.quizSession.retryQuestionIndexes = [];
      renderQuizSession();
      return;
    }
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
  const isExamMockSummary = Boolean(state.quizSession?.examMock);
  const analysis = buildQuizAnalysis(quizzes, state.quizSession?.results || {}, {
    topConceptLimit: isExamMockSummary ? 5 : 3,
  });
  const comparisonSets = isExamMockSummary
    ? buildExamMockComparisonSummaries(analysis)
    : buildSameDocumentQuizSetSummaries(analysis);
  const radarSvg = buildRadarChartSvg(analysis.stageResults);
  const topConceptHeading = isExamMockSummary ? "오답 과목" : "부족 개념 TOP 3";
  const topConceptEmptyHtml = isExamMockSummary
    ? '<li><span class="rank-badge rank-1">1</span><span>오답 과목 없음</span><strong>0문제 오답</strong></li>'
    : '<li><span class="rank-badge rank-1">1</span><span>반복 오답 개념 없음</span><strong>오답 0개</strong></li>';
  const formatTopConceptCount = (item) => isExamMockSummary ? `${item.wrongCount}문제 오답` : `오답 ${item.wrongCount}개`;
  const examReviewAction = isExamMockSummary
    ? '<button type="button" class="secondary-button exam-review-return-button">해설 다시 보기</button>'
    : "";
  const backButtonLabel = isExamMockSummary ? "기출문제로 이동" : "학습 화면으로 이동";
  const reviewQuestionButtonLabel = isExamMockSummary ? "이 문제만 다시풀기" : "다시풀기";
  const comparisonEyebrow = isExamMockSummary ? "Mock Exam History" : "Feedback Compare";
  const comparisonHeading = isExamMockSummary ? "모의고사 풀이 기록 비교" : "최종 피드백 비교";
  const comparisonCopy = isExamMockSummary
    ? "현재 모의고사 결과와 이전 풀이 기록의 점수, 오답 과목, 피드백을 비교합니다."
    : "같은 PDF에서 생성된 다른 퀴즈 결과와 현재 결과를 비교합니다.";
  const comparisonButtonLabel = isExamMockSummary ? "기록 비교 열기" : "비교 화면 열기";
  const comparisonSmallText = comparisonSets.length > 1
    ? `비교 가능한 결과 ${comparisonSets.length}개`
    : (isExamMockSummary ? "저장된 이전 풀이 기록 없음" : "비교 가능한 이전 결과 없음");
  const wrongQuestions = analysis.wrongQuestions.map((item) => `
    <li data-review-index="${item.index}">
      <span class="review-book-icon" aria-hidden="true">${bookIconSvg()}</span>
      <div>
        <strong>${item.conceptTag}</strong>
        <p>${item.question}</p>
      </div>
      <button type="button" class="text-button review-question-button" data-review-index="${item.index}">${reviewQuestionButtonLabel}</button>
    </li>
  `).join("");

  board.innerHTML = `
    <article class="quiz-summary-card quiz-result-dashboard">
      <div class="quiz-summary-header">
        <div>
          <p class="eyebrow">Quiz Result</p>
          <h2>이번 퀴즈 결과</h2>
          <p class="summary-copy">현재 퀴즈 세트의 정답률, 이해 단계, 부족 개념, 복습 대상을 한 화면에서 확인합니다.</p>
        </div>
      </div>

      <section class="summary-top-metrics">
        <div class="summary-metric-card primary">
          <div class="summary-metric-icon">%</div>
          <div>
            <p>전체 정답률</p>
            <strong>${analysis.accuracy}%</strong>
          </div>
        </div>
        <div class="summary-metric-card">
          <div class="summary-metric-icon neutral">✓</div>
          <div>
            <p>총 ${analysis.totalCount}문제 중</p>
            <strong>${analysis.correctCount}문제</strong>
            <small>정답</small>
          </div>
        </div>
      </section>

      <div class="quiz-summary-grid dashboard-grid">
        <section class="summary-panel summary-panel-emphasis dashboard-radar-panel">
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
            <span><i class="legend-dot good"></i>양호</span>
            <span><i class="legend-dot mid"></i>보통</span>
            <span><i class="legend-dot low"></i>부족</span>
          </div>
        </section>

        <section class="summary-column-stack">
          <section class="summary-panel dashboard-compact-panel">
            <h3>${topConceptHeading}</h3>
            <ol class="summary-concept-list">
              ${analysis.topConcepts.map((item, index) => `<li><span class="rank-badge rank-${index + 1}">${index + 1}</span><span>${item.name}</span><strong>${formatTopConceptCount(item)}</strong></li>`).join("") || topConceptEmptyHtml}
            </ol>
          </section>

          <section class="summary-panel dashboard-compact-panel">
            <h3>이해 단계별 결과</h3>
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


        <section class="summary-panel dashboard-compact-panel dashboard-review-panel">
          <h3>추천 복습 목록</h3>
          ${wrongQuestions ? `<ul class="summary-review-list">${wrongQuestions}</ul>` : '<p class="summary-copy">틀린 문제가 없습니다. 현재 개념 흐름을 유지하며 다음 단원으로 넘어가도 됩니다.</p>'}
        </section>

        <section class="summary-panel ai-final-feedback-card">
          <div class="ai-final-feedback-head">
            <span>OK</span>
            <div>
              <h3>최종 학습 피드백</h3>
              <p>이번 퀴즈 기준으로 다음 학습 우선순위를 제안합니다.</p>
            </div>
          </div>
          <p class="summary-copy summary-feedback-copy">${analysis.feedback}</p>
        </section>
        <section class="summary-panel comparison-entry-card">
          <div>
            <p class="eyebrow">${comparisonEyebrow}</p>
            <h3>${comparisonHeading}</h3>
            <p class="summary-copy">${comparisonCopy}</p>
          </div>
          <button type="button" class="quiz-open-comparison-button">${comparisonButtonLabel}</button>
          <small>${comparisonSmallText}</small>
        </section>
      </div>


      <div class="quiz-summary-actions">
        ${examReviewAction}
        <button type="button" class="secondary-button quiz-reset-all-button">문제들 다시풀기</button>
        <button type="button" class="secondary-button quiz-review-wrong-button">틀린 문제 다시 보기</button>
        <button type="button" class="quiz-back-workspace-button">${backButtonLabel}</button>
      </div>
    </article>
  `;

  board.querySelector(".quiz-back-workspace-button").addEventListener("click", () => {
    if (state.quizSession?.examMock) {
      handleBackFromQuiz();
      return;
    }
    showView("workspace");
  });
  board.querySelector(".exam-review-return-button")?.addEventListener("click", () => {
    state.quizSession.currentIndex = 0;
    renderExamMockReviewSession(board, quizzes);
  });

  board.querySelector(".quiz-review-wrong-button").addEventListener("click", () => {
    void restartWrongQuestions(analysis.wrongQuestions);
  });

  board.querySelector(".quiz-reset-all-button").addEventListener("click", () => {
    void resetCurrentQuizSet();
  });

  board.querySelector(".quiz-open-comparison-button").addEventListener("click", () => {
    if (state.quizSession?.examMock) {
      renderExamMockComparisonScreen(board, analysis);
      return;
    }
    renderQuizComparisonScreen(board, analysis);
  });

  board.querySelectorAll(".review-question-button").forEach((button) => {
    button.addEventListener("click", () => {
      const index = Number(button.dataset.reviewIndex);
      const wrongQuestion = analysis.wrongQuestions.find((item) => item.index === index);
      if (wrongQuestion) {
        void restartWrongQuestions([wrongQuestion]);
      }
    });
  });
}

function renderQuizComparisonScreen(board, currentAnalysis = buildQuizAnalysis(state.quizSession?.questions || [])) {
  const comparisonSets = buildSameDocumentQuizSetSummaries(currentAnalysis);
  const current = comparisonSets.find((item) => item.isCurrent) || buildQuizSetSummary(state.quizSession?.questions || [], {
    quizSetId: state.quizSession?.quizSetId,
    quizSetTitle: state.quizSession?.title,
    isCurrent: true,
    results: state.quizSession?.results || {},
  });
  const previousSets = comparisonSets.filter((item) => !item.isCurrent);
  const defaultPrevious = previousSets[0] || null;

  board.innerHTML = `
    <article class="quiz-summary-card quiz-comparison-screen">
      <div class="quiz-summary-header comparison-screen-header">
        <div>
          <p class="eyebrow">Quiz Compare</p>
          <h2>같은 PDF 퀴즈 결과 비교</h2>
          <p class="summary-copy">현재 결과와 같은 PDF에서 생성된 완료 퀴즈 결과를 비교합니다.</p>
        </div>
        <button type="button" class="secondary-button quiz-back-result-button">결과 화면으로 돌아가기</button>
      </div>

      <div class="comparison-layout">
        <section class="summary-panel comparison-list-panel">
          <h3>같은 PDF에서 생성된 퀴즈 결과 목록</h3>
          <div class="comparison-result-list">
            ${comparisonSets.map((item) => `
              <button type="button" class="comparison-result-item ${item.isCurrent ? "current" : ""}" data-quiz-set-id="${item.quizSetId}">
                <span>${item.isCurrent ? "현재" : "이전"}</span>
                <strong>${item.quizSetTitle || "퀴즈 세트"}</strong>
                <small>${item.accuracy}% · ${item.correctCount}/${item.totalCount} 정답</small>
              </button>
            `).join("") || '<p class="summary-copy">같은 PDF의 완료된 퀴즈 결과가 없습니다.</p>'}
          </div>
        </section>

        <section class="summary-panel comparison-detail-panel">
          <h3>현재 결과 vs 이전 결과 비교</h3>
          <div id="comparison-detail-content">
            ${renderComparisonDetail(current, defaultPrevious)}
          </div>
        </section>
      </div>
    </article>
  `;

  board.querySelector(".quiz-back-result-button").addEventListener("click", () => {
    renderQuizSummary(board, state.quizSession?.questions || []);
  });

  board.querySelectorAll(".comparison-result-item").forEach((button) => {
    button.addEventListener("click", () => {
      const selected = comparisonSets.find((item) => item.quizSetId === button.dataset.quizSetId);
      if (!selected?.isCurrent) {
        board.querySelector("#comparison-detail-content").innerHTML = renderComparisonDetail(current, selected);
      }
      board.querySelectorAll(".comparison-result-item").forEach((item) => item.classList.remove("selected"));
      button.classList.add("selected");
    });
  });

  const firstPreviousButton = Array.from(board.querySelectorAll(".comparison-result-item"))
    .find((button) => button.dataset.quizSetId !== String(state.quizSession?.quizSetId));
  firstPreviousButton?.classList.add("selected");
}

function renderExamMockComparisonScreen(board, currentAnalysis = buildQuizAnalysis(state.quizSession?.questions || [])) {
  const comparisonSets = buildExamMockComparisonSummaries(currentAnalysis);
  const current = comparisonSets.find((item) => item.isCurrent) || buildQuizSetSummary(state.quizSession?.questions || [], {
    quizSetId: state.quizSession?.examAttemptId || state.quizSession?.quizSetId,
    quizSetTitle: state.quizSession?.title,
    isCurrent: true,
    results: state.quizSession?.results || {},
  });
  const previousSets = comparisonSets.filter((item) => !item.isCurrent);
  const defaultPrevious = previousSets[0] || null;
  const detailHtml = defaultPrevious
    ? renderComparisonDetail(current, defaultPrevious)
    : `<div class="empty-box">저장된 이전 모의고사 풀이 기록이 없습니다. 같은 회차를 한 번 더 풀면 이 화면에서 점수와 오답 과목 변화를 비교할 수 있습니다.</div>${renderComparisonColumn("현재 결과", current)}`;

  board.innerHTML = `
    <article class="quiz-summary-card quiz-comparison-screen">
      <div class="quiz-summary-header comparison-screen-header">
        <div>
          <p class="eyebrow">Mock Exam Compare</p>
          <h2>모의고사 풀이 기록 비교</h2>
          <p class="summary-copy">현재 정보처리기사 모의고사 결과와 내 계정에 저장된 이전 풀이 기록을 비교합니다.</p>
        </div>
        <button type="button" class="secondary-button quiz-back-result-button">결과 화면으로 돌아가기</button>
      </div>

      <div class="comparison-layout">
        <section class="summary-panel comparison-list-panel">
          <h3>저장된 모의고사 풀이 기록</h3>
          <div class="comparison-result-list">
            ${comparisonSets.map((item) => `
              <div class="comparison-result-item comparison-result-item-with-action ${item.isCurrent ? "current" : ""}" role="button" tabindex="0" data-quiz-set-id="${item.quizSetId}">
                <div>
                  <span>${item.isCurrent ? "현재" : "이전"} · ${formatDateTimeLabel(item.createdAt)}</span>
                  <strong>${item.quizSetTitle || "정보처리기사 모의고사"}</strong>
                  <small>${item.accuracy}% · ${item.correctCount}/${item.totalCount} 정답</small>
                </div>
                ${item.isCurrent ? "" : `<button type="button" class="comparison-attempt-delete-button" data-attempt-id="${item.quizSetId}">삭제</button>`}
              </div>
            `).join("")}
          </div>
        </section>

        <section class="summary-panel comparison-detail-panel">
          <h3>현재 결과 vs 이전 풀이 비교</h3>
          <div id="comparison-detail-content">${detailHtml}</div>
        </section>
      </div>
    </article>
  `;

  board.querySelector(".quiz-back-result-button").addEventListener("click", () => {
    renderQuizSummary(board, state.quizSession?.questions || []);
  });

  board.querySelectorAll(".comparison-result-item").forEach((itemElement) => {
    const selectAttempt = () => {
      const selected = comparisonSets.find((item) => item.quizSetId === itemElement.dataset.quizSetId);
      if (!selected?.isCurrent) {
        board.querySelector("#comparison-detail-content").innerHTML = renderComparisonDetail(current, selected);
      }
      board.querySelectorAll(".comparison-result-item").forEach((item) => item.classList.remove("selected"));
      itemElement.classList.add("selected");
    };
    itemElement.addEventListener("click", (event) => {
      if (event.target.closest(".comparison-attempt-delete-button")) {
        return;
      }
      selectAttempt();
    });
    itemElement.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectAttempt();
      }
    });
  });

  board.querySelectorAll(".comparison-attempt-delete-button").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      void deleteExamMockAttempt(button.dataset.attemptId);
    });
  });

  const firstPreviousButton = Array.from(board.querySelectorAll(".comparison-result-item"))
    .find((itemElement) => itemElement.dataset.quizSetId !== String(current.quizSetId));
  firstPreviousButton?.classList.add("selected");
}

async function deleteExamMockAttempt(attemptId) {
  if (!attemptId) {
    return;
  }
  const confirmed = window.confirm("이 모의고사 풀이 기록을 삭제할까요?");
  if (!confirmed) {
    return;
  }

  try {
    const quizSetId = state.quizSession?.quizSetId || "it-engineer-20220424";
    await apiFetch(`/api/exam-mocks/${quizSetId}/attempts/${encodeURIComponent(attemptId)}`, {
      method: "DELETE",
    });
    state.examMockAttempts = loadExamMockAttempts().filter((attempt) => attempt.attemptId !== attemptId);
    renderExamMockComparisonScreen(
      document.getElementById("quiz-session-board"),
      buildQuizAnalysis(state.quizSession?.questions || [], state.quizSession?.results || {}, { topConceptLimit: 5 }),
    );
  } catch (error) {
    alert(formatErrorMessage(error, "모의고사 풀이 기록을 삭제하지 못했습니다."));
  }
}

function renderComparisonDetail(current, previous) {
  if (!previous) {
    return `
      <div class="empty-box">같은 PDF에서 완료된 이전 퀴즈 결과가 없습니다. 다른 퀴즈를 풀면 이 화면에서 정답률, 이해 단계, 부족 개념, 최종 피드백을 비교할 수 있습니다.</div>
      ${renderComparisonColumn("현재 결과", current)}
    `;
  }

  return `
    <div class="comparison-vs-grid">
      ${renderComparisonColumn("현재 결과", current)}
      ${renderComparisonColumn("이전 결과", previous)}
    </div>
    <section class="comparison-radar-compare">
      <div>
        <strong>이해 단계 삼각형 비교</strong>
        <p>현재 결과와 이전 결과의 단계별 정답률을 같은 삼각형 위에 겹쳐서 봅니다.</p>
      </div>
      <div class="comparison-radar-compare-chart">
        ${buildRadarComparisonSvg(current.stageResults || [], previous.stageResults || [])}
      </div>
      <div class="comparison-radar-legend">
        <span><i class="current"></i>현재</span>
        <span><i class="previous"></i>이전</span>
      </div>
    </section>
    <div class="comparison-delta-grid">
      ${renderDeltaCard("전체 정답률", current.accuracy, previous.accuracy)}
      ${renderDeltaCard("개념 이해", stageRateFromSummary(current, "CONCEPT_UNDERSTANDING"), stageRateFromSummary(previous, "CONCEPT_UNDERSTANDING"))}
      ${renderDeltaCard("개념 구분", stageRateFromSummary(current, "CONCEPT_DISTINCTION"), stageRateFromSummary(previous, "CONCEPT_DISTINCTION"))}
      ${renderDeltaCard("개념 적용", stageRateFromSummary(current, "CONCEPT_APPLICATION"), stageRateFromSummary(previous, "CONCEPT_APPLICATION"))}
    </div>
  `;
}

function renderComparisonColumn(label, summary) {
  const isExamMockComparison = Boolean(state.quizSession?.examMock);
  const topConceptLabel = isExamMockComparison ? "오답 과목" : "부족 개념 TOP";
  const feedbackHeading = isExamMockComparison ? "모의고사 피드백" : "최종 학습 피드백 요약";
  const feedbackLabel = isExamMockComparison ? "현재 풀이 결과 기준 피드백" : "현재 결과에서 나온 최종 피드백";
  return `
    <section class="comparison-column ${summary?.isCurrent ? "current" : ""}">
      <p>${label}</p>
      ${summary?.isCurrent ? `<div class="current-result-badge">현재 결과 기준 · 정답률 ${summary?.accuracy ?? 0}%</div>` : ""}
      <h4>${summary?.quizSetTitle || "퀴즈 세트"}</h4>
      <div class="comparison-score"><strong>${summary?.accuracy ?? 0}%</strong><span>${summary?.correctCount ?? 0}/${summary?.totalCount ?? 0} 정답</span></div>
      <div class="comparison-stage-mini">
        ${["CONCEPT_UNDERSTANDING", "CONCEPT_DISTINCTION", "CONCEPT_APPLICATION"].map((level) =>
          renderComparisonStageGauge(
            formatUnderstandingLevelLabel(level),
            stageRateFromSummary(summary, level),
            summary?.isCurrent ? "current" : "previous"
          )
        ).join("")}
      </div>
      <div class="comparison-top-concepts">
        <strong>${topConceptLabel}</strong>
        <p>${formatTopConcepts(summary?.topConcepts || [], isExamMockComparison)}</p>
      </div>
      <div class="comparison-feedback-summary">
        <strong>${feedbackHeading}</strong>
        ${summary?.isCurrent ? `<span class="current-feedback-label">${feedbackLabel}</span>` : ""}
        <p>${formatComparisonFeedback(summary?.feedback || "")}</p>
      </div>
    </section>
  `;
}

function renderComparisonStageGauge(label, rate, variant) {
  const safeRate = Math.max(0, Math.min(100, Number(rate) || 0));
  return `
    <div class="comparison-stage-gauge ${variant}">
      <div class="comparison-stage-gauge-head">
        <span>${label}</span>
        <strong>${safeRate}%</strong>
      </div>
      <div class="comparison-stage-gauge-track" aria-hidden="true">
        <i style="width: ${safeRate}%"></i>
      </div>
    </div>
  `;
}

function renderDeltaCard(label, currentValue, previousValue) {
  const diff = currentValue - previousValue;
  const diffText = diff === 0 ? "변화 없음" : `${diff > 0 ? "+" : ""}${diff}%p`;
  const diffClass = diff > 0 ? "good" : diff < 0 ? "low" : "mid";
  return `
    <div class="comparison-delta-card ${diffClass}">
      <span>${label}</span>
      <strong>${diffText}</strong>
      <small>현재 ${currentValue}% · 이전 ${previousValue}%</small>
    </div>
  `;
}

function buildExamMockComparisonSummaries(currentAnalysis) {
  const currentAttemptId = state.quizSession?.examAttemptId || state.quizSession?.quizSetId || "current-exam";
  const summaries = loadExamMockAttempts()
    .filter((attempt) => attempt.quizSetId === state.quizSession?.quizSetId)
    .map((attempt) => buildQuizSetSummary(attempt.questions || [], {
      quizSetId: attempt.attemptId,
      quizSetTitle: attempt.quizSetTitle,
      createdAt: attempt.createdAt,
      isCurrent: attempt.attemptId === state.quizSession?.examAttemptId,
      results: attempt.results || {},
      topConceptLimit: 5,
    }));

  if (!summaries.some((item) => item.isCurrent)) {
    summaries.unshift({
      ...currentAnalysis,
      quizSetId: currentAttemptId,
      quizSetTitle: state.quizSession?.title,
      sourceDocumentIds: [],
      isCurrent: true,
      createdAt: new Date().toISOString(),
    });
  }

  return summaries.sort((a, b) => {
    if (a.isCurrent) return -1;
    if (b.isCurrent) return 1;
    return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
  });
}

function formatDateTimeLabel(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function buildSameDocumentQuizSetSummaries(currentAnalysis) {
  const currentDocumentId = state.quizSession?.documentId;
  const currentQuizSetId = state.quizSession?.quizSetId;
  const currentSourceDocumentIds = normalizeDocumentIdSet(state.quizSession?.sourceDocumentIds || [currentDocumentId]);
  const currentSourceKey = documentSetKey(currentSourceDocumentIds);
  const allQuizzes = state.currentWorkspace?.quizzes || [];
  const map = new Map();

  allQuizzes
    .filter((quiz) => quiz.solved && documentSetKey(getQuizSourceDocumentIds(quiz)) === currentSourceKey)
    .forEach((quiz) => {
      if (!map.has(quiz.quizSetId)) {
        map.set(quiz.quizSetId, {
          quizSetId: quiz.quizSetId,
          quizSetTitle: quiz.quizSetTitle,
          sourceDocumentIds: getQuizSourceDocumentIds(quiz),
          createdAt: quiz.createdAt,
          questions: [],
        });
      }
      map.get(quiz.quizSetId).questions.push(quiz);
    });

  const summaries = Array.from(map.values()).map((quizSet) => buildQuizSetSummary(quizSet.questions, {
    quizSetId: quizSet.quizSetId,
    quizSetTitle: quizSet.quizSetTitle,
    createdAt: quizSet.createdAt,
    sourceDocumentIds: quizSet.sourceDocumentIds,
    isCurrent: quizSet.quizSetId === currentQuizSetId,
  }));

  if (!summaries.some((item) => item.quizSetId === currentQuizSetId)) {
    summaries.push({
      ...currentAnalysis,
      quizSetId: currentQuizSetId,
      quizSetTitle: state.quizSession?.title,
      sourceDocumentIds: currentSourceDocumentIds,
      isCurrent: true,
      createdAt: new Date().toISOString(),
    });
  }

  return summaries.sort((a, b) => {
    if (a.isCurrent) return -1;
    if (b.isCurrent) return 1;
    return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
  });
}

function buildQuizSetSummary(quizzes, options = {}) {
  const results = options.results || Object.fromEntries((quizzes || []).map((quiz, index) => [
    String(index),
    {
      submittedAnswer: quiz.submittedAnswer || "",
      correct: Boolean(quiz.correct),
      evaluationFeedback: quiz.evaluationFeedback || "",
    },
  ]));
  const analysis = buildQuizAnalysis(quizzes || [], results, {
    topConceptLimit: options.topConceptLimit,
  });
  return {
    ...analysis,
    quizSetId: options.quizSetId,
    quizSetTitle: options.quizSetTitle,
    sourceDocumentIds: normalizeDocumentIdSet(options.sourceDocumentIds || getQuizSourceDocumentIds((quizzes || [])[0])),
    createdAt: options.createdAt,
    isCurrent: Boolean(options.isCurrent),
  };
}

function getQuizSourceDocumentIds(quiz) {
  if (!quiz) {
    return [];
  }
  const inferredDocumentIds = inferQuizSourceDocumentIdsFromTitle(quiz.quizSetTitle);
  if (Array.isArray(quiz.sourceDocumentIds) && quiz.sourceDocumentIds.length) {
    const explicitDocumentIds = normalizeDocumentIdSet(quiz.sourceDocumentIds);
    return inferredDocumentIds.length > explicitDocumentIds.length ? inferredDocumentIds : explicitDocumentIds;
  }
  return inferredDocumentIds.length ? inferredDocumentIds : normalizeDocumentIdSet([quiz.documentId]);
}

function normalizeDocumentIdSet(documentIds) {
  return [...new Set((documentIds || [])
    .map((documentId) => Number(documentId))
    .filter((documentId) => Number.isFinite(documentId) && documentId > 0))]
    .sort((a, b) => a - b);
}

function documentSetKey(documentIds) {
  return normalizeDocumentIdSet(documentIds).join(",");
}

function inferQuizSourceDocumentIdsFromTitle(quizSetTitle) {
  const title = quizSetTitle || "";
  if (!title) {
    return [];
  }
  const matchedDocumentIds = (state.currentWorkspace?.documents || [])
    .filter((documentInfo) => {
      const candidates = [documentInfo.title, documentInfo.storedFileName]
        .filter(Boolean)
        .map((value) => String(value).trim())
        .filter(Boolean);
      return candidates.some((candidate) => title.includes(candidate));
    })
    .map((documentInfo) => documentInfo.documentId);
  return normalizeDocumentIdSet(matchedDocumentIds);
}

function stageRateFromSummary(summary, level) {
  return findStageRate(summary?.stageResults || [], level);
}

function formatTopConcepts(topConcepts, isExamMock = false) {
  return topConcepts.length
    ? topConcepts.map((item, index) => `${index + 1}. ${item.name} (${isExamMock ? `${item.wrongCount}문제 오답` : item.wrongCount})`).join(" · ")
    : (isExamMock ? "오답 과목 없음" : "부족 개념 없음");
}

function summarizeFeedback(feedback) {
  const normalized = (feedback || "").replace(/\s+/g, " ").trim();
  return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized || "피드백 없음";
}

function formatComparisonFeedback(feedback) {
  return (feedback || "").replace(/\s+/g, " ").trim() || "피드백 없음";
}

function bookIconSvg() {
  return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4.5A2.5 2.5 0 0 1 7.5 2H20v17H7.5A2.5 2.5 0 0 0 5 21.5Z"/><path d="M5 4.5v17A2.5 2.5 0 0 1 7.5 19H20"/><path d="M9 6h7"/></svg>`;
}

function buildQuizAnalysis(quizzes, results = state.quizSession?.results || {}, options = {}) {
  const totalCount = quizzes.length;
  const wrongQuestions = [];
  const conceptStats = new Map();
  const stageStats = new Map();
  let correctCount = 0;
  const topConceptLimit = options.topConceptLimit || 3;

  quizzes.forEach((quiz, index) => {
    const result = results[String(index)] || { submittedAnswer: "", correct: false };
    const conceptTag = quiz.conceptTag || "핵심 개념";
    const understandingLevel = quiz.understandingLevel || "CONCEPT_UNDERSTANDING";

    if (result.correct) {
      correctCount += 1;
    } else {
      wrongQuestions.push({
        index,
        quizId: quiz.id,
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
    .slice(0, topConceptLimit)
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

function buildRadarComparisonSvg(currentStageResults, previousStageResults) {
  const currentValues = [
    findStageRate(currentStageResults, "CONCEPT_UNDERSTANDING"),
    findStageRate(currentStageResults, "CONCEPT_APPLICATION"),
    findStageRate(currentStageResults, "CONCEPT_DISTINCTION"),
  ];
  const previousValues = [
    findStageRate(previousStageResults, "CONCEPT_UNDERSTANDING"),
    findStageRate(previousStageResults, "CONCEPT_APPLICATION"),
    findStageRate(previousStageResults, "CONCEPT_DISTINCTION"),
  ];
  const centerX = 140;
  const centerY = 128;
  const radius = 88;
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
  const currentPoints = currentValues.map((value, index) => polarPoint(centerX, centerY, radius * (value / 100), baseAngles[index]));
  const previousPoints = previousValues.map((value, index) => polarPoint(centerX, centerY, radius * (value / 100), baseAngles[index]));
  const labels = [
    { x: 140, y: 20, text: `이해 ${currentValues[0]}% / ${previousValues[0]}%` },
    { x: 232, y: 186, text: `적용 ${currentValues[1]}% / ${previousValues[1]}%` },
    { x: 48, y: 186, text: `구분 ${currentValues[2]}% / ${previousValues[2]}%` },
  ].map((label) => `<text x="${label.x}" y="${label.y}" text-anchor="middle" class="radar-compare-label">${label.text}</text>`).join("");

  return `
    <svg viewBox="0 0 280 230" class="radar-svg radar-comparison-svg" aria-hidden="true">
      ${polygons}
      ${axes}
      <polygon points="${previousPoints.map((point) => formatPoint(point)).join(" ")}" class="radar-shape radar-shape-previous" />
      <polygon points="${currentPoints.map((point) => formatPoint(point)).join(" ")}" class="radar-shape radar-shape-current" />
      ${previousPoints.map((point) => `<circle cx="${point.x}" cy="${point.y}" r="3" class="radar-point radar-point-previous" />`).join("")}
      ${currentPoints.map((point) => `<circle cx="${point.x}" cy="${point.y}" r="3.5" class="radar-point radar-point-current" />`).join("")}
      ${labels}
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

function reviewWrongQuestions(wrongQuestions) {
  if (!wrongQuestions.length) {
    showView("workspace");
    return;
  }

  state.quizSession.currentIndex = wrongQuestions[0].index;
  state.quizSession.completed = false;
  renderQuizSession();
}

function isRetryModeActive() {
  return Array.isArray(state.quizSession?.retryQuestionIndexes)
    && state.quizSession.retryQuestionIndexes.length > 0;
}

function isRetryModeComplete() {
  if (!isRetryModeActive()) {
    return false;
  }
  return state.quizSession.retryQuestionIndexes.every((index) =>
    Boolean(state.quizSession.revealed?.[String(index)]),
  );
}

function findNextRetryQuestionIndex(currentIndex) {
  if (!isRetryModeActive()) {
    return null;
  }
  const nextIndex = state.quizSession.retryQuestionIndexes
    .filter((index) => index > currentIndex)
    .find((index) => !state.quizSession.revealed?.[String(index)]);
  return nextIndex ?? null;
}

async function restartWrongQuestions(wrongQuestions) {
  if (!wrongQuestions.length) {
    showView(state.quizSession?.examMock ? "home" : "workspace");
    return;
  }

  try {
    if (state.quizSession?.examMock) {
      wrongQuestions.forEach((item) => {
        const key = String(item.index);
        delete state.quizSession.answers[key];
        delete state.quizSession.revealed[key];
        delete state.quizSession.results[key];
      });
      state.quizSession.retryQuestionIndexes = wrongQuestions.map((item) => item.index);
      state.quizSession.currentIndex = wrongQuestions[0].index;
      state.quizSession.completed = false;
      renderQuizSession();
      return;
    }

    const resetResults = await Promise.all(wrongQuestions.map((item) => {
      const quiz = state.quizSession.questions[item.index];
      const quizId = item.quizId || quiz?.id;
      if (!quizId) {
        return null;
      }
      return apiFetch(`/api/chat/sessions/${state.quizSession.sessionId}/quizzes/${quizId}/reset`, {
        method: "POST",
      });
    }));

    resetResults.filter(Boolean).forEach(syncQuizInState);

    wrongQuestions.forEach((item) => {
      const key = String(item.index);
      delete state.quizSession.answers[key];
      delete state.quizSession.revealed[key];
      delete state.quizSession.results[key];
    });

    state.quizSession.retryQuestionIndexes = wrongQuestions.map((item) => item.index);
    state.quizSession.currentIndex = wrongQuestions[0].index;
    state.quizSession.completed = false;
    renderQuizSession();
  } catch (error) {
    alert(formatErrorMessage(error, "문제를 다시 풀 수 있도록 초기화하지 못했습니다."));
  }
}

async function resetCurrentQuizSet() {
  if (!state.quizSession?.quizSetId) {
    return;
  }

  const confirmed = window.confirm("현재 퀴즈의 모든 풀이 기록과 피드백을 초기화하고 처음부터 다시 푸시겠습니까?");
  if (!confirmed) {
    return;
  }

  try {
    if (state.quizSession?.examMock) {
      state.quizSession.answers = {};
      state.quizSession.revealed = {};
      state.quizSession.results = {};
      state.quizSession.retryQuestionIndexes = [];
      state.quizSession.currentIndex = 0;
      state.quizSession.completed = false;
      renderQuizSession();
      return;
    }

    const resetQuizzes = await apiFetch(
      `/api/chat/sessions/${state.quizSession.sessionId}/quizzes/sets/${state.quizSession.quizSetId}/reset`,
      { method: "POST" },
    );

    const resetById = new Map(resetQuizzes.map((quiz) => [quiz.id, quiz]));
    state.currentWorkspace.quizzes = (state.currentWorkspace?.quizzes || []).map((quiz) =>
      resetById.get(quiz.id) || quiz,
    );
    state.quizSession.questions = state.quizSession.questions.map((quiz) =>
      resetById.get(quiz.id) || quiz,
    );
    state.quizSession.answers = {};
    state.quizSession.revealed = {};
    state.quizSession.results = {};
    state.quizSession.retryQuestionIndexes = [];
    state.quizSession.currentIndex = 0;
    state.quizSession.completed = false;
    renderQuizSession();
  } catch (error) {
    alert(formatErrorMessage(error, "퀴즈를 처음부터 다시 풀 수 있도록 초기화하지 못했습니다."));
  }
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

function toWorkspaceDocument(documentInfo) {
  return {
    documentId: documentInfo.id,
    title: documentInfo.title,
    storedFileName: documentInfo.storedFileName,
    subject: documentInfo.subject,
    unitName: documentInfo.unitName,
    trustLevel: documentInfo.trustLevel,
    createdAt: documentInfo.createdAt,
  };
}

async function pingServer() {
  try {
    await apiFetch("/api/auth/health");
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

    const [session, messages, quizzes, sessionDocuments] = await Promise.all([
      apiFetch(`/api/chat/sessions/${sessionId}`),
      apiFetch(`/api/chat/sessions/${sessionId}/messages`),
      apiFetch(`/api/chat/sessions/${sessionId}/quizzes`),
      apiFetch(`/api/chat/sessions/${sessionId}/documents`),
    ]);

    const documents = sessionDocuments.length
      ? sessionDocuments.map(toWorkspaceDocument)
      : inferDocumentsForSession(messages, quizzes);
    const currentDocumentId = documents[0]?.documentId || quizzes[0]?.documentId || null;

    state.currentSession = session;
    state.currentMessages = messages;
    state.currentWorkspace = {
      documents,
      quizzes,
      currentDocumentId,
      selectedQuizDocumentIds: documents.map((document) => document.documentId),
      latestUploadAnalysisDocumentId: null,
      activeAnalysisDocumentId: null,
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
    await apiFetch(`/api/chat/sessions/${session.id}/documents/${uploadResponse.documentId}`, { method: "POST" });

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
        uploadAnalysis: buildUploadAnalysis(uploadResponse),
      }],
      quizzes: [],
      currentDocumentId: uploadResponse.documentId,
      selectedQuizDocumentIds: [uploadResponse.documentId],
      latestUploadAnalysisDocumentId: uploadResponse.documentId,
      activeAnalysisDocumentId: null,
    };

    await fetchSessions();
    renderWorkspaceHeader();
    renderDocuments();
    renderMessages();
    renderQuizSets();
    form.reset();
    resetNewStudyAnalysis();
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
    await apiFetch(`/api/chat/sessions/${state.currentSession.id}/documents/${uploadResponse.documentId}`, { method: "POST" });

    state.currentWorkspace.documents.unshift({
      documentId: uploadResponse.documentId,
      title: uploadResponse.title,
      storedFileName: uploadResponse.storedFileName,
      subject: document.getElementById("workspace-subject").value,
      unitName: document.getElementById("workspace-unit").value,
      trustLevel: document.getElementById("workspace-trust").value,
      createdAt: new Date().toISOString(),
      uploadAnalysis: buildUploadAnalysis(uploadResponse),
    });

    state.currentWorkspace.selectedQuizDocumentIds = [
      ...new Set([...getSelectedQuizDocumentIds(), uploadResponse.documentId]),
    ];
    state.currentWorkspace.latestUploadAnalysisDocumentId = uploadResponse.documentId;
    state.currentWorkspace.activeAnalysisDocumentId = null;

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

    const selectedDocumentIds = getSelectedQuizDocumentIds();

    if (question === "!학습코스" && selectedDocumentIds.length !== 1) {
      throw new Error("학습 코스는 PDF 1개만 선택한 상태에서 만들 수 있습니다.");
    }

    const payload = {
      question,
      documentIds: selectedDocumentIds,
    };

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
      sessionId: state.currentSession.id,
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
        documentIds: normalizeDocumentIdSet(documentIds),
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

async function onFeedbackSubmit(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  clearAuthFeedback();

  try {
    const emailInput = document.getElementById("feedback-email");
    const messageInput = document.getElementById("feedback-message");
    await apiFetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: emailInput.value,
        category: document.getElementById("feedback-category").value,
        message: messageInput.value,
      }),
    });
    messageInput.value = "";
    showAuthFeedback("피드백이 접수되었습니다. 확인 후 개선에 반영하겠습니다.");
  } catch (error) {
    showAuthFeedback(formatErrorMessage(error, "피드백을 제출하지 못했습니다."), true);
  } finally {
    button.disabled = false;
  }
}

function showSignupForm() {
  document.getElementById("login-form").classList.add("hidden");
  document.getElementById("show-signup-button").classList.add("hidden");
  document.getElementById("show-feedback-button").classList.add("hidden");
  document.getElementById("feedback-form").classList.add("hidden");
  document.getElementById("signup-form").classList.remove("hidden");
}

function hideSignupForm() {
  document.getElementById("signup-form").classList.add("hidden");
  document.getElementById("login-form").classList.remove("hidden");
  document.getElementById("show-signup-button").classList.remove("hidden");
  document.getElementById("show-feedback-button").classList.remove("hidden");
}

function showFeedbackForm() {
  document.getElementById("signup-form").classList.add("hidden");
  document.getElementById("login-form").classList.remove("hidden");
  document.getElementById("feedback-form").classList.remove("hidden");
  document.getElementById("show-feedback-button").classList.add("hidden");
}

function hideFeedbackForm() {
  document.getElementById("feedback-form").classList.add("hidden");
  document.getElementById("show-feedback-button").classList.remove("hidden");
}

function logoutToAuth() {
  clearAuth();
  hideSignupForm();
  hideFeedbackForm();
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

function isPdfFile(file) {
  if (!file) return false;
  const fileName = (file.name || "").toLowerCase();
  return file.type === "application/pdf" || fileName.endsWith(".pdf");
}

function setFileInputFile(fileInput, file) {
  const dataTransfer = new DataTransfer();
  dataTransfer.items.add(file);
  fileInput.files = dataTransfer.files;
  fileInput.dispatchEvent(new Event("change", { bubbles: true }));
}

function updateDropzoneFileName(fileInput, fileNameElement) {
  if (!fileInput || !fileNameElement) return;
  fileNameElement.textContent = fileInput.files?.[0]?.name || "PDF 파일을 선택하거나 드래그하세요";
}

function showDropzoneInvalid(dropzone) {
  dropzone.classList.add("invalid-drop");
  window.setTimeout(() => dropzone.classList.remove("invalid-drop"), 900);
}

function setupPdfDropzone(dropzoneId, fileInputId, fileNameId) {
  const dropzone = document.getElementById(dropzoneId);
  const fileInput = document.getElementById(fileInputId);
  const fileNameElement = document.getElementById(fileNameId);
  if (!dropzone || !fileInput) return;

  ["dragenter", "dragover"].forEach((eventName) => {
    dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      event.stopPropagation();
      dropzone.classList.add("drag-over");
    });
  });

  ["dragleave", "dragend"].forEach((eventName) => {
    dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      event.stopPropagation();
      dropzone.classList.remove("drag-over");
    });
  });

  dropzone.addEventListener("drop", (event) => {
    event.preventDefault();
    event.stopPropagation();
    dropzone.classList.remove("drag-over");

    const file = event.dataTransfer?.files?.[0];
    if (!file) return;

    if (!isPdfFile(file)) {
      fileInput.value = "";
      updateDropzoneFileName(fileInput, fileNameElement);
      showDropzoneInvalid(dropzone);
      alert("PDF 파일만 업로드할 수 있습니다.");
      return;
    }

    setFileInputFile(fileInput, file);
  });

  fileInput.addEventListener("change", () => {
    const file = fileInput.files?.[0];
    if (file && !isPdfFile(file)) {
      fileInput.value = "";
      updateDropzoneFileName(fileInput, fileNameElement);
      showDropzoneInvalid(dropzone);
      alert("PDF 파일만 업로드할 수 있습니다.");
      return;
    }
    updateDropzoneFileName(fileInput, fileNameElement);
  });
}

setupPdfDropzone("workspace-pdf-dropzone", "workspace-file", "workspace-pdf-file-name");

document.getElementById("login-form").addEventListener("submit", (event) => void onLogin(event));
document.getElementById("signup-form").addEventListener("submit", (event) => void onSignup(event));
document.getElementById("feedback-form").addEventListener("submit", (event) => void onFeedbackSubmit(event));
document.getElementById("show-signup-button").addEventListener("click", showSignupForm);
document.getElementById("hide-signup-button").addEventListener("click", hideSignupForm);
document.getElementById("show-feedback-button").addEventListener("click", showFeedbackForm);
document.getElementById("hide-feedback-button").addEventListener("click", hideFeedbackForm);
document.getElementById("exam-mock-button")?.addEventListener("click", () => void openExamList());
document.getElementById("exam-list-back-home-button")?.addEventListener("click", () => showView("home"));
document.getElementById("exam-list-logout-button")?.addEventListener("click", logoutToAuth);
document.getElementById("start-it-engineer-20220424-button")?.addEventListener("click", () => void openItEngineerMockExam("it-engineer-20220424"));
document.getElementById("start-it-engineer-20220305-button")?.addEventListener("click", () => void openItEngineerMockExam("it-engineer-20220305"));
document.getElementById("start-it-engineer-20210814-button")?.addEventListener("click", () => void openItEngineerMockExam("it-engineer-20210814"));
document.getElementById("logout-button").addEventListener("click", logoutToAuth);
document.getElementById("workspace-logout-button").addEventListener("click", logoutToAuth);
document.getElementById("quiz-logout-button").addEventListener("click", logoutToAuth);
document.getElementById("back-home-button").addEventListener("click", async () => void loadHome());
document.getElementById("back-workspace-button").addEventListener("click", handleBackFromQuiz);
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
document.getElementById("new-study-analysis-button")?.addEventListener("click", () => {
  if (!state.newStudyAnalysis) {
    void analyzeNewStudyPdf();
    return;
  }
  state.newStudyAnalysisOpen = !state.newStudyAnalysisOpen;
  renderNewStudyAnalysis();
});
document.getElementById("new-study-file")?.addEventListener("change", resetNewStudyAnalysis);
document.getElementById("new-study-form").addEventListener("submit", (event) => void createNewStudy(event));
document.getElementById("workspace-upload-form").addEventListener("submit", (event) => void uploadWorkspacePdf(event));
document.getElementById("toggle-upload-analysis-button")?.addEventListener("click", () => {
  if (!state.currentWorkspace) return;
  if (state.currentWorkspace.activeAnalysisDocumentId) {
    state.currentWorkspace.activeAnalysisDocumentId = null;
    renderLatestUploadAnalysis();
    return;
  }

  const latestId = state.currentWorkspace.latestUploadAnalysisDocumentId
    || state.currentWorkspace.documents?.find((documentInfo) => documentInfo.uploadAnalysis)?.documentId
    || state.currentWorkspace.documents?.[0]?.documentId;
  if (latestId) {
    void showDocumentAnalysis(latestId);
  }
});
document.getElementById("chat-form").addEventListener("submit", (event) => void sendChat(event));
document.getElementById("chat-attach-button").addEventListener("click", () => {
  alert("곧 추가될 기능입니다.");
});
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

