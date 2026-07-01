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
