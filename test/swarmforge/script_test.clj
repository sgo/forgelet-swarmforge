(ns swarmforge.script-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit"))

(defn tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-script-test."}))

(defn script [name]
  (str (fs/path scripts-dir name)))

(defn write-completion-hook! [root body]
  (let [hook (fs/path root "swarmforge/hooks/card-complete.sh")]
    (write-file hook (str "#!/bin/sh\n" body))
    (run {:dir root} "chmod" "+x" (str hook))
    hook))

(defn write-board-row! [root task lane]
  (write-file (fs/path root ".swarmforge/board/tasks.tsv")
              (format "%s\t%s\t2026-09-25T00:00:00Z\t2026-09-25T00:00:00Z\ttask-id\t0\n"
                      task lane)))

(deftest completion-hook-runs-the-projects-own-script
  ;; Given a project with a finishing step of its own and a card the board calls done
  ;; When the tooling runs the card-complete event for that card
  ;; Then the project's script runs, told which card and which sender, and its output is the tooling's output
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-board-row! root "HTW" "done")
      (write-completion-hook! root
                              (str "echo \"ran for $SWARMFORGE_TASK from $SWARMFORGE_FROM ($SWARMFORGE_COMMIT)\"\n"
                                   "echo ran > .swarmforge/hook-ran\n"))
      (let [result (run {:dir root} (script "run_hook.sh") "card-complete"
                        "--task" "HTW" "--from" "coder" "--commit" "abc123")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "--- hook card-complete (card HTW) from coder abc123 ---"))
        (is (str/includes? (:out result) "ran for HTW from coder (abc123)"))
        (is (str/includes? (:out result) "--- hook card-complete exit 0 ---"))
        (is (fs/exists? (fs/path root ".swarmforge/hook-ran"))))
      (finally
        (fs/delete-tree root)))))

(deftest completion-hook-waits-for-the-board-to-call-the-card-done
  ;; Given the same project with the card still in a role's lane
  ;; When the tooling runs the event for it
  ;; Then the project's script does not run, and the tooling says why
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-board-row! root "HTW" "coder")
      (write-completion-hook! root "echo ran > .swarmforge/hook-ran\n")
      (let [result (run {:dir root} (script "run_hook.sh") "card-complete"
                        "--task" "HTW" "--from" "coder")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "HOOK_DEFERRED card-complete HTW (card lane: coder)"))
        (is (not (fs/exists? (fs/path root ".swarmforge/hook-ran")))))
      (finally
        (fs/delete-tree root)))))

(deftest completion-hook-reports-a-failure-without-failing-the-merge
  ;; Given a project whose finishing step fails
  ;; When the tooling runs the event
  ;; Then it reports the failure and still succeeds itself: the merge has already
  ;; happened, and a failed finishing step never un-merges work
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-board-row! root "HTW" "done")
      (write-completion-hook! root "exit 3\n")
      (let [result (run {:dir root} (script "run_hook.sh") "card-complete"
                        "--task" "HTW" "--from" "coder")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "--- hook card-complete exit 3 ---"))
        (is (str/includes? (:out result) "HOOK_FAILED card-complete HTW (exit 3)")))
      (finally
        (fs/delete-tree root)))))

