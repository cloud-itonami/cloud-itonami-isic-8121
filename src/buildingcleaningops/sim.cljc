(ns buildingcleaningops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean service-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a cleaning-operation-scheduling request and a
  low-cost supply-order coordination (both auto-commit clean at phase
  3), then a high-cost supply-order (ALWAYS escalates regardless of
  phase), then a safety-concern flag (ALWAYS escalates, at any phase --
  approve, then commit), then HARD-hold scenarios: an unregistered
  site, a site registered but not yet verified, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into the
  permanently-excluded chemical-safety-clearance/incompatibility-
  override scope."
  (:require [langgraph.graph :as g]
            [buildingcleaningops.advisor :as advisor]
            [buildingcleaningops.store :as store]
            [buildingcleaningops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "cleaning-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :cleaning-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :cleaning-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-service-record site-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-service-record :site-id "site-1"
                                  :patch {:areas-cleaned ["lobby" "restrooms"] :completed-at "2026-07-16T08:00:00Z"}} coordinator-phase-1)]
      (println r)
      (println "-- human cleaning coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-service-record site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-service-record :site-id "site-1"
                                  :patch {:areas-cleaned ["floors 3-5"] :completed-at "2026-07-16T18:00:00Z"}} coordinator-phase-3))

    (println "\n== schedule-cleaning-operation site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-cleaning-operation :site-id "site-1"
                                  :patch {:crew "crew-4" :date "2026-07-20" :window "06:00-08:00"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order site-1, low cost (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :site-id "site-1"
                                  :patch {:item "paper towels" :quantity 200 :estimated-cost 85.0}} coordinator-phase-3))

    (println "\n== coordinate-supply-order site-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :site-id "site-1"
                                 :patch {:item "replacement scrubber unit" :quantity 1 :estimated-cost 3200.0}} coordinator-phase-3)]
      (println r)
      (println "-- human cleaning coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-safety-concern site-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-safety-concern :site-id "site-1"
                                 :patch {:concern "two floor-cleaning chemicals stored adjacent, incompatibility label observed" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human cleaning coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-service-record site-99 (unregistered site -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-service-record :site-id "site-99"
                                  :patch {:areas-cleaned []}} coordinator-phase-3))

    (println "\n== log-service-record site-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-service-record :site-id "site-3"
                                  :patch {:areas-cleaned ["lobby"]}} coordinator-phase-3))

    (println "\n== schedule-cleaning-operation site-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-cleaning-operation :site-id "site-1"
                                           :patch {:crew "crew-2" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== log-service-record site-1, advisor drifts into chemical-safety-clearance/incompatibility-override scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :log-service-record :site-id "site-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
