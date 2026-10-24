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

(def fake-agent-command
  "sh -c 'echo \"<<AGENT READY>>\"; while IFS= read -r line; do case \"$line\" in \"[phone \"*) id=${line#\\[phone }; id=${id%%]*}; mkdir -p tmp; printf \"fake answer from %s\\n\" \"$id\" > tmp/answer.txt; ./swarmforge/scripts/pack_dashboard_request.sh answer \"$id\" ./tmp/answer.txt; echo \"<<AGENT READY>>\";; esac; done'")

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
        (is (fs/regular-file?
             (fs/path project "swarmforge" "scripts" "agent-pre-commit.sh")))
        (is (fs/regular-file? (fs/path project ".git" "hooks" "pre-commit")))
        (is (fs/regular-file?
             (fs/path project "acceptance" "src" "test" "java" "com" "sgo"
                      "acceptance" "AcceptanceRunner.java")))
        (is (fs/regular-file?
             (fs/path project "acceptance" "src" "test" "java" "com" "sgo"
                      "acceptance" "StepHandlers.java")))
        (is (fs/regular-file?
             (fs/path project "scripts" "run-acceptance-tests.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "run-acceptance-tests.sh")))
        (is (fs/regular-file?
             (fs/path project "scripts" "run-acceptance-mutation.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "run-acceptance-mutation.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "publish-snapshot.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "github-packages-token.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "check-documentation.sh")))
        (is (fs/regular-file?
             (fs/path project "docs" "manual-contract.edn")))
        (is (= "github-packages\n"
               (slurp (str (fs/path project "swarmforge" "deploy.conf")))))
        (is (fs/regular-file?
             (fs/path project "acceptance" "runner-worker.sh")))
        (is (fs/executable?
             (fs/path project "acceptance" "runner-worker.sh")))
        (is (fs/regular-file? (fs/path project ".swarmforge" "single-agent")))
        (is (fs/regular-file?
             (fs/path project "swarmforge" "constitution" "articles"
                      "engineering.prompt")))
        (is (= "standard\n" (slurp (str (fs/path project ".swarmforge" "profile")))))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             ":task \"agent\""))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             "scripts/run-acceptance-tests.sh"))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             "target/acceptance/acceptance.log"))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             "scripts/run-acceptance-mutation.sh"))
        (is (str/includes?
             (slurp (str (fs/path project "stages.edn")))
             "target/acceptance-mutation/mutation.log"))
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
                         "harden" "clean-final" "qa" "docs" "deploy" "release"]]
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
                       "--agent-command" fake-agent-command)]
          (is (zero? (:exit run)) (:err run))
          (is (not (fs/exists? (fs/path project ".swarmforge" "dashboard" "requests"
                                         "pending" "req-test.request"))))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-test.request")))]
            (is (str/includes? answer "fake answer from req-test")))))

      (testing "a turn cannot complete while stages are stale"
        (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                             "req-complete.request")
                    "id: req-complete\nstatus: pending\ncreated_at: 2026-10-18T00:00:00Z\n\nTry to complete.\n")
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command" fake-agent-command)]
          (is (zero? (:exit run)) (:err run))
          (let [answer (slurp (str (fs/path project ".swarmforge" "dashboard" "requests"
                                              "done" "req-complete.request")))]
            (is (str/includes? answer "fake answer from req-complete")
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
        (let [parsed (edn/read-string stages)
              names (mapv :name (:stages parsed))
              deploy (some #(when (= "deploy" (:name %)) %) (:stages parsed))]
          (is (= ["verify" "docs" "deploy" "release"]
                 (subvec names (- (count names) 4))))
          (is (not (:optional deploy)))
          (is (= [["scripts/publish-snapshot.sh"]] (:runs deploy))))
        (is (str/includes? stages "scripts/run-acceptance-tests.sh"))
        (is (str/includes? stages "target/acceptance/acceptance.log"))
        (is (str/includes? stages "scripts/run-acceptance-mutation.sh"))
        (is (not (str/includes? stages ":name \"qa\""))))
      (let [listing (run-stage project "list")]
        (is (zero? (:exit listing)) (:err listing))
        (is (str/includes? (:out listing) "verify"))
        (is (str/includes? (:out listing) "docs"))
        (is (str/includes? (:out listing) "deploy"))
        (is (not (str/includes? (:out listing) "qa"))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest standard-e2e-runs-the-card-procedure-and-redacts-inputs
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project "features" "agent.feature")
                  "Feature: agent\n\n  Scenario: agent\n    Given a card\n")
      (write-file (fs/path project "tasks" "agent.md")
                  "# agent\n\n## Operator-observed problem\n\nA card.\n")
      (run-stage project "propose" "spec" "features/*.feature" "tasks/*.md")
      (let [id (-> (state project) :proposals vals first :id)]
        (is (zero? (:exit (run-stage project "approve" id "--words" "approve")))))

      (write-file (fs/path project "qa" "agent.md")
                  "# Agent end-to-end procedure\n\nDrive the user-facing path.\n")
      (write-file (fs/path project "scripts" "run-qa-agent.sh")
                  "#!/bin/sh\nprintf 'e2e passed token=%s\\n' \"$E2E_SECRET\"\n")
      (fs/set-posix-file-permissions
       (fs/path project "scripts" "run-qa-agent.sh") "rwxr-xr-x")
      (write-file (fs/path project "qa" "e2e.edn")
                  (pr-str {:cards
                           {"agent"
                            {:procedure "qa/agent.md"
                             :script "scripts/run-qa-agent.sh"
                             :required-inputs ["E2E_SECRET"]}}}))

      (let [run (sh/sh (str (fs/path project "scripts" "run-qa-e2e.sh"))
                       :dir (str project)
                       :env {"PATH" (System/getenv "PATH")
                             "E2E_SECRET" "test-only-secret"})
            report (slurp (str (fs/path project "target" "e2e" "check.log")))]
        (is (zero? (:exit run)) (:err run))
        (is (str/includes? report "e2e passed"))
        (is (str/includes? report "<redacted:E2E_SECRET>"))
        (is (not (str/includes? report "test-only-secret"))))

      (let [missing (sh/sh (str (fs/path project "scripts" "run-qa-e2e.sh"))
                           :dir (str project)
                           :env {"PATH" (System/getenv "PATH")
                                 "E2E_SECRET" ""})]
        (is (not (zero? (:exit missing))))
        (is (str/includes? (str (:out missing) (:err missing))
                           "missing required inputs: E2E_SECRET")))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest a-routed-card-enters-spec-and-wakes-the-single-agent
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      (let [created
            (sh/sh "bb" "-cp" (str (fs/path project "swarmforge" "scripts"))
                   "-e"
                   (str "(require (quote pack-web))"
                        "(pack-web/create-task!"
                        " " (pr-str (str project))
                        " \"routed-card\" \"Do the routed work.\")")
                   :dir (str project))]
        (is (zero? (:exit created)) (:err created)))

      (is (re-find #"(?m)^routed-card\tspec\t.*\t[^\t]+\t0$"
                   (slurp (str (fs/path project ".swarmforge" "board"
                                        "tasks.tsv")))))
      (let [lanes (run project "swarmforge/scripts/pack_board.sh"
                       "lanes" "--root" (str project))]
        (is (zero? (:exit lanes)) (:err lanes))
        (is (= "spec" (first (str/split-lines (:out lanes))))))

      (let [requests (fs/path project ".swarmforge" "dashboard" "requests" "pending")
            bodies (mapv #(slurp (str %)) (fs/list-dir requests))]
        (is (= 1 (count bodies)))
        (is (str/includes? (first bodies) "New routed card: routed-card"))
        (is (str/includes? (first bodies) "Do the routed work.")))
      (is (empty?
           (fs/glob project ".swarmforge/handoffs/outbox/*_from_New_Task_to_agent.handoff")))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest a-snapshot-deployment-uses-the-release-gate-and-project-token
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      (let [fake-bin (fs/path forge "fake-bin")
            mvn (fs/path fake-bin "mvn")]
        (write-file mvn
                    (str "#!/bin/sh\n"
                         "case \" $* \" in\n"
                         "  *\" help:evaluate \"*) printf '0.1.0-SNAPSHOT\\n' ;;\n"
                         "  *\" deploy \"*) mkdir -p target; "
                         "printf 'token=%s args=%s\\n' \"$GITHUB_PACKAGES_TOKEN\" \"$*\" "
                         "> target/deploy.args ;;\n"
                         "  *) printf 'unexpected mvn: %s\\n' \"$*\" >&2; exit 2 ;;\n"
                         "esac\n"))
        (fs/set-posix-file-permissions mvn "rwxr-xr-x")
        (let [publish (sh/sh (str (fs/path project "scripts" "publish-snapshot.sh"))
                             :dir (str project)
                             :env {"PATH" (str fake-bin ":" (System/getenv "PATH"))
                                   "GITHUB_PACKAGES_TOKEN" "test-packages-token"})]
          (is (zero? (:exit publish)) (:err publish))
          (is (= "token=test-packages-token args=-B -Pwith-release-tests deploy\n"
                 (slurp (str (fs/path project "target" "deploy.args")))))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest a-manual-contract-runs-examples-and-detects-unowned-api-prose
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      (write-file (fs/path project "README.md")
                  (str "# Demo\n\n[Manual](docs/manual.md)\n\n"
                       "[Architecture](ARCHITECTURE.md)\n\n## Quick start\n\nRun it.\n"))
      (write-file (fs/path project "docs" "manual.md")
                  (str "# Manual\n\n## Equality\n\n"
                       "Use `Propositions.equal` and interpret it.\n"))
      (write-file (fs/path project "docs" "manual-contract.edn")
                  (pr-str
                   {:documents {:readme "README.md"
                                :manual "docs/manual.md"
                                :architecture "ARCHITECTURE.md"}
                    :sections
                    [{:id "quick-start"
                      :document "README.md"
                      :heading "## Quick start"
                      :examples ["quick"]}
                     {:id "equality"
                      :document "docs/manual.md"
                      :heading "## Equality"
                      :apis ["Propositions.equal"]
                      :examples ["equality"]}
                     {:id "architecture"
                      :document "ARCHITECTURE.md"
                      :heading "# Architecture decision"
                      :conceptual true
                      :reason "the architecture stage owns structural verification"}]
                    :examples
                    [{:id "quick"
                      :section "quick-start"
                      :command ["sh" "-c" "printf 'ready\\n'"]
                      :expect ["ready"]}
                     {:id "equality"
                      :section "equality"
                      :command ["sh" "-c" "printf 'true\\n'"]
                      :expect ["true"]}]}))
      (let [check (run project "scripts/check-documentation.sh")]
        (is (zero? (:exit check)) (:err check))
        (is (str/includes? (:out check) "2 executable examples passed"))
        (is (fs/regular-file?
             (fs/path project "target" "documentation" "check.log"))))

      (write-file (fs/path project "docs" "manual.md")
                  "# Manual\n\n## Equality\n\nUse equality.\n")
      (let [stale (run project "scripts/check-documentation.sh")]
        (is (not (zero? (:exit stale))))
        (is (str/includes? (str (:out stale) (:err stale))
                           "does not name public API Propositions.equal")))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest an-existing-project-can-adopt-the-documentation-gate
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")
        stages-file (fs/path project "stages.edn")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      ;; Make the project look like one composed before the docs gate existed.
      (let [config (edn/read-string (slurp (str stages-file)))]
        (write-file stages-file
                    (pr-str (update config :stages
                                    #(vec (remove (fn [stage]
                                                    (contains? #{"docs" "deploy"}
                                                               (:name stage)))
                                                  %))))))
      (fs/delete-if-exists (fs/path project "scripts" "check-documentation.sh"))
      (fs/delete-if-exists
       (fs/path project "swarmforge" "scripts" "check-documentation.bb"))
      (fs/delete-if-exists (fs/path project "docs" "manual-contract.edn"))
      (write-file (fs/path project "AGENTS.md") "# Old agent instructions\n")
      (write-file (fs/path project "STAGES.md") "# Old stages\n")

      (let [check (run repo-root composer "--forge" (str forge)
                       "--upgrade" (str project) "--check")]
        (is (not (zero? (:exit check))))
        (is (str/includes? (:out check) "add required docs stage"))
        (is (str/includes? (:out check) "add required deploy stage"))
        (is (str/includes? (:out check) "install single-agent correction policy")))

      (let [upgrade (run repo-root composer "--forge" (str forge)
                         "--upgrade" (str project))]
        (is (zero? (:exit upgrade)) (:err upgrade))
        (is (str/includes? (:out upgrade) "upgraded: add required docs stage"))
        (is (str/includes? (:out upgrade) "upgraded: add required deploy stage"))
        (is (str/includes? (:out upgrade) "upgraded: install single-agent correction policy")))

      (let [stages (:stages (edn/read-string (slurp (str stages-file))))
            names (mapv :name stages)
            docs-index (.indexOf names "docs")
            deploy-index (.indexOf names "deploy")
            release-index (.indexOf names "release")]
        (is (< docs-index deploy-index))
        (is (< deploy-index release-index))
        (is (not (:optional (nth stages docs-index)))))
      (is (fs/executable?
           (fs/path project "scripts" "check-documentation.sh")))
      (is (fs/regular-file?
           (fs/path project "swarmforge" "scripts" "check-documentation.bb")))
      (is (fs/regular-file? (fs/path project "docs" "manual-contract.edn")))
      (doseq [file ["AGENTS.md" "STAGES.md"]]
        (let [text (slurp (str (fs/path project file)))]
          (is (str/includes?
               text "forgelet:single-agent-correction-policy:begin"))
          (is (str/includes?
               text "do not create another\napproval request"))))
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                              "--upgrade" (str project) "--check"))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest an-existing-standard-project-can-adopt-the-e2e-gate
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")
        stages-file (fs/path project "stages.edn")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (let [config (edn/read-string (slurp (str stages-file)))]
        (write-file stages-file
                    (pr-str (update config :stages
                                    #(vec (remove (fn [stage]
                                                    (= "e2e" (:name stage)))
                                                  %))))))
      (fs/delete-if-exists (fs/path project "qa" "e2e.edn"))
      (fs/delete-if-exists (fs/path project "scripts" "run-qa-e2e.sh"))
      (fs/delete-if-exists
       (fs/path project "swarmforge" "scripts" "check-e2e.bb"))

      (let [check (run repo-root composer "--forge" (str forge)
                       "--upgrade" (str project) "--check")]
        (is (not (zero? (:exit check))))
        (is (str/includes? (:out check) "add required e2e stage")))
      (let [upgrade (run repo-root composer "--forge" (str forge)
                         "--upgrade" (str project))]
        (is (zero? (:exit upgrade)) (:err upgrade))
        (is (str/includes? (:out upgrade) "upgraded: add required e2e stage")))
      (let [names (mapv :name (:stages (edn/read-string (slurp (str stages-file)))))
            qa-index (.indexOf names "qa")]
        (is (= ["qa" "e2e" "docs"]
               (subvec names qa-index (+ qa-index 3)))))
      (is (fs/regular-file? (fs/path project "qa" "e2e.edn")))
      (is (fs/executable?
           (fs/path project "scripts" "run-qa-e2e.sh")))
      (is (fs/regular-file?
           (fs/path project "swarmforge" "scripts" "check-e2e.bb")))
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                              "--upgrade" (str project) "--check"))))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest an-old-ready-marker-does-not-deliver-into-a-busy-agent
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")
        sock (str (fs/path project ".swarmforge" "agent-tmux.sock"))]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                           "req-busy.request")
                  "id: req-busy\nstatus: pending\ncreated_at: 2026-10-20T21:24:12Z\n\nApproved. Continue.\n")
      (fs/create-dirs (fs/parent sock))
      (is (zero? (:exit
                  (sh/sh "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-agent"
                         "sh -c 'echo \"<<AGENT READY>>\"; echo \"• Working (1s • esc to interrupt)\"; sleep 60'"))))

      (let [while-busy (run project
                            "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                            "--root" (str project)
                            "--once" "--no-serve"
                            "--agent-command" fake-agent-command)]
        (is (zero? (:exit while-busy)) (:err while-busy))
        (is (str/includes? (:out while-busy) "agent is still in a turn"))
        (is (fs/regular-file? (fs/path project ".swarmforge" "dashboard" "requests"
                                        "pending" "req-busy.request"))))

      (sh/sh "tmux" "-S" sock "kill-server")
      (let [once-ready (run project
                            "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                            "--root" (str project)
                            "--once" "--no-serve"
                            "--agent-command" fake-agent-command)]
        (is (zero? (:exit once-ready)) (:err once-ready))
        (is (fs/regular-file? (fs/path project ".swarmforge" "dashboard" "requests"
                                        "done" "req-busy.request"))
            (str "runner output: " (:out once-ready) (:err once-ready))))
      (finally
        (sh/sh "tmux" "-S" sock "kill-server")
        (fs/delete-tree forge)))))