(deftest completion-hook-fires-when-a-cards-work-lands-on-master
  ;; Given a project the board calls done, a finishing step of its own, and a
  ;; handoff carrying the card's commit
  ;; When the role on master reads it with ready_for_next
  ;; Then the work is merged and the project's finishing step runs for that card
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (let [branch (str/trim (:out (run {:dir root} "git" "rev-parse" "--abbrev-ref" "HEAD")))]
        (run {:dir root} "git" "checkout" "-q" "-b" "work")
        (write-file (fs/path root "work.txt") "work\n")
        (run {:dir root} "git" "add" "work.txt")
        (run {:dir root} "git" "commit" "-q" "-m" "work")
        (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "HEAD")))]
          (run {:dir root} "git" "checkout" "-q" branch)
          (write-file (fs/path root ".swarmforge/roles.tsv")
                      (format "sender\tmaster\t%s\tsession\tSender\tcodex\ttask\tforward-only\n" root))
          (doseq [dir [".swarmforge/handoffs/outbox/tmp"
                       ".swarmforge/handoffs/sent"
                       ".swarmforge/handoffs/failed"
                       ".swarmforge/handoffs/inbox/new"
                       ".swarmforge/handoffs/inbox/in_process"
                       ".swarmforge/handoffs/inbox/completed"]]
            (fs/create-dirs (fs/path root dir)))
          (write-board-row! root "HTW" "done")
          (write-file (fs/path root "tasks/HTW.md") "# HTW\n")
          (write-completion-hook! root "echo \"ran for $SWARMFORGE_TASK\" > .swarmforge/hook-ran\n")
          (write-file (fs/path root ".swarmforge/handoffs/inbox/new/50_item.handoff")
                      (str "id: 1\n"
                           "from: coder\n"
                           "to: sender\n"
                           "priority: 50\n"
                           "type: git_handoff\n"
                           "task: HTW\n"
                           "commit: " commit "\n"
                           "\n"
                           "the card's work\n"))
          (let [ready (run {:dir root :env {"SWARMFORGE_ROLE" "sender"}} (script "ready_for_next.sh"))]
            (is (zero? (:exit ready)) (:err ready))
            (is (str/includes? (:out ready) "--- hook card-complete (card HTW) from coder"))
            (is (fs/exists? (fs/path root "work.txt")))
            (is (fs/exists? (fs/path root ".swarmforge/hook-ran"))))))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-parses-and-prints-handoff-files
  (let [root (tmp-dir)
        handoff-file (fs/path root "task.handoff")]
    (try
      (write-file handoff-file
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 10\n"
                       "type: git_handoff\n"
                       "task: task-alpha\n"
                       "\n"
                       "merge_and_process coder abcdef1234\n"))
      (let [header (run {:dir root} (script "handoff_lib.bb") "header-field" "task.handoff" "task")
            body (run {:dir root} (script "handoff_lib.bb") "body" "task.handoff")
            task (run {:dir root} (script "handoff_lib.bb") "print-task" "task.handoff")]
        (is (str/includes? (:out header) "task-alpha"))
        (is (str/includes? (:out body) "merge_and_process coder abcdef1234"))
        (is (str/includes? (:out task) "TASK: task.handoff"))
        (is (str/includes? (:out task) "FROM: coder"))
        (is (str/includes? (:out task) "TASK_NAME: task-alpha")))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-updates-headers-and-reads-role-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "/.worktrees/cleaner\tsession\tCleaner\tcodex\tbatch\n"))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/item.handoff")
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 20\n"
                       "type: note\n"
                       "\n"
                       "payload\n"))
      (run {:dir root} (script "handoff_lib.bb") "role-known" "cleaner")
      (run {:dir root} (script "handoff_lib.bb") "set-header" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at" "2026-06-16T00:00:00Z")
      (let [mode (run {:dir root} (script "handoff_lib.bb") "role-receive-mode" "cleaner")
            worktree (run {:dir root} (script "handoff_lib.bb") "role-worktree-name" "cleaner")
            dequeued (run {:dir root} (script "handoff_lib.bb") "header-field" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at")
            seq-1 (run {:dir root} (script "handoff_lib.bb") "next-sequence")
            seq-2 (run {:dir root} (script "handoff_lib.bb") "next-sequence")]
        (is (str/includes? (:out mode) "batch"))
        (is (str/includes? (:out worktree) "cleaner"))
        (is (str/includes? (:out dequeued) "2026-06-16T00:00:00Z"))
        (is (str/includes? (:out seq-1) "000001"))
        (is (str/includes? (:out seq-2) "000002")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-parses-config-and-writes-state-files
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "# comment\n"
                       "window coder codex master\n"
                       "window cleaner codex cleaner batch\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "cleaner Cleaner"))
        (is (str/includes? (:out result) "cleaner batch"))
        (is (str/includes? (:out result) "swarmforge-coder"))
        (is (str/includes? (:out result) "swarmforge-cleaner"))
        (is (fs/exists? (fs/path root ".swarmforge/tmux-socket"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-uses-portable-tmux-socket-dir
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex master\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (let [socket-path (str/trim (slurp (str (fs/path root ".swarmforge/tmux-socket"))))]
        (is (str/starts-with? socket-path "/tmp/swarmforge-"))
        (is (not (str/starts-with? socket-path "/private/tmp/"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-rejects-invalid-config
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder codex master\n"
                       "window coder codex other\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Duplicate role 'coder'")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-parses-window-invisible
  ;; Given window-invisible specifier codex master
  ;; When --test-parse
  ;; Then specifier is listed and visible? is false
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window-invisible specifier codex master\n")
      (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "specifier"))
        (is (str/includes? (:out result) "invisible")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-required-helpers-include-pack-scripts
  ;; Given the launcher required-helpers list
  ;; When --test-required-helpers
  ;; Then pack_web.sh and pack_board.sh are listed
  (let [result (run {:dir repo-root} (script "swarmforge.bb") "--test-required-helpers")
        names (set (str/split-lines (str/trim (:out result))))]
    (is (contains? names "pack_web.sh"))
    (is (contains? names "pack_board.sh"))
    (is (contains? names "pack_dashboard_request.sh"))))

(defn write-pack-conf! [root conf]
  (write-file (fs/path root "swarmforge/constitution.prompt") "Read articles.\n")
  (write-file (fs/path root "swarmforge/swarmforge.conf") conf)
  (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
  (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n"))

(deftest swarmforge-launch-plan-starts-pack-web-and-skips-invisible-terminals
  ;; Given window-invisible specifier and a visible coder window
  ;; When --test-launch-plan
  ;; Then pack_web starts, specifier skips Terminal, and coder still opens Terminal
  (let [root (tmp-dir)]
    (try
      (write-pack-conf! root
                        (str "window-invisible specifier codex master\n"
                             "window coder codex coder\n"))
      (let [out (:out (run {:dir root} (script "swarmforge.bb")
                           "--test-launch-plan" (str root)))]
        (is (str/includes? out "pack_web start"))
        (is (str/includes? out "skip-terminal specifier"))
        (is (str/includes? out "open-terminal coder"))
        (is (not (str/includes? out "skip-terminal coder")))
        (is (not (str/includes? out "open-terminal specifier"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-fails-without-a-master-worktree
  ;; Given only window coder codex coder
  ;; When --test-parse
  ;; Then exit 1 and error mentions master
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex coder\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "master")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-fails-with-two-master-worktrees
  ;; Given two windows whose worktree is master
  ;; When --test-parse
  ;; Then exit 1 and error mentions master
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window specifier codex master\n"
                       "window coder codex master\n"))
      (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "master")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-terminal-bridge-preserves-adapter-globals
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/scripts/swarm-terminal-adapter.sh")
                  (str "load_terminal_backend() {\n"
                       "  source \"$SCRIPT_DIR/terminal-adapters/$1.sh\"\n"
                       "}\n"))
      (write-file (fs/path root "swarmforge/scripts/terminal-adapters/probe.sh")
                  (str "terminal_open_session() {\n"
                       "  printf '%s\\n' \"$WORKING_DIR|$TMUX_SOCKET|$1|$2|$3\"\n"
                       "}\n"))
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-terminal-bridge"
                        (str root)
                        "probe")]
        (is (str/includes? (:out result) (str root "|")))
        (is (str/includes? (:out result) "|swarmforge-specifier|SwarmForge Specifier|"))
        (is (not (str/includes? (:out result) "cd ''")))
        (is (not (str/includes? (:out result) "-S ''"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-agent-start-delay-is-configurable
  (let [default-result (run {:dir repo-root}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")
        configured-result (run {:dir repo-root
                                :env {"SWARMFORGE_AGENT_START_DELAY_MS" "2750"}}
                               (script "swarmforge.bb")
                               "--test-agent-start-delay")
        invalid-result (run {:dir repo-root
                             :env {"SWARMFORGE_AGENT_START_DELAY_MS" "fast"}}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")]
    (is (= "1500" (str/trim (:out default-result))))
    (is (= "2750" (str/trim (:out configured-result))))
    (is (= "1500" (str/trim (:out invalid-result))))))

(deftest swarmforge-sleep-prevention-can-be-disabled
  (let [result (run {:dir repo-root
                     :env {"SWARMFORGE_PREVENT_SLEEP" "0"}}
                    (script "swarmforge.bb")
                    "--test-sleep-inhibitor-prefix")]
    (is (= "" (str/trim (:out result))))))

(deftest swarmforge-launcher-parses-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder copilot master --yolo\n"
                       "window cleaner copilot cleaner batch --allow-all-tools\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "task forward-only --yolo"))
        (is (str/includes? (:out result) "batch forward-only --allow-all-tools")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-parses-propagation-tokens
  ;; Given omitted, back-one, and back-all after receive-mode, plus extra CLI args
  ;; When --test-parse
  ;; Then omitted is forward-only, tokens round-trip in roles.tsv, extra args still apply
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window specifier grok master\n"
                       "window coder grok coder task --yolo\n"
                       "window refactorer grok refactorer task back-one\n"
                       "window architect grok architect batch back-all --allow-all-tools\n"))
      (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/refactorer.prompt") "refactorer\n")
      (write-file (fs/path root "swarmforge/roles/architect.prompt") "architect\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
            out (:out result)]
        (is (zero? (:exit result)))
        (is (str/includes? out "specifier Specifier"))
        (is (str/includes? out "task forward-only"))
        (is (str/includes? out "task forward-only --yolo"))
        (is (str/includes? out "task back-one"))
        (is (str/includes? out "batch back-all --allow-all-tools"))
        (let [roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
              lines (str/split-lines roles)]
          (is (str/ends-with? (first lines) "\ttask\tforward-only"))
          (is (str/includes? (nth lines 1) "\ttask\tforward-only"))
          (is (str/ends-with? (nth lines 2) "\ttask\tback-one"))
          (is (str/ends-with? (nth lines 3) "\tbatch\tback-all"))))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-reads-role-propagation
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "\tsession\tCleaner\tcodex\tbatch\tback-one\n"
                       "architect\tarchitect\t" root "\tsession\tArchitect\tcodex\tbatch\tback-all\n"))
      (let [coder (run {:dir root} (script "handoff_lib.bb") "role-propagation" "coder")
            cleaner (run {:dir root} (script "handoff_lib.bb") "role-propagation" "cleaner")
            architect (run {:dir root} (script "handoff_lib.bb") "role-propagation" "architect")]
        (is (str/includes? (:out coder) "forward-only"))
        (is (str/includes? (:out cleaner) "back-one"))
        (is (str/includes? (:out architect) "back-all")))
      (finally
        (fs/delete-tree root)))))

(deftest copilot-launch-command-passes-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "copilot"
                        "--yolo")
            command (:out result)]
        (is (str/includes? command "copilot -C "))
        (is (re-find #"--name 'SwarmForge Coder' --yolo -i" command)))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-passes-initial-prompt
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok")
            command (:out result)]
        (is (str/includes? command "grok --cwd "))
        (is (str/includes? command "--permission-mode bypassPermissions"))
        (is (str/includes? command "--rules \"$(cat "))
        (is (str/includes? command "--verbatim \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/coder.md"))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/coder.md"))))
      (finally
        (fs/delete-tree root)))))

(deftest start-pack-web-drops-stale-dashboard-url
  ;; Given a leftover dashboard-url and pack_web.pid from a prior run
  ;; When SwarmForge prepares to start the dashboard
  ;; Then those stale files are removed so the new port is recorded
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/dashboard-url") "http://127.0.0.1:64002\n")
      (write-file (fs/path root ".swarmforge/pack_web.pid") "99999999\n")
      (let [out (str/trim (:out (run {:dir root}
                                     (script "swarmforge.bb")
                                     "--test-reset-pack-web-state"
                                     (str root))))]
        (is (= "false false" out))
        (is (not (fs/exists? (fs/path root ".swarmforge/dashboard-url"))))
        (is (not (fs/exists? (fs/path root ".swarmforge/pack_web.pid")))))
      (finally
        (fs/delete-tree root)))))

(deftest grok-lieutenant-launch-waits-for-chat
  ;; Given a host lieutenant
  ;; When SwarmForge builds the grok launch command
  ;; Then grok loads rules and stays idle — no initial --verbatim prompt
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-lieutenant-launch-command"
                               (str root)))]
        (is (str/includes? command "grok --cwd "))
        (is (str/includes? command "--minimal --rules \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/lieutenant.md"))
        (is (not (str/includes? command "--verbatim")))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/lieutenant.md"))))
      (finally
        (fs/delete-tree root)))))

(deftest a-lieutenant-whose-agent-reads-no-prompt-file-is-handed-its-prompt
  ;; Given a host lieutenant on an agent with no flag that reads its instruction
  ;; file, and the same lieutenant on an agent that has one
  ;; When SwarmForge builds the lieutenant launch command
  ;; Then the first names the instructions to read - not their contents, and not
  ;; nothing at all - and the second is left to read the file through its flag
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/swarmforge.conf") "Lieutenant codex --yolo\n")
      (let [codex (:out (run {:dir root}
                             (script "swarmforge.bb")
                             "--test-lieutenant-launch-command"
                             (str root)))]
        (is (str/includes? codex "codex -C "))
        (is (str/includes? codex "Read swarmforge/roles/lieutenant.prompt"))
        (is (not (str/includes? codex "$(cat "))))
      (write-file (fs/path root "swarmforge/swarmforge.conf") "Lieutenant copilot --yolo\n")
      (let [copilot (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-lieutenant-launch-command"
                               (str root)))]
        (is (str/includes? copilot "-i \"Read swarmforge/roles/lieutenant.prompt")))
      (write-file (fs/path root "swarmforge/swarmforge.conf") "Lieutenant claude --yolo\n")
      (let [claude (:out (run {:dir root}
                              (script "swarmforge.bb")
                              "--test-lieutenant-launch-command"
                              (str root)))]
        (is (str/includes? claude "--append-system-prompt-file "))
        (is (not (str/includes? claude "Read swarmforge/roles/lieutenant.prompt"))))
      (finally
        (fs/delete-tree root)))))

