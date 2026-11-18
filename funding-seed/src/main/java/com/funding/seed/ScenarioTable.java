package com.funding.seed;

import com.funding.domain.Check;
import com.funding.domain.DocumentType;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Renders the generated scenario table in {@code seed-data.md} (constitution P-2.7). */
public final class ScenarioTable {

    public static final String BEGIN = "<!-- BEGIN GENERATED";
    public static final String END = "<!-- END GENERATED -->";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(risc|bo|gap|app)\\.([a-z_]+)}");

    private ScenarioTable() {}

    public static String render(List<Fixture> fixtures) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("| ID | Scenario | Override from baseline | TILA | PRICE | VIN | INCOME | GAP | Expected status | Expected stips | Spec |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Fixture f : fixtures) {
            ScenarioIntent i = f.intent();
            sb.append("| ").append(i.id())
                    .append(" | ").append(i.title())
                    .append(" | ").append(resolve(i.summary(), f));
            for (Check c : Check.values()) {
                sb.append(" | ").append(i.expect().checks().containsKey(c) ? i.expect().checks().get(c).name() : "P");
            }
            String stips = i.expect().stips().isEmpty() ? "—"
                    : i.expect().stips().stream().map(s -> s.replaceFirst("^STIP_", "")).collect(Collectors.joining(" + "));
            sb.append(" | ").append(i.expect().status())
                    .append(" | ").append(stips)
                    .append(" | ").append(i.spec().isBlank() ? "—" : i.spec())
                    .append(" |\n");
        }
        sb.append("\n`P` means PASS, the baseline outcome for that check. Stip codes omit the `STIP_` prefix.\n\n");
        return sb.toString();
    }

    /** {@code {risc.monthly_payment}} etc. resolve to the generated values, so the text can't go stale. */
    static String resolve(String summary, Fixture f) {
        Matcher m = PLACEHOLDER.matcher(summary);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "risc" -> doc(f, DocumentType.RISC, m.group(2));
                case "bo" -> doc(f, DocumentType.BUYERS_ORDER, m.group(2));
                case "gap" -> doc(f, DocumentType.GAP_AGREEMENT, m.group(2));
                default -> app(f, m.group(2));
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String doc(Fixture f, DocumentType type, String key) {
        return f.latest().latestDocuments().stream().filter(d -> d.type() == type).findFirst()
                .map(d -> d.value(key))
                .orElseThrow(() -> new IllegalArgumentException(f.scenarioId() + ": summary references missing " + type + "." + key));
    }

    private static String app(Fixture f, String key) {
        Fixture.Version v = f.latest();
        return switch (key) {
            case "vehicle_vin" -> v.vehicleVin();
            case "stated_income_annual" -> v.statedIncomeAnnual().toPlainString();
            case "dealership_id" -> v.dealershipId();
            default -> throw new IllegalArgumentException(f.scenarioId() + ": unknown summary placeholder app." + key);
        };
    }

    /** Replaces the block between the markers; returns the new document text. */
    public static String splice(String doc, String block) {
        int b = doc.indexOf(BEGIN);
        int e = doc.indexOf(END);
        if (b < 0 || e < b) {
            throw new IllegalStateException("seed-data.md has no generated-table markers");
        }
        int afterBeginLine = doc.indexOf('\n', b) + 1;
        return doc.substring(0, afterBeginLine) + block + doc.substring(e);
    }

    public static String current(String doc) {
        int b = doc.indexOf(BEGIN);
        int e = doc.indexOf(END);
        if (b < 0 || e < b) {
            throw new IllegalStateException("seed-data.md has no generated-table markers");
        }
        return doc.substring(doc.indexOf('\n', b) + 1, e);
    }
}
