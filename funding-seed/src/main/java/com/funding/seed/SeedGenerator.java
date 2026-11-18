package com.funding.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.DocumentType;
import com.funding.domain.GapForm;
import com.funding.domain.money.Amortization;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns scenario intent into concrete fixtures (constitution P-2.1). Derived values come from the
 * domain calculators and the live config; none is typed by hand. Intent vocabulary under
 * {@code given} (merged over {@code _baseline.yaml}):
 *
 * <ul>
 *   <li>{@code risc: {amountFinanced, apr, term, cashPrice}}: base deal terms</li>
 *   <li>{@code risc.totalOfPayments: {offset}}: breaks only the sum; the payment stays correct</li>
 *   <li>{@code risc.monthlyPayment: {offset, rebalance}}: breaks only the payment; with
 *       {@code rebalance: true} total and finance charge are recomputed so the sum still balances</li>
 *   <li>{@code risc.finalPayment: {offset}}: C-9 final payment relative to the computed payment</li>
 *   <li>{@code buyersOrder.totalVehicleCashPrice: {beyondTolerance: smallest}}: RISC cash price +
 *       {@code funding.price.tolerance} + 0.01</li>
 *   <li>{@code <doc>.vin | vehicleYear | vehicleMake | vehicleModel}: a literal, or for VINs
 *       {@code {mutate: lastChar | letterO | lowercase}} applied to the baseline VIN; the same on
 *       {@code vehicle.vin} (every document and the application) and {@code application.vehicleVin}</li>
 *   <li>{@code gap.form: valid | expired | otherState}: picked from the GAP registry relative to the
 *       RISC contract date; {@code gap: null} means no GAP Agreement</li>
 *   <li>{@code <doc>.creditor: primary | other}: {@code funding.gap.primary-lender-name} or a fixed
 *       non-matching name</li>
 *   <li>{@code <doc>.confidence: {field: belowFlag | atFlag}}: {@code funding.confidence.flag-below}
 *       − 0.01, or exactly the cut-off</li>
 *   <li>{@code <doc>.omit: [field]}, {@code <doc>.format: {field: thousandsSeparator}},
 *       {@code risc.extraFields: {key: value}}, {@code risc.itemizationPages: [first, last]}</li>
 * </ul>
 */
public final class SeedGenerator {

    static final String OTHER_LENDER = "Coastal Credit Union";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DOC_COMMON = Set.of("pages", "vin", "vehicleYear", "vehicleMake", "vehicleModel",
            "confidence", "omit", "format");