(deftest lieutenant-launch-reads-host-conf
  ;; Given a host conf line Lieutenant claude --yolo
  ;; When SwarmForge builds the lieutenant launch command
  ;; Then the command uses claude with --yolo
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/swarmforge.conf") "Lieutenant claude --yolo\n")
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-lieutenant-launch-command"
                               (str root)))]
        (is (str/includes? command "claude --append-system-prompt-file "))
        (is (str/includes? command "--yolo"))
        (is (not (str/includes? command "grok --cwd "))))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-uses-minimal-for-scrollback
  ;; Given a grok pack role
  ;; When SwarmForge builds the launch command
  ;; Then grok runs --minimal so finalized chatter is in tmux scrollback
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-launch-command"
                               (str root)
                               "grok"))]
        (is (str/includes? command " --minimal ")))
      (finally
        (fs/delete-tree root)))))

(deftest launch-command-puts-transcript-in-tmux-scrollback
  ;; Given each pack backend
  ;; When SwarmForge builds the launch command
  ;; Then Codex and Copilot use --no-alt-screen, Claude disables the
  ;; alternate screen, and Grok keeps --minimal
  (doseq [[agent needle] [["codex" "--no-alt-screen"]
                          ["copilot" "--no-alt-screen"]
                          ["claude" "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1"]
                          ["grok" "--minimal"]]]
    (let [root (tmp-dir)]
      (try
        (let [command (:out (run {:dir root}
                                 (script "swarmforge.bb")
                                 "--test-launch-command"
                                 (str root)
                                 agent))]
          (is (str/includes? command needle) agent))
        (finally
          (fs/delete-tree root))))))

(deftest grok-launch-command-uses-bypass-permissions-with-always-approve
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok"
                        "--always-approve")
            command (:out result)]
        (is (str/includes? command "--permission-mode bypassPermissions"))
        (is (str/includes? command "--always-approve"))
        (is (not (str/includes? command "--permission-mode acceptEdits"))))
      (finally
        (fs/delete-tree root)))))

(deftest launch-command-yolos-every-backend
  ;; Given a pack role with no extra-args
  ;; When --test-launch-command for each backend
  ;; Then the start command bypasses permission prompts
  (doseq [[agent needle] [["codex" "--yolo"]
                          ["copilot" "--yolo"]
                          ["claude" "--permission-mode bypassPermissions"]
                          ["grok" "--permission-mode bypassPermissions"]]]
    (let [root (tmp-dir)]
      (try
        (let [command (:out (run {:dir root}
                                 (script "swarmforge.bb")
                                 "--test-launch-command"
                                 (str root)
                                 agent))]
          (is (str/includes? command needle) agent))
        (finally
          (fs/delete-tree root))))))

(deftest launch-command-puts-this-forges-own-helpers-on-path
  ;; Given a launched role in a project that may keep helpers of its own
  ;; When the start command is built
  ;; Then swarmforge/local-scripts comes before the shared scripts on PATH, so a
  ;; project's own helper is found and is not the one an update replaces
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-launch-command"
                               (str root)
                               "codex"))]
        (is (str/includes? command (str "swarmforge/local-scripts'")))
        (is (< (.indexOf command "swarmforge/local-scripts")
               (.indexOf command "swarmforge/scripts'"))))
      (finally
        (fs/delete-tree root)))))

(deftest the-instructions-name-where-a-forges-own-helpers-live
  ;; Given the shared engineering article and the lieutenant's instructions
  ;; When they are read
  ;; Then both name swarmforge/local-scripts, so a session knows where a helper of
  ;; its own belongs rather than putting it among the shared, replaced scripts
  (let [article (slurp (str (fs/path repo-root "swarmforge" "constitution"
                                    "articles" "engineering.prompt")))
        lieutenant (slurp (str (fs/path repo-root "swarmforge" "roles"
                                       "lieutenant.prompt")))]
    (is (str/includes? article "swarmforge/local-scripts/"))
    (is (str/includes? lieutenant "swarmforge/local-scripts/"))))

(deftest launch-command-puts-project-tool-bin-on-path
  ;; Given a launched role
  ;; When the start command is built
  ;; Then `.swarmforge/bin` is on PATH so require/ensure wrappers are found
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-launch-command"
                               (str root)
                               "codex"))]
        (is (str/includes? command (str ".swarmforge/bin':'"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-trusts-codex-worktree-once
  ;; Given a Codex worktree with no projects block
  ;; When startup ensures trust
  ;; Then config.toml gains trust_level trusted for that exact path, once
  (let [root (tmp-dir)
        home (fs/create-temp-dir {:prefix "codex-home."})
        wt (str (fs/absolutize root))]
    (try
      (doseq [_ [1 2]]
        (run {:dir root :env {"CODEX_HOME" (str home)
                              "HOME" (str home)
                              "PATH" (System/getenv "PATH")
                              "GIT_CONFIG_NOSYSTEM" "1"}}
             (script "swarmforge.bb")
             "--test-ensure-codex-trust"
             wt))
      (let [cfg (slurp (str (fs/path home "config.toml")))
            header (str "[projects." (pr-str wt) "]")
            hits (count (re-seq (re-pattern (java.util.regex.Pattern/quote header)) cfg))]
        (is (str/includes? cfg header))
        (is (str/includes? cfg "trust_level = \"trusted\""))
        (is (= 1 hits)))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree home)))))

