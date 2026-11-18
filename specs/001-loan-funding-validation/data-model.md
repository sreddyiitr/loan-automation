# Data Model – Spec 001

The base schema comes from `auto_funding_tasks.md` §2. Changes and additions are marked **[ADDED]** or **[CHANGED]** with the reason. Migrations are managed by Flyway (`V1__baseline.sql`, …).

## Base tables (source, with changes)

```sql
CREATE TABLE loan_applications (
    loan_application_id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_versions (
    application_version_id SERIAL PRIMARY KEY,
    loan_application_id VARCHAR(64) NOT NULL REFERENCES loan_applications ON DELETE CASCADE,
    version_number INT NOT NULL DEFAULT 1,
    is_latest_version BOOLEAN NOT NULL DEFAULT TRUE,
    borrower_name VARCHAR(255) NOT NULL,
    co_borrower_name VARCHAR(255),
    ssn_itin_encrypted VARCHAR(255),
    stated_income_annual NUMERIC(12,2),
    vehicle_vin VARCHAR(17) NOT NULL,
    dealership_name VARCHAR(255) NOT NULL,
    dealership_id VARCHAR(64) NOT NULL,
    -- [CHANGED] added FUNDED; constrained values
    version_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
        CHECK (version_status IN ('DRAFT','PENDING_REVIEW','STIPULATED','APPROVED','FUNDED','REJECTED','EXPIRED')),
    submitted_at TIMESTAMPTZ,                              -- [ADDED] first entry into PENDING_REVIEW; expiry clock (AS-8.6)
    assigned_funder VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (loan_application_id, version_number)          -- [ADDED]
);
CREATE UNIQUE INDEX idx_unique_active_app_version
    ON application_versions (loan_application_id) WHERE is_latest_version;
```

**[CHANGED] Default status `DRAFT`.** OCR writes documents into a `DRAFT` version and then flips it to `PENDING_REVIEW`. That flip is the trigger. If the version were created directly in `PENDING_REVIEW`, the trigger could fire before the documents exist.

```sql
CREATE TABLE documents (
    document_id SERIAL PRIMARY KEY,
    application_version_id INT NOT NULL REFERENCES application_versions ON DELETE CASCADE,
    document_type VARCHAR(50) NOT NULL
        CHECK (document_type IN ('RISC','BUYERS_ORDER','GAP_AGREEMENT')),
    document_version_number INT NOT NULL DEFAULT 1,
    is_latest_doc BOOLEAN NOT NULL DEFAULT TRUE,
    file_name VARCHAR(255) NOT NULL,
    total_pages INT NOT NULL CHECK (total_pages > 0),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_unique_active_doc_version
    ON documents (application_version_id, document_type) WHERE is_latest_doc;
-- [ADDED] no two v2 RISCs for one application version
CREATE UNIQUE INDEX idx_unique_doc_version_number
    ON documents (application_version_id, document_type, document_version_number);

CREATE TABLE document_sections (
    section_id SERIAL PRIMARY KEY,
    document_id INT NOT NULL REFERENCES documents ON DELETE CASCADE,
    section_name VARCHAR(100) NOT NULL,
    start_page INT NOT NULL,
    end_page INT NOT NULL,
    CHECK (start_page >= 1 AND end_page >= start_page)   -- [ADDED]
);

CREATE TABLE extracted_fields (
    field_id SERIAL PRIMARY KEY,
    section_id INT NOT NULL REFERENCES document_sections ON DELETE CASCADE,
    field_key VARCHAR(100) NOT NULL,
    field_value TEXT,
    confidence_score NUMERIC(3,2) NOT NULL CHECK (confidence_score BETWEEN 0 AND 1),
    bounding_box JSONB,                 -- [ymin, xmin, ymax, xmax]
    page_number INT                     -- [ADDED] which page of the span holds the box
    -- [CHANGED] source had: is_flagged GENERATED ALWAYS AS (confidence_score < 0.90) STORED.
    -- Removed (owner decision B4): flagging is driven by funding.confidence.flag-below (default 0.90)
    -- and computed by the app (domain ConfidencePolicy; the API returns is_flagged per field).
);
CREATE INDEX idx_fields_section_key ON extracted_fields (section_id, field_key);

CREATE TABLE stipulations (
    stip_id SERIAL PRIMARY KEY,
    application_version_id INT NOT NULL REFERENCES application_versions ON DELETE CASCADE,
    triggered_by_document_id INT REFERENCES documents(document_id),
    validation_check VARCHAR(20),       -- [ADDED] TILA|PRICE|VIN|INCOME|GAP|NULL(manual)
    stip_code VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SATISFIED','WAIVED')),
    resolution_note TEXT,               -- [ADDED]
    resolved_by VARCHAR(100),           -- [ADDED]
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ
);
-- [ADDED] FR-07 idempotent stip creation under activity retries. Per check, so a shared code
-- (STIP_EXTRACTION_REVIEW_REQUIRED) from two checks is two stips. NULLS NOT DISTINCT (PG 15+)
-- makes manual stips (validation_check NULL) unique per code too.
CREATE UNIQUE INDEX idx_unique_pending_stip
    ON stipulations (application_version_id, stip_code, validation_check) NULLS NOT DISTINCT
    WHERE status = 'PENDING';
```

## Added tables

