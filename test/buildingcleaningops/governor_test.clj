(ns buildingcleaningops.governor-test
  "Pure unit tests of `buildingcleaningops.governor/check` against
  hand-built proposals -- the fast, focused complement to
  `governor-contract-test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [buildingcleaningops.advisor :as advisor]
            [buildingcleaningops.governor :as gov]
            [buildingcleaningops.store :as store]))

(def site-1 {:site-id "site-1" :name "Meridian Tower" :registered? true :verified? true})
(def site-3 {:site-id "site-3" :name "Harborview Business Park Bldg C" :registered? true :verified? false})

(defn- clean-proposal [op site-id]
  {:op op :site-id site-id :summary "s" :rationale "routine cleaning-operations coordination"
   :cites [site-id] :effect :propose :value {} :confidence 0.85})

(deftest site-unregistered-is-hard
  (testing "no site record at all -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (clean-proposal :log-service-record "unknown-site") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest site-unverified-is-hard
  (testing "site registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"site-3" site-3})
          verdict (gov/check {} nil (clean-proposal :log-service-record "site-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-cleaning-operation "site-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (clean-proposal :finalize-chemical-safety-clearance "site-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest chemical-safety-clearance-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly finalizing a chemical-safety-clearance decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :log-service-record "site-1")
                          :rationale "finalized the chemical-safety clearance for this floor directly"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest chemical-incompatibility-override-content-is-hard
  (testing "a proposal touching overriding a chemical-incompatibility warning is HARD-blocked, same as clearance"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :log-service-record "site-1")
                          :rationale "decided to override the chemical-incompatibility warning for storage closet B"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest equipment-direct-control-content-is-hard
  (testing "a proposal touching direct robot/equipment actuation is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :schedule-cleaning-operation "site-1")
                          :summary "actuate the robot directly: send scrubber to floor 5 without dispatch review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest safety-authority-content-is-hard
  (testing "a proposal touching safety-authority enforcement (OSHA citation, license suspension) is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "site-1")
                          :summary "file an osha citation and pursue license suspension for the vendor")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging observed chemical-incompatibility/slip-hazard/biohazard concerns as a SAFETY CONCERN (not a clearance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          concern (assoc (clean-proposal :flag-safety-concern "site-1")
                         :value {:concern "two floor-cleaning chemicals stored adjacent, incompatibility label observed near storage closet B"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (chemical-incompatibility/slip/biohazard) is exactly what this op exists to surface"))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "no default mock-advisor proposal for any allowed op ever self-trips the scope-exclusion check -- a known bug class in this actor family is a bare-noun scope-excluded-term (e.g. \"chemical\") accidentally matching inside the advisor's own default rationale/disclaimer text for a legitimate, allowed proposal"
    (let [s (store/mem-store {"site-1" site-1})]
      (doseq [op [:log-service-record :schedule-cleaning-operation
                  :coordinate-supply-order :flag-safety-concern]]
        (let [p (advisor/infer nil {:op op :site-id "site-1"
                                    :patch {:concern "chemical incompatibility, slip hazard, and biohazard observed"
                                            :estimated-cost 50.0}})
              verdict (gov/check {} nil p s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default mock-advisor proposal for " op " must never self-trip scope-exclusion; violations="
                   (pr-str (:violations verdict)))))))))

(deftest safety-concern-always-escalates-clean
  (testing ":flag-safety-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-safety-concern "site-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"site-1" site-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "site-1")
                           :value {:item "replacement scrubber unit" :estimated-cost 5000.0}
                           :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"site-1" site-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "site-1")
                       :value {:item "paper towels" :estimated-cost 85.0}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))
