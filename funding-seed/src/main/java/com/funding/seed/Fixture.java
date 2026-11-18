package com.funding.seed;

import com.funding.domain.DocumentType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Concrete, generated seed data for one scenario. Every derived number comes from the domain calculators. */
public record Fixture(
        String scenarioId,
        String title,
        String loanApplicationId,
        List<Version> versions,
        Map<String, String> externals,
        ScenarioIntent intent) {

    public Version latest() {
        return versions.stream().filter(Version::latest).findFirst().orElseThrow();
    }

    public record Version(
            int versionNumber,
            boolean latest,
            String status,
            String borrowerName,
            String coBorrowerName,
            String ssnItinToken,
            BigDecimal statedIncomeAnnual,
            String vehicleVin,
            String dealershipName,
            String dealershipId,
            List<Document> documents) {

        public List<Document> latestDocuments() {
            return documents.stream().filter(Document::latest).toList();
        }
    }

    public record Document(
            DocumentType type,
            int documentVersion,
            boolean latest,
            String fileName,
            int totalPages,
            List<Section> sections) {

        public String value(String key) {
            return sections.stream().flatMap(s -> s.fields().stream())
                    .filter(f -> f.key().equals(key)).map(Field::value).findFirst().orElse(null);
        }
    }

    public record Section(String name, int startPage, int endPage, List<Field> fields) {}

    public record Field(String key, String value, BigDecimal confidence, List<Integer> boundingBox, Integer page) {}
}