(deftest swarmforge-does-not-overwrite-existing-codex-project-block
  ;; Given an existing projects block for the worktree
  ;; When startup ensures trust
  ;; Then that block is left unchanged
  (let [root (tmp-dir)
        home (fs/create-temp-dir {:prefix "codex-home."})
        wt (str (fs/absolutize root))
        header (str "[projects." (pr-str wt) "]")
        original (str header "\ntrust_level = \"untrusted\"\nnote = \"keep\"\n")]
    (try
      (write-file (fs/path home "config.toml") original)
      (run {:dir root :env {"CODEX_HOME" (str home)
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarmforge.bb")
           "--test-ensure-codex-trust"
           wt)
      (is (= original (slurp (str (fs/path home "config.toml")))))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree home)))))

(deftest swarmforge-trust-does-not-duplicate-existing-config
  ;; Given a config.toml that already has another project table
  ;; When startup trusts a new worktree
  ;; Then the old table appears once and the new path appears once
  (let [root (tmp-dir)
        home (fs/create-temp-dir {:prefix "codex-home."})
        wt (str (fs/absolutize root))
        other "[projects.\"/other\"]\ntrust_level = \"trusted\"\n"]
    (try
      (write-file (fs/path home "config.toml") (str "model = \"gpt-5.5\"\n\n" other))
      (run {:dir root :env {"CODEX_HOME" (str home)
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarmforge.bb")
           "--test-ensure-codex-trust"
           wt)
      (let [cfg (slurp (str (fs/path home "config.toml")))]
        (is (= 1 (count (re-seq #"model = \"gpt-5.5\"" cfg))))
        (is (= 1 (count (re-seq #"\[projects\.\"/other\"\]" cfg))))
        (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote
                                             (str "[projects." (pr-str wt) "]")))
                                cfg)))))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree home)))))

(deftest the-tool-registry-knows-each-languages-recipe
  ;; Given the shared tool registry
  ;; When it is read
  ;; Then every language our projects are written in has the recipe its tools need -
  ;; a Go tool built from its package, and Kotlin's slopguard built through Gradle -
  ;; so a refresh from upstream cannot quietly leave a language without one
  (let [registry (slurp (str (fs/path repo-root "swarmforge" "scripts" "swarm_tool.bb")))]
    (is (str/includes? registry ":go-package \"github.com/unclebob/crap4go/cmd/crap4go\""))
    (is (str/includes? registry ":go-package \"github.com/unclebob/dry4go/cmd/dry4go\""))
    (is (str/includes? registry ":go-package \"github.com/unclebob/mutate4go/cmd/mutate4go\""))
    (is (str/includes? registry
                       "\"slopguard\" {:source \"github.com/JeevanThandi/slopguard-kotlin\""))
    (is (str/includes? registry ":gradle-install \"app:installDist\""))))

(deftest swarm-tool-knows-constitution-tool-names
  ;; Given a pack project
  ;; When require runs for clj-mutate
  ;; Then it is a known tool (missing until ensure), not Unknown tool
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (let [missing (run {:dir root :ok? false}
                         (script "swarm_tool.sh") "require" "clj-mutate")
            help (run {:dir root :ok? false}
                      (script "swarm_tool.sh") "--help")]
        (is (not= 0 (:exit missing)))
        (is (str/includes? (:err missing) "MISSING: clj-mutate"))
        (is (not (str/includes? (:err missing) "Unknown tool")))
        (is (str/includes? (str (:err help) (:out help)) "clj-mutate"))
        (is (str/includes? (str (:err help) (:out help)) "crap4clj"))
        (is (str/includes? (str (:err help) (:out help)) "dry4clj"))
        (is (str/includes? (str (:err help) (:out help)) "cloverage"))
        (is (str/includes? (str (:err help) (:out help)) "speclj"))
        (is (str/includes? (str (:err help) (:out help)) "speclj-structure-check")))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-ensure-cloverage-invokes-cloverage
  ;; Given a pack project
  ;; When swarm_tool.sh ensure cloverage
  ;; Then the wrapper launches cloverage.coverage, not crap4clj
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root} (script "swarm_tool.sh") "ensure" "cloverage")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/cloverage")))]
        (is (str/includes? wrapper "cloverage.coverage"))
        (is (str/includes? wrapper "cloverage/cloverage"))
        (is (str/includes? wrapper "\"src\""))
        (is (str/includes? wrapper "\"spec\""))
        (is (str/includes? wrapper "\"test\""))
        (is (str/includes? wrapper "-s spec"))
        (is (str/includes? wrapper "-r speclj"))
        (is (str/includes? wrapper "speclj/speclj"))
        (is (not (str/includes? wrapper "crap4clj")))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "cloverage"))))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "Cloverage")))))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-ensure-speclj-uses-speclj-main
  ;; Given a pack project
  ;; When swarm_tool.sh ensure speclj
  ;; Then the wrapper runs speclj.main -c spec, not speclj.cli
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root} (script "swarm_tool.sh") "ensure" "speclj")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/speclj")))]
        (is (str/includes? wrapper "speclj.main"))
        (is (str/includes? wrapper "-c spec"))
        (is (str/includes? wrapper "3.13.0"))
        (is (not (str/includes? wrapper "speclj.cli"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-ensure-crap4clj-also-installs-cloverage
  ;; Given a pack project with local crap4clj source
  ;; When swarm_tool.sh ensure crap4clj
  ;; Then both crap4clj and cloverage wrappers are installed
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/crap4clj/bb.edn")
                  "{:tasks {crap4clj identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "crap4clj")
      (is (fs/executable? (fs/path root ".swarmforge/bin/crap4clj")))
      (is (fs/executable? (fs/path root ".swarmforge/bin/cloverage")))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/bin/cloverage")))
                         "cloverage.coverage"))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-ensure-clj-mutate-also-installs-cloverage
  ;; Given a pack project with local clj-mutate source
  ;; When swarm_tool.sh ensure clj-mutate
  ;; Then both clj-mutate and cloverage wrappers are installed
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/clj-mutate/bb.edn")
                  "{:tasks {clj-mutate identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (is (fs/executable? (fs/path root ".swarmforge/bin/clj-mutate")))
      (is (fs/executable? (fs/path root ".swarmforge/bin/cloverage")))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/bin/cloverage")))
                         "cloverage.coverage"))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-require-and-ensure-install-aps-wrappers
  ;; Given a project without APS tools
  ;; When require runs, it reports missing
  ;; When ensure runs against a local APS source, wrappers land in .swarmforge/bin
  (let [root (tmp-dir)
        aps (fs/path root "aps-src")]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path aps "bb.edn") "{:tasks {gherkin-parser identity\n  gherkin-ir-dry-checker identity}}\n")
      (let [missing (run {:dir root :ok? false}
                         (script "swarm_tool.sh") "require" "gherkin-parser")]
        (is (not= 0 (:exit missing)))
        (is (str/includes? (:err missing) "MISSING: gherkin-parser")))
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "gherkin-parser")
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "ir-dry-checker")
      (let [parser (fs/path root ".swarmforge/bin/gherkin-parser")
            dry (fs/path root ".swarmforge/bin/ir-dry-checker")]
        (is (fs/executable? parser))
        (is (fs/executable? dry))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "gherkin-parser"))))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "ir-dry-checker")))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-start-order-opens-dashboard-before-agents
  ;; Given a pack
  ;; When --test-start-order
  ;; Then pack_web starts before agents
  (let [root (tmp-dir)]
    (try
      (write-pack-conf! root
                        (str "window-invisible specifier codex master\n"
                             "window coder codex coder\n"))
      (let [out (:out (run {:dir root} (script "swarmforge.bb")
                           "--test-start-order" (str root)))
            pack (.indexOf out "pack_web start")
            agents (.indexOf out "start-agents")]
        (is (>= pack 0))
        (is (>= agents 0))
        (is (< pack agents)))
      (finally
        (fs/delete-tree root)))))

(defn commit-body [root]
  (:out (run {:dir root} "git" "log" "-1" "--format=%B")))

