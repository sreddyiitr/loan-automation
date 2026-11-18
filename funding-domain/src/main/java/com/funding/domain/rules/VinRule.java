package com.funding.domain.rules;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.DocumentType;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * US-4 VIN and vehicle consistency. Rule ids {@code VIN_FORMAT} (17 characters, no I/O/Q),
 * {@code VIN_CROSS_DOC} (same VIN on every document), {@code VEHICLE_ATTRS} (year, make, model) and
 * {@code VIN_APP_MATCH} (documents = {@code application_versions.vehicle_vin}).
 *
 * <p>AS-4.3: VIN is compared after upper-casing only; make and model ignore case and surrounding
 * whitespace; no other normalization. The GAP Agreement takes part only when present (AS-6.4).
 */
public final class VinRule implements CheckRule {

    private static final Pattern VIN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
    private static final List<DocumentType> ORDER = List.of(DocumentType.RISC, DocumentType.BUYERS_ORDER, DocumentType.GAP_AGREEMENT);

    @Override
    public Check check() {
        return Check.VIN;
    }

    @Override
    public CheckOutcome evaluate(Snapshot s, RulesConfig config) {
        FieldReader r = new FieldReader(s, config);
        List<DocumentType> docs = ORDER.stream()
                .filter(d -> d != DocumentType.GAP_AGREEMENT || s.document(d).isPresent()).toList();
        Map<DocumentType, String> vin = new LinkedHashMap<>();
        Map<DocumentType, Integer> year = new LinkedHashMap<>();
        Map<DocumentType, String> make = new LinkedHashMap<>();
        Map<DocumentType, String> model = new LinkedHashMap<>();
        for (DocumentType d : docs) {
            vin.put(d, r.exact(d, "vin"));
            year.put(d, r.year(d, "vehicle_year"));
            make.put(d, r.text(d, "vehicle_make"));
            model.put(d, r.text(d, "vehicle_model"));
        }
        String appVin = s.vehicleVin();
        if (!r.ok()) {
            return RuleSupport.needsReview(Check.VIN, r.problems());
        }

        List<Comparison> c = new ArrayList<>();
        String reference = upper(vin.get(DocumentType.RISC));
        for (DocumentType d : docs) {
            String v = upper(vin.get(d));
            c.add(new Comparison("vin format (" + d + ")", "17 chars, no I/O/Q", vin.get(d), VIN.matcher(v).matches()));
        }
        c.add(new Comparison("vin format (application)", "17 chars, no I/O/Q", appVin, appVin != null && VIN.matcher(upper(appVin)).matches()));
        for (DocumentType d : docs.subList(1, docs.size())) {
            c.add(new Comparison("vin (RISC vs " + d + ")", vin.get(DocumentType.RISC), vin.get(d), reference.equals(upper(vin.get(d)))));
            c.add(new Comparison("vehicle_year (RISC vs " + d + ")", str(year.get(DocumentType.RISC)), str(year.get(d)),
                    year.get(DocumentType.RISC).equals(year.get(d))));
            c.add(new Comparison("vehicle_make (RISC vs " + d + ")", make.get(DocumentType.RISC), make.get(d),
                    make.get(DocumentType.RISC).equalsIgnoreCase(make.get(d))));
            c.add(new Comparison("vehicle_model (RISC vs " + d + ")", model.get(DocumentType.RISC), model.get(d),
                    model.get(DocumentType.RISC).equalsIgnoreCase(model.get(d))));
        }
        c.add(new Comparison("vin (RISC vs application)", appVin, vin.get(DocumentType.RISC), reference.equals(upper(appVin))));

        if (c.stream().allMatch(Comparison::ok)) {
            return RuleSupport.pass(Check.VIN, c);
        }
        String perDoc = docs.stream().map(d -> d + "=" + vin.get(d) + " " + year.get(d) + " " + make.get(d) + " " + model.get(d))
                .collect(Collectors.joining("; "));
        return RuleSupport.fail(Check.VIN, c, List.of(new StipRequest(StipCodes.VIN_MISMATCH,
                "VIN/vehicle mismatch: " + RuleSupport.describeFailures(c) + ". Values: " + perDoc + "; application=" + appVin)));
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static String str(Integer i) {
        return i == null ? "" : i.toString();
    }
}
