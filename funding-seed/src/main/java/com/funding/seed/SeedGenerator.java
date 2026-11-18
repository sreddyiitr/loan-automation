package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.DocumentType;
import com.funding.domain.money.Amortization;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns scenario intent into concrete fixtures (constitution P-2.1). Derived numbers (payment,
 * total of payments, finance charge) come from the domain calculators; none is typed by hand.
 *
 * <p>Intent vocabulary handled here, under {@code given} (merged over {@code _baseline.yaml}):
 * <ul>
 *   <li>{@code risc: {amountFinanced, apr, term, cashPrice}} — base deal terms</li>
 *   <li>{@code risc.totalOfPayments: {offset}} — breaks only the sum; payment stays correct</li>
 *   <li>{@code risc.monthlyPayment: {offset, rebalance}} — breaks only the payment; with
 *       {@code rebalance: true} total and finance charge are recomputed so the sum still balances</li>
 *   <li>{@code risc.finalPayment: {offset}} — C-9 final payment relative to the computed payment</li>
 * </ul>
 */
public final class SeedGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Allowed keys of the merged intent tree; anything else is a typo and fails generation. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "", Set.of("application", "vehicle", "confidence", "risc", "buyersOrder", "gap"),
            "application", Set.of("borrowerName", "coBorrowerName", "statedIncomeAnnual", "dealer"),
            "application.dealer", Set.of("name", "id"),
            "vehicle", Set.of("vin", "year", "make", "model"),
            "risc", Set.of("pages", "cashPrice", "amountFinanced", "apr", "term", "creditor", "contractDate",
                    "totalOfPayments", "monthlyPayment", "finalPayment"),
            "risc.totalOfPayments", Set.of("offset"),
            "risc.monthlyPayment", Set.of("offset", "rebalance"),
            "risc.finalPayment", Set.of("offset"),
            "buyersOrder", Set.of("pages"),
            "gap", Set.of("pages", "formNumber", "state", "creditor"));

    private final SeedConfig config;

    public SeedGenerator(SeedConfig config) {
        this.config = config;
    }

    public Fixture generate(ScenarioIntent intent, JsonNode baseline) {
        JsonNode g = merge(baseline.deepCopy(), intent.given());
        validateKeys(g, "", intent.id());

        JsonNode app = g.path("application");
        JsonNode veh = g.path("vehicle");
        JsonNode risc = g.path("risc");
        BigDecimal conf = new BigDecimal(g.path("confidence").asText("0.97"));

        // --- TILA derivation (Amortization is the same calculator the rule uses) ---
        BigDecimal amountFinanced = money(risc, "amountFinanced");
        BigDecimal apr = new BigDecimal(risc.path("apr").asText());
        int term = risc.path("term").asInt();
        BigDecimal cashPrice = money(risc, "cashPrice");

        BigDecimal correctPayment = Amortization.monthlyPayment(amountFinanced, apr, term);
        BigDecimal finalPayment = risc.has("finalPayment")
                ? Money.round(correctPayment.add(offset(risc.path("finalPayment"))))
                : null;
        BigDecimal statedPayment = correctPayment;
        BigDecimal total = Amortization.scheduledTotal(correctPayment, term, finalPayment);
        if (risc.has("monthlyPayment")) {
            statedPayment = Money.round(correctPayment.add(offset(risc.path("monthlyPayment"))));
            if (risc.path("monthlyPayment").path("rebalance").asBoolean(false)) {
                total = Amortization.scheduledTotal(statedPayment, term, finalPayment);
            }
        }
        BigDecimal financeCharge = Money.round(total.subtract(amountFinanced));
        if (risc.has("totalOfPayments")) {
            total = Money.round(total.add(offset(risc.path("totalOfPayments"))));
        }

        String vin = veh.path("vin").asText();
        String year = veh.path("year").asText();
        String make = veh.path("make").asText();
        String model = veh.path("model").asText();
        String contractDate = risc.path("contractDate").asText();

        // --- RISC: Itemization spans pages 2–3 (multi-page section) ---
        List<Fixture.Section> riscSections = List.of(
                section("Contract Header", 1, 1,
                        field("vin", vin, conf, 1), field("vehicle_year", year, conf, 1),
                        field("vehicle_make", make, conf, 1), field("vehicle_model", model, conf, 1),
                        field("creditor_name", creditor(risc), conf, 1),
                        field("contract_date", contractDate, conf, 1)),
                section("Itemization of Amount Financed", 2, 3,
                        field("cash_price", Money.format(cashPrice), conf, 2),
                        field("amount_financed", Money.format(amountFinanced), conf, 3)),
                section("Federal Truth-in-Lending Disclosures", 4, 4, tilaFields(
                        apr, term, statedPayment, total, financeCharge, finalPayment, conf)));
        Fixture.Document riscDoc = new Fixture.Document(DocumentType.RISC, 1, true,
                intent.id() + "-risc-v1.pdf", risc.path("pages").asInt(5), riscSections);

        Fixture.Document bo = new Fixture.Document(DocumentType.BUYERS_ORDER, 1, true,
                intent.id() + "-buyers-order-v1.pdf", g.path("buyersOrder").path("pages").asInt(2), List.of(
                section("Vehicle", 1, 1,
                        field("vin", vin, conf, 1), field("vehicle_year", year, conf, 1),
                        field("vehicle_make", make, conf, 1), field("vehicle_model", model, conf, 1)),
                section("Pricing", 2, 2,
                        field("total_vehicle_cash_price", Money.format(cashPrice), conf, 2))));

        List<Fixture.Document> docs = new ArrayList<>(List.of(riscDoc, bo));
        JsonNode gap = g.path("gap");
        if (!gap.isMissingNode() && !gap.isNull()) {
            docs.add(new Fixture.Document(DocumentType.GAP_AGREEMENT, 1, true,
                    intent.id() + "-gap-v1.pdf", gap.path("pages").asInt(3), List.of(
                    section("GAP Agreement", 1, 1,
                            field("form_number", gap.path("formNumber").asText(), conf, 1),
                            field("state", gap.path("state").asText(), conf, 1),
                            field("creditor_name", creditor(gap), conf, 1)),
                    section("Vehicle", 2, 2,
                            field("vin", vin, conf, 2), field("vehicle_year", year, conf, 2),
                            field("vehicle_make", make, conf, 2), field("vehicle_model", model, conf, 2)))));
        }

        Fixture.Version version = new Fixture.Version(
                1, true, "DRAFT",
                app.path("borrowerName").asText(),
                app.hasNonNull("coBorrowerName") ? app.get("coBorrowerName").asText() : null,
                "enc:" + config.env() + ":" + intent.id(),
                money(app, "statedIncomeAnnual"),
                vin,
                app.path("dealer").path("name").asText(),
                app.path("dealer").path("id").asText(),
                List.copyOf(docs));

        return new Fixture(intent.id(), intent.title(), intent.id(), List.of(version),
                externals(intent), intent);
    }

    private static List<Fixture.Field> tilaFields(BigDecimal apr, int term, BigDecimal payment, BigDecimal total,
                                                  BigDecimal financeCharge, BigDecimal finalPayment, BigDecimal conf) {
        List<Fixture.Field> f = new ArrayList<>(List.of(
                field("apr", apr.stripTrailingZeros().toPlainString(), conf, 4),
                field("term_months", Integer.toString(term), conf, 4),
                field("monthly_payment", Money.format(payment), conf, 4),
                field("total_of_payments", Money.format(total), conf, 4),
                field("finance_charge", Money.format(financeCharge), conf, 4)));
        if (finalPayment != null) {
            f.add(field("final_payment", Money.format(finalPayment), conf, 4));
        }
        return List.copyOf(f);
    }

    /** Defaults: income {@code ok-100k}, disbursement {@code SUCCESS}; a scenario can override either. */
    private static Map<String, String> externals(ScenarioIntent intent) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("income", "ok-100k");
        m.put("disbursement", "SUCCESS");
        JsonNode e = intent.externals();
        if (e != null && !e.isMissingNode()) {
            e.fields().forEachRemaining(f -> {
                if (!m.containsKey(f.getKey())) {
                    throw new IllegalArgumentException(intent.id() + ": unknown external API '" + f.getKey() + "'");
                }
                m.put(f.getKey(), f.getValue().path("respond").asText());
            });
        }
        return Map.copyOf(m);
    }

    private String creditor(JsonNode doc) {
        String c = doc.path("creditor").asText("primary");
        return "primary".equals(c) ? config.primaryLender() : c;
    }

    private static Fixture.Section section(String name, int start, int end, Fixture.Field... fields) {
        return section(name, start, end, List.of(fields));
    }

    private static Fixture.Section section(String name, int start, int end, List<Fixture.Field> fields) {
        return new Fixture.Section(name, start, end, fields);
    }

    private static Fixture.Field field(String key, String value, BigDecimal conf, int page) {
        // Bounding boxes are deterministic placeholders (ymin, xmin, ymax, xmax) per key.
        int h = Math.floorMod(key.hashCode(), 800);
        return new Fixture.Field(key, value, conf, List.of(100 + h, 80, 120 + h, 480), page);
    }

    private static BigDecimal money(JsonNode n, String key) {
        String raw = n.path(key).asText();
        return Money.parse(raw).orElseThrow(() -> new IllegalArgumentException("not money: " + key + "=" + raw));
    }

    private static BigDecimal offset(JsonNode n) {
        return new BigDecimal(n.path("offset").asText().replace("+", ""));
    }

    static JsonNode merge(JsonNode base, JsonNode over) {
        if (over == null || over.isMissingNode()) {
            return base;
        }
        if (!(base instanceof ObjectNode b) || !over.isObject()) {
            return over.deepCopy();
        }
        over.fields().forEachRemaining(f -> {
            JsonNode existing = b.get(f.getKey());
            b.set(f.getKey(), existing == null ? f.getValue().deepCopy() : merge(existing, f.getValue()));
        });
        return b;
    }

    private static void validateKeys(JsonNode n, String path, String id) {
        Set<String> allowed = ALLOWED.get(path);
        if (allowed == null || !n.isObject()) {
            return;
        }
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(id + ": unknown intent key '" + (path.isEmpty() ? k : path + "." + k)
                        + "' (allowed: " + allowed + ")");
            }
            validateKeys(n.get(k), path.isEmpty() ? k : path + "." + k, id);
        }
    }

    /** Canonical JSON of a fixture, for {@code target/generated-seed/} and fingerprints. */
    public static String toJson(Fixture f) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenarioId", f.scenarioId());
            m.put("versions", f.versions());
            m.put("externals", new java.util.TreeMap<>(f.externals()));
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
