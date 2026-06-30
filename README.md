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

## Contract

```clojure
(require '[kotoba.property :as prop])

(prop/parcel "P1" :address "1 Main St" :area-m2 120 :zoning :residential)
(prop/listing "L1" "P1" :rent 1000)
(prop/lease "LS1" "P1" "tenant" "landlord" 1000 "2026-01-01" "2026-12-31")
(prop/term-overlaps? lease-a lease-b)   ; => true/false
```

## License

Apache License 2.0.
