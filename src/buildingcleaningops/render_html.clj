(ns buildingcleaningops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`buildingcleaningops.operation` -> `buildingcleaningops.governor` ->
  `buildingcleaningops.store`) through a scenario adapted from this
  repo's own `buildingcleaningops.sim` demo driver (`clojure -M:dev:run`,
  confirmed to run correctly against the real seeded site directory
  before this file was written -- unlike `cloud-itonami-isic-851`'s
  original `schoolops.sim`, this repo's own sim driver uses site ids
  (site-1/site-2/site-3) that DO match `buildingcleaningops.store/
  demo-data`, and its `:commit` node genuinely calls `store/commit-
  record!` (a real `swap!` on the store atom, confirmed by running it --
  the coordination log grows by one real entry per committed op, not a
  no-op), so it was safe to adapt rather than author from scratch),
  trimmed to a representative subset (a full commit lifecycle across
  three write ops, two ALWAYS-escalate lifecycles each followed by a
  human approval, and three distinct HARD-hold reasons that never reach
  a human) and rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against
  the same seed (verified by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [buildingcleaningops.store :as store]
            [buildingcleaningops.advisor :as advisor]
            [buildingcleaningops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :cleaning-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: site-1 clears three write ops that all
  auto-commit clean at phase 3 (log-service-record,
  schedule-cleaning-operation, a low-cost coordinate-supply-order);
  site-1's high-cost coordinate-supply-order ALWAYS escalates (its
  :estimated-cost 3200.0 exceeds `governor/supply-cost-threshold` 500.0)
  and is approved by a human; site-2's flag-safety-concern ALWAYS
  escalates (per `governor/always-escalate-ops`) even though clean, and
  is approved by a human; site-3 (registered but NOT `:verified?` in the
  seed data) HARD-holds on `:site-unverified` -- never reaches a human;
  an advisor that drifts into claiming direct actuation (`:effect
  :commit` instead of `:propose`) HARD-holds on `:effect-not-propose`
  -- never reaches a human; a proposal whose advisor drifted into
  out-of-scope territory (`:out-of-scope? true`) HARD-holds on
  `:scope-excluded` -- also never reaches a human. Returns the resulting
  store -- every field read by `render` below is real governor/store
  output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "s1-log" {:op :log-service-record :site-id "site-1"
                            :patch {:areas-cleaned ["lobby" "restrooms"]
                                    :completed-at "2026-07-16T08:00:00Z"}})

    (exec! actor "s1-schedule" {:op :schedule-cleaning-operation :site-id "site-1"
                                 :patch {:crew "crew-4" :date "2026-07-20"
                                         :window "06:00-08:00"}})

    (exec! actor "s1-supply-low" {:op :coordinate-supply-order :site-id "site-1"
                                   :patch {:item "paper towels" :quantity 200
                                           :estimated-cost 85.0}})

    (exec! actor "s1-supply-high" {:op :coordinate-supply-order :site-id "site-1"
                                    :patch {:item "replacement scrubber unit" :quantity 1
                                            :estimated-cost 3200.0}})
    (approve! actor "s1-supply-high")

    (exec! actor "s2-safety" {:op :flag-safety-concern :site-id "site-2"
                               :patch {:concern "two floor-cleaning chemicals stored adjacent, incompatibility label observed"
                                       :confidence 0.92}})
    (approve! actor "s2-safety")

    (exec! actor "s3-log" {:op :log-service-record :site-id "site-3"
                            :patch {:areas-cleaned ["lobby"]}})

    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "s1-direct" {:op :schedule-cleaning-operation :site-id "site-1"
                                        :patch {:crew "crew-2" :date "2026-07-22"}}))

    (exec! actor "s1-scope" {:op :log-service-record :site-id "site-1"
                              :out-of-scope? true :patch {}})
    db))

;; ----------------------------- rendering (structure unchanged;
;; column labels + row extraction are domain-specific) -----------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger site-id]
  (last (filter #(= (:site-id %) site-id) ledger)))

(defn- status-cell [ledger site-id]
  (let [f (last-fact-for ledger site-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-row [ledger {:keys [site-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc site-id) (esc name)
          (if (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
              "<span class=\"warn\">registered, unverified</span>")
          (status-cell ledger site-id)))

(defn- ledger-row [{:keys [t op site-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc site-id)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; `Ops` table, `buildingcleaningops.governor`/`buildingcleaningops.phase`)
  ;; -- documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-service-record</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-cleaning-operation</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-order</code></td><td><span class=\"ok\">phase-3 auto when clean &middot; ALWAYS human approval above USD 500 estimated cost</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        sites (store/all-sites db)
        site-rows (str/join "\n" (map (partial site-row ledger) sites))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-8121 &middot; building-cleaning operations</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>General building cleaning operations (ISIC 8121) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never finalizes chemical-safety clearance or actuates equipment directly</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>buildingcleaningops.store</code> via <code>buildingcleaningops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Name</th><th>Contract status</th><th>Last coordination status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (BuildingCleaning Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Chemical-safety-clearance finalization, chemical-incompatibility-warning override, direct equipment actuation and safety-authority enforcement are permanently out of scope — see governor scope-exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Site</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
