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

(load-file (str stage-script))

(def stage-operation-cache-var
  (get (ns-publics 'stage) '*operation-cache*))

(defn stage-call [name & args]
  (apply (deref (ns-resolve 'stage (symbol name))) args))

(defn with-stage-cache [f]
  (with-bindings {stage-operation-cache-var (atom {})}
    (f)))

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

(defn delete-tree-best-effort [path]
  (try
    (fs/delete-tree path)
    (catch Exception _
      ;; A process-owned temporary object can disappear between listing and
      ;; visiting it. Test teardown must not turn that race into a gate failure.
      nil)))

(defn run-script [script root & args]
  (apply sh/sh
         (concat ["bb" (str script)]
                 args
                 [:dir (str root)])))

(defn run-stage [root & args]
  (apply run-script stage-script root args))

(defn run-pack-web-test [root & args]
  (apply run-script pack-web-test root args))

(defn state [root]
  (edn/read-string
   (slurp (str (fs/path root ".swarmforge" "stages" "fixture.edn")))))

(defn latest-proposal-id [root]
  (last (sort (keys (:proposals (state root))))))

(defn board-lane [root name]
  (let [file (fs/path root ".swarmforge" "board" "tasks.tsv")]
    (some (fn [line]
            (let [[row-name lane] (str/split line #"\t" -1)]
              (when (= row-name name) lane)))
          (str/split-lines (slurp (str file))))))

(defn fixture-at [root]
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
  root)

(defn fixture []
  (fixture-at (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage."})))

(defn commit-change! [root message]
  (let [add (sh/sh "git" "add" "-A" :dir (str root))
        commit (sh/sh "git" "commit" "-q" "-m" message :dir (str root))]
    (when-not (and (zero? (:exit add)) (zero? (:exit commit)))
      (throw (ex-info "fixture commit failed"
                      {:add add :commit commit})))))

(defn scoped-stage-config []
  {:task "fixture"
   :stages
   [{:name "spec"
     :approval :operator
     :spec ["spec.md" "features/extra.feature"]
     :stamp ["spec.md" "features/extra.feature"]}
    {:name "code"
     :runs [["sh" "-c" "mkdir -p target && echo code > target/code.txt"]]
     :evidence ["target/code.txt"]
     :stamp [:sources]}
    {:name "clean"
     :runs [["sh" "-c" "echo clean > target/clean.txt"]]
     :evidence ["target/clean.txt"]
     :stamp ["core/src/main"]}
    {:name "architect"
     :runs [["sh" "-c" "echo architect > target/architect.txt"]]
     :evidence ["target/architect.txt"]
     :stamp ["scripts/harness.sh"]}
    {:name "harden"
     :runs [["sh" "-c" "echo harden > target/harden.txt"]]
     :evidence ["target/harden.txt"]
     :stamp ["build.edn"]}
    {:name "qa"
     :runs [["sh" "-c" "echo qa > target/qa.txt"]]
     :evidence ["target/qa.txt"]
     :stamp [:features]}
    {:name "docs"
     :runs [["sh" "-c" "echo docs > target/docs.txt"]]
     :evidence ["target/docs.txt"]
     :stamp ["README.md" "spec.md"]}
    {:name "deploy"
     :runs [["sh" "-c" "echo deploy > target/deploy.txt"]]
     :evidence ["target/deploy.txt"]
     :stamp ["core/pom.xml"]}]})

(defn scoped-fixture []
  (let [root (fixture)]
    (write-file (fs/path root "scripts" "harness.sh") "#!/bin/sh\nexit 0\n")
    (write-file (fs/path root "build.edn") "{:build :fixture}\n")
    (write-file (fs/path root "README.md") "# Fixture\n")
    (write-file (fs/path root "stages.edn") (pr-str (scoped-stage-config)))
    (commit-change! root "scoped stage fixture")
    root))

(defn legacy-input-files [root pattern]
  (let [glob? (boolean (re-find #"[*?\[\]]" pattern))]
    (cond
      (= pattern ".") (->> (fs/glob root "**")
                           (filter fs/regular-file?)
                           (sort))
      glob? (->> (fs/glob root pattern)
                 (filter fs/regular-file?)
                 (sort))
      (fs/directory? (fs/path root pattern))
      (->> (fs/glob root (str pattern "/**"))
           (filter fs/regular-file?)
           (sort))
      (fs/regular-file? (fs/path root pattern)) [(fs/path root pattern)]
      :else [])))

(deftest rooted-input-expansion-preserves-declared-path-semantics
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage-inputs."})]
    (try
      (doseq [[path content]
              [["src/root.txt" "root\n"]
               ["src/main/Main.java" "class Main {}\n"]
               ["src/main/nested/Helper.java" "class Helper {}\n"]
               ["src/test/MainTest.java" "class MainTest {}\n"]
               ["modules/a/build.gradle.kts" "plugins {}\n"]
               ["modules/b/build.gradle.kts" "plugins {}\n"]
               ["modules/a/nested/ignored.txt" "not a module build\n"]]]
        (write-file (fs/path root path) content))
      (with-stage-cache
        (fn []
          (doseq [pattern ["."
                           "src/root.txt"
                           "src/main"
                           "src/*.txt"
                           "**/*.txt"
                           "modules/*/build.gradle.kts"
                           "missing/**"]]
            (is (= (mapv str (legacy-input-files root pattern))
                   (mapv str (stage-call "input-files" root pattern)))
                pattern))))
      (finally
        (fs/delete-tree root)))))

(deftest input-expansion-cache-is-scoped-to-one-stage-command
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage-cache."})]
    (try
      (write-file (fs/path root "src" "main" "Main.java") "class Main {}\n")
      (with-stage-cache
        (fn []
          (let [before (mapv str (stage-call "input-files" root "src/main"))]
            (write-file (fs/path root "src" "main" "Later.java")
                        "class Later {}\n")
            (is (= before
                   (mapv str (stage-call "input-files" root "src/main")))))))
      (is (some #(str/ends-with? % "Later.java")
                (mapv str (stage-call "input-files" root "src/main"))))
      (finally
        (fs/delete-tree root)))))

(def evidence-task-requirement
  (str "# evidence-card\n\n"
       "## Requirement\n\n"
       "Keep the real requirement stable.\n\n"))

(def evidence-task-report
  (str "## Verification\n\n"
       "Focused gates will run.\n\n"
       "## Operator acceptance\n\n"
       "None.\n"))

(defn evidence-only-fixture []
  (let [root (fixture)
        config {:task "fixture"
                :stages
                [{:name "spec"
                  :approval :operator
                  :spec ["tasks/evidence-card.md" "features"]
                  :stamp ["tasks/evidence-card.md" "features"]}
                 {:name "code"
                  :runs [["true"]]
                  :evidence []
                  :stamp ["source.txt" "bb.edn"]}
                 {:name "clean"
                  :runs [["sh" "-c" "mkdir -p target && echo clean > target/clean.txt"]]
                  :evidence ["target/clean.txt"]
                  :stamp ["test.txt" "bb.edn"]}
                 {:name "deploy"
                  :runs [["true"]]
                  :evidence []
                  :stamp ["source.txt"]}]}]
    (write-file (fs/path root "stages.edn") (pr-str config))
    (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
    (write-file (fs/path root ".swarmforge" "board" "tasks.tsv")
                "evidence-card\tspec\t2026-11-07T00:00:00Z\t2026-11-07T00:00:00Z\tevidence-task\t0\n")
    (write-file (fs/path root "tasks" "evidence-card.md")
                (str evidence-task-requirement evidence-task-report))
    (write-file (fs/path root "features" "requirement.feature")
                "Feature: stable requirement\n")
    (write-file (fs/path root "source.txt") "source\n")
    (write-file (fs/path root "test.txt") "test\n")
    (write-file (fs/path root "bb.edn") "{}\n")
    (write-file (fs/path root "README.md") "# Evidence fixture\n")
    (write-file (fs/path root "docs" "manual.md") "# Manual\n")
    (commit-change! root "evidence-only fixture")
    root))

(defn run-evidence-stages! [root]
  (run-stage root "propose" "spec"
             "tasks/evidence-card.md" "features/requirement.feature")
  (doseq [stage ["code" "clean" "deploy"]]
    (let [result (run-stage root "check" stage)]
      (when-not (zero? (:exit result))
        (throw (ex-info (str stage " stage failed") result))))))

(defn stage-statuses [root]
  (let [status (-> (run-stage root "status" "--json")
                   :out
                   (json/parse-string true))]
    (into {} (map (juxt :name :status)) (:stages status))))

(defn run-scoped-stages! [root]
  (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
  (run-stage root "approve" (latest-proposal-id root) "--words" "approve")
  (doseq [stage ["code" "clean" "architect" "harden" "qa" "docs" "deploy"]]
    (let [result (run-stage root "check" stage)]
      (when-not (zero? (:exit result))
        (throw (ex-info (str stage " stage failed") result))))))

(declare seed-single-agent-card!)

(defn task-report
  [acceptance]
  (str "# fixture-card\n\n"
       "## Verification\n\n"
       "Automated gates: 418 tests, 2,662 assertions. Source pushed.\n\n"
       "## Operator acceptance\n\n"
       acceptance "\n"))

(defn operator-acceptance-fixture
  [acceptance]
  (let [root (scoped-fixture)]
    (write-file (fs/path root "tasks" "fixture-card.md")
                (task-report acceptance))
    (commit-change! root "add fixture-card task report")
    (seed-single-agent-card! root)
    (run-scoped-stages! root)
    root))

(defn seed-single-agent-card!
  ([root] (seed-single-agent-card! root "fixture-card"))
  ([root name]
   (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
   (write-file (fs/path root ".swarmforge" "board" "tasks.tsv")
               (str name "\tspec\t2026-11-07T00:00:00Z\t"
                    "2026-11-07T00:00:00Z\tfixture-task\t0\n"))))

(deftest a-fixture-walks-every-stage
  (let [root (fixture)]
    (try
      (testing "the spec is proposed and approved"
        (let [propose (run-stage root "propose" "spec"
                                 "spec.md" "features/extra.feature")]
          (is (zero? (:exit propose)) (:err propose))
          (is (str/includes? (:out propose) "spec proposal"))
          (is (not (contains? (get-in (state root)
                                      [:proposals (latest-proposal-id root)])
                              :body))
              "proposal bodies are transient, not stage state"))
        (let [approve (run-stage root "approve" (latest-proposal-id root)
                                 "--words" "approve")]
          (is (zero? (:exit approve)) (:err approve))
          (is (= "spec" (board-lane root "fixture")))))

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
          (is (some? (:complete parsed)))
          (is (= "fully complete" (:completion_status parsed)))
          (is (= "not-recorded"
                 (get-in parsed [:operator_acceptance :status])))))
      (finally
        (fs/delete-tree root)))))

(deftest pending-environment-bound-operator-acceptance-completes-and-persists
  (let [root (operator-acceptance-fixture
              (str "- owner: operator | environment: physical signed iPhone | "
                   "checks: Install the build and verify save/restore | "
                   "status: pending"))]
    (try
      (let [before (-> (run-stage root "status" "--json")
                       :out
                       (json/parse-string true))
            listed (run-stage root "list")]
        (is (= "automation complete; operator acceptance pending"
               (:completion_status before)))
        (is (= "pending" (get-in before [:operator_acceptance :status])))
        (is (= 1 (count (get-in before [:operator_acceptance :entries]))))
        (is (str/includes? (:out listed)
                           "automation complete; operator acceptance pending"))
        (is (str/includes? (:out listed) "operator acceptance: pending"))
        (is (str/includes? (:out listed) "physical signed iPhone")))
      (let [complete (run-stage root "complete")
            status (-> (run-stage root "status" "--json")
                       :out
                       (json/parse-string true))
            stored (state root)]
        (is (zero? (:exit complete)) (:err complete))
        (is (str/includes? (:out complete) "operator acceptance: pending"))
        (is (str/includes? (:out complete) "physical signed iPhone"))
        (is (= "done" (board-lane root "fixture-card")))
        (is (= :pending
               (get-in stored [:complete :operator_acceptance :status])))
        (is (= "physical signed iPhone"
               (get-in stored
                       [:complete :operator_acceptance :entries 0 :environment])))
        (is (= "automation complete; operator acceptance pending"
               (:completion_status status)))
        (is (= "pending" (get-in status [:operator_acceptance :status]))))
      (finally
        (fs/delete-tree root)))))

(deftest no-operator-acceptance-residual-completes-as-none
  (let [root (operator-acceptance-fixture "None.")]
    (try
      (let [complete (run-stage root "complete")
            status (-> (run-stage root "status" "--json")
                       :out
                       (json/parse-string true))]
        (is (zero? (:exit complete)) (:err complete))
        (is (str/includes? (:out complete) "operator acceptance: none"))
        (is (= "fully complete" (:completion_status status)))
        (is (= "none" (get-in status [:operator_acceptance :status])))
        (is (= [] (get-in status [:operator_acceptance :entries]))))
      (finally
        (fs/delete-tree root)))))

(deftest malformed-or-unsafe-operator-acceptance-is-refused
  (doseq [acceptance
          [(str "- owner: operator | environment: iPhone | checks: verify | "
                "status: maybe")
           (str "- owner: agent | environment: iPhone | checks: verify | "
                "status: pending")
           (str "- owner: operator | environment: /Users/operator/private | "
                "checks: verify | status: pending")
           (str "- owner: operator | environment: token "
                "ghp_1234567890abcdefghijklmnopqrstuvwxyz | "
                "checks: verify | status: pending")]]
    (let [root (operator-acceptance-fixture acceptance)]
      (try
        (let [complete (run-stage root "complete")]
          (is (not (zero? (:exit complete))))
          (is (re-find #"Operator acceptance" (str (:err complete) (:out complete))))
          (is (not= "done" (board-lane root "fixture-card")))
          (is (nil? (:complete (state root)))))
        (finally
          (fs/delete-tree root))))))

(deftest pending-acceptance-cannot-bypass-a-required-release-gate
  (let [root (scoped-fixture)
        config (scoped-stage-config)
        config (update config :stages conj
                       {:name "release"
                        :runs [["sh" "-c" "echo release-approved > target/release.txt"]]
                        :evidence ["target/release.txt"]
                        :stamp ["README.md"]})]
    (try
      (write-file (fs/path root "stages.edn") (pr-str config))
      (write-file (fs/path root "tasks" "fixture-card.md")
                  (task-report
                   (str "- owner: operator | environment: iPhone | "
                        "checks: sign and install | status: pending")))
      (commit-change! root "add required release gate and acceptance")
      (seed-single-agent-card! root)
      (run-scoped-stages! root)
      (let [complete (run-stage root "complete")]
        (is (not (zero? (:exit complete))))
        (is (str/includes? (str (:err complete) (:out complete))
                           "Stages not current: release"))
        (is (not= "done" (board-lane root "fixture-card"))))
      (finally
        (fs/delete-tree root)))))

(deftest evidence-only-commits-preserve-technical-stage-freshness
  (let [root (evidence-only-fixture)]
    (try
      (run-evidence-stages! root)
      (let [all-current {"spec" "done" "code" "done" "clean" "done"
                         "deploy" "done"}]
        (is (= all-current (stage-statuses root)))

        (write-file (fs/path root "README.md") "# Updated evidence fixture\n")
        (write-file (fs/path root "docs" "manual.md")
                    "# Updated manual\n\nNo runtime input changed.\n")
        (commit-change! root "documentation only")
        (is (= all-current (stage-statuses root)))

        (write-file
         (fs/path root "tasks" "evidence-card.md")
         (str evidence-task-requirement
              "## Verification\n\n"
              "Tests and clean completed.\n\n"
              "## Operator acceptance\n\n"
              "- owner: operator | environment: physical device | "
              "checks: verify the visible result | status: pending\n"))
        (commit-change! root "finalize task report")
        (is (= all-current (stage-statuses root)))
        (let [complete (run-stage root "complete")]
          (is (zero? (:exit complete)) (:err complete))
          (is (str/includes? (:out complete) "operator acceptance: pending"))
          (is (= :pending
                 (get-in (state root)
                         [:complete :operator_acceptance :status]))))

        (write-file
         (fs/path root "tasks" "evidence-card.md")
         (str evidence-task-requirement
              "## Verification\n\n"
              "Final report exists.\n\n"
              "## Operator acceptance\n\n"
              "- owner: operator | environment: iPhone | "
              "checks: verify | status: maybe\n"))
        (commit-change! root "malformed final report")
        (let [refused (run-stage root "complete")]
          (is (not (zero? (:exit refused))))
          (is (str/includes? (str (:err refused) (:out refused))
                             "Operator acceptance status"))))
      (finally
        (fs/delete-tree root)))))

(deftest genuine-source-test-and-configuration-changes-invalidate-stages
  (let [root (evidence-only-fixture)]
    (try
      (run-evidence-stages! root)
      (let [current {"spec" "done" "code" "done" "clean" "done"
                     "deploy" "done"}]
        (write-file (fs/path root "tasks" "evidence-card.md")
                    (str evidence-task-requirement
                         "Changed requirement.\n\n"
                         evidence-task-report))
        (commit-change! root "change task requirement")
        (is (= (assoc current "spec" "stale") (stage-statuses root)))
        (write-file (fs/path root "tasks" "evidence-card.md")
                    (str evidence-task-requirement evidence-task-report))
        (commit-change! root "restore task requirement")
        (is (= current (stage-statuses root)))

        (write-file (fs/path root "features" "requirement.feature")
                    "Feature: changed requirement\n")
        (commit-change! root "change feature")
        (is (= (assoc current "spec" "stale") (stage-statuses root)))
        (write-file (fs/path root "features" "requirement.feature")
                    "Feature: stable requirement\n")
        (commit-change! root "restore feature")

        (write-file (fs/path root "source.txt") "changed source\n")
        (commit-change! root "change source")
        (is (= (assoc current "code" "stale" "deploy" "stale")
               (stage-statuses root)))
        (write-file (fs/path root "source.txt") "source\n")
        (commit-change! root "restore source")

        (write-file (fs/path root "test.txt") "changed test\n")
        (commit-change! root "change test")
        (is (= (assoc current "clean" "stale") (stage-statuses root)))
        (write-file (fs/path root "test.txt") "test\n")
        (commit-change! root "restore test")

        (write-file (fs/path root "bb.edn") "{:changed true}\n")
        (commit-change! root "change bb configuration")
        (is (= (assoc current "code" "stale" "clean" "stale")
               (stage-statuses root)))
        (write-file (fs/path root "bb.edn") "{}\n")
        (commit-change! root "restore bb configuration")

        (fs/delete (fs/path root "test.txt"))
        (commit-change! root "delete test input")
        (is (= (assoc current "clean" "stale") (stage-statuses root)))
        (write-file (fs/path root "test.txt") "test\n")
        (commit-change! root "restore test input")
        (is (= current (stage-statuses root))))
      (finally
        (fs/delete-tree root)))))

(deftest queued-task-notes-do-not-stale-scoped-stage-evidence
  (let [root (scoped-fixture)]
    (try
      (run-scoped-stages! root)
      (let [all-done (zipmap ["spec" "code" "clean" "architect" "harden"
                              "qa" "docs" "deploy"]
                             (repeat "done"))
            before-tree (:tree (json/parse-string
                                (:out (run-stage root "status" "--json"))
                                true))]
        (is (= all-done (stage-statuses root)))
        (write-file (fs/path root "tasks" "queued-next-card.md")
                    "# queued-next-card\n\nWaiting behind the active card.\n")
        (commit-change! root "queue unrelated card")
        (let [status (-> (run-stage root "status" "--json")
                         :out
                         (json/parse-string true))]
          (is (not= before-tree (:tree status)))
          (is (= (:tree status) (:head-tree status)))
          (is (= all-done (into {} (map (juxt :name :status)) (:stages status)))))
        (let [complete (run-stage root "complete")]
          (is (zero? (:exit complete)) (:err complete))))
      (finally
        (fs/delete-tree root)))))

(deftest large-generated-trees-stay-out-of-status-fingerprints
  (let [root (scoped-fixture)]
    (try
      (run-scoped-stages! root)
      (doseq [index (range 1000)]
        (write-file
         (fs/path root "generated" (str "module-" (mod index 20))
                  (str "artifact-" index ".txt"))
         (str "generated " index "\n")))
      (doseq [index (range 200)]
        (write-file
         (fs/path root "target" "generated" (str "ignored-" index ".txt"))
         (str "ignored " index "\n")))
      (commit-change! root "add large generated tree")
      (let [started (System/nanoTime)
            status (-> (run-stage root "status" "--json")
                       :out
                       (json/parse-string true))
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
            inputs (set (mapcat :inputs (:stages status)))]
        (is (= {"spec" "done" "code" "done" "clean" "done"
                "architect" "done" "harden" "done" "qa" "done"
                "docs" "done" "deploy" "done"}
               (into {} (map (juxt :name :status)) (:stages status))))
        (is (not-any? #(or (= "generated" %)
                           (str/starts-with? % "generated/")
                           (str/starts-with? % "target/"))
                      inputs))
        (is (< elapsed-ms 10000.0)
            (format "status took %.0f ms with an unrelated generated tree"
                    elapsed-ms)))
      (finally
        (delete-tree-best-effort root)))))

(deftest stage-freshness-follows-exact-stamped-inputs-and-deletions
  (let [root (scoped-fixture)
        all-done (zipmap ["spec" "code" "clean" "architect" "harden"
                          "qa" "docs" "deploy"]
                         (repeat "done"))
        assert-statuses! (fn [expected]
                           (is (= expected (stage-statuses root))))
        status-json (fn []
                      (-> (run-stage root "status" "--json")
                          :out
                          (json/parse-string true)))]
    (try
      (run-scoped-stages! root)
      (assert-statuses! all-done)

      (testing "a dirty and then committed main-source edit stales code and clean"
        (write-file (fs/path root "core" "src" "main" "Main.java")
                    "class Main { int changed; }\n")
        (is (false? (:clean (status-json))))
        (assert-statuses! (assoc all-done "code" "stale" "clean" "stale"))
        (commit-change! root "change main source")
        (is (true? (:clean (status-json))))
        (assert-statuses! (assoc all-done "code" "stale" "clean" "stale"))
        (write-file (fs/path root "core" "src" "main" "Main.java")
                    "class Main {}\n")
        (commit-change! root "restore main source")
        (assert-statuses! all-done))

      (testing "a test-source edit stales only the source-consuming stages"
        (write-file (fs/path root "core" "src" "test" "MainTest.java")
                    "class MainTest { int changed; }\n")
        (commit-change! root "change test source")
        (assert-statuses! (assoc all-done "code" "stale"))
        (write-file (fs/path root "core" "src" "test" "MainTest.java")
                    "class MainTest {}\n")
        (commit-change! root "restore test source"))

      (testing "a pom edit stales source and deploy inputs"
        (write-file (fs/path root "core" "pom.xml")
                    "<project><name>changed</name></project>\n")
        (commit-change! root "change module pom")
        (assert-statuses! (assoc all-done "code" "stale" "deploy" "stale"))
        (write-file (fs/path root "core" "pom.xml") "<project/>\n")
        (commit-change! root "restore module pom"))

      (testing "a harness edit stales only architect"
        (write-file (fs/path root "scripts" "harness.sh")
                    "#!/bin/sh\nexit 1\n")
        (commit-change! root "change harness")
        (assert-statuses! (assoc all-done "architect" "stale"))
        (write-file (fs/path root "scripts" "harness.sh")
                    "#!/bin/sh\nexit 0\n")
        (commit-change! root "restore harness"))

      (testing "deleting a stamped build input stales harden"
        (fs/delete (fs/path root "build.edn"))
        (commit-change! root "delete build input")
        (assert-statuses! (assoc all-done "harden" "stale"))
        (write-file (fs/path root "build.edn") "{:build :fixture}\n")
        (commit-change! root "restore build input"))

      (testing "a feature edit stales spec and the feature-consuming stage"
        (write-file (fs/path root "features" "extra.feature")
                    "Feature: extra\n\n  Scenario: changed\n    Given a change\n")
        (commit-change! root "change feature")
        (assert-statuses! (assoc all-done "spec" "stale" "qa" "stale"))
        (write-file (fs/path root "features" "extra.feature")
                    "Feature: extra\n\n  Scenario: extra\n    Given a fixture\n")
        (commit-change! root "restore feature")
        (assert-statuses! all-done))

      (testing "a task-note edit stales spec and its note-consuming stage"
        (write-file (fs/path root "spec.md")
                    "# Fixture spec\n\nChanged contract.\n")
        (commit-change! root "change task note")
        (assert-statuses! (assoc all-done "spec" "stale" "docs" "stale"))
        (let [complete (run-stage root "complete")]
          (is (not (zero? (:exit complete))))
          (is (str/includes? (:err complete) "spec"))
          (is (str/includes? (:err complete) "docs"))))
        (write-file (fs/path root "spec.md") "# Fixture spec\n\nA fixture.\n")
        (commit-change! root "restore task note")
        (assert-statuses! all-done)
        (let [complete (run-stage root "complete")]
          (is (zero? (:exit complete)) (:err complete)))
      (finally
        (fs/delete-tree root)))))

(deftest unknown-stamp-inputs-widen-to-the-whole-tree-with-a-warning
  (let [root (scoped-fixture)
        config (update (scoped-stage-config) :stages
                       (fn [stages]
                         (mapv #(if (= "harden" (:name %))
                                  (assoc % :stamp [:not-a-known-input])
                                  %)
                               stages)))]
    (try
      (write-file (fs/path root "stages.edn") (pr-str config))
      (commit-change! root "widen harden stamp")
      (run-scoped-stages! root)
      (let [listing (run-stage root "list")]
        (is (str/includes? (:err listing) "unknown stamp input"))
        (is (str/includes? (:err listing) "widened to the whole tree")))
      (is (= "done" (get (stage-statuses root) "harden")))
      (write-file (fs/path root "tasks" "queued-next-card.md")
                  "# Queued card\n")
      (commit-change! root "queue card after widened harden")
      (is (= "stale" (get (stage-statuses root) "harden")))
      (let [complete (run-stage root "complete")]
        (is (not (zero? (:exit complete))))
        (is (str/includes? (:err complete) "harden")))
      (finally
        (fs/delete-tree root)))))

(deftest legacy-stage-records-without-input-hashes-are-stale
  (let [root (scoped-fixture)
        state-file (fs/path root ".swarmforge" "stages" "fixture.edn")]
    (try
      (run-scoped-stages! root)
      (let [legacy (update-in (state root) [:stages "harden"] dissoc :input_hash)]
        (write-file state-file (pr-str legacy)))
      (is (= "stale" (get (stage-statuses root) "harden")))
      (let [complete (run-stage root "complete")]
        (is (not (zero? (:exit complete))))
        (is (str/includes? (:err complete) "Stages not current: harden")))
      (is (nil? (get-in (state root) [:stages "harden" :input_hash])))
      (finally
        (fs/delete-tree root)))))

(deftest approved-spec-is-scoped-to-the-active-card
  (let [root (scoped-fixture)
        state-file (fs/path root ".swarmforge" "stages" "fixture.edn")]
    (try
      (run-scoped-stages! root)
      (let [next-state (assoc-in (state root) [:card :id] "next-card")]
        (write-file state-file (pr-str next-state)))
      (let [status (-> (run-stage root "status" "--json")
                       :out
                       (json/parse-string true))]
        (is (false? (:spec_approved status)))
        (is (= "stale" (get (into {} (map (juxt :name :status)) (:stages status))
                             "spec"))))
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

(deftest a-single-agent-maven-project-requires-a-required-local-install-stage
  (let [root (fixture)
        stages-file (fs/path root "stages.edn")]
    (try
      (seed-single-agent-card! root)
      (let [missing (run-stage root "complete")]
        (is (not (zero? (:exit missing))))
        (is (str/includes? (:err missing) "required deploy stage")))

      (write-file stages-file
                  (pr-str (update stage-config :stages conj
                                  {:name "deploy"
                                   :optional true
                                   :runs [["scripts/install-snapshot.sh"]]
                                   :stamp [:sources]})))
      (let [optional (run-stage root "complete")]
        (is (not (zero? (:exit optional))))
        (is (str/includes? (:err optional) "must not be optional")))

      (write-file stages-file
                  (pr-str (update stage-config :stages conj
                                  {:name "deploy"
                                   :runs [["scripts/install-snapshot.sh"]]
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

(deftest a-single-agent-auto-approves-specs-by-default
  (let [root (fixture)
        approval-file (fn [id]
                        (fs/path root ".swarmforge" "handoffs"
                                 "pending_approval" (str id ".handoff")))]
    (try
      (seed-single-agent-card! root)
      (let [automatic (run-stage root "propose" "spec"
                                 "spec.md" "features/extra.feature")]
        (is (zero? (:exit automatic)) (:err automatic)))
      (let [first-id (latest-proposal-id root)]
        (is (= :approved (get-in (state root) [:proposals first-id :status])))
        (is (= "forge policy: automatic spec approval"
               (get-in (state root) [:proposals first-id :approval-policy])))
        (is (= "code" (board-lane root "fixture-card")))
        (is (= first-id (get-in (state root) [:stages "spec" :evidence 0 :proposal])))
        (is (nil? (get-in (state root) [:stages "code"])))
        (is (not (fs/exists? (fs/path root "target" "code.txt"))))
        (let [parsed (-> (run-stage root "status" "--json")
                         :out
                         (json/parse-string true))
              code-stage (some #(when (= "code" (:name %)) %) (:stages parsed))]
          (is (= "todo" (:status code-stage))))
        (is (not (fs/exists? (approval-file first-id))))
        (is (str/includes? (:out (run-stage root "list"))
                           "(automatic approval)"))
        (is (= "automatic"
               (-> (run-stage root "status" "--json")
                   :out
                   (json/parse-string true)
                   :spec_approval_mode)))

        (write-file (fs/path root "spec.md")
                    "# Fixture spec\n\nA correction found during implementation.\n")
        (let [correction (run-stage root "propose" "spec"
                                    "spec.md" "features/extra.feature")
              correction-id (latest-proposal-id root)]
          (is (zero? (:exit correction)) (:err correction))
          (is (str/includes? (:out correction) "Auto-approved"))
          (is (= :approved (get-in (state root) [:proposals correction-id :status])))
          (is (= "forge policy: automatic spec approval"
                 (get-in (state root) [:proposals correction-id :approval-policy])))
          (is (= "code" (board-lane root "fixture-card")))
          (is (nil? (get-in (state root) [:stages "code"])))
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
          (is (= :approved (get-in (state root) [:proposals next-id :status])))
          (is (not (fs/exists? (approval-file next-id))))))
      (finally
        (fs/delete-tree root)))))

(deftest a-forge-can-opt-a-single-agent-back-into-spec-approval
  (let [forge (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage-forge."})
        root (fs/path forge "projects" "fixture")]
    (try
      (fs/create-dirs root)
      (fixture-at root)
      (seed-single-agent-card! root)
      (write-file (fs/path forge "swarmforge" "local-spec-approval.conf")
                  "# Restore the explicit operator gate.\nspec=operator\n")
      (let [proposal (run-stage root "propose" "spec"
                                "spec.md" "features/extra.feature")
            id (latest-proposal-id root)
            approval (fs/path root ".swarmforge" "handoffs"
                             "pending_approval" (str id ".handoff"))]
        (is (zero? (:exit proposal)) (:err proposal))
        (is (= :pending (get-in (state root) [:proposals id :status])))
        (is (fs/regular-file? approval))
        (is (str/includes? (:out (run-stage root "list"))
                           "(operator approval)"))
        (is (= "operator"
               (-> (run-stage root "status" "--json")
                   :out
                   (json/parse-string true)
                   :spec_approval_mode)))
        (let [approved (run-stage root "approve" id "--words" "approve")]
          (is (zero? (:exit approved)) (:err approved))
          (is (= "code" (board-lane root "fixture-card")))
          (is (= id (get-in (state root) [:stages "spec" :evidence 0 :proposal])))
          (is (nil? (get-in (state root) [:stages "code"])))
          (is (not (fs/exists? (fs/path root "target" "code.txt"))))))
      (finally
        (fs/delete-tree forge)))))

(deftest spec-approval-refuses-a-missing-active-card
  (let [root (fixture)]
    (try
      (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
      (write-file (fs/path root ".swarmforge" "board" "tasks.tsv")
                  "unrelated\tqueued\t2026-11-07T00:00:00Z\t2026-11-07T00:00:00Z\tother\t0\n")
      (let [proposal (run-stage root "propose" "spec"
                                "spec.md" "features/extra.feature")]
        (is (not (zero? (:exit proposal))))
        (is (str/includes? (:err proposal) "active card fixture"))
        (is (= "queued" (board-lane root "unrelated"))))
      (finally
        (fs/delete-tree root)))))

(deftest correction-approval-does-not-demote-a-later-gate-lane
  (let [root (fixture)]
    (try
      (seed-single-agent-card! root)
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [board (fs/path root ".swarmforge" "board" "tasks.tsv")]
        (write-file board
                    (str/replace (slurp (str board)) "\tcode\t" "\tclean\t")))
      (write-file (fs/path root "spec.md")
                  "# Fixture spec\n\nA correction after the code gate.\n")
      (let [correction (run-stage root "propose" "spec"
                                  "spec.md" "features/extra.feature")]
        (is (zero? (:exit correction)) (:err correction))
        (is (= "clean" (board-lane root "fixture-card")))
        (is (nil? (get-in (state root) [:stages "code"]))))
      (finally
        (fs/delete-tree root)))))

(deftest a-pack-project-ignores-the-single-agent-approval-policy
  (let [forge (fs/create-temp-dir {:dir "/tmp" :prefix "sf-stage-pack-forge."})
        root (fs/path forge "projects" "fixture")]
    (try
      (fs/create-dirs root)
      (fixture-at root)
      (write-file (fs/path forge "swarmforge" "local-spec-approval.conf")
                  "spec=automatic\n")
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [id (latest-proposal-id root)]
        (is (= :pending (get-in (state root) [:proposals id :status])))
        (is (str/includes? (:out (run-stage root "list"))
                           "(operator approval)")))
      (finally
        (fs/delete-tree forge)))))

(deftest an-old-proposal-body-is-compacted-when-state-is-loaded
  (let [root (fixture)
        state-file (fs/path root ".swarmforge" "stages" "fixture.edn")]
    (try
      (run-stage root "propose" "spec" "spec.md" "features/extra.feature")
      (let [id (latest-proposal-id root)
            current (state root)
            legacy (assoc-in current [:proposals id :body]
                             (apply str (repeat 100000 "old proposal text\n")))]
        (write-file state-file (pr-str legacy))
        (is (> (fs/size state-file) 100000))
        (let [listing (run-stage root "list")]
          (is (zero? (:exit listing)) (:err listing)))
        (is (not (contains? (get-in (state root) [:proposals id]) :body)))
        (is (< (fs/size state-file) 20000)))
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
            (is (str/includes? approval "features/extra.feature"))
            (is (str/includes? approval "Fixture spec")))
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
