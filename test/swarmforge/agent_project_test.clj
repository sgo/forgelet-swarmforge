(ns swarmforge.agent-project-test
  (:require [babashka.fs :as fs]
            [forge :as forge]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def composer (str (fs/path repo-root "get-agent-project")))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn seed-active-board-card! [project]
  (write-file
   (fs/path project ".swarmforge" "board" "tasks.tsv")
   "agent\tspec\t2026-11-07T00:00:00Z\t2026-11-07T00:00:00Z\tagent\t0\n"))

(defn delete-tree! [path]
  (loop [attempt 0]
    (let [retry?
          (try
            (fs/delete-tree path)
            false
            (catch java.nio.file.NoSuchFileException _
              false)
            (catch java.nio.file.DirectoryNotEmptyException _
              true))]
      (when (and retry? (< attempt 20))
        (Thread/sleep 50)
        (recur (inc attempt))))))

(defn run
  [dir & args]
  (apply sh/sh (concat args [:dir (str dir)])))

(defn run-env
  [dir env & args]
  (apply sh/sh
         (concat args
                 [:dir (str dir)
                  :env (merge {"PATH" (System/getenv "PATH")} env)])))

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

(defn git [project & args]
  (apply sh/sh "git" "-C" (str project) args))

(defn git-out [project & args]
  (str/trim (:out (apply git project args))))

(defn open-without-start! [forge-root name]
  (let [started (atom false)]
    (with-redefs [forge/skip-start? (constantly true)
                  forge/start-project-runtime!
                  (fn [_ _]
                    (reset! started true)
                    {:name name :started true :reused false})]
      (let [result (forge/open-project! (str forge-root) name)]
        {:result result :started @started}))))

(defn stop-agent-session! [project]
  (sh/sh "tmux" "-S" (str (fs/path project ".swarmforge" "agent-tmux.sock"))
         "kill-server"))

(defn wait-for-path [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

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
        (doseq [file ["AGENTS.md" "STAGES.md"]]
          (is (str/includes?
               (slurp (str (fs/path project file)))
               "forgelet:single-agent-commit-policy:begin")))
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
             (fs/path project "scripts" "install-snapshot.sh")))
        (is (not (fs/exists?
                  (fs/path project "scripts" "publish-snapshot.sh"))))
        (is (fs/executable?
             (fs/path project "scripts" "github-packages-token.sh")))
        (is (fs/executable?
             (fs/path project "scripts" "check-documentation.sh")))
        (is (fs/regular-file?
             (fs/path project "docs" "manual-contract.edn")))
        (is (= "github-packages\n"
               (slurp (str (fs/path project "swarmforge" "release.conf")))))
        (is (not (fs/exists? (fs/path project "swarmforge" "deploy.conf"))))
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
             (str "agent\tmaster\t" project "\tswarmforge-agent\tAgent\tcodex\ttask")))
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
        (seed-active-board-card! project)
        (run-stage project "propose" "spec"
                   "features/*.feature" "tasks/*.md")
        (is (= :approved
               (-> (state project) :proposals vals first :status)))
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
                       "--agent-command" fake-agent-command)
              done (fs/path project ".swarmforge" "dashboard" "requests"
                             "done" "req-test.request")]
          (is (zero? (:exit run)) (:err run))
          (is (wait-for-path done 3000) "the agent answered the delivered request")
          (is (not (fs/exists? (fs/path project ".swarmforge" "dashboard" "requests"
                                         "pending" "req-test.request"))))
          (let [answer (slurp (str done))]
            (is (str/includes? answer "fake answer from req-test")))))

      (testing "a turn cannot complete while stages are stale"
        (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                             "req-complete.request")
                    "id: req-complete\nstatus: pending\ncreated_at: 2026-10-18T00:00:00Z\n\nTry to complete.\n")
        (let [run (run project
                       "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       "--root" (str project)
                       "--once" "--no-serve"
                       "--agent-command" fake-agent-command)
              done (fs/path project ".swarmforge" "dashboard" "requests"
                             "done" "req-complete.request")]
          (is (zero? (:exit run)) (:err run))
          (is (wait-for-path done 3000) "the agent answered the delivered request")
          (let [answer (slurp (str done))]
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
        (delete-tree! forge)))))

