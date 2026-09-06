package com.opengpt

import com.opengpt.auth.TokenStore
import com.opengpt.model.OAuthToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthAndModelsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokenStore: TokenStore,
) {
    @BeforeEach
    fun seedApiKey() {
        tokenStore.save(
            OAuthToken(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAt = System.currentTimeMillis() + 60_000,
                accountId = "acc",
                apiKey = "sk-cla-test-key",
            ),
        )
    }

    @Test
    fun `health is up`() {
        mockMvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `models lists openai compatible ids`() {
        mockMvc
            .get("/v1/models") {
                header("Authorization", "Bearer sk-cla-test-key")
            }.andExpect {
                status { isOk() }
                jsonPath("$.object") { value("list") }
                jsonPath("$.data[0].id") { exists() }
                jsonPath("$.data[?(@.id == 'gpt-5.6-sol-medium')]") { isNotEmpty() }
                jsonPath("$.data[?(@.id == 'cla-sol-low')]") { isNotEmpty() }
            }
    }
}
