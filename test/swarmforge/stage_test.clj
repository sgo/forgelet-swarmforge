(ns swarmforge.stage-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def stage-script (fs/path repo-root "swarmforge" "scripts" "stage.bb"))

(def stage-config
  {:task "fixture"
   :stages
   [{:name "spec"
     :approval :operator
     :spec "spec.md"
     :stamp ["spec.md"]}
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
    (write-file (fs/path root "stages.edn") (pr-str stage-config))
    (sh/sh "git" "add" "-A" :dir (str root))
    (sh/sh "git" "commit" "-q" "-m" "fixture" :dir (str root))
    root))

(deftest a-fixture-walks-every-stage
  (let [root (fixture)]
    (try
      (testing "the spec is proposed and approved"
        (let [propose (run-stage root "propose" "spec" "spec.md")]
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

(deftest approval-is-single-use-and-tied-to-the-spec
  (let [root (fixture)]
    (try
      (run-stage root "propose" "spec" "spec.md")
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
        (run-stage root "propose" "spec" "spec.md")
        (let [pending (latest-proposal-id root)]
          (write-file (fs/path root "spec.md") "# Changed again\n")
          (let [result (run-stage root "approve" pending "--words" "approve")]
            (is (not (zero? (:exit result))))
            (is (str/includes? (:err result) "changed")))))
      (finally
        (fs/delete-tree root)))))
