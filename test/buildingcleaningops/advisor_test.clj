(ns buildingcleaningops.advisor-test
  "Unit tests of `buildingcleaningops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [buildingcleaningops.advisor :as adv]
            [buildingcleaningops.store :as store]))

(def db (store/seed-db))

(deftest propose-service-record-shape
  (testing "service-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-service-record
                           :site-id "site-1"
                           :patch {:areas-cleaned ["lobby"] :completed-at "2026-07-16T08:00:00Z"}})]
      (is (= :log-service-record (:op p)))
      (is (= "site-1" (:site-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :site-id)))))

(deftest propose-cleaning-operation-shape
  (testing "cleaning-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-cleaning-operation
                           :site-id "site-2"
                           :patch {:crew "crew-1" :date "2026-07-20"}})]
      (is (= :schedule-cleaning-operation (:op p)))
      (is (= "site-2" (:site-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :site-id "site-1"
                           :patch {:item "paper towels" :quantity 200 :estimated-cost 85.0}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-safety-concern-shape
  (testing "safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-safety-concern
                           :site-id "site-1"
                           :patch {:concern "chemical incompatibility observed near storage closet"}})]
      (is (= :flag-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-service-record :schedule-cleaning-operation :coordinate-supply-order
                :flag-safety-concern]]
      (let [p (adv/infer db {:op op :site-id "site-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-service-record :schedule-cleaning-operation :coordinate-supply-order
                :flag-safety-concern]]
      (let [p (adv/infer db {:op op :site-id "site-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