(deftest single-agent-projects-persist-and-start-the-chosen-agent
  (let [forge (fixture-forge)
        fake-bin (fs/path forge "fake-agent-bin")
        fake (fs/path fake-bin "fake-agent")
        agents ["codex" "claude" "copilot" "grok"]]
    (write-file fake
                (str "#!/bin/sh\n"
                     "printf '%s %s\\n' \"$(basename \"$0\")\" \"$*\" "
                     "> \"$SWARMFORGE_TEST_AGENT_LOG\"\n"
                     "printf '%s|%s|%s\\n' \"${NO_COLOR-unset}\" \"$TERM\" \"$COLORTERM\" "
                     "> \"$SWARMFORGE_TEST_AGENT_ENV_LOG\"\n"
                     "printf '<<AGENT READY>>\\n'\n"
                     "sleep 30\n"))
    (fs/set-posix-file-permissions fake "rwxr-xr-x")
    (try
      (doseq [agent agents]
        (let [name (str "agent-" agent)
              executable (fs/path fake-bin agent)
              log (fs/path forge (str name ".argv"))
              env-log (fs/path forge (str name ".env"))]
          (fs/copy fake executable)
          (let [composed (run repo-root composer "--forge" (str forge)
                              "--agent" agent name)]
            (is (zero? (:exit composed)) (:err composed))
            (is (str/includes? (:out composed) (str "Agent: " agent))))
          (let [project (fs/path forge "projects" name)
                role-line (slurp (str (fs/path project ".swarmforge" "roles.tsv")))
                role-fields (str/split (first (str/split-lines role-line)) #"\t")]
            (is (= agent (nth role-fields 5 nil)))
            (let [started (run-env project
                                   {"PATH" (str fake-bin ":" (System/getenv "PATH"))
                                    "NO_COLOR" "1"
                                    "TERM" "xterm-kitty"
                                    "COLORTERM" "truecolor"
                                    "SWARMFORGE_TEST_AGENT_LOG" (str log)
                                    "SWARMFORGE_TEST_AGENT_ENV_LOG" (str env-log)}
                                   "bb" (str (fs/path project "swarmforge" "scripts"
                                                       "agent-runner.bb"))
                                   "--root" (str project)
                                   "--once" "--no-serve")]
              (is (zero? (:exit started)) (:err started))
              (let [command (str/trim (slurp (str log)))]
                (is (str/starts-with? command (str agent " ")))
                (is (case agent
                      "codex" (str/includes?
                               command
                               "--dangerously-bypass-approvals-and-sandbox")
                      "claude" (str/includes?
                                command
                                "--permission-mode bypassPermissions")
                      "copilot" (str/includes?
                                 command "--name SwarmForge Agent --yolo")
                      "grok" (str/includes?
                              command "--permission-mode bypassPermissions --minimal"))))
              (is (= "unset|xterm-kitty|truecolor\n"
                     (slurp (str env-log))))
            (stop-agent-session! project)))))
      (let [project (fs/path forge "projects" "agent-missing")]
        (write-file (fs/path project ".swarmforge" "single-agent") "agent\n")
        (let [missing (run project
                           "bb" (str (fs/path repo-root "swarmforge" "scripts"
                                               "agent-runner.bb"))
                           "--root" (str project)
                           "--once" "--no-serve")]
          (is (not (zero? (:exit missing))))
          (is (str/includes? (:err missing) "has no agent role"))
          (is (not (fs/exists? (fs/path project ".swarmforge" "agent-tmux.sock"))))))
      (finally
        (doseq [agent agents]
          (stop-agent-session! (fs/path forge "projects" (str "agent-" agent))))
        (delete-tree! forge)))))

(deftest get-agent-project-rejects-invalid-agents-before-writing
  (let [forge (fixture-forge)]
    (try
      (doseq [[index agent] (map-indexed vector
                                        ["rust" "../codex" "codex/../claude" "Codex"])]
        (let [name (str "invalid-" index)
              result (run repo-root composer "--forge" (str forge)
                          "--agent" agent name)]
          (is (not (zero? (:exit result))))
          (is (str/includes? (:err result) "unknown agent"))
          (is (not (fs/exists? (fs/path forge "projects" name))))))
      (let [missing (run repo-root composer "--forge" (str forge)
                         "missing-value" "--agent")]
        (is (not (zero? (:exit missing))))
        (is (str/includes? (:err missing) "--agent needs a value"))
        (is (not (fs/exists? (fs/path forge "projects" "missing-value")))))
      (finally
        (delete-tree! forge)))))

(deftest project-runtime-environment-supplies-defaults-and-fails-visibly
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-env")
        runtime-env (fs/path forge "private" "project-runtime.env")
        missing-env (fs/path forge "private" "missing.env")
        log (fs/path forge "agent-env.log")
        probe (fs/path project ".swarmforge" "env-probe.sh")
        runner (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "agent-env"))))
      (write-file runtime-env
                  (str "RUNTIME_FILE_ONLY=from-file\n"
                       "NO_COLOR=from-file\n"
                       "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY="
                       "'file-snapshots::default::file:///tmp/file-snapshots'\n"))
      (write-file probe
                  (str "#!/bin/sh\n"
                       "printf '%s|%s|%s|%s|%s|%s\\n' "
                       "\"${SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY:-}\" "
                       "\"${RUNTIME_FILE_ONLY:-}\" \"${NO_COLOR:-}\" \"$PATH\" "
                       "\"${TERM:-}\" \"${COLORTERM:-}\" "
                       "> \"$SWARMFORGE_TEST_AGENT_LOG\"\n"
                       "printf '<<AGENT READY>>\\n'\n"
                       "sleep 30\n"))
      (fs/set-posix-file-permissions probe "rwxr-xr-x")

      (let [run-probe
            (fn [env-file unset-snapshot? extra-env]
              (let [env (merge {"PATH" (System/getenv "PATH")
                                "NO_COLOR" "1"
                                "TERM" "xterm-kitty"
                                "COLORTERM" "truecolor"
                                "SWARMFORGE_PROJECT_RUNTIME_ENV" (str env-file)
                                "SWARMFORGE_TEST_AGENT_LOG" (str log)}
                               extra-env)
                    prefix (cond-> ["env"]
                             unset-snapshot?
                             (conj "-u"
                                   "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY"))]
                (apply run-env project env
                       (concat prefix
                               ["bb" runner "--root" (str project)
                                "--once" "--no-serve"
                                "--agent-command" (str probe)]))))]
        (let [started (run-probe runtime-env true {})]
          (is (zero? (:exit started)) (:err started))
          (is (re-find
               #"file-snapshots::default::file:///tmp/file-snapshots\|from-file\|\|.*\.swarmforge/bin:.*swarmforge/scripts:.*\|xterm-kitty\|truecolor"
               (slurp (str log)))))
        (stop-agent-session! project)

        (let [started
              (run-probe
               runtime-env false
               {"SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY"
                "caller-snapshots::default::file:///tmp/caller-snapshots"})]
          (is (zero? (:exit started)) (:err started))
          (is (str/starts-with?
               (slurp (str log))
               "caller-snapshots::default::file:///tmp/caller-snapshots|from-file||")))
        (stop-agent-session! project)

        (let [started (run-probe missing-env true {})]
          (is (zero? (:exit started)) (:err started))
          (is (re-find #"^\|\|\|.*\|xterm-kitty\|truecolor$"
                       (slurp (str log)))))
        (stop-agent-session! project)

        (let [started (run-probe runtime-env true
                                 {"TERM" "dumb"
                                  "SWARMFORGE_TMUX_TERM" "xterm-kitty"})]
          (is (zero? (:exit started)) (:err started))
          (is (re-find #"\|xterm-kitty\|truecolor$" (slurp (str log)))))
        (stop-agent-session! project)

        (doseq [[label body expected]
                [["malformed" "not-an-assignment\n" "malformed assignment"]
                 ["syntax" "export BROKEN='unterminated\n"
                  "malformed shell syntax"]]]
          (write-file runtime-env body)
          (let [failed (run-probe runtime-env true {})]
            (is (not (zero? (:exit failed))) label)
            (is (str/includes? (:err failed) expected) label)))
        (stop-agent-session! project)

        (write-file runtime-env
                    "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY=unreadable\n")
        (fs/set-posix-file-permissions runtime-env "---------")
        (let [failed (run-probe runtime-env true {})]
          (is (not (zero? (:exit failed))))
          (is (str/includes? (:err failed) "cannot read"))))
      (finally
        (when (fs/exists? runtime-env)
          (fs/set-posix-file-permissions runtime-env "rw-r--r--"))
        (stop-agent-session! project)
        (delete-tree! forge)))))