    /** Allowed keys of the merged intent tree; anything else is a typo and fails generation. */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("", Set.of("application", "vehicle", "confidence", "risc", "buyersOrder", "gap")),
            Map.entry("application", Set.of("borrowerName", "coBorrowerName", "statedIncomeAnnual", "dealer", "vehicleVin")),
            Map.entry("application.dealer", Set.of("name", "id")),
            Map.entry("vehicle", Set.of("vin", "year", "make", "model")),
            Map.entry("risc", union(DOC_COMMON, Set.of("cashPrice", "amountFinanced", "apr", "term", "creditor", "contractDate",
                    "totalOfPayments", "monthlyPayment", "finalPayment", "extraFields", "itemizationPages"))),
            Map.entry("risc.totalOfPayments", Set.of("offset")),
            Map.entry("risc.monthlyPayment", Set.of("offset", "rebalance")),
            Map.entry("risc.finalPayment", Set.of("offset")),
            Map.entry("buyersOrder", union(DOC_COMMON, Set.of("totalVehicleCashPrice"))),
            Map.entry("buyersOrder.totalVehicleCashPrice", Set.of("beyondTolerance")),
            Map.entry("gap", union(DOC_COMMON, Set.of("form", "formNumber", "state", "creditor"))));

    private final SeedConfig config;
    private final List<GapForm> registry;
    private final com.funding.seed.mock.MockCatalog income;

    public SeedGenerator(SeedConfig config, List<GapForm> registry, com.funding.seed.mock.MockCatalog income) {
        this.config = config;
        this.registry = List.copyOf(registry);
        this.income = income;
    }

    public Fixture generate(ScenarioIntent intent, JsonNode baseline) {
        JsonNode g = merge(baseline.deepCopy(), intent.given());
        validateKeys(g, "", intent.id());

        JsonNode app = g.path("application");
        JsonNode veh = g.path("vehicle");
        JsonNode risc = g.path("risc");
        JsonNode bo = g.path("buyersOrder");
        JsonNode gap = g.path("gap");
        BigDecimal conf = new BigDecimal(g.path("confidence").asText("0.97"));

        // --- TILA derivation (Amortization is the same calculator the rule uses) ---
        BigDecimal amountFinanced = money(risc, "amountFinanced");
        BigDecimal apr = new BigDecimal(risc.path("apr").asText());
        int term = risc.path("term").asInt();
        BigDecimal cashPrice = money(risc, "cashPrice");
        BigDecimal correctPayment = Amortization.monthlyPayment(amountFinanced, apr, term);
        BigDecimal finalPayment = risc.has("finalPayment")
                ? Money.round(correctPayment.add(offset(risc.path("finalPayment")))) : null;
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

        // --- Price parity (AS-3): the Buyer's Order follows the RISC unless the intent breaks it ---
        BigDecimal boCashPrice = cashPrice;
        if (bo.has("totalVehicleCashPrice")) {
            String how = bo.path("totalVehicleCashPrice").path("beyondTolerance").asText();
            if (!"smallest".equals(how)) {
                throw new IllegalArgumentException(intent.id() + ": totalVehicleCashPrice.beyondTolerance must be 'smallest'");
            }
            boCashPrice = Money.round(cashPrice.add(config.rules().priceTolerance()).add(new BigDecimal("0.01")));
        }

        // --- Vehicle (US-4) ---
        // A VIN intent mutates the *baseline* VIN, so read the base from the unmerged baseline.
        String baselineVin = baseline.path("vehicle").path("vin").asText();
        String baseVin = vin(veh.path("vin"), baselineVin, intent.id());
        String year = veh.path("year").asText();
        String make = veh.path("make").asText();
        String model = veh.path("model").asText();
        String appVin = app.has("vehicleVin") ? vin(app.path("vehicleVin"), baseVin, intent.id()) : baseVin;
        LocalDate contractDate = LocalDate.parse(risc.path("contractDate").asText());

        // --- RISC ---
        List<Integer> item = new ArrayList<>(List.of(2, 3));
        if (risc.has("itemizationPages")) {
            item = List.of(risc.path("itemizationPages").get(0).asInt(), risc.path("itemizationPages").get(1).asInt());
        }
        DocBuilder rb = new DocBuilder(conf)
                .section("Contract Header", 1, 1)
                .field("vin", vehicleField(risc, "vin", baseVin, intent.id()), 1)
                .field("vehicle_year", vehicleField(risc, "vehicleYear", year, intent.id()), 1)
                .field("vehicle_make", vehicleField(risc, "vehicleMake", make, intent.id()), 1)
                .field("vehicle_model", vehicleField(risc, "vehicleModel", model, intent.id()), 1)
                .field("creditor_name", creditor(risc), 1)
                .field("contract_date", contractDate.toString(), 1);
        risc.path("extraFields").fields().forEachRemaining(e -> rb.field(e.getKey(), e.getValue().asText(), 1));
        rb.section("Itemization of Amount Financed", item.get(0), item.get(1))
                .field("cash_price", Money.format(cashPrice), item.get(0))
                .field("amount_financed", Money.format(amountFinanced), item.get(1))
                .section("Federal Truth-in-Lending Disclosures", 4, 4)
                .field("apr", apr.stripTrailingZeros().toPlainString(), 4)
                .field("term_months", Integer.toString(term), 4)
                .field("monthly_payment", Money.format(statedPayment), 4)
                .field("total_of_payments", Money.format(total), 4)
                .field("finance_charge", Money.format(financeCharge), 4);
        if (finalPayment != null) {
            rb.field("final_payment", Money.format(finalPayment), 4);
        }
        List<Fixture.Document> docs = new ArrayList<>();
        docs.add(rb.apply(risc, this, intent.id()).build(DocumentType.RISC, intent.id() + "-risc-v1.pdf", risc.path("pages").asInt(5)));

        // --- Buyer's Order ---
        docs.add(new DocBuilder(conf)
                .section("Vehicle", 1, 1)
                .field("vin", vehicleField(bo, "vin", baseVin, intent.id()), 1)
                .field("vehicle_year", vehicleField(bo, "vehicleYear", year, intent.id()), 1)
                .field("vehicle_make", vehicleField(bo, "vehicleMake", make, intent.id()), 1)
                .field("vehicle_model", vehicleField(bo, "vehicleModel", model, intent.id()), 1)
                .section("Pricing", 2, 2)
                .field("total_vehicle_cash_price", Money.format(boCashPrice), 2)
                .apply(bo, this, intent.id())
                .build(DocumentType.BUYERS_ORDER, intent.id() + "-buyers-order-v1.pdf", bo.path("pages").asInt(2)));

        // --- GAP Agreement (optional, AS-6.4) ---
        if (!gap.isMissingNode() && !gap.isNull()) {
            String state = gap.path("state").asText();
            String form = gap.has("form") ? pickForm(gap.path("form").asText(), state, contractDate, intent.id())
                    : gap.path("formNumber").asText();
            docs.add(new DocBuilder(conf)
                    .section("GAP Agreement", 1, 1)
                    .field("form_number", form, 1)
                    .field("state", state, 1)
                    .field("creditor_name", creditor(gap), 1)
                    .section("Vehicle", 2, 2)
                    .field("vin", vehicleField(gap, "vin", baseVin, intent.id()), 2)
                    .field("vehicle_year", vehicleField(gap, "vehicleYear", year, intent.id()), 2)
                    .field("vehicle_make", vehicleField(gap, "vehicleMake", make, intent.id()), 2)
                    .field("vehicle_model", vehicleField(gap, "vehicleModel", model, intent.id()), 2)
                    .apply(gap, this, intent.id())
                    .build(DocumentType.GAP_AGREEMENT, intent.id() + "-gap-v1.pdf", gap.path("pages").asInt(3)));
        }

        Fixture.Version version = new Fixture.Version(
                1, true, "DRAFT",
                app.path("borrowerName").asText(),
                app.hasNonNull("coBorrowerName") ? app.get("coBorrowerName").asText() : null,
                "enc:" + config.env() + ":" + intent.id(),
                statedIncome(app.path("statedIncomeAnnual"), intent),
                appVin,
                app.path("dealer").path("name").asText(),
                app.path("dealer").path("id").asText(),
                List.copyOf(docs));

        return new Fixture(intent.id(), intent.title(), intent.id(), List.of(version), externals(intent), registry, intent);
    }

    // ------------------------------------------------------------------ vocabulary

    /**
     * Stated income (US-5): a literal, {@code {relativeToThreshold: d}} = verified × (1 + t + d), or
     * {@code {variance: v}} = verified × (1 + v), where verified is the scenario's income intent's
     * payroll figure and t is {@code funding.income.variance-threshold}.
     */
    BigDecimal statedIncome(JsonNode n, ScenarioIntent intent) {
        if (!n.isObject()) {
            return money(n.asText(), "statedIncomeAnnual");
        }
        String respond = externals(intent).get("income");
        String verified = income == null ? null : income.verifiedFor(respond);
        if (verified == null) {
            throw new IllegalArgumentException(intent.id() + ": a relative stated income needs an income intent with a verified figure, not '"
                    + respond + "'");
        }
        BigDecimal factor;
        if (n.has("relativeToThreshold")) {
            factor = BigDecimal.ONE.add(config.rules().incomeVarianceThreshold()).add(new BigDecimal(n.get("relativeToThreshold").asText().replace("+", "")));
        } else if (n.has("variance")) {
            factor = BigDecimal.ONE.add(new BigDecimal(n.get("variance").asText().replace("+", "")));
        } else {
            throw new IllegalArgumentException(intent.id() + ": statedIncomeAnnual must be a literal, {relativeToThreshold} or {variance}");
        }
        return Money.round(new BigDecimal(verified).multiply(factor));
    }

    /** {@code valid}: effective on the contract date in the GAP state; {@code expired}: same state, ended before;
     *  {@code otherState}: effective on the contract date but registered for a different state. */
    String pickForm(String how, String state, LocalDate contractDate, String id) {
        return registry.stream().filter(f -> switch (how) {
                    case "valid" -> f.stateCode().equals(state) && f.effectiveOn(contractDate);
                    case "expired" -> f.stateCode().equals(state) && f.effectiveTo() != null && f.effectiveTo().isBefore(contractDate);
                    case "otherState" -> !f.stateCode().equals(state) && f.effectiveOn(contractDate);
                    default -> throw new IllegalArgumentException(id + ": gap.form must be valid, expired or otherState");
                }).map(GapForm::formNumber).sorted().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + ": the GAP registry has no '" + how + "' form for " + state
                        + " on " + contractDate));
    }

    String confidence(String how, String id) {
        BigDecimal cut = config.rules().confidenceFlagBelow();
        return switch (how) {
            case "belowFlag" -> cut.subtract(new BigDecimal("0.01")).toPlainString();
            case "atFlag" -> cut.toPlainString();
            default -> throw new IllegalArgumentException(id + ": confidence must be belowFlag or atFlag");
        };
    }

    private static String vehicleField(JsonNode doc, String key, String base, String id) {
        if (!doc.has(key)) {
            return base;
        }
        return "vin".equals(key) ? vin(doc.path(key), base, id) : doc.path(key).asText();
    }

    /** A literal VIN, or {@code {mutate: lastChar | letterO | lowercase}} applied to {@code base}. */
    static String vin(JsonNode n, String base, String id) {
        if (n.isMissingNode() || n.isNull()) {
            return base;
        }
        if (!n.isObject()) {
            return n.asText();
        }
        String how = n.path("mutate").asText();
        return switch (how) {
            case "lastChar" -> {
                char last = base.charAt(base.length() - 1);
                char next = Character.isDigit(last) ? (char) ('0' + (last - '0' + 1) % 10) : (last == 'Z' ? 'A' : (char) (last + 1));
                yield base.substring(0, base.length() - 1) + next;
            }
            case "letterO" -> {
                int i = base.indexOf('0', base.length() - 6);
                if (i < 0) {
                    throw new IllegalArgumentException(id + ": VIN " + base + " has no 0 in its serial to turn into O");
                }
                yield base.substring(0, i) + "O" + base.substring(i + 1);
            }
            case "lowercase" -> base.toLowerCase();
            default -> throw new IllegalArgumentException(id + ": vin.mutate must be lastChar, letterO or lowercase");
        };
    }

    private String creditor(JsonNode doc) {
        String c = doc.path("creditor").asText("primary");
        return switch (c) {
            case "primary" -> config.primaryLender();
            case "other" -> OTHER_LENDER;
            default -> c;
        };
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

    // ------------------------------------------------------------------ document building

    /** Builds one document's sections; {@link #apply} then applies omit / confidence / format intents. */
    static final class DocBuilder {
        private final BigDecimal defaultConfidence;
        private final List<Object[]> sections = new ArrayList<>(); // {name, start, end, List<Field>}

        DocBuilder(BigDecimal defaultConfidence) {
            this.defaultConfidence = defaultConfidence;
        }

        DocBuilder section(String name, int start, int end) {
            sections.add(new Object[] {name, start, end, new ArrayList<Fixture.Field>()});
            return this;
        }

        @SuppressWarnings("unchecked")
        DocBuilder field(String key, String value, int page) {
            List<Fixture.Field> fields = (List<Fixture.Field>) sections.get(sections.size() - 1)[3];
            int h = Math.floorMod(key.hashCode(), 800);
            fields.add(new Fixture.Field(key, value, defaultConfidence, List.of(100 + h, 80, 120 + h, 480), page));
            return this;
        }

        @SuppressWarnings("unchecked")
        DocBuilder apply(JsonNode doc, SeedGenerator gen, String id) {
            Set<String> omit = new java.util.HashSet<>();
            doc.path("omit").forEach(o -> omit.add(o.asText()));
            Map<String, String> conf = new LinkedHashMap<>();
            doc.path("confidence").fields().forEachRemaining(e -> conf.put(e.getKey(), gen.confidence(e.getValue().asText(), id)));
            Map<String, String> fmt = new LinkedHashMap<>();
            doc.path("format").fields().forEachRemaining(e -> fmt.put(e.getKey(), e.getValue().asText()));
            Set<String> known = new java.util.HashSet<>();
            for (Object[] s : sections) {
                List<Fixture.Field> fields = (List<Fixture.Field>) s[3];
                List<Fixture.Field> out = new ArrayList<>();
                for (Fixture.Field f : fields) {
                    known.add(f.key());
                    if (omit.contains(f.key())) {
                        continue;
                    }
                    String value = fmt.containsKey(f.key()) ? format(f.value(), fmt.get(f.key()), id) : f.value();
                    BigDecimal c = conf.containsKey(f.key()) ? new BigDecimal(conf.get(f.key())) : f.confidence();
                    out.add(new Fixture.Field(f.key(), value, c, f.boundingBox(), f.page()));
                }
                s[3] = out;
            }
            for (String k : union(union(omit, conf.keySet()), fmt.keySet())) {
                if (!known.contains(k)) {
                    throw new IllegalArgumentException(id + ": intent names field '" + k + "', which this document doesn't have");
                }
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        Fixture.Document build(DocumentType type, String fileName, int pages) {
            List<Fixture.Section> out = new ArrayList<>();
            for (Object[] s : sections) {
                out.add(new Fixture.Section((String) s[0], (Integer) s[1], (Integer) s[2], List.copyOf((List<Fixture.Field>) s[3])));
            }
            return new Fixture.Document(type, 1, true, fileName, pages, List.copyOf(out));
        }

        private static String format(String value, String how, String id) {
            if (!"thousandsSeparator".equals(how)) {
                throw new IllegalArgumentException(id + ": format must be thousandsSeparator");
            }
            return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US))
                    .format(new BigDecimal(value));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal money(JsonNode n, String key) {
        return money(n.path(key).asText(), key);
    }

    private static BigDecimal money(String raw, String key) {
        return Money.parse(raw).orElseThrow(() -> new IllegalArgumentException("not money: " + key + "=" + raw));
    }

    private static BigDecimal offset(JsonNode n) {
        return new BigDecimal(n.path("offset").asText().replace("+", ""));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
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
            b.set(f.getKey(), existing == null || f.getValue().isNull() ? f.getValue().deepCopy() : merge(existing, f.getValue()));
        });
        return b;
    }

    private static final Set<String> FREE_FORM = Set.of("application.statedIncomeAnnual","risc.confidence", "risc.format", "risc.extraFields",
            "buyersOrder.confidence", "buyersOrder.format", "gap.confidence", "gap.format",
            "risc.vin", "buyersOrder.vin", "gap.vin", "vehicle.vin", "application.vehicleVin");

    private static void validateKeys(JsonNode n, String path, String id) {
        Set<String> allowed = ALLOWED.get(path);
        if (allowed == null || !n.isObject() || FREE_FORM.contains(path)) {
            return;
        }
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(id + ": unknown intent key '" + (path.isEmpty() ? k : path + "." + k)
                        + "' (allowed: " + new java.util.TreeSet<>(allowed) + ")");
            }
            validateKeys(n.get(k), path.isEmpty() ? k : path + "." + k, id);
        }
    }

    /** Canonical JSON of a fixture, for {@code target/generated-seed/}. */
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
