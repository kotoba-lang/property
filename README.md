# kotoba-property

[![CI](https://github.com/kotoba-lang/property/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/property/actions/workflows/ci.yml)

**Parcels, listings and leases in pure Clojure.** A
[kotoba-lang](https://github.com/kotoba-lang) capability library for the
[`cloud-itonami-6810`](https://github.com/gftdcojp/cloud-itonami-6810)
community real-estate-agency open business: land-parcel records, property
listings (sale/rent), and lease records with term-overlap detection for
conflict-free tenant scheduling.

No network, no I/O. Amounts are plain numbers in the smallest currency unit.
Portable `.cljc` across JVM / ClojureScript / SCI / GraalVM.

## Public ownership collection

`resources/property/open_data/` defines a provenance-first EDN interchange
for publicly attributable land and building ownership claims. It is designed
for both Datomic and DataScript. The repository does not publish names of
natural-person owners, private-register data, or data whose upstream licence
does not permit redistribution.

Each imported claim must identify its property, holder, source, observation
time, licence, and disclosure class. `:ownership/disclosure` is `:public`
only when the upstream publisher makes that attribution publicly available.
Use `:not-published` for parcel data where ownership is not released.

```clojure
(require '[kotoba.property.ownership :as ownership])

ownership/public-claims-by-parcel-query
;; Run this Datalog vector unchanged with datomic.api/q or datascript.core/q.

(ownership/validate-claim
 {:ownership/id "nyc:mappluto:example"
  :ownership/parcel "nyc:borough-block-lot:1000010001"
  :ownership/holder "Example public authority"
  :ownership/holder-kind :public-body
  :ownership/source "nyc-mappluto"
  :ownership/observed-at "2026-07-10"
  :ownership/licence "NYC Open Data Terms of Use"
  :ownership/disclosure :public})
;; => {:ownership/valid? true}
```

`sources.edn` is a reviewed source catalog, not a mirror of the referenced
datasets. Add a data extract only after confirming the source's publication
scope, licence, jurisdiction, and refresh date.

## UBO and natural-person data

Natural-person names are not property-owner records in this project. They can
only be ingested as a beneficial-ownership relation to a corporate owner when
the jurisdiction/source pair is allowlisted in
`resources/property/open_data/sources.edn`. The initial allowlist is the UK
Companies House PSC product. It deliberately excludes residential addresses,
full dates of birth, identity-verification material, and protected records.

The UBO records belong in a governed database or a source-derived local
snapshot, never in this repository. See `DATA-GOVERNANCE.md` before enabling
an importer.

```bash
# Requires a Companies House Public Data API key and a corporate-owner number.
COMPANIES_HOUSE_API_KEY=... clojure -M:collect --company 00000006
clojure -M:query --company 00000006
```

The collector writes `var/kotoba-property/gb-ubo.edn`, which is ignored by
Git. It retains only the reduced UBO fields defined by the schema.

After accepting the relevant HMLR data licence and downloading CCOD or OCOD,
import only corporate-owner records and join them to PSC by company number:

```bash
clojure -M:collect-hmlr --csv /licensed/CCOD_FULL.csv \
  --source hmlr-uk-corporate-property --observed-at 2026-07-10
clojure -M:query --parcel GB-HMLR:TITLE_NUMBER
```

NYC-owned parcels require no credentials and can be collected immediately.
They are kept separate from natural-person ownership data. The preferred
runtime is `kotoba` contracts -> ClojureScript -> `nbb`; the nbb scripts own
the capability boundary for network and local-file I/O.

```bash
nbb -cp src scripts/collect_nyc.cljs --limit 500
nbb -cp src scripts/query_owned_property.cljs \
  --parcel US-NY-NYC:BBL:1017900009.0
```

Both nbb query commands execute Datalog through the npm DataScript runtime;
their query strings use explicit namespaced attribute names, preserving the
same EDN contract used by Datomic/DataScript clients.

## Corporate graph

GLEIF Level 2 is credential-free and provides accounting-consolidation parent
relations between legal entities. It is not a natural-person UBO registry.

```bash
npm install
nbb -cp src scripts/collect_gleif.cljs --lei 529900T8BM49AURSDO55
nbb -cp src scripts/query_corporate_parent.cljs --lei 529900T8BM49AURSDO55
```

To expand global legal-entity coverage without credentials, collect bounded
GLEIF Level 1 pages by jurisdiction. Increase `--pages` deliberately because
jurisdictions can contain hundreds of thousands of records.

```bash
nbb -cp src scripts/collect_gleif_jurisdiction.cljs \
  --jurisdiction US --pages 2 --page-size 100
```

When a licensed HMLR store and a GLEIF store contain the same UK company
registration number, join them directly:

```bash
nbb -cp src scripts/query_property_parent.cljs \
  --parcel GB-HMLR:TITLE_NUMBER
```


## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 20 assertions, all green |
| Operator console (UI/UX) | yes |
| Export (CSV/JSON) | yes |
| Shared CSS design system | yes (css.core/operator-theme) |

## Contract

```clojure
(require '[kotoba.property :as prop])

(prop/parcel "P1" :address "1 Main St" :area-m2 120 :zoning :residential)
(prop/listing "L1" "P1" :rent 1000)
(prop/lease "LS1" "P1" "tenant" "landlord" 1000 "2026-01-01" "2026-12-31")
(prop/term-overlaps? lease-a lease-b)   ; => true/false
```

## Operator console (UI/UX)

A read-only HTML dashboard renders parcels, listings and leases (with term-overlap warnings) for an operator. Built on
[`kotoba-lang/html`](https://github.com/kotoba-lang/html) (Hiccup→HTML) +
[`kotoba-lang/css`](https://github.com/kotoba-lang/css) (EDN→CSS). Pure data
→ markup; the console never exposes a write surface (no `<form>`/`<button>`)
— writes stay behind the governor.

```clojure
(require '[kotoba.property.ui :as ui])

(ui/dashboard
  {:parcels [(prop/parcel "P1" :address "1 Main St")]
   :listings [(prop/listing "L1" "P1" :rent 1000)]
   :leases [(prop/lease "LS1" "P1" "tenant" "landlord" 1000 "2026-01-01" "2026-12-31")]})
;; => "<html>...read-only · governor-gated...</html>"
```

## Export (CSV / JSON)

Audit-grade CSV (RFC-4180 quoting) and JSON (quote/backslash/newline
escaped) for parcels, listings and leases (term-overlap flagged).

```clojure
(require '[kotoba.property.export :as ex])

(ex/parcels->csv parcels)
(ex/leases->csv leases)      ; term-overlap flag
(ex/leases->json leases)
```

## License

Apache License 2.0.

## Test

```bash
clojure -M:test
```
