package com.funding.api.funder;

import com.funding.api.auth.Actor;
import com.funding.domain.Role;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funding.api.ProblemHandler;
import com.funding.api.auth.SecurityConfig;
import com.funding.api.auth.UserDirectory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * AS-10.9 (spec 001, now bearer tokens) and AS-1.1's backend part (spec 002): the real security
 * chain in front of the funder API. Spring Security Test's {@code jwt()} stands in for a decoded
 * token, so no OIDC provider is needed; the role still comes from our {@link UserDirectory}.
 * The functional tests cover real tokens from the mock provider.
 */
@WebMvcTest(controllers = FunderController.class, properties = {
        "funding.auth.users.docs-1=DOCUMENTS_SPECIALIST", "funding.auth.users.supervisor-1=SUPERVISOR"})
@Import({SecurityConfig.class, ProblemHandler.class})
class FunderAuthTest {

    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired private MockMvc mvc;
    @Autowired private UserDirectory users;
    @MockBean private FunderService service;

    private RequestPostProcessor as(String userId) {
        return jwt().jwt(j -> j.subject(userId)).authorities(users.authorities());
    }

    private static MockHttpServletRequestBuilder approve() {
        return post("/api/v1/versions/{v}/approve", 12);
    }

    @Test
    void AS_10_9_noToken401_onReadsAndWrites() throws Exception {
        for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[] {
                get("/api/v1/applications/LA-1006"), get("/api/v1/versions/12/stipulations"), approve()}) {
            mvc.perform(req).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.reason").value("UNAUTHORIZED"));
        }
        verify(service, never()).overview(anyString(), any(Actor.class));
        verify(service, never()).approve(anyLong(), any(Actor.class));
    }

    @Test
    void AS_10_9_oldFunderHeaderIsNotIdentity() throws Exception {
        mvc.perform(approve().header("X-Funder-Id", "docs-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("UNAUTHORIZED"));
        verify(service, never()).approve(anyLong(), any(Actor.class));
    }

    @Test
    void AS_1_1_unknownUser403_evenWithValidToken() throws Exception {
        mvc.perform(get("/api/v1/applications/LA-1006").with(as("stranger"))).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.reason").value("UNKNOWN_USER"));
        mvc.perform(approve().with(as("stranger"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_USER"));
        verify(service, never()).approve(anyLong(), any(Actor.class));
    }

    @Test
    void AS_10_9_actorIsTheTokenSubject() throws Exception {
        when(service.approve(eq(12L), any(Actor.class))).thenReturn(Map.of("status", "APPROVED"));
        mvc.perform(approve().with(as("docs-1"))).andExpect(status().isOk());
        verify(service).approve(eq(12L), eq(new Actor("docs-1", Role.DOCUMENTS_SPECIALIST)));   // the id and role reach the service

        when(service.overview(eq("LA-1006"), any(Actor.class))).thenReturn(Map.of("loanApplicationId", "LA-1006"));
        mvc.perform(get("/api/v1/applications/LA-1006").with(as("supervisor-1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.loanApplicationId").value("LA-1006"));
    }

    @Test
    void otherPathsKeepTheirOwnAccess() throws Exception {
        // Not the funder API: the security chain lets it through (here there's no handler, so 404).
        mvc.perform(get("/api/v1/dev/applications/LA-1006")).andExpect(status().isNotFound());
    }
}