(deftest installed-agent-project-cache-is-complete-and-composes-every-agent
  (let [root (tmp-dir)
        forge (fixture-forge)
        home (fs/path root "home")
        cache (fs/path root "cache")
        cmds (fs/path root "commands")
        wrapper (fs/path cmds "get-agent-project")
        installer (fs/path repo-root "devbox/get-agent-project/install.sh")
        env {"HOME" (str home)
             "FORGELET_AGENT_PROJECT_CACHE" (str cache)
             "FORGELET_CMDS_DIR" (str cmds)
             "FORGELET_SWARM_FORGE_DIR" (str repo-root)}
        agents ["codex" "claude" "copilot" "grok"]]
    (try
      ;; Start from the pre-fix shape: current points at a different release
      ;; that lacks agents.conf.
      (let [stale (fs/path cache "releases/stale")]
        (fs/create-dirs (fs/path stale "swarmforge/templates"))
        (write-file (fs/path stale "get-agent-project") "old composer\n")
        (write-file (fs/path stale "convert-pack-project") "old converter\n")
        (fs/create-dirs (fs/path cache))
        (fs/create-sym-link (fs/path cache "current") "releases/stale"))
      (let [installed (run-env repo-root env (str installer))]
        (is (zero? (:exit installed)) (:err installed)))
      (is (fs/executable? wrapper))
      (is (not (fs/exists? (fs/path home "cmds/get-agent-project"))))
      (is (str/starts-with? (str (fs/read-link (fs/path cache "current")))
                           "releases/"))
      (is (not= "releases/stale"
                (str (fs/read-link (fs/path cache "current")))))
      (is (fs/regular-file?
           (fs/path cache "current/swarmforge/scripts/agents.conf")))

      (doseq [agent agents]
        (let [name (str "cached-" agent)
              result (run-env root env (str wrapper)
                              "--forge" (str forge) "--agent" agent name)
              project (fs/path forge "projects" name)
              role-line (slurp (str (fs/path project ".swarmforge" "roles.tsv")))
              role-fields (str/split (first (str/split-lines role-line)) #"\t")]
          (is (zero? (:exit result)) (:err result))
          (is (= agent (nth role-fields 5 nil)))))

      (is (zero? (:exit (run-env repo-root env (str installer) "--check"))))

      (fs/delete (fs/path cache "current/swarmforge/scripts/agents.conf"))
      (let [missing (run-env repo-root env (str installer) "--check")]
        (is (not (zero? (:exit missing))))
        (is (str/includes? (str (:err missing) (:out missing))
                           "run install.sh")))
      (is (zero? (:exit (run-env repo-root env (str installer)))))
      (is (zero? (:exit (run-env repo-root env (str installer) "--check"))))

      (spit (str (fs/path cache "current/swarmforge/scripts/agents.conf"))
            "codex\n")
      (let [stale (run-env repo-root env (str installer) "--check")]
        (is (not (zero? (:exit stale))))
        (is (str/includes? (str (:err stale) (:out stale))
                           "cache is behind")))
      (is (zero? (:exit (run-env repo-root env (str installer)))))
      (is (zero? (:exit (run-env repo-root env (str installer) "--check"))))
      (finally
        (delete-tree! forge)
        (delete-tree! root)))))

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
          (is (= [["scripts/install-snapshot.sh"]] (:runs deploy))))
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
        (delete-tree! forge)))))

