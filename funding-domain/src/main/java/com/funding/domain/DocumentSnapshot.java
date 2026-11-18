package com.funding.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The latest document of one type. {@code fields} is already resolved per §8: if a key appears in
 * several sections, the highest-confidence value wins.
 */
public record DocumentSnapshot(DocumentType type, long documentId, Map<String, FieldValue> fields) {
    public DocumentSnapshot {
        fields = Map.copyOf(fields);
    }

    /** §8: if a key appears in more than one section, the value with the highest confidence wins. */
    public static DocumentSnapshot resolve(DocumentType type, long documentId, List<ExtractedField> rows) {
        Map<String, FieldValue> best = new LinkedHashMap<>();
        for (ExtractedField f : rows) {
            FieldValue current = best.get(f.key());
            if (current == null || f.confidence().compareTo(current.confidence()) > 0) {
                best.put(f.key(), new FieldValue(f.raw(), f.confidence()));
            }
        }
        return new DocumentSnapshot(type, documentId, best);
    }
}
