package com.gyeongtaekim.ai_tutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.repository.ChatMessageRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.ConceptRepository;
import com.gyeongtaekim.ai_tutor.repository.DocumentChunkRepository;
import com.gyeongtaekim.ai_tutor.repository.LearningMemoryRepository;
import com.gyeongtaekim.ai_tutor.repository.ProblemRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ReviewQueueRepository;
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
    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws IOException {
        chatMessageRepository.deleteAll();
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
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("auth@example.com"));
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
                .andExpect(jsonPath("$.correct").value(false));

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

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("subject", "computer-science")
                        .param("unitName", "algorithm")
                        .param("trustLevel", "high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1));

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
