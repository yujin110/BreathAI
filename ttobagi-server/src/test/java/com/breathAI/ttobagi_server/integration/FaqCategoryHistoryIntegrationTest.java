package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.domain.faq.entity.Faq;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FAQ 카테고리 목록·부분 일치 검색·카테고리 이름 표시, FAQ별 수정 이력 통합 시험
// 카테고리 마스터는 테스트 전용 bronze_cate_info(V900)를 쓰고, 다른 시험이 쓰지 않는 코드만 새로 넣는다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqCategoryHistoryIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private FaqRepository faqRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("카테고리 목록은 코드별로 가장 최근에 적재된 이름 하나씩 코드 순으로 준다")
    void categoriesUseLatestNamePerCode() throws Exception {
        jdbcTemplate.update("INSERT IGNORE INTO bronze_cate_info (seq_num, q_type, q_disp_name, state, _ingested_at) "
                + "VALUES (9001, 901, '옛 이름', 1, '2026-01-01 00:00:00'), (9002, 901, '새 이름', 1, '2026-02-01 00:00:00')");

        String body = body(mvc.perform(get("/api/v1/faq/categories").header(HttpHeaders.AUTHORIZATION, bearer(userToken())))
                .andExpect(status().isOk())
                .andReturn());

        List<Integer> codes = JsonPath.read(body, "$.data.categories[*].qType");
        assertThat(codes).isSorted().doesNotHaveDuplicates().contains(172, 176, 188, 901);
        assertThat((List<String>) JsonPath.read(body, "$.data.categories[?(@.qType == 901)].name")).containsExactly("새 이름");
        assertThat((List<String>) JsonPath.read(body, "$.data.categories[?(@.qType == 188)].name")).containsExactly("열차운영관련");
    }

    @Test
    @DisplayName("카테고리 검색은 이름 일부만 맞아도 찾고, 비어 있는 카테고리 이름은 마스터에서 채운다")
    void partialSearchAndMasterName() throws Exception {
        String admin = adminToken();
        String marker = "카테고리검색" + UUID.randomUUID().toString().substring(0, 8);
        Faq empty = saveFaq(marker + " 빈 이름", 188, null);
        Faq named = saveFaq(marker + " 저장된 이름", 188, "직접 정한 이름");

        String list = body(mvc.perform(get("/api/v1/faq").param("size", "50").param("category", "열차운영").param("keyword", marker)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andReturn());
        assertThat((List<String>) JsonPath.read(list, "$.data.faqList[?(@.faqId == " + empty.getFaqId() + ")].category"))
                .containsExactly("열차운영관련");
        // 저장된 이름이 있으면 그대로 둔다 (코드가 바뀔 때만 마스터 이름으로 갱신하는 규칙)
        assertThat((List<String>) JsonPath.read(list, "$.data.faqList[?(@.faqId == " + named.getFaqId() + ")].category"))
                .containsExactly("직접 정한 이름");

        mvc.perform(get("/api/v1/faq/" + empty.getFaqId()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("열차운영관련"));

        // 마스터에 없는 이름이면 지금처럼 결과가 없다
        mvc.perform(get("/api/v1/faq").param("category", "없는카테고리").param("keyword", marker).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("수정 이력은 faqId로 그 FAQ의 이력만 볼 수 있고, 없는 FAQ면 빈 목록이다")
    void historyFilteredByFaq() throws Exception {
        String admin = adminToken();
        Faq target = saveFaq("이력 대상 " + UUID.randomUUID(), 176, null);
        Faq other = saveFaq("다른 FAQ " + UUID.randomUUID(), 176, null);
        updateFaq(admin, target, "답변 1");
        updateFaq(admin, target, "답변 2");
        updateFaq(admin, other, "답변 3");
        mvc.perform(delete("/api/v1/faq/" + target.getFaqId()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        String history = body(mvc.perform(get("/api/v1/faq/history?size=50&faqId=" + target.getFaqId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories.length()").value(3))
                .andReturn());
        assertThat((List<Integer>) JsonPath.read(history, "$.data.histories[*].faqId"))
                .containsOnly(target.getFaqId().intValue());
        // 최신순이라 삭제가 맨 앞이다
        assertThat((String) JsonPath.read(history, "$.data.histories[0].editType")).isEqualTo("DELETE");

        mvc.perform(get("/api/v1/faq/history?faqId=" + target.getFaqId() + "&editType=MANUAL")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories.length()").value(2));
        mvc.perform(get("/api/v1/faq/history?faqId=999999999").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories.length()").value(0));
    }

    private Faq saveFaq(String question, Integer qType, String category) {
        return faqRepository.save(Faq.builder()
                .question(question)
                .answer("통합테스트 답변")
                .qType(qType)
                .category(category)
                .build());
    }

    private void updateFaq(String token, Faq faq, String answer) throws Exception {
        String json = "{\"qType\":" + faq.getQType() + ",\"standardQuestion\":\"" + faq.getQuestion() + "\",\"answer\":\""
                + answer + "\",\"keywords\":[\"시험\"],\"editReason\":\"통합테스트\"}";
        mvc.perform(withJson(patch("/api/v1/faq/" + faq.getFaqId()), json).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private String adminToken() throws Exception {
        return token(newEmail("faq-admin"), ADMIN_CODE);
    }

    private String userToken() throws Exception {
        return token(newEmail("faq-user"), null);
    }

    private String token(String email, String adminCode) throws Exception {
        String code = adminCode == null ? "" : ",\"adminCode\":\"" + adminCode + "\"";
        mvc.perform(withJson(post("/api/v1/auth/signup"), "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"" + code + "}"))
                .andExpect(status().isCreated());
        String login = body(mvc.perform(withJson(post("/api/v1/auth/login"),
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(login, "$.data.accessToken");
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String json) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8));
    }

    private String newEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