(deftest commit-msg-hook-adds-missing-role-byline
  ;; Given a specifier commit whose message has no byline
  ;; When the commit-msg hook runs
  ;; Then it appends `By specifier.` and does not duplicate an existing byline
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (let [body (commit-body root)]
        (is (str/includes? body "Specify Hunt the Wumpus console app"))
        (is (str/includes? body "By specifier."))
        (is (= 1 (count (re-seq #"By specifier\." body)))))
      (write-file (fs/path root "spec.md") "hunt two\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Add a scenario\n\nBy specifier.")
      (is (= 1 (count (re-seq #"By specifier\." (commit-body root)))))
      (finally
        (fs/delete-tree root)))))

(deftest commit-msg-hook-infers-role-from-worktree
  ;; Given SWARMFORGE_ROLE is unset and roles.tsv maps this worktree to specifier
  ;; When a commit is made
  ;; Then the hook still adds `By specifier.`
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (is (str/includes? (commit-body root) "By specifier."))
      (finally
        (fs/delete-tree root)))))

(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm_window_watchdog.bb") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-detects-nonzero-pane-base-index
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g base-index 1\nset -g pane-base-index 1\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-tmux-base-indexes"
                        sock)]
        (is (= "1 1" (str/trim (:out result)))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest role-session-keeps-tmux-scrollback
  ;; Given a tmux socket
  ;; When SwarmForge creates a role session
  ;; Then history-limit keeps thousands of lines
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g history-limit 50\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-create-role-session"
                        sock
                        "swarmforge-specifier")
            limit (Long/parseLong (str/trim (:out result)))]
        (is (zero? (:exit result)))
        (is (>= limit 2000)))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest swarm-cleanup-tolerates-missing-runtime-state
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "window-a\nwindow-b\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        "/tmp/nonexistent.sock"
                        (str ids-file))]
        (is (= 0 (:exit result)))
        (is (= "" (:err result))))
      (finally
        (fs/delete-tree root)))))

(defn close-swarm []
  (str (fs/path repo-root "close-swarm")))

(deftest close-swarm-reports-when-no-swarm-state
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root :ok? false
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (not= 0 (:exit result)))
        (is (str/includes? (str (:err result) (:out result)) "No SwarmForge swarm")))
      (finally
        (fs/delete-tree root)))))

(deftest close-swarm-kills-tmux-sessions-and-stops-daemon
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        pid-file (fs/path root ".swarmforge/daemon/handoffd.pid")
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))]
    (try
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file (fs/path root ".swarmforge/sessions.tsv")
                  (str "1\tcoder\tswarmforge-coder\tCoder\tcodex\n"
                       "2\tcleaner\tswarmforge-cleaner\tCleaner\tcodex\n"))
      (write-file (fs/path root ".swarmforge/window-ids") "win-a\nwin-b\n")
      (write-file pid-file (str pid "\n"))
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-coder" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-cleaner" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-coder"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-cleaner"))))
        (is (not (fs/exists? pid-file)))
        (is (false? (.isAlive daemon))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(defn write-echo-tool! [root tool]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
  (write-file (fs/path root ".swarmforge/tools" tool "bb.edn")
              (str "{:tasks {" tool " (apply println *command-line-args*)}}\n")))

(deftest clj-mutate-wrapper-is-differential-with-four-workers
  ;; Given an installed clj-mutate wrapper
  ;; When it is invoked with --mutate-all
  ;; Then --mutate-all is dropped and --max-workers 4 is used
  (let [root (tmp-dir)]
    (try
      (write-echo-tool! root "clj-mutate")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/clj-mutate"))
                           "src/htw/game.clj" "--reuse-lcov" "--mutate-all"
                           "--test-command" "bb test"))]
        (is (str/includes? out "--max-workers 4"))
        (is (not (str/includes? out "--mutate-all"))))
      (finally
        (fs/delete-tree root)))))

(deftest clj-mutate-scan-does-not-inject-max-workers
  ;; Given an installed clj-mutate wrapper
  ;; When it is invoked with --scan
  ;; Then it does not add --max-workers
  (let [root (tmp-dir)]
    (try
      (write-echo-tool! root "clj-mutate")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/clj-mutate"))
                           "src/htw/game.clj" "--scan"))]
        (is (not (str/includes? out "--max-workers"))))
      (finally
        (fs/delete-tree root)))))

(deftest gherkin-mutator-wrapper-is-differential-with-four-workers
  ;; Given an installed gherkin-mutator wrapper
  ;; When it is invoked with --level full
  ;; Then the level is hard and --workers 4 is used
  (let [root (tmp-dir)
        aps (fs/path root "aps-src")]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path aps "bb.edn")
                  "{:tasks {gherkin-mutator (apply println *command-line-args*)}}\n")
      (run {:dir root :env {"SWARMFORGE_TOOL_SRC" (str aps)
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "gherkin-mutator")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/gherkin-mutator"))
                           "--feature" "features/a.feature" "--level" "full"
                           "--runner-worker" "true"))]
        (is (str/includes? out "--level hard"))
        (is (str/includes? out "--workers 4"))
        (is (not (str/includes? out "--level full"))))
      (finally
        (fs/delete-tree root)))))

(deftest constitution-tool-wrappers-do-not-use-a-lock-file
  ;; Given an installed constitution tool wrapper
  ;; When it is written
  ;; Then it does not take a project lock directory
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/crap4clj/bb.edn")
                  "{:tasks {crap4clj identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "crap4clj")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/crap4clj")))]
        (is (not (str/includes? wrapper "constitution-tools.lock")))
        (is (not (str/includes? wrapper "SWARMFORGE_TOOL_HELD"))))
      (finally
        (fs/delete-tree root)))))

(deftest ready-for-next-treats-blank-receive-mode-as-task
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\t\n" root))
      (doseq [dir [".swarmforge/handoffs/outbox/tmp"
                   ".swarmforge/handoffs/sent"
                   ".swarmforge/handoffs/failed"
                   ".swarmforge/handoffs/inbox/new"
                   ".swarmforge/handoffs/inbox/in_process"
                   ".swarmforge/handoffs/inbox/completed"]]
        (fs/create-dirs (fs/path root dir)))
      (let [mode (run {:dir root :env {"SWARMFORGE_ROLE" "sender"}}
                      (script "handoff_lib.bb") "role-receive-mode" "sender")
            ready (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                       (script "ready_for_next.sh"))]
        (is (str/includes? (:out mode) "task"))
        (is (zero? (:exit ready)))
        (is (str/includes? (:out ready) "NO_TASK")))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_item.handoff")
                  (str "id: 1\n"
                       "from: sender\n"
                       "to: sender\n"
                       "priority: 50\n"
                       "type: note\n"
                       "task: HTW\n"
                       "\n"
                       "body\n"))
      (let [done (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                      (script "done_with_current.sh"))]
        (is (zero? (:exit done)))
        (is (str/includes? (:out done) "COMPLETED:"))
        (is (re-find #"MAIL_WAITING|NO_TASK" (:out done))))
      (finally
        (fs/delete-tree root)))))

(deftest ready-for-next-unknown-role-fails
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\ttask\n" root))
      (let [ready (run {:dir root :env {"SWARMFORGE_ROLE" "ghost"} :ok? false}
                       (script "ready_for_next.sh"))
            done (run {:dir root :env {"SWARMFORGE_ROLE" "ghost"} :ok? false}
                      (script "done_with_current.sh"))]
        (is (not (zero? (:exit ready))))
        (is (str/includes? (str (:err ready) (:out ready)) "Unknown role"))
        (is (not (zero? (:exit done))))
        (is (str/includes? (str (:err done) (:out done)) "Unknown role")))
      (finally
        (fs/delete-tree root)))))

(deftest finish-done-logs-archive-throw-and-still-announces
  (let [root (tmp-dir)
        lib (fs/path root "handoff_lib.bb")]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\ttask\n" root))
      (fs/create-dirs (fs/path root ".swarmforge/handoffs/inbox/new"))
      (fs/copy (script "handoff_lib.bb") lib)
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                        "bb" (str lib) "finish-done")]
        (is (zero? (:exit result)))
        (is (re-find #"MAIL_WAITING|NO_TASK" (:out result)))
        (is (str/includes? (str (:err result)) "archive failed"))
        (is (str/includes? (str (:err result)) "sender"))
        (is (str/includes? (str (:err result)) (str root))))
      (finally
        (fs/delete-tree root)))))

