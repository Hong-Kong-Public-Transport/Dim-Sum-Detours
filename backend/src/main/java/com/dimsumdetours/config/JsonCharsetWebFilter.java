package com.dimsumdetours.config;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Forces {@code charset=UTF-8} onto every {@code application/json} response — most relevant for
 * the static {@code /assets/i18n/*.json} files served out of the prebuilt Angular bundle.
 *
 * <p>Spring WebFlux's static {@code ResourceWebHandler} resolves JSON via {@code MediaTypeFactory}
 * which omits the charset parameter. Some browsers / intermediaries then fall back to ISO-8859-1
 * for {@code application/json}, which mangles CJK translations (繁體中文 / 日本語 / 한글).
 *
 * <p>Hooks just before the response commits via {@link ServerWebExchange#getResponse()} →
 * {@code beforeCommit}, so it works for any handler (controllers, static resources, error pages).
 */
@Component
public class JsonCharsetWebFilter implements WebFilter {

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		exchange.getResponse().beforeCommit(() -> {
			MediaType current = exchange.getResponse().getHeaders().getContentType();
			if (current != null
				&& MediaType.APPLICATION_JSON.isCompatibleWith(current)
				&& current.getCharset() == null) {
				exchange.getResponse().getHeaders().setContentType(
					new MediaType(current, StandardCharsets.UTF_8));
			}
			return Mono.empty();
		});
		return chain.filter(exchange);
	}
}

