(ns kotoba.property.houjin-bangou-projection
  "Select a bounded slice of the 法人番号 corpus for the workspace query plane,
   and resolve company *names* to corporate numbers honestly.

   Why a projection exists at all: the corpus is 5.8M rows / ~5.1M live
   entities. On this workspace's DataScript loader a million entities cost
   ~5.6 GB and ~156 s (measured for GLEIF, `org-gleif-projections/README.md`),
   so the whole registry is not a price a CLI query can pay. The corpus stays
   whole on disk; the plane loads the slice a question needs.

   What that costs, stated plainly (CLAUDE.md, kotobase one-ref rule): a
   corporate number outside the loaded slice does NOT join to GLEIF,
   market-intel or cloud-itonami-lei in Datalog. It is answerable only by a
   scan of the corpus (`scripts/query_houjin_bangou_corpus.cljs`), which
   cannot join. Widening the join surface means widening the projection, not
   sharding it further.

   ## Name resolution is where this dataset is easy to lie with

   A JP company name is not a key. `normalize-name` is exact enough to trust;
   `name-core` (form-insensitive) is not, because 株式会社A and 有限会社A share
   a core, as do a parent and its 100%-owned namesake. So the resolver keeps
   three outcomes apart and never collapses them:

     :exact      — one record whose normalized name equals the query
     :core       — no exact hit, exactly one form-insensitive hit
     :ambiguous  — more than one hit at whichever level answered

   An ambiguous name resolves to NOTHING and is reported by name. Picking the
   first candidate would produce a projection that looks complete and is
   quietly wrong about which company it is."
  (:require [clojure.string :as str]
            [kotoba.property.houjin-bangou-zenken :as hb]))

(defn- prefecture-match?
  "Accepts both \"JP-13\" and \"13\" so a caller does not have to remember which
   half of the ISO code the NTA publishes."
  [region wanted]
  (boolean
   (when region
     (some (fn [w]
             (let [w (str/upper-case (str/trim w))]
               (or (= region w) (= region (str "JP-" w)))))
           wanted))))

(defn matcher
  "Predicate over corpus records built from a selection spec.

   `:numbers`     — explicit 法人番号 allowlist (the join-driven default)
   `:name-keys`   — normalized name keys to *collect* (see `collect-names`);
                    resolution happens after the scan, not inside it
   `:prefectures` — \"JP-13\" or \"13\"
   `:kinds`       — 法人種別 codes, e.g. #{\"301\"} for 設立登記法人
   `:latest-only?` — drop superseded history rows
   `:active-only?` — drop entities whose registration record is closed

   With no criteria the matcher takes everything: a projector invoked with no
   filter should produce the whole corpus, not silently produce nothing."
  [{:keys [numbers name-keys prefectures kinds latest-only? active-only?]}]
  (let [numbers (when (seq numbers) (set numbers))
        name-keys (when (seq name-keys) (set name-keys))
        prefectures (when (seq prefectures) (vec prefectures))
        kinds (when (seq kinds) (set kinds))]
    (fn [rec]
      (and (or (nil? latest-only?) (not latest-only?) (:company/nta-latest? rec))
           (or (nil? active-only?) (not active-only?)
               (str/blank? (:company/closed-at rec)))
           (or (nil? prefectures) (prefecture-match? (:company/region rec) prefectures))
           (or (nil? kinds) (contains? kinds (:company/nta-kind rec)))
           (or (and (nil? numbers) (nil? name-keys))
               (boolean (or (and numbers (contains? numbers (:company/houjin-bangou rec)))
                            (and name-keys
                                 (or (contains? name-keys (hb/normalize-name (:company/legal-name rec)))
                                     (contains? name-keys (hb/name-core (:company/legal-name rec))))))))))))

(def max-candidates
  "Candidates kept per name key before a key is declared ambiguous. A core like
   \"japan\" matches thousands; holding them all would trade a bounded report
   for an unbounded one, and the answer is the same either way."
  50)

(defn collect-candidate
  "Fold one matched record into the per-key candidate map. `keys-of` is the set
   of query keys this record could answer (its normalized name and its core)."
  [acc rec]
  (let [nm (:company/legal-name rec)]
    (reduce (fn [m [level k]]
              (if (nil? k)
                m
                (update-in m [level k]
                           (fn [v] (if (and v (>= (count v) max-candidates))
                                     v
                                     (conj (or v []) rec))))))
            acc
            [[:exact (hb/normalize-name nm)]
             [:core (hb/name-core nm)]])))

(defn resolve-names
  "queries (raw names) + candidate map -> {:resolved {query rec}
                                           :ambiguous {query {:level l :count n}}
                                           :unmatched [query ...]}

   Exact beats core; a tie at either level is unresolved, by name."
  [queries candidates]
  (reduce
   (fn [acc q]
     (let [exact (get-in candidates [:exact (hb/normalize-name q)])
           core (get-in candidates [:core (hb/name-core q)])
           [level hits] (cond
                          (seq exact) [:exact exact]
                          (seq core) [:core core]
                          :else [nil nil])]
       (cond
         (nil? level) (update acc :unmatched conj q)
         (= 1 (count hits)) (assoc-in acc [:resolved q]
                                      (assoc (first hits) :company/name-match level))
         :else (assoc-in acc [:ambiguous q] {:level level :count (count hits)}))))
   {:resolved {} :ambiguous {} :unmatched []}
   queries))

(defn projection-manifest
  "Line 1 of a projection: the corpus manifest it came from, plus the criteria
   that produced it. A projection that does not say what it excluded reads as
   if it were the whole registry."
  [corpus-manifest {:keys [numbers name-keys prefectures kinds latest-only? active-only?]} record-count]
  (cond-> (assoc (dissoc corpus-manifest :corpus/record-count)
                 ;; Set unconditionally, not inherited: a corpus written before
                 ;; the manifest convention would leave line 1 unmarked, and a
                 ;; reader would then load the header as if it were a company.
                 :corpus/manifest true
                 :corpus/projection true
                 :corpus/record-count record-count)
    (seq numbers) (assoc :projection/number-count (count numbers))
    (seq name-keys) (assoc :projection/name-count (count name-keys))
    (seq prefectures) (assoc :projection/prefectures (vec prefectures))
    (seq kinds) (assoc :projection/kinds (vec kinds))
    latest-only? (assoc :projection/latest-only true)
    active-only? (assoc :projection/active-only true)))
