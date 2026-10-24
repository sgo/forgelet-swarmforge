(ns swarmforge.stage-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def stage-script (fs/path repo-root "swarmforge" "scripts" "stage.bb"))
(def pack-web-test (fs/path repo-root "test" "swarmforge" "pack_web_test.bb"))

(def stage-config
  {:task "fixture"
   :stages
   [{:name "spec"
     :approval :operator
     :spec ["spec.md" "features/extra.feature"]
     :stamp ["spec.md" "features"]}
    {:name "code"
     :runs [["sh" "-c" "mkdir -p target && echo code > target/code.txt"]]
     :evidence ["target/code.txt"]
     :stamp [:sources]}
    {:name "clean"
     :runs [["sh" "-c" "echo clean > target/clean.txt"]]
     :evidence ["target/clean.txt"]
     :stamp [:sources]}
    {:name "architect"
     :runs [["sh" "-c" "echo architect > target/architect.txt"]]
     :evidence ["target/architect.txt"]
     :stamp [:sources]}
    {:name "harden"
     :runs [["sh" "-c" "echo harden > target/harden.txt"]]
     :evidence ["target/harden.txt"]
     :stamp [:sources]}
    {:name "qa"
     :runs [["sh" "-c" "echo qa > target/qa.txt"]]
     :evidence ["target/qa.txt"]
     :stamp [:sources]}
    {:name "release"
     :optional true
     :runs [["sh" "-c" "echo release > target/release.txt"]]
     :evidence ["target/release.txt"]
     :stamp ["pom.xml"]}]})

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run-stage [root & args]
  (apply sh/sh
         (concat ["bb" (str stage-script)]
                 args
                 [:dir (str root)])))

(defn run-pack-web-test [root & args]
  (apply sh/sh
         (concat ["bb" (str pack-web-test)]
                 args
                 [:dir (str root)])))

(defn state [root]
  (edn/read-string
   (slurp (str (fs/path root ".swarmforge" "stages" "fixture.edn")))))

(defn latest-proposal-id [root]
  (last (sort (keys (:proposals (state root))))))

(defn fixture []
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage."})]
    (sh/sh "git" "init" "-q" :dir (str root))
    (sh/sh "git" "config" "user.email" "test@example.com" :dir (str root))
    (sh/sh "git" "config" "user.name" "Test User" :dir (str root))
    (write-file (fs/path root ".gitignore") "target/\n.swarmforge/\n")
    (write-file (fs/path root "pom.xml")
                "<project><modules><module>core</module></modules></project>\n")
    (write-file (fs/path root "core" "pom.xml") "<project/>\n")
    (write-file (fs/path root "core" "src" "main" "Main.java") "class Main {}\n")
    (write-file (fs/path root "core" "src" "test" "MainTest.java")
                "class MainTest {}\n")
    (write-file (fs/path root "spec.md") "# Fixture spec\n\nA fixture.\n")
    (write-file (fs/path root "features" "extra.feature")
                "Feature: extra\n\n  Scenario: extra\n    Given a fixture\n")
    (write-file (fs/path root ".swarmforge" "roles.tsv")
                (format "agent\tmaster\t%s\tfixture-agent\tAgent\tcodex\ttask\n" root))
    (write-file (fs/path root "stages.edn") (pr-str stage-config))
    (sh/sh "git" "add" "-A" :dir (str root))
    (sh/sh "git" "commit" "-q" "-m" "fixture" :dir (str root))
    root))