(deftest a-dashboard-reuses-its-port-only-when-the-port-is-free
  ;; Given a forge whose dashboard last served on a port
  ;; When the launcher is asked what it would reuse
  ;; Then it names that port, and it says whether the port it was given is free -
  ;; including a port another process is holding, which is the case that would
  ;; otherwise hand a taken port to the new dashboard
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/dashboard-url") "http://127.0.0.1:49999\n")
      (let [held (java.net.ServerSocket.)
            _ (.bind held (java.net.InetSocketAddress. "127.0.0.1" 0))
            free-port (.getLocalPort held)]
        (try
          (.close held)
          (let [free (run {:dir root} (script "swarmforge.bb") "--test-dashboard-address"
                          (str root) (str free-port))]
            (is (str/includes? (:out free) "previous=49999"))
            (is (str/includes? (:out free) "available=true")))
          (let [taken-socket (doto (java.net.ServerSocket.)
                               (.setReuseAddress true)
                               (.bind (java.net.InetSocketAddress. "127.0.0.1" free-port)))
                taken (run {:dir root} (script "swarmforge.bb") "--test-dashboard-address"
                           (str root) (str free-port))]
            (try
              (is (str/includes? (:out taken) "previous=49999"))
              (is (str/includes? (:out taken) "available=false"))
              (finally (.close taken-socket))))
          (fs/delete-tree (fs/path root ".swarmforge"))
          (let [none (run {:dir root} (script "swarmforge.bb") "--test-dashboard-address"
                          (str root) (str free-port))]
            (is (str/includes? (:out none) "previous=none")))
          (finally (.close held))))
      (finally (fs/delete-tree root)))))

(deftest pack-web-production-main-does-not-run-test-flags
  (let [result (run {:dir repo-root :ok? false}
                    "bb" (script "pack_web.bb") "--test-html")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (slurp (script "pack_web.bb")) "--serve"))
    (is (not (re-find #"--test-state\" \(test-state!" (slurp (script "pack_web.bb")))))
    (is (str/includes? (slurp (str (fs/path repo-root "test/swarmforge/pack_web_test.bb"))) "--test-state"))))

(deftest a-forwarded-approval-carries-the-gate-with-it
  ;; Given a request whose text is an approval or clarification notification the
  ;; bridge wrote, and a request that is neither
  ;; When the dashboard wakes the pane
  ;; Then the matching one carries the rule that the decision is the operator's,
  ;; and the ordinary one carries only the answering command
  (let [approval (run {:dir repo-root} (script "pack_web.sh") "--test-chat-wake"
                      "req-a" (str "Approval for initial-setup in forgelet-app\n"
                                   "Gate: specifier → coder\n"
                                   "React ✅ to approve, or reply in this thread to send it back."))
        clarification (run {:dir repo-root} (script "pack_web.sh") "--test-chat-wake"
                           "req-c" (str "Clarification for forgelet-app from specifier\n"
                                        "Question: restart the daemon?\n"
                                        "Reply in this thread with the answer."))
        ordinary (run {:dir repo-root} (script "pack_web.sh") "--test-chat-wake"
                      "req-o" "is the build green?")]
    (is (str/includes? (:out approval) "the gate is the operator's"))
    (is (str/includes? (:out approval) "Do not approve unless the operator says to"))
    (is (str/includes? (:out clarification) "the answer is"))
    (is (str/includes? (:out clarification) "Do not answer it unless the operator says to"))
    (is (not (str/includes? (:out ordinary) "the operator's")))))

(deftest chat-request-carries-the-command-that-answers-it
  ;; Given a chat request the dashboard took for the operator
  ;; When the dashboard wakes the master role's pane
  ;; Then the request carries the command that answers it, so a session that
  ;; never read its prompt, or read a stale one, still answers through the tool
  (let [result (run {:dir repo-root}
                    (script "pack_web.sh") "--test-chat-wake"
                    "req-1" "is the build green?")
        wake (:out result)]
    (is (zero? (:exit result)) (:err result))
    (is (str/includes? wake "[req-1] is the build green?"))
    (is (str/includes? wake
                       "Answer with: pack_dashboard_request.sh answer req-1 ./tmp/answer.txt"))
    (is (str/includes? wake "a reply only in this pane reaches nobody"))))

(deftest get-swarm-forge-help-names-the-layer-readme
  ;; Given an operator who asks the helper what it does
  ;; When it prints its usage
  ;; Then the usage points at this fork's README, which is where what the layer is
  ;; and where a forge's own things go are documented - and a composition aimed at
  ;; another branch by override points at that branch's page, not at ours
  (let [helper (str (fs/path repo-root "get-swarm-forge"))
        usage (fn [result] (str (:out result) (:err result)))
        mine (run {:dir repo-root} helper "-h")
        other (run {:dir repo-root
                    :env {"SWARMFORGE_REPO_URL" "https://example.test/other"
                          "SWARMFORGE_BASE_REF" "somebranch"}}
                   helper "-h")]
    (is (zero? (:exit mine)) (:err mine))
    (is (str/includes? (usage mine) "blob/forgelet/README.md") (usage mine))
    (is (str/includes? (usage mine) "local-scripts") (usage mine))
    (is (str/includes? (usage other)
                       "https://example.test/other/blob/somebranch/README.md")
        (usage other))))

(deftest get-swarm-forge-keeps-its-scratch-when-it-fails
  ;; Given a bridge whose build fails
  ;; When the composition runs
  ;; Then it names the scratch it left, and that scratch is there to be read
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)
        bridge (tmp-dir)
        marker "get-swarm-forge: failed, and left its scratch at "]
    (try
      (doseq [name ["swarmforge.sh" "handoffd.bb" "done_with_current.sh"]]
        (write-file (fs/path base "swarmforge/scripts" name) (str name "\n")))
      (write-file (fs/path base "swarmforge/roles/lieutenant.prompt") "LIEUTENANT\n")
      (write-file (fs/path base "swarmforge/constitution/articles/engineering.prompt") "ENG\n")
      (write-file (fs/path base "swarmforge/constitution/articles/workflow.prompt") "WF\n")
      (write-file (fs/path base "swarmforge/constitution/articles/handoffs.prompt") "HO\n")
      (write-file (fs/path base "swarm") "#!/bin/sh\necho swarm\n")
      (doseq [pack-name ["two-pack" "four-pack" "six-pack"]]
        (let [pack (fs/path packs pack-name)]
          (write-file (fs/path pack "swarm") "#!/bin/sh\necho swarm\n")
          (write-file (fs/path pack "swarmforge/swarmforge.conf") "window specifier grok master\n")
          (write-file (fs/path pack "swarmforge/roles/specifier.prompt") "specifier\n")))
      (write-file (fs/path bridge "scripts/build.sh") "#!/bin/sh\nexit 1\n")
      (run {:dir bridge} "chmod" "+x" "scripts/build.sh")
      (let [result (run {:dir host
                         :ok? false
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_PACKS_DIR" (str packs)
                               "SWARMFORGE_BRIDGE_DIR" (str bridge)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "project-manager")
            named (some (fn [line]
                          (let [line (str/trim line)]
                            (when (str/starts-with? line marker)
                              (subs line (count marker)))))
                        (str/split-lines (str (:err result))))]
        (is (not (zero? (:exit result))))
        (is (some? named) (str "the failure did not name its scratch: " (:err result)))
        (is (fs/directory? named))
        ;; /var is a symlink to /private/var on macOS, so compare the real paths.
        (is (str/starts-with? (str (fs/real-path named)) (str (fs/real-path host))))
        (is (str/includes? (str named) "/tmp/swarmforge-compose-")))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)
        (fs/delete-tree bridge)))))

