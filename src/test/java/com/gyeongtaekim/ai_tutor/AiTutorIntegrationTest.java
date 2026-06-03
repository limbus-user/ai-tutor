package com.gyeongtaekim.ai_tutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.repository.ChatMessageRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ConceptRepository;
import com.gyeongtaekim.ai_tutor.repository.DocumentChunkRepository;
import com.gyeongtaekim.ai_tutor.repository.LearningMemoryRepository;
import com.gyeongtaekim.ai_tutor.repository.ProblemRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ReviewQueueRepository;
import com.gyeongtaekim.ai_tutor.repository.SessionQuizRepository;
import com.gyeongtaekim.ai_tutor.repository.UserProblemAttemptRepository;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import com.gyeongtaekim.ai_tutor.repository.WrongAnswerNoteRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTutorIntegrationTest {

    private static final Path TEST_UPLOAD_DIR;

    static {
        try {
            TEST_UPLOAD_DIR = Files.createTempDirectory("ai-tutor-test-upload");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("upload.path", () -> TEST_UPLOAD_DIR.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatSessionDocumentRepository chatSessionDocumentRepository;

    @Autowired
    private ReviewQueueRepository reviewQueueRepository;

    @Autowired
    private WrongAnswerNoteRepository wrongAnswerNoteRepository;

    @Autowired
    private UserProblemAttemptRepository userProblemAttemptRepository;

    @Autowired
    private LearningMemoryRepository learningMemoryRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private ConceptRepository conceptRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private RagDocumentRepository ragDocumentRepository;

    @Autowired
    private SessionQuizRepository sessionQuizRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws IOException {
        chatMessageRepository.deleteAll();
        sessionQuizRepository.deleteAll();
        chatSessionDocumentRepository.deleteAll();
        chatSessionRepository.deleteAll();
        reviewQueueRepository.deleteAll();
        wrongAnswerNoteRepository.deleteAll();
        userProblemAttemptRepository.deleteAll();
        learningMemoryRepository.deleteAll();
        problemRepository.deleteAll();
        conceptRepository.deleteAll();
        documentChunkRepository.deleteAll();
        ragDocumentRepository.deleteAll();
        userRepository.deleteAll();

        Files.createDirectories(TEST_UPLOAD_DIR);
        try (var files = Files.list(TEST_UPLOAD_DIR)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void signupAndLoginReturnJwtToken() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"auth@example.com",
                                  "password":"secret",
                                  "name":"Auth User"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("auth@example.com"))
                .andExpect(jsonPath("$.name").value("Auth User"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"auth@example.com",
                                  "password":"secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("auth@example.com"));
    }

    @Test
    void frontendIndexPageIsServed() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("AI Tutor")));
    }

    @Test
    void sessionDocumentsRemainAvailableAfterReopeningSession() throws Exception {
        long userId = createUser("session-documents@example.com");
        long sessionId = createSession(userId);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "persistent.pdf",
                "application/pdf",
                createPdf("Persistent session document.")
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        long documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray())
                .get("documentId")
                .asLong();

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/documents/{documentId}", sessionId, documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/documents", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(documentId))
                .andExpect(jsonPath("$[0].title").value("persistent.pdf"));
    }

    @Test
    void sessionDocumentsDoNotIncludeFilesAttachedToAnotherSession() throws Exception {
        long userId = createUser("isolated-session-documents@example.com");
        long firstSessionId = createSession(userId);
        long secondSessionId = createSession(userId);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "isolated.pdf",
                "application/pdf",
                createPdf("Only the first session should contain this document.")
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        long documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray())
                .get("documentId")
                .asLong();

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/documents/{documentId}", firstSessionId, documentId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/documents", secondSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void wrongAnswerSubmissionCreatesReviewArtifacts() throws Exception {
        long userId = createUser("learner@example.com");
        long conceptId = createConcept();
        long problemId = createProblem(conceptId);

        mockMvc.perform(post("/api/problems/{problemId}/submit", problemId)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "userId": %d,
                                  "submittedAnswer":"O(n)"
                                }
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problemId").value(problemId))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.correct").value(false))
                .andExpect(jsonPath("$.correctAnswer").value("O(log n)"))
                .andExpect(jsonPath("$.explanation").value("The search interval is halved each step."));

        mockMvc.perform(get("/api/reviews/wrong-answers/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId))
                .andExpect(jsonPath("$[0].attemptId").isNumber())
                .andExpect(jsonPath("$[0].conceptTags[0]").value("binary search"))
                .andExpect(jsonPath("$[0].reviewStatus").value("PENDING"));

        mockMvc.perform(get("/api/reviews/queue/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].referenceName").value("What is the time complexity of binary search?"));
    }

    @Test
    void problemLookupDoesNotExposeAnswerBeforeSubmission() throws Exception {
        long conceptId = createConcept();
        long problemId = createProblem(conceptId);

        mockMvc.perform(get("/api/problems/{problemId}", problemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(problemId))
                .andExpect(jsonPath("$.questionText").value("What is the time complexity of binary search?"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    void oxProblemCanBeCreatedAndSubmittedWithOxAlias() throws Exception {
        long userId = createUser("ox@example.com");
        long conceptId = createConcept();

        MvcResult createResult = mockMvc.perform(post("/api/problems")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "questionText":"Binary search works on a sorted array. O/X",
                                  "answer":"O",
                                  "explanation":"Binary search requires the input to be sorted.",
                                  "difficulty":"easy",
                                  "understandingLevel":"CONCEPT_UNDERSTANDING",
                                  "type":"ox",
                                  "conceptIds":[%d]
                                }
                                """.formatted(conceptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRUE_FALSE"))
                .andReturn();

        long problemId = readId(createResult);

        mockMvc.perform(post("/api/problems/{problemId}/submit", problemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": %d,
                                  "submittedAnswer":"O"
                                }
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true))
                .andExpect(jsonPath("$.correctAnswer").value("O"));
    }

    @Test
    void uploadedPdfCanBeQueriedAndUsedByTutorFlow() throws Exception {
        long userId = createUser("tutor@example.com");
        long sessionId = createSession(userId);

        mockMvc.perform(put("/api/memory/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "weakConceptSummary":"binary search complexity",
                                  "historySummary":"reviewing algorithm basics",
                                  "preferences":"concise explanations"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "binary-search.pdf",
                "application/pdf",
                createPdf("""
                        Binary search works on a sorted array.
                        Each step halves the remaining search space.
                        This makes binary search efficient for lookup.
                        """)
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("subject", "computer-science")
                        .param("unitName", "algorithm")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andReturn();

        long documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray()).get("documentId").asLong();

        MvcResult generatedQuestionsResult = mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.questions.length()").value(5))
                .andExpect(jsonPath("$.questions[0].question").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].type").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].correctAnswer").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].modelAnswer").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].explanation").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].conceptTag").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].understandingLevel").isNotEmpty())
                .andReturn();

        JsonNode generatedQuestions = objectMapper.readTree(generatedQuestionsResult.getResponse().getContentAsByteArray());

        MvcResult savedQuizzesResult = mockMvc.perform(post("/api/chat/sessions/{sessionId}/quizzes", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentId": %d,
                                  "questions": %s
                                }
                                """.formatted(documentId, generatedQuestions.get("questions").toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$[0].documentId").value(documentId))
                .andExpect(jsonPath("$[0].question").isNotEmpty())
                .andExpect(jsonPath("$[0].conceptTag").isNotEmpty())
                .andExpect(jsonPath("$[0].understandingLevel").isNotEmpty())
                .andReturn();

        JsonNode savedQuizzes = objectMapper.readTree(savedQuizzesResult.getResponse().getContentAsByteArray());
        long firstQuizId = savedQuizzes.get(0).get("id").asLong();
        String firstQuizAnswer = savedQuizzes.get(0).get("correctAnswer").asText();

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/quizzes/{quizId}/submit", sessionId, firstQuizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "submittedAnswer": %s
                                }
                                """.formatted(objectMapper.writeValueAsString(firstQuizAnswer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstQuizId))
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.correct").value(true))
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.evaluationFeedback").value(org.hamcrest.Matchers.containsString("답안 비교\n- 내 답:")))
                .andExpect(jsonPath("$.evaluationFeedback").value(org.hamcrest.Matchers.containsString("\n핵심 피드백\n")))
                .andExpect(jsonPath("$.evaluationFeedback").value(org.hamcrest.Matchers.containsString("정답입니다.")));

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/quizzes", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].documentId").value(documentId))
                .andExpect(jsonPath("$[0].correctAnswer").isNotEmpty());

        mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId))
                        .param("type", "mixed")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(3))
                .andExpect(jsonPath("$.questions[0].type").value("multiple_choice"))
                .andExpect(jsonPath("$.questions[1].type").value("ox"))
                .andExpect(jsonPath("$.questions[2].type").value("short_answer"));

        mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId))
                        .param("type", "multiple_choice")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(3))
                .andExpect(jsonPath("$.questions[0].type").value("multiple_choice"))
                .andExpect(jsonPath("$.questions[0].choices.length()").value(4))
                .andExpect(jsonPath("$.questions[1].type").value("multiple_choice"))
                .andExpect(jsonPath("$.questions[1].choices.length()").value(4))
                .andExpect(jsonPath("$.questions[2].type").value("multiple_choice"))
                .andExpect(jsonPath("$.questions[2].choices.length()").value(4))
                .andExpect(jsonPath("$.questions[0].correctAnswer").isNotEmpty());

        mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId))
                        .param("type", "ox")
                        .param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].type").value("ox"))
                .andExpect(jsonPath("$.questions[0].question").value(org.hamcrest.Matchers.containsString("고르세요.\n\"")))
                .andExpect(jsonPath("$.questions[0].choices[0]").value("O"))
                .andExpect(jsonPath("$.questions[0].choices[1]").value("X"))
                .andExpect(jsonPath("$.questions[0].correctAnswer").isNotEmpty())
                .andExpect(jsonPath("$.questions[1].type").value("ox"))
                .andExpect(jsonPath("$.questions[1].choices[0]").value("O"))
                .andExpect(jsonPath("$.questions[1].choices[1]").value("X"));

        mockMvc.perform(post("/api/rag/query")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("How does binary search shrink the search space?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0]").value("binary-search.pdf [chunk 0]"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("halves the remaining search space")));

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/ask", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question":"How does binary search shrink the search space?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.sources[0]").value("binary-search.pdf [chunk 0]"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("binary-search.pdf [chunk 0]")));

        MvcResult result = mockMvc.perform(get("/api/chat/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andReturn();

        JsonNode messages = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(messages.get(1).get("sourceReferences").asText()).contains("binary-search.pdf [chunk 0]");
    }

    @Test
    void tutorAskCanBeScopedToUploadedDocument() throws Exception {
        long userId = createUser("scope@example.com");
        long sessionId = createSession(userId);

        MockMultipartFile firstFile = new MockMultipartFile(
                "file",
                "java.pdf",
                "application/pdf",
                createPdf("Java uses classes and objects.")
        );
        MockMultipartFile secondFile = new MockMultipartFile(
                "file",
                "python.pdf",
                "application/pdf",
                createPdf("Python uses indentation to define blocks.")
        );

        MvcResult firstUpload = mockMvc.perform(multipart("/api/rag/upload")
                        .file(firstFile)
                        .param("subject", "computer-science")
                        .param("unitName", "java")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(secondFile)
                        .param("subject", "computer-science")
                        .param("unitName", "python")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk());

        long firstDocumentId = objectMapper.readTree(firstUpload.getResponse().getContentAsByteArray()).get("documentId").asLong();

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/ask", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question":"Explain classes and objects simply.",
                                  "documentId": %d
                                }
                                """.formatted(firstDocumentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0]").value("java.pdf [chunk 0]"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("classes and objects")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("indentation"))));
    }

    @Test
    void tutorAskAnswersKoreanConceptQuestionFromUploadedPdf() throws Exception {
        long userId = createUser("korean@example.com");
        long sessionId = createSession(userId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "oop.pdf",
                "application/pdf",
                Files.readAllBytes(Path.of(System.getProperty("user.dir"), "sample-upload-test-ko-cs.pdf"))
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("subject", "computer-science")
                        .param("unitName", "oop")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk())
                .andReturn();

        long documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray()).get("documentId").asLong();

        mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(5))
                .andExpect(jsonPath("$.questions[0].question").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].modelAnswer").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].explanation").isNotEmpty());

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/ask", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "question":"다형성이 뭐야?",
                                  "documentId": %d
                                }
                                """.formatted(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.answer").isNotEmpty());
    }

    @Test
    void deletingSessionRemovesMessagesAndQuizzes() throws Exception {
        long userId = createUser("delete-session@example.com");
        long sessionId = createSession(userId);

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role":"USER",
                                  "content":"delete me",
                                  "sourceReferences":""
                                }
                                """))
                .andExpect(status().isOk());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "delete-session.pdf",
                "application/pdf",
                createPdf("Deletion test content.")
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("subject", "computer-science")
                        .param("unitName", "cleanup")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk())
                .andReturn();

        long documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray()).get("documentId").asLong();

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/quizzes", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentId": %d,
                                  "quizSetTitle": "Delete Set",
                                  "questions": [
                                    {
                                      "order": 1,
                                      "type": "ox",
                                      "question": "Deletion test question",
                                      "choices": ["O", "X"],
                                      "correctAnswer": "O",
                                      "modelAnswer": "O",
                                      "explanation": "It should delete cleanly.",
                                      "sourceEvidence": "delete-session.pdf [chunk 0]",
                                      "difficulty": "easy"
                                    }
                                  ]
                                }
                                """.formatted(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(sessionId));

        mockMvc.perform(delete("/api/chat/sessions/{sessionId}", sessionId))
                .andExpect(status().isNoContent());

        assertThat(chatSessionRepository.findById(sessionId)).isEmpty();
        assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).isEmpty();
        assertThat(sessionQuizRepository.findBySessionIdOrderByCreatedAtAscQuestionOrderAsc(sessionId)).isEmpty();
    }

    @Test
    void generatesQuestionsFromMultipleSelectedDocuments() throws Exception {
        MockMultipartFile firstFile = new MockMultipartFile(
                "file",
                "sorting.pdf",
                "application/pdf",
                createPdf("Merge sort divides an array and combines sorted halves.")
        );
        MockMultipartFile secondFile = new MockMultipartFile(
                "file",
                "graph.pdf",
                "application/pdf",
                createPdf("Breadth first search explores graph vertices level by level.")
        );

        MvcResult firstUpload = mockMvc.perform(multipart("/api/rag/upload").file(firstFile))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult secondUpload = mockMvc.perform(multipart("/api/rag/upload").file(secondFile))
                .andExpect(status().isOk())
                .andReturn();

        long firstDocumentId = objectMapper.readTree(firstUpload.getResponse().getContentAsByteArray()).get("documentId").asLong();
        long secondDocumentId = objectMapper.readTree(secondUpload.getResponse().getContentAsByteArray()).get("documentId").asLong();

        mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentIds", String.valueOf(firstDocumentId), String.valueOf(secondDocumentId))
                        .param("type", "mixed")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(firstDocumentId))
                .andExpect(jsonPath("$.title").value("sorting.pdf, graph.pdf"))
                .andExpect(jsonPath("$.questions.length()").value(3));
    }

    @Test
    void generatedShortAnswerApplicationUsesCompleteConceptAlignedAnswer() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "kernel.pdf",
                "application/pdf",
                createPdf("""
                        Kernel manages hardware resources for programs.
                        Kernel mediates file and memory requests from user programs.
                        Kernel protects the operating system by controlling resource access.
                        """)
        );

        MvcResult upload = mockMvc.perform(multipart("/api/rag/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();

        long documentId = objectMapper.readTree(upload.getResponse().getContentAsByteArray()).get("documentId").asLong();

        MvcResult result = mockMvc.perform(post("/api/rag/generate-questions")
                        .param("documentId", String.valueOf(documentId))
                        .param("type", "short_answer")
                        .param("mode", "application")
                        .param("count", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.questions[0].type").value("short_answer"))
                .andReturn();

        JsonNode question = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .get("questions")
                .get(0);
        String stem = question.get("question").asText();
        String answer = question.get("modelAnswer").asText();
        String correctAnswer = question.get("correctAnswer").asText();

        assertThat(stem.toLowerCase()).contains("kernel");
        assertThat(answer.toLowerCase()).contains("kernel");
        assertThat(correctAnswer).isEqualTo(answer);
        assertThat(answer).containsAnyOf(
                "\uD504\uB85C\uADF8\uB7A8",
                "\uD30C\uC77C",
                "\uBA54\uBAA8\uB9AC",
                "\uC694\uCCAD",
                "\uC0C1\uD669"
        );
        assertThat(answer).doesNotEndWith("\uB2E4\uC74C\uACFC \uAC19\uC74C")
                .doesNotEndWith("\uC544\uB798\uC640 \uAC19\uC74C")
                .doesNotEndWith("\uC8FC\uC694 \uC5ED\uD560\uC740");
        assertThat(answer.length()).isGreaterThan(25);
    }

    private long createUser(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s",
                                  "password":"secret",
                                  "name":"Test User"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        return readId(result);
    }

    private long createConcept() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/concepts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject":"computer-science",
                                  "unitName":"algorithm",
                                  "name":"binary search",
                                  "description":"divide search interval in half"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        return readId(result);
    }

    private long createProblem(long conceptId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/problems")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "questionText":"What is the time complexity of binary search?",
                                  "answer":"O(log n)",
                                  "explanation":"The search interval is halved each step.",
                                  "difficulty":"easy",
                                  "understandingLevel":"CONCEPT_UNDERSTANDING",
                                  "type":"short_answer",
                                  "conceptIds":[%d]
                                }
                                """.formatted(conceptId)))
                .andExpect(status().isOk())
                .andReturn();

        return readId(result);
    }

    private long createSession(long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": %d,
                                  "title":"Binary Search Session"
                                }
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();

        return readId(result);
    }

    private long readId(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return json.get("id").asLong();
    }

    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                for (String line : text.split("\\R")) {
                    contentStream.showText(line.trim());
                    contentStream.newLineAtOffset(0, -16);
                }
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}
