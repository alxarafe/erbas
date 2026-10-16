package com.alxarafe.erbas.adapter.http;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LocalizationHttpTests.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({LocalizationConfiguration.class, ValidationExceptionHandler.class, LocalizationHttpTests.ProbeController.class})
@TestPropertySource(properties = "spring.messages.basename=i18n/core/messages,i18n/probe/messages")
class LocalizationHttpTests {
    private static final String PATH = "/test/i18n";
    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @CsvSource(value = {
            "en|The request contains invalid data.|This field is required.",
            "es|La solicitud contiene datos no válidos.|Este campo es obligatorio.",
            "NONE|The request contains invalid data.|This field is required.",
            "fr|The request contains invalid data.|This field is required.",
            "es-ES|La solicitud contiene datos no válidos.|Este campo es obligatorio.",
            "en-US|The request contains invalid data.|This field is required.",
            "en;q=0.5,es;q=0.9|La solicitud contiene datos no válidos.|Este campo es obligatorio."
    }, delimiter = '|')
    void negotiatesThroughHttpWithoutTranslatingNestedPaths(String language, String title, String message)
            throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"children\":[{\"name\":null}]}");
        if (!language.equals("NONE")) request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ValidationExceptionHandler.VALIDATION_CODE))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.errors['children[0].name'][0]").value(message))
                .andExpect(jsonPath("$.errors.length()").value(1));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "en|This field must contain between 2 and 4 characters.",
            "es|Este campo debe contener entre 2 y 4 caracteres."
    }, delimiter = '|')
    void interpolatesConstraintParametersInTheOwningBundle(String language, String message) throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.ACCEPT_LANGUAGE, language)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"children\":[{\"name\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['children[0].name'][0]").value(message));
    }

    @Test
    void queryAndCookieCannotOverrideTheHeaderAndCultureDoesNotLeakBetweenRequests() throws Exception {
        mvc.perform(post(PATH + "?lang=es").header(HttpHeaders.COOKIE, "lang=es")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"children\":[{\"name\":\"\"}]}"))
                .andExpect(jsonPath("$.title").value("The request contains invalid data."));
        mvc.perform(post(PATH).header(HttpHeaders.ACCEPT_LANGUAGE, "es")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"children\":[{\"name\":\"ok\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"children\":[{\"name\":\"\"}]}"))
                .andExpect(jsonPath("$.title").value("The request contains invalid data."));
    }

    // Transport probes only: these types/resources never enter the production artifact.
    @RestController
    static class ProbeController {
        @PostMapping(PATH)
        Payload validate(@Valid @RequestBody Payload payload) { return payload; }
    }

    record Payload(@Valid List<Child> children) { }
    record Child(@NotBlank(message = "{probe.validation.required}")
                 @Size(min = 2, max = 4, message = "{probe.validation.size}") String name) { }
}
