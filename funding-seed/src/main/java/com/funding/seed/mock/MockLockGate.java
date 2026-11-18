package com.funding.seed.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P-5.5 "no silent outcome flips": a stub whose expected outcome differs from {@code mocks.lock}
 * blocks {@code make seed-regenerate} unless {@code ACCEPT_OUTCOME_CHANGES=<RULE_ID>} names a rule
 * that consumes the stub's API (or the API itself, for stubs no rule consumes) <b>and</b>
 * {@code test/data/RULE-CHANGES.md} has a dated entry headed with that id.
 */
public final class MockLockGate {

    public record Flip(String stubId, String before, String after, String rules) {}

    private MockLockGate() {}

    public static List<Flip> flips(Map<String, MocksLock.Entry> was, Map<String, MocksLock.Entry> now) {
        List<Flip> out = new ArrayList<>();
        now.forEach((id, e) -> {
            MocksLock.Entry w = was.get(id);
            if (w != null && !Objects.equals(w.expect(), e.expect())) {
                out.add(new Flip(id, w.expect(), e.expect(), e.rules()));
            }
        });
        return out;
    }

    /** Refusal messages; empty means every flip is accepted and documented. */
    public static List<String> refusals(List<Flip> flips, Set<String> accepted, String ruleChangesMd) {
        List<String> out = new ArrayList<>();
        for (Flip f : flips) {
            List<String> owners = owners(f);
            String id = owners.stream().filter(accepted::contains).findFirst().orElse(null);
            if (id == null) {
                out.add(f.stubId() + ": expected outcome " + f.before() + " -> " + f.after()
                        + ". Rerun with ACCEPT_OUTCOME_CHANGES=" + String.join("|", owners) + " after adding a RULE-CHANGES.md entry.");
            } else if (!documented(id, ruleChangesMd)) {
                out.add(f.stubId() + ": ACCEPT_OUTCOME_CHANGES=" + id + " but test/data/RULE-CHANGES.md has no dated '## " + id
                        + "' entry explaining why the flips are correct.");
            }
        }
        return out;
    }

    /** Rule ids from the lock's {@code rules} column ({@code INCOME_VARIANCE@abc,…}), or the API for rule-less stubs. */
    static List<String> owners(Flip f) {
        List<String> ids = new ArrayList<>();
        if (f.rules() != null && !"none".equals(f.rules())) {
            for (String r : f.rules().split(",")) {
                ids.add(r.contains("@") ? r.substring(0, r.indexOf('@')) : r);
            }
        }
        if (ids.isEmpty()) {
            ids.add(f.stubId().substring(0, f.stubId().indexOf('/')));
        }
        return ids;
    }

    /** An entry is {@code ## <ID>} followed (before the next heading) by a line {@code Date: YYYY-MM-DD}. */
    static boolean documented(String id, String md) {
        if (md == null) {
            return false;
        }
        return Pattern.compile("(?m)^## " + Pattern.quote(id) + "\\b[^\\n]*\\n(?:(?!^## )[\\s\\S])*?^Date: \\d{4}-\\d{2}-\\d{2}")
                .matcher(md).find();
    }
}