(deftest get-swarm-forge-updates-its-own-copy-from-the-layer
  ;; Given an installed helper and a layer whose branch carries a different one
  ;; When the installed copy composes
  ;; Then it replaces itself with the layer's copy and starts again as that - and
  ;; the escape hatch leaves it alone, for working on the helper itself
  (let [dir (tmp-dir)
        installed (fs/path dir "get-swarm-forge")
        layer (fs/path dir "layer")
        stub "#!/usr/bin/env zsh\necho 'the newer helper ran'\n"
        env {"SWARMFORGE_REPO_URL" "http://127.0.0.1:9/archive"
             "SWARMFORGE_GIT_DIR" (str layer)}]
    (try
      (fs/copy (fs/path repo-root "get-swarm-forge") installed)
      (write-file (fs/path layer "get-swarm-forge") stub)
      (run {:dir layer} "chmod" "+x" "get-swarm-forge")
      (run {:dir layer} "git" "init" "-q")
      (run {:dir layer} "git" "config" "user.email" "test@example.com")
      (run {:dir layer} "git" "config" "user.name" "Test User")
      (run {:dir layer} "git" "add" "get-swarm-forge")
      (run {:dir layer} "git" "commit" "-q" "-m" "the layer")
      (run {:dir layer} "git" "branch" "-M" "forgelet")
      ;; The product is fetched before the layer, so the layer repository needs a
      ;; branch by that name for the run to reach the self-update at all.
      (run {:dir layer} "git" "branch" "project-manager")

      (let [result (run {:dir dir :env env :ok? false} (str installed) "project-manager")]
        (is (str/includes? (:out result) "replaced itself with the helper from forgelet")
            (:out result))
        (is (str/includes? (:out result) "the newer helper ran") (:out result))
        (is (= stub (slurp (str installed)))))

      (fs/copy (fs/path repo-root "get-swarm-forge") installed {:replace-existing true})
      (run {:dir dir :env (assoc env "SWARMFORGE_NO_SELF_UPDATE" "1") :ok? false}
           (str installed) "project-manager")
      (is (str/includes? (slurp (str installed)) "maybe-update-self")
          "the escape hatch left the installed copy alone")
      (finally
        (fs/delete-tree dir)))))

(deftest get-swarm-forge-seeds-this-forges-own-lieutenant-file-once
  ;; Given a layer that carries a default for the forge's own lieutenant additions
  ;; When a forge is composed twice, with the forge's own file written in between
  ;; Then the first composition seeds it and the second leaves it alone
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)
        local-file (fs/path host "swarmforge/roles/local-lieutenant.prompt")]
    (try
      (doseq [name ["swarmforge.sh" "handoffd.bb" "done_with_current.sh"]]
        (write-file (fs/path base "swarmforge/scripts" name) (str name "\n")))
      (write-file (fs/path base "swarmforge/roles/lieutenant.prompt") "LAYER-LIEUTENANT\n")
      (write-file (fs/path base "swarmforge/roles/local-lieutenant.prompt") "LAYER-DEFAULT-LOCAL\n")
      (write-file (fs/path base "swarmforge/constitution/articles/engineering.prompt") "MAIN-ENGINEERING\n")
      (write-file (fs/path base "swarmforge/constitution/articles/workflow.prompt") "MAIN-WORKFLOW\n")
      (write-file (fs/path base "swarmforge/constitution/articles/handoffs.prompt") "MAIN-HANDOFFS\n")
      (write-file (fs/path base "swarm") "#!/bin/sh\necho swarm\n")
      (doseq [pack-name ["two-pack" "four-pack" "six-pack"]]
        (let [pack (fs/path packs pack-name)]
          (write-file (fs/path pack "swarm") "#!/bin/sh\necho swarm\n")
          (write-file (fs/path pack "swarmforge/swarmforge.conf") "window specifier grok master\n")
          (write-file (fs/path pack "swarmforge/roles/specifier.prompt") "specifier\n")))
      (let [compose (fn []
                      (run {:dir host
                            :env {"SWARMFORGE_BASE_DIR" (str base)
                                  "SWARMFORGE_PACKS_DIR" (str packs)
                                  "SWARMFORGE_SKIP_BRIDGE" "1"}}
                           (str (fs/path repo-root "get-swarm-forge"))
                           "project-manager"))]
        (is (zero? (:exit (compose))) "the first composition failed")
        (is (= "LAYER-DEFAULT-LOCAL\n" (slurp (str local-file)))
            "a fresh forge did not get the layer's default")
        (write-file local-file "WHAT-THIS-FORGE-ADDS\n")
        (is (zero? (:exit (compose))) "the second composition failed")
        (is (= "WHAT-THIS-FORGE-ADDS\n" (slurp (str local-file)))
            "the second composition overwrote this forge's own words"))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)))))

(deftest six-pack-carries-the-ignore-lines-its-build-needs
  ;; Given a pack whose language writes build output beside its source
  ;; When the layer's copy of that pack's ignore lines is read
  ;; Then the lines a project needs before its first build are in the file
  (let [file (fs/path repo-root "swarmforge" "packs" "six-pack" "gitignore")]
    (is (fs/exists? file) (str "missing " file))
    (let [lines (set (str/split-lines (slurp (str file))))]
      (doseq [line [".idea/" "build/" ".gradle/" ".kotlin/" "local.properties" "tmp/"]]
        (is (contains? lines line) (str "six-pack gitignore lacks " line))))))

(deftest the-lieutenant-is-told-where-this-forges-own-rules-live
  ;; Given the shared lieutenant instructions
  ;; When the prompt is read
  ;; Then it names the file this forge's own additions live in, which is what the
  ;; read-and-follow-it handover follows
  (let [prompt (slurp (str (fs/path repo-root "swarmforge" "roles" "lieutenant.prompt")))]
    (is (str/includes? prompt "swarmforge/roles/local-lieutenant.prompt"))))

