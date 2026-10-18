(ns swarmforge.agent-project-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def composer (str (fs/path repo-root "get-agent-project")))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [dir & args]
  (apply sh/sh (concat args [:dir (str dir)])))

(defn tmp-dir []
  (fs/create-temp-dir {:dir "/tmp" :prefix "sf-agent-project."}))

(defn fixture-forge []
  (let [forge (tmp-dir)]
    (fs/create-dirs (fs/path forge "projects"))
    (fs/create-dirs (fs/path forge "swarmforge"))
    (fs/copy-tree (fs/path repo-root "swarmforge" "scripts")
                  (fs/path forge "swarmforge" "scripts")
                  {:replace-existing true})
    forge))

(defn state [project]
  (edn/read-string
   (slurp (str (fs/path project ".swarmforge" "stages" "agent-project.edn")))))

(defn run-stage [project & args]
  (apply run project
         "bb" (str (fs/path project "swarmforge" "scripts" "stage.bb"))
         (concat args ["--root" (str project)])))

(deftest a-single-agent-project-composes-and-walks-the-template
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (testing "the composer writes the template and refuses a second run"
        (let [first-run (run repo-root composer "--forge" (str forge) "agent")]
          (is (zero? (:exit first-run)) (:err first-run)))
        (is (fs/regular-file? (fs/path project "stages.edn")))
        (is (fs/regular-file? (fs/path project "STAGES.md")))
        (is (fs/regular-file? (fs/path project ".swarmforge" "single-agent")))
        (is (= "Java\n" (slurp (str (fs/path project "swarmforge" "language.conf")))))
        (is (str/includes?
             (slurp (str (fs/path project ".swarmforge" "roles.tsv")))
             (str "agent\tmaster\t" project)))
        (let [second-run (run repo-root composer "--forge" (str forge) "agent")]
          (is (not (zero? (:exit second-run))))
          (is (str/includes? (:err second-run) "already exists"))))

      (testing "the template lists every stage"
        (let [listing (run-stage project "list")]
          (is (zero? (:exit listing)) (:err listing))
          (doseq [stage ["spec" "code" "clean" "architect"
                         "harden" "clean-final" "qa" "release"]]
            (is (str/includes? (:out listing) stage)))))

      (testing "the code gate runs its command and records evidence"
        (run-stage project "propose" "spec" "spec.md")
        (let [id (-> (state project) :proposals vals first :id)
              approve (run-stage project "approve" id "--words" "approve")]
          (is (zero? (:exit approve)) (:err approve)))
        (let [fake-bin (fs/path forge "fake-bin")
              mvn (fs/path fake-bin "mvn")]
          (write-file mvn
                      "#!/bin/sh\nmkdir -p target/surefire-reports\necho '<testsuite/>' > target/surefire-reports/TEST.xml\n")
          (fs/set-posix-file-permissions mvn "rwxr-xr-x")
          (let [check (apply sh/sh
                             (concat ["bb" (str (fs/path project "swarmforge" "scripts" "stage.bb"))
                                      "check" "code" "--root" (str project)]
                                     [:dir (str project)
                                      :env {"PATH" (str fake-bin ":" (System/getenv "PATH"))}]))]
            (is (zero? (:exit check)) (:err check))))
        (is (= 1 (count (get-in (state project) [:stages "code" :evidence])))))

      (testing "one dashboard request becomes one agent turn"
        (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                             "req-test.request")
                    "id: req-test\nstatus: pending\ncreated_at: 2026-10-18T00:00:00Z\n\nDo the thing.\n")
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command" "cat")]
          (is (zero? (:exit run)) (:err run))
          (is (not (fs/exists? (fs/path project ".swarmforge" "dashboard" "requests"
                                         "pending" "req-test.request"))))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-test.request")))]
            (is (str/includes? answer "Do the thing."))
            (is (str/includes? answer "--- stage list ---")))))

      (testing "a turn cannot complete while stages are stale"
        (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                             "req-complete.request")
                    "id: req-complete\nstatus: pending\ncreated_at: 2026-10-18T00:00:00Z\n\nTry to complete.\n")
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command"
                       "sh -c 'cat; bb swarmforge/scripts/stage.bb complete --root . 2>&1 || true'")]
          (is (zero? (:exit run)) (:err run))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-complete.request")))]
            (is (str/includes? answer "Stages not current")))))

      (testing "the runner can own the project dashboard"
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once"
                       "--agent-command" "cat")]
          (is (zero? (:exit run)) (:err run))
          (let [url-file (fs/path project ".swarmforge" "dashboard-url")]
            (is (fs/regular-file? url-file))
            (is (str/starts-with? (slurp (str url-file)) "http://")))))
      (finally
        (fs/delete-tree forge)))))
