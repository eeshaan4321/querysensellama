package com.querysense.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * AppConfig
 *
 * WHY THIS CLASS EXISTS:
 * Spring needs to know about objects (beans) it should manage.
 * RestTemplate is not a Spring component itself — it's a plain Java class
 * from the Spring library. So we can't put @Component on it.
 * Instead, we create a @Configuration class and use @Bean to tell Spring:
 * "create this object, manage its lifecycle, and inject it wherever needed."
 *
 * WHY NOT just do `new RestTemplate()` inside OllamaService?
 * 1. If 10 classes need RestTemplate, you'd create 10 instances — wasteful.
 * 2. You lose centralized control (timeouts, interceptors, error handlers).
 * 3. It makes testing harder — you can't mock a `new`-ed object easily.
 *
 * With @Bean, Spring creates ONE instance and reuses it everywhere.
 * This pattern is called a "Singleton Bean" and is the default in Spring.
 *
 * @Configuration tells Spring: "this class contains bean definitions,
 * scan it at startup and register everything annotated with @Bean."
 *
 * WHY A GLOBAL CORS MAPPING (added here, not another @CrossOrigin on each
 * controller): QueryController already had @CrossOrigin, but
 * QueryAnalysisController and SchemaAdvisorController didn't — so calls to
 * /api/query/analyze and /api/query/schema-advisor from a browser on a
 * different origin (e.g. the Vite dev server on :5173) were silently
 * blocked by CORS even though curl worked fine, since curl doesn't enforce
 * it. A per-controller annotation is easy to forget to add to the next new
 * controller; a single global rule here covers all of them going forward.
 */
@Configuration
public class AppConfig {

    /**
     * WHY EXPLICIT TIMEOUTS (this bean previously had none at all):
     * A bare `new RestTemplate()` has no connect timeout and no read timeout,
     * meaning a request can hang indefinitely. That was already sloppy against
     * a hosted API; against a local Ollama runtime it is worse, because a
     * legitimate response genuinely takes seconds and "slow" and "hung" are no
     * longer easy to tell apart by feel.
     *
     * Read timeout defaults to 60s, which is far above what a real generation
     * costs — a warm 7B model on this hardware produces a query in roughly 1-2
     * seconds, and the num_predict cap in OllamaService bounds even a rambling
     * reply to a few seconds more. The headroom covers a cold model load
     * (~2-4s) on top of that, while still failing in reasonable time when
     * something is actually wrong rather than merely slow.
     *
     * Connect timeout is short by comparison because connecting to localhost
     * either succeeds immediately or is refused immediately; there is no
     * middle ground worth waiting through.
     *
     * ⚠️ The frontend's axios client uses a 20s timeout (src/api/client.js).
     * If a generation ever legitimately runs longer than that, the browser
     * gives up while this backend is still working and the user sees a
     * timeout for a request that eventually succeeded. Keep the two values in
     * mind together when tuning either.
     */
    @Bean
    public RestTemplate restTemplate(
            @Value("${http.client.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${http.client.read-timeout-ms:60000}") long readTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return new RestTemplate(factory);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }
}
