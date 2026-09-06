package com.opengpt.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(
    AdapterProperties::class,
    OAuthProperties::class,
    CodexProperties::class,
)
class AppConfig {
    @Bean
    fun webClientBuilder(): WebClient.Builder {
        val httpClient =
            HttpClient.create()
                .responseTimeout(Duration.ofMinutes(10))
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(
                ExchangeStrategies.builder()
                    .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
                    .build(),
            )
    }
}