(deftest standard-e2e-runs-the-card-procedure-and-redacts-inputs
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project "features" "agent.feature")
                  "Feature: agent\n\n  Scenario: agent\n    Given a card\n")
      (write-file (fs/path project "tasks" "agent.md")
                  "# agent\n\n## Operator-observed problem\n\nA card.\n")
      (seed-active-board-card! project)
      (run-stage project "propose" "spec" "features/*.feature" "tasks/*.md")
      (is (= :approved
             (-> (state project) :proposals vals first :status)))

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
        (delete-tree! forge)))))

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
        (is (= "queued" (first (str/split-lines (:out lanes))))))

      (let [requests (fs/path project ".swarmforge" "dashboard" "requests" "pending")
            bodies (mapv #(slurp (str %)) (fs/list-dir requests))]
        (is (= 1 (count bodies)))
        (is (str/includes? (first bodies) "New routed card: routed-card"))
        (is (str/includes? (first bodies) "Do the routed work.")))
      (is (empty?
           (fs/glob project ".swarmforge/handoffs/outbox/*_from_New_Task_to_agent.handoff")))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

(deftest a-card-installs-its-snapshot-in-the-shared-local-repository
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
                         "printf 'args=%s\\n' \"$*\" > target/install.args ;;\n"
                         "  *) printf 'unexpected mvn: %s\\n' \"$*\" >&2; exit 2 ;;\n"
                         "esac\n"))
        (fs/set-posix-file-permissions mvn "rwxr-xr-x")
        (let [install (sh/sh (str (fs/path project "scripts" "install-snapshot.sh"))
                             :dir (str project)
                             :env {"PATH" (str fake-bin ":" (System/getenv "PATH"))
                                   "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY"
                                   "fixture-snapshots::default::file:///tmp/fixture-snapshots"})]
          (is (zero? (:exit install)) (:err install))
          (is (= (str "args=-B -Pwith-release-tests deploy "
                      "-DaltSnapshotDeploymentRepository=fixture-snapshots::default::file:///tmp/fixture-snapshots\n")
                 (slurp (str (fs/path project "target" "install.args"))))))
        (doseq [[label value message]
                [["missing" nil "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY is required"]
                 ["legacy" "fixture-snapshots::file:///tmp/snapshots"
                  "<repository-id>::default::<repository-url>"]
                 ["blank" "   " "<repository-id>::default::<repository-url>"]]]
          (let [env (cond-> {"PATH" (str fake-bin ":" (System/getenv "PATH"))}
                      (some? value)
                      (assoc "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY" value))
                refused (sh/sh (str (fs/path project "scripts" "install-snapshot.sh"))
                               :dir (str project) :env env)]
            (is (not (zero? (:exit refused))) label)
            (is (str/includes? (:err refused) message) label)))
        (write-file mvn
                    (str "#!/bin/sh\n"
                         "case \" $* \" in\n"
                         "  *\" help:evaluate \"*) printf '1.0.0\\n' ;;\n"
                         "  *) exit 99 ;;\n"
                         "esac\n"))
        (let [refused (sh/sh (str (fs/path project "scripts" "install-snapshot.sh"))
                             :dir (str project)
                             :env {"PATH" (str fake-bin ":" (System/getenv "PATH"))
                                   "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY"
                                   "fixture-snapshots::default::file:///tmp/fixture-snapshots"})]
          (is (not (zero? (:exit refused))))
          (is (str/includes? (:err refused) "is not a snapshot"))))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

