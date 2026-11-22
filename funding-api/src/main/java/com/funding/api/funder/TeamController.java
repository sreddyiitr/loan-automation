package com.funding.api.funder;

import com.funding.api.ApiProblem;
import com.funding.api.auth.Actor;
import com.funding.api.auth.UserDirectory;
import com.funding.domain.Role;
import com.funding.persistence.FunderQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is signed in, and who's on the team (spec 002 US-12, FR-13): the agent shows "Suggest
 * assignments" to supervisors only, and its tools read each user's role and workload.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Funder")
@SecurityRequirement(name = "bearerAuth")
public class TeamController {
    private final UserDirectory directory;
    private final FunderQueries queries;

    public TeamController(UserDirectory directory, JdbcClient jdbc) {
        this.directory = directory;
        this.queries = new FunderQueries(jdbc);
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user", description = "Spec 002: the token's user id and their role from the directory")
    public Map<String, Object> me(@Parameter(hidden = true) Actor actor) {
        return Map.of("id", actor.id(), "role", actor.role().name());
    }

    @GetMapping("/users")
    @Operation(summary = "Every user, with their role and open deals",
            description = "Spec 002 FR-13 (US-12): supervisor only; openDeals counts the open latest versions assigned to each user")
    public List<Map<String, Object>> users(@Parameter(hidden = true) Actor actor) {
        if (actor.role() != Role.SUPERVISOR) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED", "the team list is the supervisor's; you are " + actor.role());
        }
        Map<String, Long> open = queries.openDealCounts();
        List<Map<String, Object>> out = new ArrayList<>();
        directory.users().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", e.getKey());
            u.put("role", e.getValue().name());
            u.put("openDeals", open.getOrDefault(e.getKey(), 0L));
            out.add(u);
        });
        return out;
    }
}
