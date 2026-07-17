# Data Governance

This repository publishes schemas, source metadata, and code. It does not
publish raw natural-person or UBO records.

## Initial jurisdiction policy

| Jurisdiction | Data | Ingestion status | Distribution status |
| --- | --- | --- | --- |
| GB | Companies House PSC data product | allowlisted | governed database only |
| GB | Natural-person land-title owners | disallowed | not collected |
| EU/EEA | General-public UBO registers | disallowed | not collected |

The UK Companies House PSC product provides a daily JSON snapshot of PSCs.
Companies House makes most PSC information public, but excludes home address
and full date of birth. The registrar also permits protection of PSC details
in qualifying cases. An importer must refresh from the source, delete or
supersede protected records, and avoid every field not required for entity
resolution.

UK land-title owner names are public in a property-specific title register,
but this is not an authority to build a person-indexed, redistributable land
owner dataset. HM Land Registry says private-individual name searches are not
available through its Index of Proprietors' Names. Keep direct natural-person
property ownership out of this collection.

The EU Court of Justice invalidated general public access to beneficial
ownership information under the EU AML rules. Do not treat national register
pages as a reusable general-public UBO feed without a jurisdiction-specific
review.

## Required controls

- Preserve source URL, source record identifier, retrieval time, and licence.
- Store only name, company identifier, control nature, and source dates needed
  to express a UBO relation. Never retain home address, full date of birth,
  identity documents, or verification data.
- Enforce the source allowlist before transaction submission.
- Apply source suppression, correction, and refresh events promptly.
- Make entity-resolution confidence explicit; a matching name alone is not a
  reliable identity link.
- Require a documented legal/privacy review before enabling another
  jurisdiction or publishing any raw extract.

## Primary sources checked 2026-07-10

- https://www.gov.uk/guidance/companies-house-data-products
- https://download.companieshouse.gov.uk/en_pscdata.html
- https://www.gov.uk/guidance/your-personal-information-on-the-companies-house-register
- https://www.gov.uk/guidance/finding-information-held-by-hm-land-registry
- https://use-land-property-data.service.gov.uk/datasets/ocod
- https://curia.europa.eu/site/upload/docs/application/pdf/2022-11/cp220188en.pdf
