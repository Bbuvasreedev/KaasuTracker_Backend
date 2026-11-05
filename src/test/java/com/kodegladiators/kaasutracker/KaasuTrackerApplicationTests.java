//package com.kodegladiators.kaasutracker;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.kodegladiators.kaasutracker.model.Emi;
//import com.kodegladiators.kaasutracker.model.User;
//import com.kodegladiators.kaasutracker.repository.EmiRepository;
//import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
//import com.kodegladiators.kaasutracker.repository.UserRepository;
//import com.kodegladiators.kaasutracker.service.ReceiptAnalysisService;
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.util.UUID;
//import org.junit.jupiter.api.Test;
//import org.mockito.Mockito;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Import;
//import org.springframework.http.MediaType;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.transaction.annotation.Transactional;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//@Import(KaasuTrackerApplicationTests.AiTestConfiguration.class)
//class KaasuTrackerApplicationTests {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private ExpenseRepository expenseRepository;
//
//    @Autowired
//    private EmiRepository emiRepository;
//
//    @Autowired
//    private ObjectMapper objectMapper;
//
//    @Autowired
//    private ReceiptAnalysisService receiptAnalysisService;
//
//    @Test
//    void contextLoads() {
//    }
//
//    @Test
//    void receiptAnalysisRejectsInvalidImageBeforeCallingTheModel() {
//        assertThrows(
//                IllegalArgumentException.class,
//                () -> receiptAnalysisService.analyzeReceipt("not-a-valid-image")
//        );
//    }
//
//    @Test
//    void versionedApiRequiresAuthentication() throws Exception {
//        mockMvc.perform(get("/api/v1/expenses"))
//                .andExpect(status().isUnauthorized());
//    }
//
//    @Test
//    @Transactional
//    void expenseEndpointsCreateListAndAnalyze() throws Exception {
//        User user = new User();
//        user.setId(UUID.randomUUID().toString());
//        user.setEmail(UUID.randomUUID() + "@example.com");
//        user.setPasswordHash("test-hash");
//        user = userRepository.saveAndFlush(user);
//        String userId = user.getId();
//
//        String expensesUrl = "/api/v1/expenses";
//        String request = """
//                {
//                  "amount": 42.50,
//                  "category": "Groceries",
//                  "merchant": "Market",
//                  "description": "Market",
//                  "expenseDate": "2026-10-18",
//                  "receiptUrl": "https://example.com/receipt.png",
//                  "isConfirmed": true
//                }
//                """;
//
//        var createResult = mockMvc.perform(post(expensesUrl)
//                        .with(jwt().jwt(token -> token.subject(userId)))
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(request))
//                .andExpect(status().isCreated())
//                .andExpect(jsonPath("$.amount").value(42.5))
//                .andReturn();
//
//        String expenseId = objectMapper.readTree(createResult.getResponse().getContentAsString())
//                .get("id")
//                .asText();
//
//        mockMvc.perform(get(expensesUrl).with(jwt().jwt(token -> token.subject(userId))))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$[0].id").value(expenseId));
//
//        org.junit.jupiter.api.Assertions.assertEquals(
//                1,
//                expenseRepository.findByUserIdAndExpenseDateBetween(
//                        user.getId(),
//                        LocalDate.parse("2026-10-18"),
//                        LocalDate.parse("2026-10-18")
//                ).size()
//        );
//        org.junit.jupiter.api.Assertions.assertEquals(
//                new BigDecimal("42.50"),
//                expenseRepository.sumAmountByUserIdAndExpenseDateBetween(
//                        user.getId(),
//                        LocalDate.parse("2026-10-18"),
//                        LocalDate.parse("2026-10-18")
//                )
//        );
//
//        Emi activeEmi = Emi.builder()
//                .title("Car loan")
//                .amount(new BigDecimal("250.00"))
//                .dueDateDay(18)
//                .durationMonths(36)
//                .isActive(true)
//                .user(user)
//                .build();
//        emiRepository.save(activeEmi);
//        Emi inactiveEmi = Emi.builder()
//                .title("Old loan")
//                .amount(new BigDecimal("100.00"))
//                .dueDateDay(18)
//                .durationMonths(12)
//                .isActive(false)
//                .user(user)
//                .build();
//        emiRepository.save(inactiveEmi);
//        org.junit.jupiter.api.Assertions.assertEquals(
//                1,
//                emiRepository.findAllActiveByDueDateDay(18).size()
//        );
//
//        mockMvc.perform(post("/api/v1/expenses/analyze")
//                        .with(jwt())
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content("""
//                                {"base64Image":"not-a-valid-image"}
//                                """))
//                .andExpect(status().isBadRequest());
//
//        org.junit.jupiter.api.Assertions.assertEquals(1, expenseRepository.count());
//        org.junit.jupiter.api.Assertions.assertEquals(2, emiRepository.count());
//    }
//
//    @TestConfiguration(proxyBeanMethods = false)
//    static class AiTestConfiguration {
//
//        @Bean
//        ChatModel testChatModel() {
//            return Mockito.mock(ChatModel.class);
//        }
//    }
//}