(deftest a-single-agent-window-is-named-for-its-project
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "propositions")
        sock (str (fs/path project ".swarmforge" "agent-tmux.sock"))]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "propositions"))))
      (let [once (run project
                      "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                      "--root" (str project)
                      "--once" "--no-serve"
                      "--agent-command"
                      "sh -c 'echo \"<<AGENT READY>>\"; sleep 60'")]
        (is (zero? (:exit once)) (:err once)))
      (sh/sh "tmux" "-S" sock "rename-window" "-t" "swarmforge-agent:0" "codex")
      (sh/sh "tmux" "-S" sock "set-window-option" "-t" "swarmforge-agent:0"
             "automatic-rename" "on")
      (let [again (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command"
                       "sh -c 'echo \"<<AGENT READY>>\"; sleep 60'")]
        (is (zero? (:exit again)) (:err again)))
      (let [windows (sh/sh "tmux" "-S" sock "list-windows"
                           "-t" "swarmforge-agent"
                           "-F" "#{window_name}:#{automatic-rename}:#{allow-rename}")]
        (is (zero? (:exit windows)) (:err windows))
        (is (= "propositions:0:0\n" (:out windows))))
      (finally
        (sh/sh "tmux" "-S" sock "kill-server")
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

(deftest a-commit-before-the-spec-is-approved-is-refused
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (run repo-root composer "--forge" (str forge) "agent")
      (write-file (fs/path project "README.md") "changed\n")
      (sh/sh "git" "-C" (str project) "add" "README.md")
      (let [commit (sh/sh "git" "-C" (str project) "commit" "-m" "try")]
        (is (not (zero? (:exit commit))))
        (is (str/includes? (str (:err commit) (:out commit)) "Commit refused")))
      (write-file (fs/path project "features" "agent.feature")
                  "Feature: agent\n\n  Scenario: agent\n    Given a card\n")
      (write-file (fs/path project "tasks" "agent.md")
                  "# agent\n\n## Operator-observed problem\n\nA card.\n")
      (run-stage project "propose" "spec" "features/*.feature" "tasks/*.md")
      (let [id (-> (state project) :proposals vals first :id)]
        (is (zero? (:exit (run-stage project "approve" id "--words" "approve")))))
      (sh/sh "git" "-C" (str project) "add" "-A")
      (let [commit (sh/sh "git" "-C" (str project) "commit" "-m" "approved spec")]
        (is (zero? (:exit commit)) (:err commit)))
      (finally
        (stop-agent-session! project)
        (fs/delete-tree forge)))))

