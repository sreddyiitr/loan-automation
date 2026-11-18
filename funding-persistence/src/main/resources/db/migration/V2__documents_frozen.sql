-- AS-9.7 / owner decision B5 (slice S2): documents freeze once a version is approved or final.
-- Any later change, a RISC change included, needs a new application version (US-9).
CREATE FUNCTION refuse_frozen_documents() RETURNS trigger AS $$
DECLARE s VARCHAR(50);
BEGIN
  SELECT version_status INTO s FROM application_versions WHERE application_version_id = NEW.application_version_id;
  IF s IN ('APPROVED','FUNDED','REJECTED','EXPIRED') THEN
    RAISE EXCEPTION 'DOCUMENTS_FROZEN: application version % is %; create a new application version',
      NEW.application_version_id, s
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_documents_frozen BEFORE INSERT ON documents
  FOR EACH ROW EXECUTE FUNCTION refuse_frozen_documents();