(deftest a-fixture-walks-every-stage
  (let [root (fixture)]
    (try
      (testing "the spec is proposed and approved"
        (let [propose (run-stage root "propose" "spec"
                                 "spec.md" "features/extra.feature")]
          (is (zero? (:exit propose)) (:err propose))
          (is (str/includes? (:out propose) "spec proposal")))
        (let [approve (run-stage root "approve" (latest-proposal-id root)
                                 "--words" "approve")]
          (is (zero? (:exit approve)) (:err approve))))

      (testing "the ordered stages run"
        (doseq [stage ["code" "clean" "architect" "harden" "qa"]]
          (let [result (run-stage root "check" stage)]
            (is (zero? (:exit result))
                (str stage " failed: " (:err result))))))

      (testing "list names the discovered reactor inputs"
        (let [result (run-stage root "list")]
          (is (zero? (:exit result)) (:err result))
          (is (str/includes? (:out result) "core/src/main"))
          (is (re-find #"release\s+n/a" (:out result)))))

      (testing "the committed tree completes"
        (let [complete (run-stage root "complete")]
          (is (zero? (:exit complete)) (:err complete))
          (is (str/includes? (:out complete) "complete: fixture"))))

      (testing "status --json reports the same state"
        (let [result (run-stage root "status" "--json")
              parsed (json/parse-string (:out result) true)]
          (is (zero? (:exit result)) (:err result))
          (is (= "fixture" (:task parsed)))
          (is (every? #(= "done" (:status %))
                      (remove #(= "release" (:name %)) (:stages parsed))))
          (is (some? (:complete parsed)))))
      (finally
        (fs/delete-tree root)))))

(deftest a-stage-refuses-out-of-order
  (let [root (fixture)]
    (try
      (let [result (run-stage root "check" "code")]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:err result) "blocked by: spec")))
      (let [result (run-stage root "complete")]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:err result) "Stages not current: spec")))
      (finally
        (fs/delete-tree root)))))

(deftest a-configured-deploy-target-requires-a-required-stage
  (let [root (fixture)
        stages-file (fs/path root "stages.edn")]
    (try
      (write-file (fs/path root "swarmforge" "deploy.conf") "github-packages\n")
      (let [missing (run-stage root "complete")]
        (is (not (zero? (:exit missing))))
        (is (str/includes? (:err missing) "no deploy stage")))

      (write-file stages-file
                  (pr-str (update stage-config :stages conj
                                  {:name "deploy"
                                   :optional true
                                   :runs [["scripts/publish-snapshot.sh"]]
                                   :stamp [:sources]})))
      (let [optional (run-stage root "complete")]
        (is (not (zero? (:exit optional))))
        (is (str/includes? (:err optional) "deploy stage is optional")))

      (write-file stages-file
                  (pr-str (update stage-config :stages conj
                                  {:name "deploy"
                                   :runs [["scripts/publish-snapshot.sh"]]
                                   :stamp [:sources]})))
      (let [required (run-stage root "complete")]
        (is (not (zero? (:exit required))))
        (is (not (str/includes? (:err required) "deploy stage")))
        (is (str/includes? (:err required) "working tree is not committed")))
      (finally
        (fs/delete-tree root)))))

(deftest approval-is-single-use-and-tied-to-the-spec
  (let [root (fixture)]
    (try
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [id (latest-proposal-id root)]
        (is (zero? (:exit (run-stage root "approve" id "--words" "approve"))))
        (let [again (run-stage root "approve" id "--words" "approve")]
          (is (not (zero? (:exit again))))
          (is (str/includes? (:err again) "single-use"))))

      (testing "editing the spec makes the approval stale"
        (write-file (fs/path root "spec.md") "# Changed spec\n")
        (let [result (run-stage root "list")]
          (is (re-find #"spec\s+stale" (:out result))))
        (let [result (run-stage root "complete")]
          (is (not (zero? (:exit result))))
          (is (str/includes? (:err result) "spec"))))

      (testing "a changed spec cannot be approved from the old proposal"
        (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
        (let [pending (latest-proposal-id root)]
          (write-file (fs/path root "spec.md") "# Changed again\n")
          (let [result (run-stage root "approve" pending "--words" "approve")]
            (is (not (zero? (:exit result))))
            (is (str/includes? (:err result) "changed")))))
      (finally
        (fs/delete-tree root)))))

(deftest a-single-agent-auto-approves-corrections-until-the-card-finishes
  (let [root (fixture)
        approval-file (fn [id]
                        (fs/path root ".swarmforge" "handoffs"
                                 "pending_approval" (str id ".handoff")))]
    (try
      (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [first-id (latest-proposal-id root)]
        (is (= :pending (get-in (state root) [:proposals first-id :status])))
        (is (fs/regular-file? (approval-file first-id)))
        (is (zero? (:exit (run-stage root "approve" first-id "--words" "approve"))))

        (write-file (fs/path root "spec.md")
                    "# Fixture spec\n\nA correction found during implementation.\n")
        (let [correction (run-stage root "propose" "spec"
                                    "spec.md" "features/extra.feature")
              correction-id (latest-proposal-id root)]
          (is (zero? (:exit correction)) (:err correction))
          (is (str/includes? (:out correction) "Auto-approved"))
          (is (= :approved (get-in (state root) [:proposals correction-id :status])))
          (is (= "correction after first operator approval"
                 (get-in (state root) [:proposals correction-id :approval-policy])))
          (is (not (fs/exists? (approval-file correction-id)))))

        (let [current (state root)
              completed (assoc current :complete
                               {:tree "completed-tree"
                                :at (str (System/currentTimeMillis))
                                :card (:card current)})]
          (write-file (fs/path root ".swarmforge" "stages" "fixture.edn")
                      (pr-str completed)))
        (write-file (fs/path root "spec.md")
                    "# Fixture spec\n\nA new card changes the contract again.\n")
        (let [next-card (run-stage root "propose" "spec"
                                   "spec.md" "features/extra.feature")
              next-id (latest-proposal-id root)]
          (is (zero? (:exit next-card)) (:err next-card))
          (is (= :pending (get-in (state root) [:proposals next-id :status])))
          (is (fs/regular-file? (approval-file next-id)))))
      (finally
        (fs/delete-tree root)))))

(deftest a-spec-proposal-reaches-the-dashboard-and-the-board
  (let [root (fixture)
        approval-file (fn [id]
                        (fs/path root ".swarmforge" "handoffs"
                                 "pending_approval" (str id ".handoff")))
        board-file (fs/path root ".swarmforge" "board" "tasks.tsv")]
    (try
      (testing "proposing writes one stage approval and a spec card"
        (let [propose (run-stage root "propose" "spec"
                                 "spec.md" "features/extra.feature")
              id (latest-proposal-id root)]
          (is (zero? (:exit propose)) (:err propose))
          (is (str/includes? (:out propose) "phone's approvals room"))
          (is (fs/regular-file? (approval-file id)))
          (let [approval (slurp (str (approval-file id)))]
            (is (str/includes? approval "kind: stage-spec"))
            (is (str/includes? approval "to: operator"))
            (is (str/includes? approval "features/extra.feature")))
          (is (re-find #"(?m)^fixture\tspec\t" (slurp (str board-file))))
          (is (str/includes?
               (slurp (str (fs/path root ".swarmforge" "board" "lanes.tsv")))
               "code"))
          (let [dashboard (json/parse-string
                           (:out (run-pack-web-test root "--test-state" (str root)))
                           true)
                approval (first (:approvals dashboard))]
            (is (= "stage-spec" (:kind approval)))
            (is (= "spec → operator" (:gate approval)))
            (is (= "Fixture spec" (:task approval))))))

      (testing "a new proposal replaces the old approval, and approving clears it"
        (let [old-id (latest-proposal-id root)]
          (run-stage root "propose" "spec" "spec.md" "features/extra.feature" "--replace")
          (let [new-id (latest-proposal-id root)]
            (is (not= old-id new-id))
            (is (not (fs/exists? (approval-file old-id))))
            (is (fs/regular-file? (approval-file new-id)))
            (is (zero? (:exit (run-stage root "approve" new-id "--words" "approve"))))
            (is (not (fs/exists? (approval-file new-id)))))))

      (testing "a stage check moves the card, and completion finishes it"
        (doseq [stage ["code" "clean" "architect" "harden" "qa"]]
          (is (zero? (:exit (run-stage root "check" stage)))))
        (is (re-find #"(?m)^fixture\tqa\t" (slurp (str board-file))))
        (is (zero? (:exit (run-stage root "complete"))))
        (is (re-find #"(?m)^fixture\tdone\t" (slurp (str board-file)))))
      (finally
        (fs/delete-tree root)))))

(deftest a-routed-card-is-carried-through-the-stage-tool
  (let [root (fixture)
        board-file (fs/path root ".swarmforge" "board" "tasks.tsv")]
    (try
      (write-file board-file
                  (str "routed-card\tspec\t2026-10-24T08:00:00Z\t"
                       "2026-10-24T08:00:00Z\troute-123\t0\n"
                       "fixture\tspec\t2026-10-24T08:01:00Z\t"
                       "2026-10-24T08:01:00Z\tfixture\t0\n"))
      (let [propose (run-stage root "propose" "spec"
                               "spec.md" "features/extra.feature")
            id (latest-proposal-id root)
            approval (slurp (str (fs/path root ".swarmforge" "handoffs"
                                          "pending_approval" (str id ".handoff"))))
            board (slurp (str board-file))]
        (is (zero? (:exit propose)) (:err propose))
        (is (str/includes? approval "task: routed-card"))
        (is (str/includes? approval "task_id: route-123"))
        (is (re-find #"(?m)^routed-card\tspec\t.*\troute-123\t0$" board))
        (is (not (re-find #"(?m)^fixture\tspec\t" board)))
        (is (zero? (:exit (run-stage root "approve" id "--words" "approve")))))

      (doseq [stage ["code" "clean" "architect" "harden" "qa"]]
        (is (zero? (:exit (run-stage root "check" stage)))))
      (is (zero? (:exit (run-stage root "complete"))))
      (let [board (slurp (str board-file))]
        (is (re-find #"(?m)^routed-card\tdone\t.*\troute-123\t0$" board))
        (is (not (re-find #"(?m)^fixture\t" board))))
      (finally
        (fs/delete-tree root)))))

(deftest a-sent-back-spec-is-not-an-approval
  (let [root (fixture)]
    (try
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [id (latest-proposal-id root)]
        (let [reject (run-stage root "reject" id "--words" "split the scenario")]
          (is (zero? (:exit reject)) (:err reject))
          (is (str/includes? (:out reject) "sent back"))
          (is (= :sent_back (get-in (state root) [:proposals id :status])))
          (is (not (fs/exists?
                    (fs/path root ".swarmforge" "handoffs"
                             "pending_approval" (str id ".handoff")))))
          (let [check (run-stage root "check" "spec")]
            (is (not (zero? (:exit check))))
            (is (str/includes? (:err check) "No current approved proposal")))))
      (finally
        (fs/delete-tree root)))))

(deftest a-pending-spec-must-be-decided-or-explicitly-replaced
  (let [root (fixture)
        approval-file (fn [id]
                        (fs/path root ".swarmforge" "handoffs"
                                 "pending_approval" (str id ".handoff")))
        resolved-file (fn [id]
                        (fs/path root ".swarmforge" "approvals"
                                 "resolved" (str id ".edn")))]
    (try
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [first-id (latest-proposal-id root)]
        (let [blocked (run-stage root "propose" "spec"
                                 "spec.md" "features/extra.feature")]
          (is (not (zero? (:exit blocked))))
          (is (str/includes? (:err blocked) "already pending"))
          (is (str/includes? (:err blocked) first-id))
          (is (fs/regular-file? (approval-file first-id))))
        (let [replaced (run-stage root "propose" "spec"
                                  "spec.md" "features/extra.feature" "--replace")
              second-id (latest-proposal-id root)]
          (is (zero? (:exit replaced)) (:err replaced))
          (is (not= first-id second-id))
          (is (= :superseded (get-in (state root) [:proposals first-id :status])))
          (is (fs/regular-file? (resolved-file first-id)))
          (is (str/includes? (slurp (str (resolved-file first-id))) "superseded"))
          (is (not (fs/exists? (approval-file first-id))))
          (is (fs/regular-file? (approval-file second-id)))))
      (finally
        (fs/delete-tree root)))))

(deftest the-dashboard-decides-a-stage-spec-through-the-stage-tool
  (let [root (fixture)
        approval-file (fn [id]
                        (fs/path root ".swarmforge" "handoffs"
                                 "pending_approval" (str id ".handoff")))]
    (try
      (testing "accepting approves the proposal and clears the approval"
        (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
        (let [id (latest-proposal-id root)
              result (run-pack-web-test root "--test-approve" (str root) id)]
          (is (zero? (:exit result)) (:err result))
          (is (= :approved (get-in (state root) [:proposals id :status])))
          (is (not (fs/exists? (approval-file id))))))

      (testing "sending back records the operator's words and wakes the agent"
        (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
        (let [id (latest-proposal-id root)
              result (run-pack-web-test root "--test-retry-task"
                                        (str root) id "split the scenario")]
          (is (zero? (:exit result)) (:err result))
          (is (= :sent_back (get-in (state root) [:proposals id :status])))
          (is (not (fs/exists? (approval-file id))))
          (let [pending (fs/path root ".swarmforge" "dashboard" "requests" "pending")
                bodies (when (fs/directory? pending)
                         (mapv #(slurp (str %)) (fs/list-dir pending)))]
            (is (some #(str/includes? % "split the scenario") bodies)))))
      (finally
        (fs/delete-tree root)))))