```sql
-- Per-check outcome; backs the mismatches/reconciliation view (FR-06)
CREATE TABLE validation_results (
    result_id SERIAL PRIMARY KEY,
    application_version_id INT NOT NULL REFERENCES application_versions ON DELETE CASCADE,
    validation_check VARCHAR(20) NOT NULL
        CHECK (validation_check IN ('TILA','PRICE','VIN','INCOME','GAP')),
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('PASS','FAIL','NEEDS_REVIEW','ERROR')),
    details JSONB NOT NULL,             -- {comparisons:[{field, sources:{RISC:..,BUYERS_ORDER:..}, expected, actual}], flagged_fields:[..], document_ids:{..}}
    attempt INT NOT NULL DEFAULT 1,     -- 1 = first run; +1 per re-run (history kept for the dashboard)
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    workflow_run_id VARCHAR(100),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (application_version_id, validation_check, attempt)
);
CREATE UNIQUE INDEX idx_current_result
    ON validation_results (application_version_id, validation_check) WHERE is_current;

-- Transactional outbox for the SQS trigger (FR-02, AS-1.8)
CREATE TABLE outbox_events (
    event_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,    -- 'APPLICATION_VERSION_PENDING_REVIEW' | 'DOCUMENT_VERSION_ADDED'
    payload JSONB NOT NULL,             -- {event_type, loan_application_id, application_version_id[, document_id]}
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (event_id) WHERE published_at IS NULL;

-- DB trigger: write outbox row in the same txn as the status flip
CREATE FUNCTION enqueue_pending_review() RETURNS trigger AS $$
BEGIN
  IF NEW.version_status = 'PENDING_REVIEW'
     AND (TG_OP = 'INSERT' OR OLD.version_status = 'DRAFT') THEN     -- only the first submission triggers; re-runs go STIPULATED→PENDING_REVIEW without a new workflow
    INSERT INTO outbox_events (event_type, payload)
    VALUES ('APPLICATION_VERSION_PENDING_REVIEW',
            jsonb_build_object('event_type', 'APPLICATION_VERSION_PENDING_REVIEW',
                               'loan_application_id', NEW.loan_application_id,
                               'application_version_id', NEW.application_version_id));
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_pending_review AFTER INSERT OR UPDATE OF version_status
  ON application_versions FOR EACH ROW EXECUTE FUNCTION enqueue_pending_review();

-- [ADDED] AS-8.6: stamp the expiry clock on first submission; never overwritten
CREATE FUNCTION stamp_submitted_at() RETURNS trigger AS $$
BEGIN
  IF NEW.version_status = 'PENDING_REVIEW' AND NEW.submitted_at IS NULL THEN
    NEW.submitted_at := CURRENT_TIMESTAMP;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_stamp_submitted_at BEFORE INSERT OR UPDATE OF version_status
  ON application_versions FOR EACH ROW EXECUTE FUNCTION stamp_submitted_at();

-- [ADDED] AS-1.8: a new latest document on a version under review triggers a re-run
CREATE FUNCTION enqueue_document_added() RETURNS trigger AS $$
DECLARE v application_versions%ROWTYPE;
BEGIN
  SELECT * INTO v FROM application_versions WHERE application_version_id = NEW.application_version_id;
  IF NEW.is_latest_doc AND v.is_latest_version
     AND v.version_status IN ('PENDING_REVIEW','STIPULATED') THEN
    INSERT INTO outbox_events (event_type, payload)
    VALUES ('DOCUMENT_VERSION_ADDED',
            jsonb_build_object('event_type', 'DOCUMENT_VERSION_ADDED',
                               'loan_application_id', v.loan_application_id,
                               'application_version_id', v.application_version_id,
                               'document_id', NEW.document_id));
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_added AFTER INSERT ON documents
  FOR EACH ROW EXECUTE FUNCTION enqueue_document_added();
-- Retention: OutboxRelay deletes rows published more than 7 days ago.

-- [ADDED] AS-9.7 / owner decision B5: documents freeze once a version is approved or final
CREATE FUNCTION refuse_frozen_documents() RETURNS trigger AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM application_versions
             WHERE application_version_id = NEW.application_version_id
               AND version_status IN ('APPROVED','FUNDED','REJECTED','EXPIRED')) THEN
    RAISE EXCEPTION 'DOCUMENTS_FROZEN: application version % is %; create a new application version',
      NEW.application_version_id,
      (SELECT version_status FROM application_versions WHERE application_version_id = NEW.application_version_id)
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_documents_frozen BEFORE INSERT ON documents
  FOR EACH ROW EXECUTE FUNCTION refuse_frozen_documents();

-- State GAP form registry (US-6)
CREATE TABLE gap_form_registry (
    state_code CHAR(2) NOT NULL,
    form_number VARCHAR(50) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    PRIMARY KEY (state_code, form_number, effective_from)
);

-- Audit trail (FR-10)
CREATE TABLE audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,   -- APPLICATION_VERSION | STIPULATION | ASSIGNMENT
    entity_id VARCHAR(64) NOT NULL,
    action VARCHAR(50) NOT NULL,
    actor VARCHAR(100) NOT NULL,        -- funder id or 'system:<workflow-id>'
    before_state JSONB,
    after_state JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- [ADDED] local profile only (created by a 'local' Flyway location): which scenario + fingerprint seeded each application
CREATE TABLE seed_scenarios (
    loan_application_id VARCHAR(64) PRIMARY KEY REFERENCES loan_applications ON DELETE CASCADE,
    scenario_id VARCHAR(20) NOT NULL,
    fingerprint CHAR(64) NOT NULL,      -- hash of scenario intent + rule fingerprints + effective config
    seeded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Disbursement idempotency record (US-8)
CREATE TABLE disbursements (
    application_version_id INT PRIMARY KEY REFERENCES application_versions,
    external_reference VARCHAR(100),
    amount NUMERIC(12,2) NOT NULL,      -- RISC amount_financed (AS-8.9)
    dealership_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','COMPLETED','FAILED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
```
