-- AS-1.8 (slice S5): a new latest document on the latest version, while that version is under review
-- (PENDING_REVIEW or STIPULATED), writes an outbox event in the same transaction. The start API turns
-- it into a documentsUpdated signal, and the workflow re-runs every check against the latest documents.
-- DRAFT versions (OCR still writing, or a new version being assembled) and non-latest versions never
-- trigger; APPROVED and later versions refuse the insert altogether (V2, AS-9.7).
CREATE FUNCTION enqueue_document_added() RETURNS trigger AS $$
DECLARE
  v_app VARCHAR(64);
  v_latest BOOLEAN;
  v_status VARCHAR(50);
BEGIN
  SELECT loan_application_id, is_latest_version, version_status INTO v_app, v_latest, v_status
    FROM application_versions WHERE application_version_id = NEW.application_version_id;
  IF NEW.is_latest_doc AND v_latest AND v_status IN ('PENDING_REVIEW','STIPULATED') THEN
    INSERT INTO outbox_events (event_type, payload)
    VALUES ('DOCUMENT_VERSION_ADDED',
            jsonb_build_object('event_type', 'DOCUMENT_VERSION_ADDED',
                               'loan_application_id', v_app,
                               'application_version_id', NEW.application_version_id,
                               'document_id', NEW.document_id));
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_added AFTER INSERT ON documents
  FOR EACH ROW EXECUTE FUNCTION enqueue_document_added();
