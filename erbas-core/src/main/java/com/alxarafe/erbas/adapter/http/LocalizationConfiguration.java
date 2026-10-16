package com.alxarafe.erbas.adapter.http;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/** Stateless, request-local negotiation; resource basenames belong to their owners. */
@Configuration(proxyBeanMethods = false)
public class LocalizationConfiguration {
    public static final Locale BASE_LOCALE = Locale.ENGLISH;
    public static final List<Locale> SUPPORTED_LOCALES = List.of(BASE_LOCALE, Locale.of("es"));

    @Bean
    public LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(BASE_LOCALE);
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        return resolver;
    }
}
