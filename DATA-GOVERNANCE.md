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

## What may be committed to `data/`

`var/` is a Git-ignored collector cache. `data/` holds committed projections
that other systems load. An extract may move from `var/` to `data/` only when
all of the following hold, and the ADR that authorised it says so:

- the authority entry in `coverage.edn` is `:allow-login-free`;
- the records contain no natural-person data (a public body or a legal entity
  is not a natural person);
- the upstream licence permits redistribution — CC0 for GLEIF, the NYC Open
  Data Terms of Use for NYC-owned parcels;
- every record keeps its source, observation time, licence and disclosure
  class, and the file's manifest line keeps the publish id and source hash.

Reviewed and permitted as of 2026-08-01: GLEIF LEI records (corporate identity,
CC0) and NYC-owned property claims (public-body holders). Everything else stays
in `var/` or in a governed database. Companies House PSC and HM Land Registry
CCOD/OCOD extracts are **not** permitted in `data/` — they are licensed or
person-adjacent.

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
