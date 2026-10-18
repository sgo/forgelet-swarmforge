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
    (write-file (fs/path forge "swarmforge" "constitution" "articles"
                          "engineering.prompt")
                "engineering\n")
    forge))

(defn state [project]
  (edn/read-string
   (slurp (str (fs/path project ".swarmforge" "stages" "agent.edn")))))

(defn run-stage [project & args]
  (apply run project
         "bb" (str (fs/path project "swarmforge" "scripts" "stage.bb"))
         (concat args ["--root" (str project)])))

(defn stop-agent-session! [project]
  (sh/sh "tmux" "-S" (str (fs/path project ".swarmforge" "agent-tmux.sock"))
         "kill-server"))

(deftest a-single-agent-project-composes-and-walks-the-template
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (testing "the composer writes the template and refuses a second run"
        (let [first-run (run repo-root composer "--forge" (str forge) "agent")]
          (is (zero? (:exit first-run)) (:err first-run)))
        (is (fs/regular-file? (fs/path project "stages.edn")))
        (is (fs/regular-file? (fs/path project "STAGES.md")))
        (is (fs/regular-file? (fs/path project "AGENTS.md")))
        (is (fs/regular-file? (fs/path project ".swarmforge" "single-agent")))
        (is (fs/regular-file?
             (fs/path project "swarmforge" "constitution" "articles"
                      "engineering.prompt")))
        (is (= "standard\n" (slurp (str (fs/path project ".swarmforge" "profile")))))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             ":task \"agent\""))
        (is (= "Java\n" (slurp (str (fs/path project "swarmforge" "language.conf")))))
        (is (str/includes?
             (slurp (str (fs/path project ".swarmforge" "roles.tsv")))
             (str "agent\tmaster\t" project "\tswarmforge-agent")))
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
        (write-file (fs/path project "features" "agent.feature")
                    "Feature: agent\n\n  Scenario: agent\n    Given a card\n")
        (write-file (fs/path project "tasks" "agent.md")
                    "# agent\n\n## Operator-observed problem\n\nA card.\n")
        (run-stage project "propose" "spec"
                   "features/*.feature" "tasks/*.md")
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
                       "--agent-command"
                       "sh -c 'echo \"<<AGENT READY>>\"; while IFS= read -r line; do printf \"answer: %s\\n<<AGENT READY>>\\n\" \"$line\"; done'")]
          (is (zero? (:exit run)) (:err run))
          (is (not (fs/exists? (fs/path project ".swarmforge" "dashboard" "requests"
                                         "pending" "req-test.request"))))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-test.request")))]
            (is (str/includes? answer "Do the thing.")))))

      (testing "a turn cannot complete while stages are stale"
        (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                             "req-complete.request")
                    "id: req-complete\nstatus: pending\ncreated_at: 2026-10-18T00:00:00Z\n\nTry to complete.\n")
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command"
                       "sh -c 'echo \"<<AGENT READY>>\"; while IFS= read -r line; do bb swarmforge/scripts/stage.bb complete --root . 2>&1 || true; echo \"<<AGENT READY>>\"; done'")]
          (is (zero? (:exit run)) (:err run))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-complete.request")))]
            (is (str/includes? answer "Try to complete.")
                (str "answer was: " answer)))))

      (testing "the runner can own the project dashboard"
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once"
                       "--agent-command"
                       "sh -c 'echo \"<<AGENT READY>>\"; sleep 60'")]
          (is (zero? (:exit run)) (:err run))
          (let [url-file (fs/path project ".swarmforge" "dashboard-url")]
            (is (fs/regular-file? url-file))
            (is (str/starts-with? (slurp (str url-file)) "http://")))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest a-library-project-uses-the-verify-profile-instead-of-qa
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")]
    (try
      (let [run (run repo-root composer "--forge" (str forge)
                     "--profile" "library" "agent-lib")]
        (is (zero? (:exit run)) (:err run)))
      (is (= "library\n" (slurp (str (fs/path project ".swarmforge" "profile")))))
      (let [stages (slurp (str (fs/path project "stages.edn")))]
        (is (str/includes? stages ":profile :library"))
        (is (str/includes? stages ":task \"agent-lib\""))
        (is (str/includes? stages ":name \"verify\""))
        (is (not (str/includes? stages ":name \"qa\""))))
      (let [listing (run-stage project "list")]
        (is (zero? (:exit listing)) (:err listing))
        (is (str/includes? (:out listing) "verify"))
        (is (not (str/includes? (:out listing) "qa"))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest stop-closes-the-agent-session
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")
        sock (str (fs/path project ".swarmforge" "agent-tmux.sock"))
        pointer (fs/path project ".swarmforge" "tmux-socket")]
    (try
      (run repo-root composer "--forge" (str forge) "agent")
      (spit (str pointer) (str sock "\n"))
      (sh/sh "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-agent" "sleep" "60")
      (is (zero? (:exit (sh/sh "tmux" "-S" sock "has-session" "-t" "swarmforge-agent"))))
      (let [stop (run project
                      "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                      "--root" (str project) "--stop")]
        (is (zero? (:exit stop)) (:err stop)))
      (is (not (zero? (:exit (sh/sh "tmux" "-S" sock "has-session" "-t" "swarmforge-agent")))))
      (is (not (fs/exists? pointer)))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))
