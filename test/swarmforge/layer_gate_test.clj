(ns swarmforge.layer-gate-test
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- run-bb [& args]
  (apply process/shell
         {:out :string :err :string :continue true}
         "bb" args))

(deftest crap-measures-hyphenated-helper-files
  (let [{:keys [exit out err]} (run-bb "crap")]
    (testing "Cloverage resolves and measures the checked-in helper names"
      (is (zero? exit) (str out err))
      (is (not (str/includes? out "Cannot read ns declaration")))
      (is (str/includes? out "Instrumented agent-runner"))
      (is (str/includes? out "Instrumented pack-dashboard-request"))
      (is (str/includes? out "CRAP Report"))
      (is (not (str/includes? out " N/A "))))))

(deftest the-layer-stage-lists-code-and-crap-gates-with-lcov-evidence
  (let [{:keys [exit out err]} (run-bb "swarmforge/scripts/stage.bb"
                                      "list" "--root" ".")]
    (is (zero? exit) (str out err))
    (is (re-find #"(?m)^code\s+\S+\s+bb test$" out))
    (is (re-find #"(?m)^clean\s+\S+\s+bb crap$" out))
    (is (str/includes? out "evidence: target/coverage/lcov.info"))))