(deftest a-snapshot-deploys-timestamped-files-and-installs-locally
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")
        root (tmp-dir)
        home (fs/path root "home")
        snapshots (fs/path root "snapshot-repository")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      (write-file (fs/path project "pom.xml")
                  (str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                       "  <modelVersion>4.0.0</modelVersion>\n"
                       "  <groupId>fixture</groupId>\n"
                       "  <artifactId>snapshot-fixture</artifactId>\n"
                       "  <version>0.1.0-SNAPSHOT</version>\n"
                       "  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n"
                       "  <dependencies>\n"
                       "    <dependency><groupId>org.junit.jupiter</groupId>"
                       "<artifactId>junit-jupiter</artifactId><version>5.10.2</version>"
                       "<scope>test</scope></dependency>\n"
                       "  </dependencies>\n"
                       "  <build><plugins>\n"
                       "    <plugin><artifactId>maven-surefire-plugin</artifactId>"
                       "<version>3.2.5</version></plugin>\n"
                       "    <plugin><artifactId>maven-deploy-plugin</artifactId>"
                       "<version>3.1.4</version></plugin>\n"
                       "  </plugins></build>\n"
                       "  <profiles><profile><id>with-release-tests</id></profile></profiles>\n"
                       "</project>\n"))
      (write-file (fs/path project "src/main/java/fixture/Demo.java")
                  (str "package fixture;\n\n"
                       "public final class Demo {\n"
                       "  public static int answer() { return 42; }\n"
                       "}\n"))
      (write-file (fs/path project "src/test/java/fixture/DemoTest.java")
                  (str "package fixture;\n\n"
                       "import org.junit.jupiter.api.Test;\n"
                       "import static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                       "final class DemoTest {\n"
                       "  @Test void answers() { assertEquals(42, Demo.answer()); }\n"
                       "}\n"))
      (let [deployed
            (sh/sh (str (fs/path project "scripts" "install-snapshot.sh"))
                   :dir (str project)
                   :env {"HOME" (str home)
                         "MAVEN_OPTS" (str "-Duser.home=" home)
                         "PATH" (System/getenv "PATH")
                         "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY"
                         (str "fixture-snapshots::default::file://" snapshots)})]
        (is (zero? (:exit deployed)) (str (:out deployed) (:err deployed))))
      (is (some #(re-find #"snapshot-fixture-0\.1\.0-\d{8}\.\d{6}-\d+\.jar$"
                          (str %))
                (fs/glob snapshots "**/snapshot-fixture-0.1.0-*.jar")))
      (is (fs/regular-file?
           (fs/path home ".m2/repository/fixture/snapshot-fixture"
                    "0.1.0-SNAPSHOT/snapshot-fixture-0.1.0-SNAPSHOT.jar")))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)
        (delete-tree! root)))))

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
        (delete-tree! forge)))))

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
      (fs/delete-if-exists (fs/path project "swarmforge" "release.conf"))
      (write-file (fs/path project "swarmforge" "deploy.conf") "github-packages\n")
      (write-file (fs/path project ".mvn" "maven.config")
                  "-Dmaven.repo.local=.m2/repository\n-s\n.mvn/settings.xml\n")
      (write-file (fs/path project "core" ".mvn" "maven.config")
                  "-Dmaven.repo.local=../.m2/repository\n-s\n../.mvn/settings.xml\n")
      (write-file (fs/path project "AGENTS.md") "# Old agent instructions\n")
      (write-file (fs/path project "STAGES.md") "# Old stages\n")

      (let [check (run repo-root composer "--forge" (str forge)
                       "--upgrade" (str project) "--check")]
        (is (not (zero? (:exit check))))
        (is (str/includes? (:out check) "add required docs stage"))
        (is (str/includes? (:out check) "install local-snapshot deploy stage"))
        (is (str/includes? (:out check) "rename deploy.conf to release.conf"))
        (is (str/includes? (:out check) "use the shared Maven repository"))
        (is (str/includes? (:out check) "install single-agent correction policy"))
        (is (str/includes? (:out check) "install single-agent commit policy")))

      (let [upgrade (run repo-root composer "--forge" (str forge)
                         "--upgrade" (str project))]
        (is (zero? (:exit upgrade)) (:err upgrade))
        (is (str/includes? (:out upgrade) "upgraded: add required docs stage"))
        (is (str/includes? (:out upgrade) "upgraded: install local-snapshot deploy stage"))
        (is (str/includes? (:out upgrade) "upgraded: rename deploy.conf to release.conf"))
        (is (str/includes? (:out upgrade) "upgraded: use the shared Maven repository"))
        (is (str/includes? (:out upgrade) "upgraded: install single-agent correction policy"))
        (is (str/includes? (:out upgrade) "upgraded: install single-agent commit policy")))

      (let [stages (:stages (edn/read-string (slurp (str stages-file))))
            names (mapv :name stages)
            docs-index (.indexOf names "docs")
            deploy-index (.indexOf names "deploy")
            release-index (.indexOf names "release")]
        (is (< docs-index deploy-index))
        (is (< deploy-index release-index))
        (is (not (:optional (nth stages docs-index))))
        (is (= [["scripts/install-snapshot.sh"]]
               (:runs (nth stages deploy-index)))))
      (is (fs/executable?
           (fs/path project "scripts" "check-documentation.sh")))
      (is (fs/regular-file?
           (fs/path project "swarmforge" "scripts" "check-documentation.bb")))
      (is (fs/regular-file? (fs/path project "docs" "manual-contract.edn")))
      (is (fs/executable? (fs/path project "scripts" "install-snapshot.sh")))
      (is (fs/regular-file? (fs/path project "swarmforge" "release.conf")))
      (is (not (fs/exists? (fs/path project "swarmforge" "deploy.conf"))))
      (is (= "-s\n.mvn/settings.xml\n"
             (slurp (str (fs/path project ".mvn" "maven.config")))))
      (is (= "-s\n../.mvn/settings.xml\n"
             (slurp (str (fs/path project "core" ".mvn" "maven.config")))))
      (doseq [file ["AGENTS.md" "STAGES.md"]]
        (let [text (slurp (str (fs/path project file)))]
          (is (str/includes?
               text "forgelet:single-agent-correction-policy:begin"))
          (is (str/includes?
               text "approves its spec automatically by default"))
          (is (str/includes? text "spec=operator"))))
      (doseq [file ["AGENTS.md" "STAGES.md"]]
        (let [text (slurp (str (fs/path project file)))]
          (is (str/includes?
               text "forgelet:single-agent-commit-policy:begin"))
          (is (str/includes?
               text "Do not wait for every gate or for deployment"))
          (is (str/includes?
               text "does not\ninvalidate a stage that has passed"))))
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                              "--upgrade" (str project) "--check"))))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

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
        (delete-tree! forge)))))

(deftest an-outdated-managed-spec-approval-block-is-refreshed
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent-lib")
        old (str "<!-- forgelet:single-agent-correction-policy:begin -->\n"
                 "## Spec corrections after the first approval\n\n"
                 "The first spec proposal for each card requires the operator's approval.\n"
                 "<!-- forgelet:single-agent-correction-policy:end -->")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                            "--profile" "library" "agent-lib"))))
      (doseq [file ["AGENTS.md" "STAGES.md"]]
        (let [path (fs/path project file)
              updated (str/replace
                       (slurp (str path))
                       #"(?s)<!-- forgelet:single-agent-correction-policy:begin -->.*?<!-- forgelet:single-agent-correction-policy:end -->"
                       old)]
          (write-file path updated)))
      (let [check (run repo-root composer "--forge" (str forge)
                       "--upgrade" (str project) "--check")]
        (is (not (zero? (:exit check))))
        (is (str/includes? (:out check) "install single-agent correction policy")))
      (is (zero? (:exit (run repo-root composer "--forge" (str forge)
                              "--upgrade" (str project)))))
      (doseq [file ["AGENTS.md" "STAGES.md"]]
        (is (str/includes?
             (slurp (str (fs/path project file)))
             "approves its spec automatically by default")))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

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
        (is (wait-for-path
             (fs/path project ".swarmforge" "dashboard" "requests"
                      "done" "req-busy.request")
             3000)
            (str "runner output: " (:out once-ready) (:err once-ready))))
      (finally
        (sh/sh "tmux" "-S" sock "kill-server")
        (delete-tree! forge)))))