(deftest a-pack-project-converts-in-place-after-a-safe-dry-run
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "pack")
        worktree (fs/path project ".worktrees" "qa")
        board (fs/path project ".swarmforge" "board" "tasks.tsv")
        git (fn [dir & args]
              (apply sh/sh "git" "-c" "user.name=Converter Test"
                     "-c" "user.email=converter@test.invalid"
                     (concat ["-C" (str dir)] args)))
        convert (fn [& extra]
                  (apply run repo-root composer "--forge" (str forge)
                         "--convert" "pack" extra))]
    (try
      (write-file (fs/path project ".gitignore")
                  ".swarmforge/\n.worktrees/\ntarget/\n")
      (write-file (fs/path project "src" "main" "java" "Demo.java")
                  "package demo; final class Demo {}\n")
      (write-file (fs/path project "features" "existing.feature")
                  "Feature: existing\n")
      (write-file (fs/path project "tasks" "existing.md")
                  "# Existing card\n")
      (write-file (fs/path project "qa" "existing.md")
                  "# Existing QA procedure\n")
      (write-file (fs/path project "scripts" "local-check.sh")
                  "#!/bin/sh\nexit 0\n")
      (write-file (fs/path project "swarmforge" "roles" "specifier.prompt")
                  "pack role\n")
      (write-file (fs/path project "swarmforge" "constitution" "articles"
                           "local-project.prompt")
                  "project-local rule\n")
      (write-file (fs/path project "swarmforge" "swarmforge.conf")
                  "window specifier codex specifier\n")
      (write-file (fs/path project "swarmforge" "hooks" "card-complete.sh")
                  "#!/bin/sh\necho old completion hook\n")
      (write-file (fs/path project ".swarmforge" "pack") "six-pack\n")
      (write-file (fs/path project ".swarmforge" "roles.tsv")
                  "specifier\tspecifier\t/tmp/specifier\tswarmforge-specifier\n")
      (write-file board
                  (str "finished\tdone\t2026-10-20T00:00:00Z\t2026-10-20T01:00:00Z\ttask-finished\t1\n"
                       "active\tcoder\t2026-10-21T00:00:00Z\t2026-10-21T01:00:00Z\ttask-active\t0\n"))
      (write-file (fs/path project ".swarmforge" "board" "lanes.tsv")
                  "specifier\ncoder\nQA\ndone\n")
      (write-file (fs/path project ".swarmforge" "stages" "old.edn")
                  "{:pack-state true}\n")
      (is (zero? (:exit (git project "init" "-q" "-b" "main"))))
      (is (zero? (:exit (git project "add" "."))))
      (is (zero? (:exit (git project "commit" "-q" "-m" "pack project"))))
      (is (zero? (:exit (git project "branch" "swarmforge-qa"))))
      (is (zero? (:exit (git project "worktree" "add" "-q"
                          (str worktree) "swarmforge-qa"))))
      (write-file (fs/path worktree "qa" "from-worktree.md")
                  "# Work landed by QA\n")
      (is (zero? (:exit (git worktree "add" "qa/from-worktree.md"))))
      (is (zero? (:exit (git worktree "commit" "-q" "-m" "QA result"))))

      (testing "an unmerged worktree blocks conversion"
        (let [plan (convert)]
          (is (not (zero? (:exit plan))))
          (is (str/includes? (:err plan) "not merged into HEAD"))
          (is (fs/regular-file? (fs/path project ".swarmforge" "pack")))))
      (is (zero? (:exit (git project "merge" "--no-ff" "-q"
                          "-m" "merge QA" "swarmforge-qa"))))
      (let [head (str/trim (:out (git project "rev-parse" "HEAD")))]

        (testing "an open project or dirty checkout blocks conversion"
          (write-file (fs/path forge ".swarmforge" "open-projects") "pack\n")
          (let [open (convert)]
            (is (not (zero? (:exit open))))
            (is (str/includes? (:err open) "close pack through")))
          (fs/delete (fs/path forge ".swarmforge" "open-projects"))
          (write-file (fs/path project "README.md") "dirty\n")
          (let [dirty (convert)]
            (is (not (zero? (:exit dirty))))
            (is (str/includes? (:err dirty) "not clean")))
          (fs/delete (fs/path project "README.md")))

        (testing "the default is a non-mutating plan"
          (let [plan (convert)]
            (is (zero? (:exit plan)) (:err plan))
            (is (str/includes? (:out plan) "Conversion plan for"))
            (is (str/includes? (:out plan) "1 active card(s) will move to spec"))
            (is (str/includes? (:out plan) "DRY RUN: nothing changed"))
            (is (fs/regular-file? (fs/path project ".swarmforge" "pack")))
            (is (fs/directory? worktree))
            (is (not (fs/exists?
                      (fs/path project ".swarmforge" "single-agent")))))

        (testing "apply preserves the project and converts its runtime"
          (let [result (convert "--apply")]
            (is (zero? (:exit result)) (:err result)))
          (is (= "main\n" (:out (git project "branch" "--show-current"))))
          (is (zero? (:exit (git project "merge-base" "--is-ancestor" head "HEAD"))))
          (is (fs/regular-file?
               (fs/path project "src" "main" "java" "Demo.java")))
          (is (fs/regular-file? (fs/path project "features" "existing.feature")))
          (is (fs/regular-file? (fs/path project "tasks" "existing.md")))
          (is (fs/regular-file? (fs/path project "qa" "existing.md")))
          (is (fs/regular-file? (fs/path project "qa" "from-worktree.md")))
          (is (fs/regular-file? (fs/path project "scripts" "local-check.sh")))
          (is (fs/regular-file?
               (fs/path project "swarmforge" "constitution" "articles"
                        "local-project.prompt")))
          (let [rows (str/split-lines (slurp (str board)))]
            (is (re-find #"^finished\tdone\t.*\ttask-finished\t1$" (first rows)))
            (is (re-find #"^active\tspec\t.*\ttask-active\t0$" (second rows))))
          (let [lanes (str/split-lines
                       (:out (run project "swarmforge/scripts/pack_board.sh"
                                  "lanes" "--root" (str project))))]
            (is (= "spec" (first lanes)))
            (is (= "done" (last lanes)))
            (is (some #{"qa"} lanes))
            (is (some #{"e2e"} lanes))
            (is (some #{"docs"} lanes)))
          (is (not (fs/exists? worktree)))
          (is (not (zero? (:exit (git project "show-ref" "--verify" "--quiet"
                                  "refs/heads/swarmforge-qa")))))
          (is (= "standard\n"
                 (slurp (str (fs/path project ".swarmforge" "profile")))))
          (is (re-find #"(?m)^agent\tmain\t.*\tswarmforge-agent\t"
                       (slurp (str (fs/path project ".swarmforge" "roles.tsv")))))
          (is (= "agent\n"
                 (:out (run project "swarmforge/scripts/pack_board.sh"
                            "master-lane" "--root" (str project)))))
          (let [probe (run project "bb" "-cp"
                           (str (fs/path project "swarmforge" "scripts"))
                           "-e"
                           (str "(require (quote pack-web)) "
                                "(println (first (pack-web/master-row "
                                (pr-str (str project)) ")))"))]
            (is (zero? (:exit probe)) (:err probe))
            (is (= "agent\n" (:out probe))))
          (is (fs/regular-file?
               (fs/path project ".swarmforge" "single-agent")))
          (is (fs/regular-file? (fs/path project "swarmforge" "scripts" "stage.bb")))
          (is (fs/regular-file? (fs/path project "qa" "e2e.edn")))
          (is (fs/regular-file? (fs/path project ".git" "hooks" "pre-commit")))
          (is (fs/executable? (fs/path project ".git" "hooks" "pre-commit")))
          (let [stages (edn/read-string
                        (slurp (str (fs/path project "stages.edn"))))
                names (mapv :name (:stages stages))]
            (is (= "pack" (:task stages)))
            (is (= ["qa" "e2e" "docs"]
                   (subvec names (.indexOf names "qa")
                           (+ (.indexOf names "qa") 3)))))
          (let [archives (sort (fs/glob project
                                        ".swarmforge/archive/pack-conversion-*"))
                archive (last archives)]
            (is (= 1 (count archives)))
            (is (fs/regular-file?
                 (fs/path archive ".swarmforge" "pack")))
            (is (fs/regular-file?
                 (fs/path archive ".swarmforge" "roles.tsv")))
            (is (fs/regular-file?
                 (fs/path archive "swarmforge" "roles" "specifier.prompt")))
            (is (fs/regular-file?
                 (fs/path archive "swarmforge" "hooks" "card-complete.sh")))
            (is (fs/regular-file?
                 (fs/path archive "swarmforge" "swarmforge.conf")))
            (is (fs/regular-file?
                 (fs/path archive ".swarmforge" "stages" "old.edn")))
            (is (fs/regular-file?
                 (fs/path archive "tasks.before.tsv")))))))
      (finally
        (fs/delete-tree forge)))))
