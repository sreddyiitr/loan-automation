# Enterprise Auto Loan Funding & Validation Architecture Specification

## 1. Executive Summary & Core Problems Solved

In enterprise retail automotive finance, manual loan funding is a major bottleneck. When a dealer submits funding packages (Retail Installment Sale Contract [RISC], Buyer's Order, and GAP Insurance Agreement), funders spend hours manually cross-referencing line items, checking math, verifying statutory limits, and issuing dealer stipulations (stips).

### Problems Addressed by This Architecture:

1. **Asynchronous & Iterative Resubmissions:** Dealers frequently amend deals (changing down payments, terms, or vehicle VINs) and resubmit paperwork. Our **dual-versioning schema** (Application Versions + Document Versions) preserves immutable historical lineages while cleanly isolating active state.

2. **Granular Multi-Page Extraction Complexity:** Dealer documents span multiple pages where key sections (like the *Itemization of Amount Financed*) break across page boundaries. Section-level and field-level confidence scoring allows the system to pinpoint exactly which field failed extraction or validation.

3. **Monolithic Validation Bottlenecks:** Rather than running a single monolithic script, our architecture decouples validation into **independent Temporal workflows** triggered via AWS SQS, enabling parallel, fault-tolerant, and deterministic verification.

4. **Funder UI & Exception Visibility:** Providing human underwriters with transparent, tabbed interfaces via ADK A2UI to inspect extracted document versions, active stipulations, cross-document mismatches, and ownership assignments.

---

## 2. PostgreSQL Database Schema Design & Rationale

To support multi-page section spans, granular field-level confidence scores, document versioning, and full application resubmissions, we use the following relational PostgreSQL schema:

```sql
-- 1. Master Application Group (Persistent identifier across resubmissions)
CREATE TABLE loan_applications (
    loan_application_id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Application Versions (Tracks each amendment or resubmission of the deal structure)
CREATE TABLE application_versions (
    application_version_id SERIAL PRIMARY KEY,
    loan_application_id VARCHAR(64) REFERENCES loan_applications(loan_application_id) ON DELETE CASCADE,
    version_number INT NOT NULL DEFAULT 1, -- App v1, v2, v3...
    is_latest_version BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- Core deal terms for this version
    borrower_name VARCHAR(255) NOT NULL,
    co_borrower_name VARCHAR(255),
    ssn_itin_encrypted VARCHAR(255),
    stated_income_annual NUMERIC(12,2),
    vehicle_vin VARCHAR(17) NOT NULL,
    dealership_name VARCHAR(255) NOT NULL,
    dealership_id VARCHAR(64) NOT NULL,
    
    version_status VARCHAR(50) DEFAULT 'PENDING_REVIEW', -- 'PENDING_REVIEW', 'STIPULATED', 'APPROVED', 'REJECTED'
    assigned_funder VARCHAR(100), -- Responsible underwriter or funder
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Ensure only one active latest application version per loan application
CREATE UNIQUE INDEX idx_unique_active_app_version 
ON application_versions (loan_application_id) 
WHERE is_latest_version = TRUE;

-- 3. Documents Table (Tied to a specific Application Version)
CREATE TABLE documents (
    document_id SERIAL PRIMARY KEY,
    application_version_id INT REFERENCES application_versions(application_version_id) ON DELETE CASCADE,
    document_type VARCHAR(50) NOT NULL, -- e.g., 'RISC', 'BUYERS_ORDER', 'GAP_AGREEMENT'
    document_version_number INT NOT NULL DEFAULT 1, -- Doc v1, v2... within this app version
    is_latest_doc BOOLEAN NOT NULL DEFAULT TRUE,
    file_name VARCHAR(255) NOT NULL,
    total_pages INT NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Ensure unique active document type per application version
CREATE UNIQUE INDEX idx_unique_active_doc_version 
ON documents (application_version_id, document_type) 
WHERE is_latest_doc = TRUE;

-- 4. Document Sections (Tied to document version, handles multi-page spans)
CREATE TABLE document_sections (
    section_id SERIAL PRIMARY KEY,
    document_id INT REFERENCES documents(document_id) ON DELETE CASCADE,
    section_name VARCHAR(100) NOT NULL, -- e.g., 'Itemization of Amount Financed'
    start_page INT NOT NULL,
    end_page INT NOT NULL
);

-- 5. Granular Extracted Fields (With field-level confidence scores)
CREATE TABLE extracted_fields (
    field_id SERIAL PRIMARY KEY,
    section_id INT REFERENCES document_sections(section_id) ON DELETE CASCADE,
    field_key VARCHAR(100) NOT NULL,    -- e.g., 'cash_price', 'apr'
    field_value TEXT,
    confidence_score NUMERIC(3,2) NOT NULL,
    bounding_box JSONB,                 -- [ymin, xmin, ymax, xmax] for UI bounding box highlights
    is_flagged BOOLEAN GENERATED ALWAYS AS (confidence_score < 0.90) STORED
);

-- 6. Stipulations (Tied to the specific application version and triggering document)
CREATE TABLE stipulations (
    stip_id SERIAL PRIMARY KEY,
    application_version_id INT REFERENCES application_versions(application_version_id) ON DELETE CASCADE,
    triggered_by_document_id INT REFERENCES documents(document_id),
    stip_code VARCHAR(50) NOT NULL,     -- e.g., 'STIP_PROOF_OF_INCOME'
    description TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'SATISFIED', 'WAIVED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE
);
```

---

## 3. End-to-End Event-Driven Temporal Architecture

### Event Flow:

1. **Enterprise OCR Ingestion:** External enterprise processes parse multi-page dealer packets and write structured sections, fields, and confidence scores into the PostgreSQL tables.

2. **Status Change & SQS Trigger:** When an application version status transitions to `PENDING_REVIEW`, a message containing `loan_application_id` and `application_version_id` is published to an **AWS SQS Queue**.

3. **Temporal Worker Orchestration:** A parent Temporal workflow polls SQS, reads the payload, and fans out execution to **independent child workflows**. Each worker handles a discrete underwriting verification domain (e.g., TILA math, income API verification, GAP compliance).

4. **Deterministic Validation & Stips:** If validation fails, the worker inserts a record into the `stipulations` table and flags the application. If all checks pass, the workflow proceeds to disbursement API execution.

---

## 4. Comprehensive Funder Review Matrix (Temporal Workflow Rules)

Implement the following business verification rules across independent Temporal workflows:

| Workflow Task / Domain | Target Document(s) / Data Source | Validation Rule & Business Logic | Stipulation Generated on Failure |
| :--- | :--- | :--- | :--- |
| **1. TILA Math Verification** | RISC | Verify that `Amount Financed + Finance Charge = Total of Payments`. Verify monthly payment against term and APR. | `STIP_TILA_MATH_DISCREPANCY` |
| **2. Pricing & Cash Price Parity** | RISC & Buyer's Order | Cross-check that Item 1 (Cash Price) on the RISC perfectly matches Total Vehicle Cash Price on the Buyer's Order ($0.00 tolerance). | `STIP_PRICE_MISMATCH` |
| **3. VIN & Vehicle Consistency** | RISC, Buyer's Order, GAP Agreement | Verify 17-character VIN, Year, Make, and Model match identically across all documents in the active application version. | `STIP_VIN_MISMATCH` |
| **4. External Income Verification** | Application Stated Income + External Payroll API | Call mock income verification API (`POST /api/v1/external/verify-income`) passing encrypted SSN/ITIN. Compare stated annual income against verified payroll data (allow 15% variance threshold). | `STIP_INCOME_VARIANCE_EXCEEDED` |
| **5. GAP Insurance Form & Lender Validation** | GAP Agreement & RISC | Verify state-approved form number on GAP agreement matches state registry, and ensure financing creditor/funder name matches primary lender. | `STIP_GAP_FORM_MISMATCH` or `STIP_LENDER_MISMATCH` |

---

## 5. ADK A2UI Funder Dashboard & Tabbed Interface Specification

The front-end utilizes **Agent Development Kit (ADK) A2UI** components to present funders with comprehensive loan application insights organized into dedicated operational tabs:

### Tab 1: Application Overview & Metadata
* **Content:** Master loan application ID, active application version number, dealership name & ID, borrower and co-borrower names, stated annual income, vehicle VIN, and current application status (`PENDING_REVIEW`, `STIPULATED`, `APPROVED`, `FUNDED`).
* **Assignment Widget:** Displays who is currently responsible for reviewing the application (e.g., *Assigned Funder: Sarah Jenkins*), with manual reassignment controls.

### Tab 2: Active Stipulations & Exception Queue
* **Content:** List of open dealer and customer stipulations generated by the Temporal validation workflows (e.g., `STIP_PROOF_OF_INCOME`, `STIP_PRICE_MISMATCH`).
* **Actions:** Allows funders to manually override, waive, or mark stipulations as satisfied upon receiving updated dealer uploads.

### Tab 3: Cross-Document Mismatches & Reconciliation
* **Content:** Side-by-side comparison tables highlighting data discrepancies found across the RISC, Buyer's Order, and GAP Agreement (e.g., Cash Price on RISC vs. Buyer's Order).
* **Confidence Highlighting:** Color-coded field tags displaying granular extraction confidence scores (`< 0.90` highlighted in yellow/red).

### Tab 4: Document Version History & Viewer
* **Content:** Dropdown allowing funders to view historical document versions (e.g., RISC v1 vs. RISC v2) alongside split-screen PDF image renderers with bounding box overlays.
* **Audit Trail:** Timestamps of when each document version was uploaded by the dealer portal.