(deftest an-idle-agent-with-a-request-in-its-composer-is-given-enter
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")
        sock (str (fs/path project ".swarmforge" "agent-tmux.sock"))
        fake (fs/path project ".swarmforge" "fake-composer.sh")
        request "req-composer"]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                           (str request ".request"))
                  (str "id: " request "\nstatus: pending\n"
                       "created_at: 2026-10-29T20:00:00Z\n\nQueued card.\n"))
      ;; A non-empty composer removes Codex's "Ask Codex to do anything"
      ;; placeholder and its previous ready marker is no longer visible.
      (write-file fake
                  (str "#!/bin/sh\n"
                       "printf '› [phone " request "] Queued card\\n'\n"
                       "IFS= read -r line\n"
                       "mkdir -p .swarmforge/dashboard/requests/done\n"
                       "printf 'id: " request "\\nstatus: done\\n\\nreceived\\n' "
                       "> .swarmforge/dashboard/requests/done/" request ".request\n"
                       "printf '• Working (1s • esc to interrupt)\\n'\n"
                       "sleep 30\n"))
      (fs/set-posix-file-permissions fake "rwxr-xr-x")
      (fs/create-dirs (fs/parent sock))
      (is (zero? (:exit
                  (sh/sh "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-agent"
                         "-c" (str project) (str fake)))))
      (let [once (run project
                      "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                      "--root" (str project)
                      "--once" "--no-serve"
                      "--agent-command" "unused")]
        (is (zero? (:exit once)) (:err once))
        (is (str/includes? (:out once)
                           "submitting request already in the composer"))
        (is (fs/regular-file?
             (fs/path project ".swarmforge" "dashboard" "requests" "done"
                      (str request ".request")))))
      (finally
        (sh/sh "tmux" "-S" sock "kill-server")
        (delete-tree! forge)))))

(deftest an-idle-agent-submits-one-collapsed-pending-paste-without-repasting
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")
        sock (str (fs/path project ".swarmforge" "agent-tmux.sock"))
        fake (fs/path project ".swarmforge" "fake-collapsed-composer.sh")
        submission-log (fs/path project ".swarmforge" "submission.log")
        request "req-collapsed"]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project ".swarmforge" "dashboard" "requests" "pending"
                           (str request ".request"))
                  (str "id: " request "\nstatus: pending\n"
                       "created_at: 2026-11-03T14:03:39Z\n\nLarge routed card.\n"))
      ;; Codex collapses a large bracketed paste in the idle composer. The
      ;; request marker is no longer visible, but the sole pending request is
      ;; already the composer contents.
      (write-file fake
                  (str "#!/bin/sh\n"
                       "printf '› [Pasted Content 4096 chars]\\n'\n"
                       "IFS= read -r line\n"
                       "if [ -n \"$line\" ]; then\n"
                       "  printf 'paste:%s\\n' \"$line\" >> .swarmforge/submission.log\n"
                       "else\n"
                       "  printf 'enter\\n' >> .swarmforge/submission.log\n"
                       "fi\n"
                       "mkdir -p .swarmforge/dashboard/requests/done\n"
                       "printf 'id: " request "\\nstatus: done\\n\\nreceived\\n' "
                       "> .swarmforge/dashboard/requests/done/" request ".request\n"
                       "printf '• Working (1s • esc to interrupt)\\n'\n"
                       "sleep 30\n"))
      (fs/set-posix-file-permissions fake "rwxr-xr-x")
      (fs/create-dirs (fs/parent sock))
      (is (zero? (:exit
                  (sh/sh "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-agent"
                         "-c" (str project) (str fake)))))
      (let [once (run project
                      "bb" (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                      "--root" (str project)
                      "--once" "--no-serve"
                      "--agent-command" "unused")]
        (is (zero? (:exit once)) (:err once))
        (is (str/includes? (:out once)
                           "submitting request already in the composer"))
        (is (= "enter\n" (slurp (str submission-log))))
        (is (fs/regular-file?
             (fs/path project ".swarmforge" "dashboard" "requests" "done"
                      (str request ".request")))))
      (finally
        (sh/sh "tmux" "-S" sock "kill-server")
        (delete-tree! forge)))))

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
        (delete-tree! forge)))))

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
        (delete-tree! forge)))))

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
      (seed-active-board-card! project)
      (write-file (fs/path forge "swarmforge" "local-spec-approval.conf")
                  "spec=operator\n")
      (run-stage project "propose" "spec" "features/*.feature" "tasks/*.md")
      (let [id (-> (state project) :proposals vals first :id)]
        (is (zero? (:exit (run-stage project "approve" id "--words" "approve")))))
      (sh/sh "git" "-C" (str project) "add" "-A")
      (let [commit (sh/sh "git" "-C" (str project) "commit" "-m" "approved spec")]
        (is (zero? (:exit commit)) (:err commit)))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

