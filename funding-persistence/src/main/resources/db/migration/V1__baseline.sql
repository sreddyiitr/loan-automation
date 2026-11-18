-- Spec 001 data model (specs/001-loan-funding-validation/data-model.md), slice S1.
-- Not here yet: the document-freeze trigger (V2, slice S2) and the document-added re-run trigger (V3, slice S5).

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
    version_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
        CHECK (version_status IN ('DRAFT','PENDING_REVIEW','STIPULATED','APPROVED','FUNDED','REJECTED','EXPIRED')),
    submitted_at TIMESTAMPTZ,
    assigned_funder VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (loan_application_id, version_number)
);
CREATE UNIQUE INDEX idx_unique_active_app_version
    ON application_versions (loan_application_id) WHERE is_latest_version;

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
CREATE UNIQUE INDEX idx_unique_doc_version_number
    ON documents (application_version_id, document_type, document_version_number);

CREATE TABLE document_sections (
    section_id SERIAL PRIMARY KEY,
    document_id INT NOT NULL REFERENCES documents ON DELETE CASCADE,
    section_name VARCHAR(100) NOT NULL,
    start_page INT NOT NULL,
    end_page INT NOT NULL,
    CHECK (start_page >= 1 AND end_page >= start_page)
);

-- No is_flagged column (owner decision B4): flagging follows funding.confidence.flag-below, computed by the app.
CREATE TABLE extracted_fields (
    field_id SERIAL PRIMARY KEY,
    section_id INT NOT NULL REFERENCES document_sections ON DELETE CASCADE,
    field_key VARCHAR(100) NOT NULL,
    field_value TEXT,
    confidence_score NUMERIC(3,2) NOT NULL CHECK (confidence_score BETWEEN 0 AND 1),
    bounding_box JSONB,
    page_number INT
);
CREATE INDEX idx_fields_section_key ON extracted_fields (section_id, field_key);

CREATE TABLE stipulations (
    stip_id SERIAL PRIMARY KEY,
    application_version_id INT NOT NULL REFERENCES application_versions ON DELETE CASCADE,
    triggered_by_document_id INT REFERENCES documents(document_id),
    validation_check VARCHAR(20),
    stip_code VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SATISFIED','WAIVED')),
    resolution_note TEXT,
    resolved_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ
);
-- FR-07: at most one PENDING stip per (version, code, check); NULLS NOT DISTINCT covers manual stips.
CREATE UNIQUE INDEX idx_unique_pending_stip
    ON stipulations (application_version_id, stip_code, validation_check) NULLS NOT DISTINCT
    WHERE status = 'PENDING';

CREATE TABLE validation_results (
    result_id SERIAL PRIMARY KEY,
    application_version_id INT NOT NULL REFERENCES application_versions ON DELETE CASCADE,
    validation_check VARCHAR(20) NOT NULL
        CHECK (validation_check IN ('TILA','PRICE','VIN','INCOME','GAP')),
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('PASS','FAIL','NEEDS_REVIEW','ERROR')),
    details JSONB NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    workflow_run_id VARCHAR(100),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (application_version_id, validation_check, attempt)
);
CREATE UNIQUE INDEX idx_current_result
    ON validation_results (application_version_id, validation_check) WHERE is_current;

CREATE TABLE outbox_events (
    event_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (event_id) WHERE published_at IS NULL;

-- FR-02 / AS-1.1 / AS-1.4: only the first submission (DRAFT -> PENDING_REVIEW) writes an outbox row.
CREATE FUNCTION enqueue_pending_review() RETURNS trigger AS $$
BEGIN
  IF NEW.version_status = 'PENDING_REVIEW'
     AND (TG_OP = 'INSERT' OR OLD.version_status = 'DRAFT') THEN
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

-- AS-8.6: stamp the expiry clock on first submission; never overwritten.
CREATE FUNCTION stamp_submitted_at() RETURNS trigger AS $$
BEGIN
  IF NEW.version_status = 'PENDING_REVIEW' AND NEW.submitted_at IS NULL THEN
    NEW.submitted_at := CURRENT_TIMESTAMP;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_stamp_submitted_at BEFORE INSERT OR UPDATE OF version_status
  ON application_versions FOR EACH ROW EXECUTE FUNCTION stamp_submitted_at();

CREATE TABLE gap_form_registry (
    state_code CHAR(2) NOT NULL,
    form_number VARCHAR(50) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    PRIMARY KEY (state_code, form_number, effective_from)
);

CREATE TABLE audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    action VARCHAR(50) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    before_state JSONB,
    after_state JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);

CREATE TABLE disbursements (
    application_version_id INT PRIMARY KEY REFERENCES application_versions,
    external_reference VARCHAR(100),
    amount NUMERIC(12,2) NOT NULL,
    dealership_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','COMPLETED','FAILED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
