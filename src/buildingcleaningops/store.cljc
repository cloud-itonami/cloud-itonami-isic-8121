(ns buildingcleaningops.store
  "SSoT for the ISIC-8121 general-building-cleaning COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a routine,
  general-purpose janitorial/floor-care building-cleaning service (ISIC
  Rev.5 8121 -- distinct from specialized cleaning/decontamination
  (8129) and from landscape care (8130)): cleaning-visit/task-completion
  service-record logging, crew/site cleaning-operation scheduling,
  cleaning-supply procurement coordination, and safety-concern flagging
  (chemical-incompatibility, slip-hazard, biohazard). It never finalizes
  a chemical-safety-clearance decision and never overrides a chemical-
  incompatibility warning -- see `buildingcleaningops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `sites` directory keyed by `:site-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified site (client/site-contract) record must exist
  before ANY proposal for that site may ever commit or escalate --
  `buildingcleaningops.governor`'s `site-unverified-violations` re-derives
  this from the site's own `:registered?`/`:verified?` fields, never
  from proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which site a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (site [s site-id] "Registered site record, or nil.
    Site map: {:site-id .. :name .. :registered? bool :verified? bool}.")
  (all-sites [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map site-id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:sites
   {"site-1" {:site-id "site-1" :name "Meridian Tower -- Downtown Office (12 floors)"
              :registered? true :verified? true}
    "site-2" {:site-id "site-2" :name "Lincoln Municipal Building -- Common Areas"
              :registered? true :verified? true}
    "site-3" {:site-id "site-3" :name "Harborview Business Park Bldg C (in intake)"
              :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ site-id] (get-in @a [:sites site-id]))
  (all-sites [_] (sort-by :site-id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `sites` map (site-id string ->
  site map) -- the primary test/dev entry point. `sites` may be empty
  (an unregistered-everywhere store)."
  [sites]
  (->MemStore (atom {:sites (or sites {}) :ledger [] :coordination-log []})))
