# Rule changes that flip expected mock outcomes

Constitution P-5.5. `make seed-regenerate` refuses to rewrite `test/data/mocks.lock` when a stub's
expected outcome changes, unless it runs with `ACCEPT_OUTCOME_CHANGES=<RULE_ID>` **and** this file
has a dated entry for that rule. A changed threshold moves the bands with it and flips nothing;
only a real change in rule logic does, and that is what a person must review here.

Entry format (newest first):

```
## <RULE_ID>
Date: YYYY-MM-DD
Author: <who>
What changed: <the rule change>
Why the flips are correct: <reasoning, and which stubs flipped (see target/seed-report/mock-impact.md)>
```

<!-- No entries yet. -->