(deftest get-swarm-forge-installs-the-bridges-tools
  ;; Given a forge this fork composes, and a bridge to build it from
  ;; When the composition runs
  ;; Then the build is laid out where the adapter looks, the adapter lands in the
  ;; forge's own scripts, and the tools and the rules are installed from that tree
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)
        bridge (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (doseq [name ["swarmforge.sh" "handoffd.bb" "done_with_current.sh"]]
        (write-file (fs/path base "swarmforge/scripts" name) (str name "\n")))
      (write-file (fs/path base "swarmforge/constitution.prompt") "MAIN\n")
      (write-file (fs/path base "swarmforge/roles/lieutenant.prompt") "LIEUTENANT\n")
      (write-file (fs/path base "swarmforge/swarmforge.conf") "# Lieutenant grok\n")
      (doseq [name ["engineering.prompt" "workflow.prompt" "handoffs.prompt"]]
        (write-file (fs/path base "swarmforge/constitution/articles" name) (str name "\n")))
      (doseq [pack-name ["two-pack" "four-pack" "six-pack"]]
        (let [pack (fs/path packs pack-name)]
          (write-file (fs/path pack "swarm") "#!/bin/sh\necho swarm\n")
          (write-file (fs/path pack "swarmforge/swarmforge.conf") "window specifier grok master\n")
          (write-file (fs/path pack "swarmforge/constitution.prompt") "PACK\n")
          (write-file (fs/path pack "swarmforge/roles/specifier.prompt") "specifier\n")))
      ;; A bridge standing in for the real one: it builds the two installers and
      ;; the adapter runs them, recording what each was handed.
      (write-file (fs/path bridge "scripts/build.sh")
                  (str "#!/bin/sh\nset -eu\n"
                       "mkdir -p build/acceptance/bin\n"
                       "cp -R installers/. build/acceptance/bin/\n"
                       "chmod +x build/acceptance/bin/*\n"))
      (write-file (fs/path bridge "scripts/matrix-bridge.sh")
                  (str "#!/bin/sh\nset -eu\n"
                       "case \"$1\" in\n"
                       "  install-kit) \"$MATRIX_BRIDGE_KIT_BINARY\" --kit \"$MATRIX_BRIDGE_KIT\" ;;\n"
                       "  install-rules) \"$MATRIX_BRIDGE_RULES_BINARY\" --rules \"$MATRIX_BRIDGE_RULES\" ;;\n"
                       "esac\n"))
      (write-file (fs/path bridge "installers/install-kit")
                  (str "#!/bin/sh\n"
                       "mkdir -p \"$MATRIX_BRIDGE_FORGE_ROOT/.swarmforge\"\n"
                       "echo \"kit $MATRIX_BRIDGE_KIT\" >> \"$MATRIX_BRIDGE_FORGE_ROOT/.swarmforge/bridge-record\"\n"))
      (write-file (fs/path bridge "installers/install-rules")
                  (str "#!/bin/sh\n"
                       "mkdir -p \"$MATRIX_BRIDGE_FORGE_ROOT/.swarmforge\"\n"
                       "echo \"rules $MATRIX_BRIDGE_RULES\" >> \"$MATRIX_BRIDGE_FORGE_ROOT/.swarmforge/bridge-record\"\n"))
      (write-file (fs/path bridge "swarmforge/scripts/kit-marker.sh") "the tools\n")
      (write-file (fs/path bridge "rules/stopping-without-finishing.md") "the rules\n")
      (run {:dir bridge} "chmod" "+x"
           "scripts/build.sh" "scripts/matrix-bridge.sh"
           "installers/install-kit" "installers/install-rules")
      (let [result (run {:dir host
                         :ok? false
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_PACKS_DIR" (str packs)
                               "SWARMFORGE_BRIDGE_DIR" (str bridge)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "project-manager")
            record (fs/path host ".swarmforge/bridge-record")]
        (is (zero? (:exit result)) (:err result))
        (is (fs/directory? (fs/path host "projects/forgelet-bridge/build/acceptance/bin")))
        (is (fs/directory? (fs/path host "projects/forgelet-bridge/rules")))
        (is (fs/executable? (fs/path host "swarmforge/scripts/matrix-bridge.sh")))
        (is (fs/exists? record))
        (let [lines (str/split-lines (slurp (str record)))]
          (is (= 2 (count lines)))
          ;; The kit comes from the freshly fetched bridge under this directory's
          ;; own tmp/, not from the forge's projects/forgelet-bridge - and that
          ;; scratch is gone again, because the compose succeeded.
          (is (str/includes? (first lines)
                             (str host "/tmp/swarmforge-compose-")))
          (is (str/includes? (first lines) "/forgelet-bridge/swarmforge/scripts"))
          (is (str/includes? (second lines)
                             (str host "/tmp/swarmforge-compose-")))
          (is (str/includes? (second lines) "/forgelet-bridge/rules"))
          (is (not (str/includes? (first lines) (str host "/projects/"))))
          (is (empty? (fs/glob host "tmp/swarmforge-compose-*")))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)
        (fs/delete-tree bridge)))))

(deftest get-swarm-forge-copies-only-swarmforge-owned-paths
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (write-file (fs/path host "bb.edn") "{:paths [\"test\"]}\n")
      (write-file (fs/path host "test/keep.clj") "keep\n")
      (doseq [name ["swarmforge.sh" "handoffd.bb" "done_with_current.sh"]]
        (write-file (fs/path base "swarmforge/scripts" name) (str name "\n")))
      (write-file (fs/path base "swarmforge/packs/six-pack/gitignore") "build/\n.idea/\n")
      (write-file (fs/path base "swarmforge/packs/six-pack/language.conf") "Kotlin\n")
      (write-file (fs/path base "swarm") "#!/bin/sh\necho swarm\n")
      (write-file (fs/path base "swarmforge/constitution.prompt") "MAIN-CONSTITUTION\n")
      (write-file (fs/path base "swarmforge/roles/lieutenant.prompt") "LAYER-LIEUTENANT\n")
      (write-file (fs/path base "swarmforge/roles/local-lieutenant.prompt") "LAYER-DEFAULT-LOCAL\n")
      (write-file (fs/path base "swarmforge/swarmforge.conf") "# Lieutenant grok\n")
      (write-file (fs/path base "swarmforge/constitution/articles/engineering.prompt") "MAIN-ENGINEERING\n")
      (write-file (fs/path base "swarmforge/constitution/articles/workflow.prompt") "MAIN-WORKFLOW\n")
      (write-file (fs/path base "swarmforge/constitution/articles/handoffs.prompt") "MAIN-HANDOFFS\n")
      (doseq [pack-name ["two-pack" "four-pack" "six-pack"]]
        (let [pack (fs/path packs pack-name)]
          (write-file (fs/path pack "swarm") "#!/bin/sh\necho swarm\n")
          (write-file (fs/path pack "README.md") "pack-readme\n")
          (write-file (fs/path pack "bb.edn") "pack-bb\n")
          (write-file (fs/path pack "swarmforge/swarmforge.conf")
                      "window specifier grok master\n")
          (write-file (fs/path pack "swarmforge/constitution.prompt") "PACK-CONSTITUTION\n")
          (write-file (fs/path pack "swarmforge/roles/specifier.prompt") "specifier\n")
          (write-file (fs/path pack "swarmforge/constitution/articles/engineering.prompt") "PACK-STALE-ENGINEERING\n")
          (write-file (fs/path pack "swarmforge/constitution/articles/project.prompt") "PACK-PROJECT\n")
          (write-file (fs/path pack "swarmforge/constitution/articles/local-workflow.prompt") "PACK-LOCAL-WORKFLOW\n")))
      (let [result (run {:dir host
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_PACKS_DIR" (str packs)
                               ;; The bridge step builds a real bridge; this test is
                               ;; about which files the composition owns.
                               "SWARMFORGE_SKIP_BRIDGE" "1"}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "project-manager")]
        (is (zero? (:exit result)) (:err result))
        (is (= "host-readme\n" (slurp (str (fs/path host "README.md")))))
        (is (= "{:paths [\"test\"]}\n" (slurp (str (fs/path host "bb.edn")))))
        (is (= "keep\n" (slurp (str (fs/path host "test/keep.clj")))))
        (is (= "MAIN-ENGINEERING\n" (slurp (str (fs/path host "swarmforge/constitution/articles/engineering.prompt")))))
        (is (= "MAIN-WORKFLOW\n" (slurp (str (fs/path host "swarmforge/constitution/articles/workflow.prompt")))))
        (is (= "MAIN-HANDOFFS\n" (slurp (str (fs/path host "swarmforge/constitution/articles/handoffs.prompt")))))
        (is (= "MAIN-CONSTITUTION\n" (slurp (str (fs/path host "swarmforge/constitution.prompt")))))
        (is (fs/exists? (fs/path host "swarmforge/roles/lieutenant.prompt")))
        (is (= "LAYER-LIEUTENANT\n"
               (slurp (str (fs/path host "swarmforge/roles/lieutenant.prompt")))))
        (is (= "LAYER-DEFAULT-LOCAL\n"
               (slurp (str (fs/path host "swarmforge/roles/local-lieutenant.prompt")))))
        (is (fs/exists? (fs/path host "swarmforge/swarmforge.conf")))
        (is (not (fs/exists? (fs/path host "swarmforge/roles/specifier.prompt"))))
        (is (not (fs/exists? (fs/path host "swarmforge/constitution/articles/project.prompt"))))
        (is (= "PACK-PROJECT\n" (slurp (str (fs/path host "packs/two-pack/swarmforge/constitution/articles/project.prompt")))))
        (is (= "PACK-LOCAL-WORKFLOW\n" (slurp (str (fs/path host "packs/four-pack/swarmforge/constitution/articles/local-workflow.prompt")))))
        (is (fs/directory? (fs/path host "projects")))
        (is (fs/exists? (fs/path host "packs/six-pack/swarmforge/swarmforge.conf")))
        (is (= "build/\n.idea/\n" (slurp (str (fs/path host "packs/six-pack/gitignore")))))
        (is (= "Kotlin\n" (slurp (str (fs/path host "packs/six-pack/language.conf")))))
        (is (not (fs/exists? (fs/path host "packs/two-pack/gitignore"))))
        (is (not (fs/exists? (fs/path host "packs/two-pack/language.conf"))))
        (is (fs/exists? (fs/path host "swarm"))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)))))