(deftest the-host-can-commit-the-operator-mission-before-a-card-exists
  (let [forge (fixture-forge)
        project (fs/path forge "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge) "agent"))))
      (write-file (fs/path project "mission.md")
                  "# Mission\n\nBuild the office app\n")
      (is (zero? (:exit (sh/sh "git" "-C" (str project) "add" "mission.md"))))
      (let [commit (sh/sh "git" "-C" (str project)
                          "-c" "user.name=forgelet-host"
                          "-c" "user.email=forgelet-host@localhost"
                          "commit" "-m" "Record project mission")]
        (is (zero? (:exit commit)) (:err commit))
        (is (= "# Mission\n\nBuild the office app\n"
               (:out (sh/sh "git" "-C" (str project)
                            "show" "HEAD:mission.md")))))
      (finally
        (stop-agent-session! project)
        (delete-tree! forge)))))

(deftest opening-commits-only-refreshed-runtime-files-and-preserves-user-work
  (let [forge-root (fixture-forge)
        project (fs/path forge-root "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge-root)
                            "agent"))))
      (let [shared-stage (fs/path forge-root "swarmforge/scripts/stage.bb")
            shared-article (fs/path forge-root "swarmforge/constitution/articles"
                                    "engineering.prompt")]
        (write-file shared-stage
                    (str (slurp (str shared-stage)) "\n;; managed runtime marker\n"))
        (write-file shared-article "engineering refreshed\n"))
      (write-file (fs/path project "notes" "user.md") "staged user work\n")
      (is (zero? (:exit (git project "add" "notes/user.md"))))
      (write-file (fs/path project "AGENTS.md")
                  (str (slurp (str (fs/path project "AGENTS.md")))
                       "\nunstaged user work\n"))
      (let [subject-at-start (atom nil)]
        (with-redefs [forge/skip-start? (constantly false)
                      forge/start-project-runtime!
                      (fn [_ _]
                        (reset! subject-at-start
                                (git-out project "log" "-1" "--format=%s"))
                        {:name "agent" :started true :reused false})]
          (forge/open-project! (str forge-root) "agent"))
        (is (= "Sync SwarmForge runtime" @subject-at-start))
        (is (= "Sync SwarmForge runtime"
               (git-out project "log" "-1" "--format=%s")))
        (is (= "SwarmForge Runtime"
               (git-out project "log" "-1" "--format=%an")))
        (is (= "swarmforge-runtime@localhost"
               (git-out project "log" "-1" "--format=%ae")))
        (is (= #{"swarmforge/constitution/articles/engineering.prompt"
                 "swarmforge/scripts/stage.bb"}
               (set (str/split-lines
                     (git-out project "show" "--pretty=" "--name-only" "HEAD")))))
        (is (= (str/trim
                (slurp (str (fs/path forge-root "swarmforge/scripts/stage.bb"))))
               (git-out project "show" "HEAD:swarmforge/scripts/stage.bb")))
        (is (= ["notes/user.md"]
               (str/split-lines
                (git-out project "diff" "--cached" "--name-only"))))
        (is (str/includes? (git-out project "diff" "--" "AGENTS.md")
                           "unstaged user work"))
        (is (fs/executable? (fs/path project ".git/hooks/pre-commit")))
        (is (= (str/trim
                (slurp (str (fs/path repo-root
                                     "swarmforge/scripts/agent-pre-commit.sh"))))
               (str/trim
                (slurp (str (fs/path project ".git/hooks/pre-commit")))))))
      (let [head (git-out project "rev-parse" "HEAD")]
        (with-redefs [forge/skip-start? (constantly true)]
          (forge/close-project! (str forge-root) "agent"))
        (open-without-start! forge-root "agent")
        (is (= head (git-out project "rev-parse" "HEAD"))))
      (finally
        (delete-tree! forge-root)))))

(deftest managed-runtime-hook-exception-does-not-admit-non-runtime-work
  (let [forge-root (fixture-forge)
        project (fs/path forge-root "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge-root)
                            "agent"))))
      (write-file (fs/path project "README.md") "unapproved source\n")
      (is (zero? (:exit (git project "add" "README.md"))))
      (let [commit (run-env project
                            {"SWARMFORGE_MANAGED_RUNTIME_SYNC" "1"
                             "HOME" (System/getenv "HOME")
                             "PATH" (System/getenv "PATH")}
                            "git" "commit" "-m" "not a runtime sync")]
        (is (not (zero? (:exit commit))))
        (is (str/includes? (str (:err commit) (:out commit))
                           "Commit refused")))
      (finally
        (delete-tree! forge-root)))))

(deftest opening-refuses-detached-head-and-a-failing-managed-hook
  (let [forge-root (fixture-forge)
        project (fs/path forge-root "projects" "agent")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge-root)
                            "agent"))))
      (is (zero? (:exit (git project "checkout" "--detach" "HEAD"))))
      (let [started-ref (atom false)
            error (try
                    (with-redefs [forge/skip-start? (constantly true)
                                  forge/start-project-runtime!
                                  (fn [_ _] (reset! started-ref true))]
                      (forge/open-project! (str forge-root) "agent"))
                    nil
                    (catch Exception e e))]
        (is error)
        (is (str/includes? (ex-message error) "HEAD is detached"))
        (is (false? @started-ref))
        (let [open-file (fs/path forge-root ".swarmforge/open-projects")]
          (is (or (not (fs/exists? open-file))
                  (not (str/includes? (slurp (str open-file)) "agent"))))))

      (is (zero? (:exit (git project "checkout" "master"))))
      (write-file (fs/path forge-root "swarmforge/scripts/agent-pre-commit.sh")
                  "#!/usr/bin/env bash\necho managed hook refused >&2\nexit 7\n")
      (write-file (fs/path project "notes" "staged.md") "user staged\n")
      (is (zero? (:exit (git project "add" "notes/staged.md"))))
      (let [started (atom false)
            error (try
                    (with-redefs [forge/skip-start? (constantly true)
                                  forge/start-project-runtime!
                                  (fn [_ _] (reset! started true))]
                      (forge/open-project! (str forge-root) "agent"))
                    nil
                    (catch Exception e e))]
        (is error)
        (is (str/includes? (ex-message error) "Managed runtime sync failed"))
        (is (str/includes? (ex-message error) "managed hook refused"))
        (is (false? @started))
        (is (= ["notes/staged.md"]
               (str/split-lines
                (git-out project "diff" "--cached" "--name-only")))))
      (finally
        (delete-tree! forge-root)))))

(deftest the-source-layer-project-does-not-commit-its-own-source-during-open
  (let [forge-root (fixture-forge)
        project (fs/path forge-root "projects" "forgelet-swarmforge")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge-root)
                            "forgelet-swarmforge"))))
      (let [stage (fs/path project "swarmforge/scripts/stage.bb")
            head-before (git-out project "rev-parse" "HEAD")]
        (write-file stage (str (slurp (str stage)) "\n;; source edit\n"))
        (write-file (fs/path forge-root "swarmforge/scripts/stage.bb")
                    "#!/usr/bin/env bb\n(println :runtime-copy)\n")
        (let [{:keys [started]} (open-without-start! forge-root
                                                    "forgelet-swarmforge")]
          (is (false? started))
          (is (= head-before (git-out project "rev-parse" "HEAD")))
          (is (str/includes? (slurp (str stage)) ";; source edit"))
          (is (not (= "Sync SwarmForge runtime"
                      (git-out project "log" "-1" "--format=%s"))))))
      (finally
        (delete-tree! forge-root)))))

(deftest queue-aware-stage-is-in-head-before-stashed-work-is-restored
  (let [forge-root (fixture-forge)
        project (fs/path forge-root "projects" "queue")]
    (try
      (is (zero? (:exit (run repo-root composer "--forge" (str forge-root)
                            "queue"))))
      (let [stage (fs/path forge-root "swarmforge/scripts/stage.bb")]
        (write-file stage
                    (str (slurp (str stage))
                         "\n;; queue-aware managed runtime marker\n")))
      (open-without-start! forge-root "queue")
      (let [board (fs/path project "swarmforge/scripts/pack_board.sh")
            route (fn [name id]
                    (run project (str board) "route"
                         "--name" name "--task-id" id
                         "--text" (str name " card\n")))]
        (is (zero? (:exit (route "active" "task-active"))))
        (is (zero? (:exit (route "queued" "task-queued")))))
      (let [proposal (run-stage project "propose" "spec" "tasks/*.md")]
        (is (zero? (:exit proposal)) (str (:err proposal) (:out proposal))))
      (is (zero? (:exit (git project "add" "tasks/active.md" "tasks/queued.md"))))
      (is (zero? (:exit (git project "commit" "-q" "-m" "queue cards"))))
      (let [status (-> (run-stage project "status" "--json")
                       :out
                       (json/parse-string true))
            state-file (fs/path project ".swarmforge/stages/queue.edn")
            current (edn/read-string (slurp (str state-file)))
            stages (into {}
                         (map (fn [stage]
                                (let [name (:name stage)
                                      previous (get-in current [:stages name])]
                                  [name (assoc previous
                                               :tree (:tree status)
                                               :input_hash (:input_hash stage))])))
                         (:stages status))]
        (write-file state-file
                    (pr-str (assoc current :stages stages))))
      (let [head-stage (git-out project "show" "HEAD:swarmforge/scripts/stage.bb")]
        (is (str/includes? head-stage "queue-aware managed runtime marker"))
        (is (str/includes? head-stage "complete-promote")))
      (write-file (fs/path project "AGENTS.md")
                  (str (slurp (str (fs/path project "AGENTS.md")))
                       "\noperator work to restore\n"))
      (is (zero? (:exit (git project "stash" "push" "-m" "user work"
                             "--" "AGENTS.md"))))
      (is (= "" (git-out project "status" "--porcelain")))
      (let [complete (run project "bb"
                          (str (fs/path project "swarmforge/scripts/stage.bb"))
                          "complete" "--root" (str project))]
        (is (zero? (:exit complete)) (:err complete))
        (is (str/includes? (:out complete) "PROMOTED:queued:task-queued:")))
      (is (= {"active" "done" "queued" "spec"}
             (into {}
                   (map (fn [line]
                          (let [[name lane] (str/split line #"\t" -1)]
                            [name lane])))
                   (str/split-lines
                    (slurp (str (fs/path project ".swarmforge/board/tasks.tsv")))))))
      (is (zero? (:exit (git project "stash" "pop"))))
      (is (str/includes? (slurp (str (fs/path project "AGENTS.md")))
                         "operator work to restore"))
      (finally
        (delete-tree! forge-root)))))

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
      (write-file (fs/path project ".mvn" "maven.config")
                  "-Dmaven.repo.local=.m2/repository\n-s\n.mvn/settings.xml\n")
      (write-file (fs/path project "pom.xml") "<project/>\n")
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
            (is (= "queued" (first lanes)))
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
          (is (fs/executable?
               (fs/path project "scripts" "install-snapshot.sh")))
          (is (fs/regular-file? (fs/path project "swarmforge" "release.conf")))
          (is (= "-s\n.mvn/settings.xml\n"
                 (slurp (str (fs/path project ".mvn" "maven.config")))))
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
        (delete-tree! forge)))))

(deftest conversion-refuses-a-forge-with-the-old-master-only-runtime
  (let [forge (fixture-forge)
        package (fs/path forge "swarmforge" "scripts" "pack_board.sh")]
    (try
      (fs/create-dirs (fs/path forge "projects" "pack"))
      (write-file package "#!/bin/sh\nprintf 'master\\n'\n")
      (fs/set-posix-file-permissions package "rwxr-xr-x")
      (let [result (run repo-root composer "--forge" (str forge)
                        "--convert" "pack")]
        (is (not (zero? (:exit result))))
        (is (str/includes?
             (:err result)
             "does not support single-agent projects on a non-master branch"))
        (is (str/includes? (:err result) "upgrade")))
      (finally
        (delete-tree! forge)))))
