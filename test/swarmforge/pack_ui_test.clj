(ns swarmforge.pack-ui-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests use-fixtures]]))

(def six-pack-roles ["specifier" "coder" "cleaner" "architect" "hardender" "QA"])

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))
(def temp-dirs (atom []))

(use-fixtures :once
  (fn [tests]
    (try
      (tests)
      (finally
        (doseq [dir @temp-dirs]
          (fs/delete-tree dir))))))

(defn script [name]
  (str (fs/path scripts-dir name)))

(defn tmp-dir []
  ;; A short base rather than TMPDIR: tmux refuses a socket path past the unix limit,
  ;; and TMPDIR's /var/folders path is most of that budget (see script_test.clj).
  (let [dir (fs/create-temp-dir {:dir "/tmp" :prefix "sf-pack-ui."})]
    (swap! temp-dirs conj dir)
    dir))

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

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn pack-worktree [root roles role]
  (if (= role (first roles))
    (str root)
    (str (fs/path root ".worktrees" role))))

(defn setup-pack!
  ([root] (setup-pack! root ["specifier"]))
  ([root roles] (setup-pack! root roles {}))
  ([root roles propagation]
   (write-file
    (fs/path root ".swarmforge/roles.tsv")
    (apply str
           (map-indexed
            (fn [i role]
              (format "%s\t%s\t%s\t%s\t%s\tcodex\ttask\t%s\n"
                      role
                      (if (zero? i) "master" role)
                      (pack-worktree root roles role)
                      role
                      (str/capitalize role)
                      (get propagation role "forward-only")))
            roles)))
   (doseq [role roles
           dir [".swarmforge/handoffs/outbox"
                ".swarmforge/handoffs/sent"
                ".swarmforge/handoffs/failed"
                ".swarmforge/handoffs/inbox/new"]]
     (fs/create-dirs (fs/path (pack-worktree root roles role) dir)))
   (fs/create-dirs (fs/path root ".swarmforge/handoffs/pending_approval"))))

(defn init-project-git! [project]
  (run {:dir project} "git" "init" "-q")
  (run {:dir project} "git" "add" "-A")
  (run {:dir project} "git"
       "-c" "user.name=Fixture"
       "-c" "user.email=fixture@test.invalid"
       "commit" "-q" "--allow-empty" "-m" "fixture"))

(defn pack-board
  ([root ok? & args]
   (apply run {:dir root :ok? ok?} (script "pack_board.sh") args)))

(defn pack-web
  ([root ok? & args]
   (apply run {:dir root :ok? ok?} (script "pack_web.sh") args)))

(defn pack-web-env
  [root env & args]
  (apply run {:dir root :env env} (script "pack_web.sh") args))

(defn set-backend!
  [root backend]
  (let [file (fs/path root ".swarmforge/roles.tsv")]
    (spit (str file)
          (str/replace (slurp (str file)) #"\tcodex\t" (str "\t" backend "\t")))))

(defn read-argv [path]
  (when (fs/exists? path)
    (->> (str/split-lines (slurp (str path)))
         (remove str/blank?)
         (mapv read-string))))

(defn completion-project!
  [forge name]
  (let [project (fs/path forge "projects" name)]
    (fs/create-dirs (fs/path project ".swarmforge" "board"))
    (write-file (fs/path project "stages.edn")
                (pr-str {:task name :stages []}))
    (write-file (fs/path forge ".swarmforge/open-projects")
                (str name "\n"))
    project))

(defn completion-row
  [name lane created updated task-id]
  (str/join "\t" [name lane created updated task-id "0"]))

(defn write-completion-board!
  [project rows]
  (write-file (fs/path project ".swarmforge/board/tasks.tsv")
              (str (str/join "\n" rows) "\n")))

(defn write-activation!
  [project task-id activated-at]
  (write-file (fs/path project ".swarmforge/card-activations"
                        (str task-id ".edn"))
              (pr-str {:task_id task-id :activated_at activated-at})))

(defn completed-card-report
  [forge ok? & args]
  (apply run {:dir forge :ok? ok?}
         (script "completed_card_report.sh")
         args))

(defn write-completed-card-report!
  [forge report-id cards]
  (let [file (fs/path forge "tmp" (str report-id ".edn"))]
    (write-file file
                (pr-str {:version 1
                         :report_id report-id
                         :reported_at "2026-01-03T12:00:00Z"
                         :family "forgelet"
                         :lieutenant "lieutenant"
                         :cards cards}))
    file))

(defn done-count [state project]
  (count (filter #(= "done" (:lane %))
                 (:tasks (first (filter #(= project (:name %))
                                        (:projects state)))))))

(defn seed-dashboard-scale-fixture! [forge]
  (setup-pack! forge ["lieutenant"])
  (fs/create-dirs (fs/path forge "projects"))
  (let [projects (mapv #(format "project-%02d" %) (range 13))
        rows-per-project (mapv #(if (< % 4) 33 32) (range 13))
        _ (write-file (fs/path forge ".swarmforge" "open-projects")
                      (apply str (map #(str % "\n") projects)))]
    (doseq [[project task-count] (map vector projects rows-per-project)]
      (let [root (fs/path forge "projects" project)
            board (fs/path root ".swarmforge" "board" "tasks.tsv")
            rows (mapv (fn [index]
                         (let [lane (cond
                                      (zero? index) "spec"
                                      (= 1 index) "code"
                                      (= 2 index) "queued"
                                      :else "done")]
                           (str/join
                            "\t"
                            [(str "card-" project "-" index)
                             lane
                             "2026-01-01T00:00:00Z"
                             (str "2026-01-01T00:"
                                  (format "%02d" (mod index 60))
                                  ":00Z")
                             (str "task-" project "-" index)
                             "0"])))
                       (range task-count))]
        (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
        (write-file (fs/path root ".swarmforge" "roles.tsv")
                    (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                            root))
        (write-file (fs/path root "stages.edn")
                    (pr-str {:task project
                             :stages [{:name "spec"}
                                      {:name "code"}]}))
        (write-file board (str (str/join "\n" rows) "\n"))))
    (doseq [index (range 450)]
      (let [status (if (zero? (mod index 3)) "pending" "done")
            id (format "req-scale-%03d" index)]
        (write-file
         (fs/path forge ".swarmforge" "dashboard" "requests" status
                  (str id ".request"))
         (str "id: " id "\n"
              "status: " status "\n"
              "created_at: 2026-01-01T00:00:00Z\n"
              "source: app.lieutenant-chat\n"
              "thread_id: conversation-" index "\n"
              (when (= status "done")
                "response: completed response\\n\n")
              "\n"
              "Scale fixture chat record " index ": "
              (apply str (repeat 1700 "x"))
              "\n"))))
    forge))

(defn create-task
  ([root name lane] (create-task root name lane true))
  ([root name lane ok?]
   (pack-board root ok?
               "create"
               "--root" (str root)
               "--name" name
               "--lane" lane
               "--text" "Integrate HTW stories")))

(defn list-tasks [root]
  (pack-board root true "list" "--root" (str root)))

(defn task-row [listed name]
  (some #(when (str/starts-with? % (str name "\t")) %)
        (str/split-lines listed)))

(defn task-lane [root name]
  (let [cols (str/split (or (task-row (:out (list-tasks root)) name) "") #"\t")]
    (nth cols 1 nil)))

(defn increment-audit! [root task-id]
  (pack-board root true "increment-audit" "--root" (str root) "--task-id" task-id))

(defn queue-handoff! [root {:keys [from to task task-id artifacts non-forwarding non-terminal priority body]}]
  (let [priority (or priority "50")]
    (write-file
     (fs/path root ".swarmforge/handoffs/outbox"
              (str priority "_from_" from "_to_" (str/replace to #"," "_") ".handoff"))
     (str "from: " from "\n"
          "to: " to "\n"
          "priority: " priority "\n"
          "type: git_handoff\n"
          "task: " task "\n"
          (when task-id (str "task_id: " task-id "\n"))
          (when artifacts (str "artifacts: " artifacts "\n"))
          (when non-forwarding "non-forwarding: true\n")
          (when non-terminal "non-terminal: true\n")
          "\n"
          (or body "payload") "\n"))))

(defn handoff-names [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".handoff"))
         (mapv #(fs/file-name %)))
    []))

(defn pending-names [root]
  (handoff-names (fs/path root ".swarmforge/handoffs/pending_approval")))

(defn write-pending-audit! [root task-id]
  (write-file
   (fs/path root ".swarmforge/handoffs/audit_pending/sender" (str task-id ".edn"))
   (str (pr-str {:candidate {:version 1
                             :sender "specifier"
                             :task-id task-id
                             :type "git_handoff"}})
        "\n")))

(defn pending-audits [root]
  (let [dir (fs/path root ".swarmforge/handoffs/audit_pending")]
    (if (fs/directory? dir)
      (vec (fs/glob dir "**/*.edn"))
      [])))

(defn pending-audit-task-ids [root]
  (->> (pending-audits root)
       (map #(get-in (edn/read-string (slurp (str %))) [:candidate :task-id]))
       set))

(defn inbox-names [root roles role]
  (handoff-names (fs/path (pack-worktree root roles role)
                          ".swarmforge/handoffs/inbox/new")))

(defn in-process-dir [root roles role]
  (fs/path (pack-worktree root roles role)
           ".swarmforge/handoffs/inbox/in_process"))

(defn put-in-process! [root roles role {:keys [from task filename]}]
  (write-file
   (fs/path (in-process-dir root roles role)
            (or filename (str "50_from_" from "_to_" role ".handoff")))
   (str "from: " from "\n"
        "to: " role "\n"
        "priority: 50\n"
        "type: git_handoff\n"
        "task: " task "\n"
        "\n"
        "payload\n")))

(defn web-state [root]
  (json/parse-string (:out (pack-web root true "--test-state" (str root))) true))

(defn paged-web-state [root query]
  (json/parse-string
   (:out (pack-web root true "--test-state-query" (str root) query))
   true))

(defn chat-timestamp [n]
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (.plusSeconds (java.time.Instant/parse "2026-11-10T00:00:00Z")
                         (long n))))

(defn write-chat-turn!
  [root {:keys [id status created-at thread-id source proposal-id body]}]
  (let [state (if (= status "pending") "pending" "done")
        file (fs/path root ".swarmforge/dashboard/requests" state
                      (str id ".request"))]
    (write-file
     file
     (str "id: " id "\n"
          "status: " status "\n"
          (when thread-id (str "thread_id: " thread-id "\n"))
          (when source (str "source: " source "\n"))
          (when proposal-id (str "proposal_id: " proposal-id "\n"))
          "created_at: " created-at "\n"
          (when (= status "done") "response: answer\\nline\n")
          "\n"
          (or body (str "body " id))
          "\n"))))

(defn task-card [root name]
  (some #(when (= name (:name %)) %) (:tasks (web-state root))))

(defn start-tmux! [root sessions]
  (let [sock (str (fs/path root "tmux.sock"))]
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (doseq [session sessions]
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" session "sleep" "120"))
    sock))

(defn start-tmux-command!
  [root session window command]
  (let [sock (str (fs/path root "tmux.sock"))]
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" session
         "sh" "-c" command)
    (run {:dir root} "tmux" "-S" sock "rename-window" "-t" (str session ":0")
         window)
    sock))

(defn wait-for-file-size [file size]
  (let [deadline (+ (System/nanoTime) 15000000000)]
    (loop []
      (if (and (fs/regular-file? file)
               (>= (fs/size file) size))
        true
        (do
          (when (> (System/nanoTime) deadline)
            (throw (ex-info (str "Timed out waiting for " file)
                            {:expected-size size
                             :actual-size (when (fs/exists? file) (fs/size file))})))
          (Thread/sleep 50)
          (recur))))))

(defn stop-tmux! [sock]
  (run {:dir "." :ok? false} "tmux" "-S" sock "kill-server"))

(defn handoffd-once
  ([root] (handoffd-once root nil))
  ([root env]
   (run {:dir root :env env} "bb" (script "handoffd.bb") "--once" (str root))))

(defn pane-path [root role task]
  (fs/path root ".swarmforge/sessions" role task "pane.txt"))

(defn role-pane-path [root role]
  (fs/path root ".swarmforge/sessions" role "pane.txt"))

(deftest pack-board-creates-a-task-in-the-master-lane
  ;; Given a pack with specifier on master
  ;; When New Task records name htw-console-app
  ;; Then the card sits in lane specifier
  (let [root (tmp-dir)
        _ (setup-pack! root)
        created (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        on-disk (slurp (str (fs/path root ".swarmforge/board/tasks.tsv")))
        cols (str/split (or (task-row listed "htw-console-app") "") #"\t")]
    (is (zero? (:exit created)))
    (is (= listed on-disk))
    (is (= "htw-console-app" (nth cols 0 nil)))
    (is (= "specifier" (nth cols 1 nil)))
    (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (nth cols 2 "")))
    (is (= (nth cols 2 nil) (nth cols 3 nil)))
    (is (= "0" (nth cols 5 nil)))))

(deftest new-task-writes-the-card-and-body
  ;; Given specifier is master
  ;; When create name=htw-console-app text="Integrate HTW stories…"
  ;; Then lane is specifier AND board/htw-console-app.txt has the text
  (let [root (tmp-dir)
        text "Integrate HTW stories…"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"))
    (let [created (pack-board root true
                              "create"
                              "--root" (str root)
                              "--name" "htw-console-app"
                              "--lane" "specifier"
                              "--text" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit created)))
      (is (= "specifier" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (= (str "# htw-console-app\n\n" text "\n")
             (slurp (str (fs/path root "tasks/htw-console-app.md"))))))))

(deftest pack-board-serializes-concurrent-audit-increments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        increments (doall (repeatedly 8 #(future (increment-audit! root task-id))))]
    (doseq [increment increments]
      @increment)
    (is (= 8 (:audit_count (task-card root "HTW"))))))

(deftest pack-board-lists-lanes-in-role-order
  ;; Given roles specifier, coder, QA
  ;; When pack_board lanes
  ;; Then it prints those roles in conf order
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier" "coder" "QA"])
        result (pack-board root true "lanes" "--root" (str root))]
    (is (= "specifier\ncoder\nQA\n" (:out result)))))

(deftest pack-board-reports-the-master-lane
  ;; Given specifier's worktree is master
  ;; When pack_board master-lane
  ;; Then it prints specifier
  (let [root (tmp-dir)]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"
          "coder\tcoder\t" root "/.worktrees/coder\tsession\tCoder\tcodex\ttask\n"))
    (let [result (pack-board root true "master-lane" "--root" (str root))]
      (is (= "specifier\n" (:out result))))))

(deftest pack-board-rejects-a-duplicate-task-name
  ;; Given a card named htw-console-app
  ;; When New Task records the same name again
  ;; Then the create is rejected and the original card is unchanged
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "htw-console-app" "specifier")
        before (:out (list-tasks root))
        duplicate (create-task root "htw-console-app" "specifier" false)
        after (:out (list-tasks root))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= before after))))

(deftest handoffd-moves-the-task-card-to-the-recipient
  ;; Given card htw-console-app in coder
  ;; When a git_handoff coder→cleaner for that task is delivered
  ;; Then the card lane is cleaner
  (let [root (tmp-dir)
        roles ["specifier" "coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= 1 (:audit_count (task-card root "htw-console-app"))))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-marks-the-task-card-done-for-terminal-handoff
  ;; Given six-pack, card in QA (not master)
  ;; When QA queues git_handoff to every other role
  ;; Then the card lane is done
  (let [root (tmp-dir)
        to "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to to :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))

(def four-pack-roles ["specifier" "coder" "refactorer" "architect"])
(def reverse-structure-body
  (str "Re-read your role and constitution.\n\n"
       "merge_and_process.sh refactorer abcdef1234\n\n"
       "The inbound tree is the structure. Replay this role's current task onto that shape."))

(deftest handoffd-refactorer-back-one-does-not-done-or-hold
  ;; Given four-pack, card in refactorer, reverse copy to coder and forward to architect
  ;; When handoffd delivers
  ;; Then coder gets the 00 reverse file, lane is architect, Attention is empty, card is not Done
  (let [root (tmp-dir)
        roles four-pack-roles
        sock (do (setup-pack! root roles {"refactorer" "back-one" "architect" "back-all"})
                 (create-task root "HTW" "refactorer")
                 (write-file
                  (fs/path (pack-worktree root roles "coder")
                           ".swarmforge/handoffs/inbox/new/50_next_card.handoff")
                  (str "from: specifier\nto: coder\npriority: 50\ntype: note\n"
                       "message: next card\n\nnote\n"))
                 (queue-handoff! root {:from "refactorer" :to "architect" :task "HTW"
                                       :priority "50"})
                 (queue-handoff! root {:from "refactorer" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true
                                       :body reverse-structure-body})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (let [coder-mail (sort (inbox-names root roles "coder"))
            delivered (slurp (str (fs/path (pack-worktree root roles "coder")
                                           ".swarmforge/handoffs/inbox/new"
                                           (first coder-mail))))]
        (is (str/starts-with? (first coder-mail) "00_"))
        (is (str/starts-with? (second coder-mail) "50_"))
        (is (str/includes? delivered "merge_and_process.sh refactorer"))
        (is (str/includes? delivered "inbound tree is the structure"))
        (is (str/includes? delivered "non-forwarding: true")))
      (is (seq (inbox-names root roles "architect")))
      (is (= [] (pending-names root)))
      (is (= "architect" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-four-pack-architect-back-all-dones-because-last
  (let [root (tmp-dir)
        roles four-pack-roles
        sock (do (setup-pack! root roles {"refactorer" "back-one" "architect" "back-all"})
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "refactorer"]]
                   (queue-handoff! root {:from "architect" :to role :task "HTW"
                                         :priority "00" :non-forwarding true
                                         :body reverse-structure-body}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "refactorer"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-six-pack-architect-back-all-moves-to-hardender
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect" :to "hardender" :task "HTW"
                                       :priority "50"})
                 (doseq [role ["specifier" "coder" "cleaner"]]
                   (queue-handoff! root {:from "architect" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner"]]
        (is (seq (inbox-names root roles role)) role))
      (is (seq (inbox-names root roles "hardender")))
      (is (= [] (inbox-names root roles "QA")))
      (is (= "hardender" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-six-pack-qa-back-all-dones-because-last
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "QA")
                 (queue-handoff! root {:from "QA" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
                   (queue-handoff! root {:from "QA" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-a-non-terminal-handback-keeps-the-card-open
  ;; Given the last role in the pack handing a card back with a finding
  ;; When the daemon reads the handoff
  ;; Then the card is not called done - a finding is not a completion, whatever
  ;; role sent it - and it goes to the role that must act, so it can still take
  ;; handoffs where the finding has to be fixed
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "QA")
                 (queue-handoff! root {:from "QA" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true
                                       :non-terminal true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "coder")) "the finding still reaches the role that must act")
      (is (= "coder" (task-lane root "HTW")) "the card goes to that role")
      (is (not= "done" (task-lane root "HTW")) "and is not called done")
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-two-pack-cleaner-back-one-dones-because-last
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles {"cleaner" "back-one"})
                 (create-task root "HTW" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "coder")))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-exposes-dashboard-state-from-conf-and-board
  ;; Given a six-pack with specifier as master and a board card
  ;; When pack_web --test-state
  ;; Then JSON includes lanes from conf, the master display name, and the card
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        updated (nth (str/split (or (task-row listed "htw-console-app") "") #"\t") 3 nil)
        result (pack-web root true "--test-state" (str root))
        state (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= "specifier" (:master_role state)))
    (is (= "Specifier" (:master_display state)))
    (is (= six-pack-roles (:lanes state)))
    (let [card (first (:tasks state))]
      (is (= "htw-console-app" (:name card)))
      (is (str/starts-with? (:id card) "20"))
      (is (= "specifier" (:lane card)))
      (is (= updated (:updated_at card)))
      (is (= 0 (:audit_count card)))
      (is (= "" (:status card))))
    (is (= [] (:approvals state)))
    (is (= six-pack-roles (mapv :role (:work_in_flight state))))))

(deftest dashboard-health-is-constant-small-and-scan-free
  ;; Given zero, one and many projects
  ;; When the liveness route runs
  ;; Then it returns the same tiny shape without scanning dashboard state
  (let [empty-forge (tmp-dir)
        one-project (doto (tmp-dir) setup-pack!)
        many-projects (doto (tmp-dir) seed-dashboard-scale-fixture!)
        results (mapv
                 (fn [root]
                   (let [result (pack-web root true "--test-health-metrics")
                         body (json/parse-string (:out result) true)]
                     (is (zero? (:exit result)))
                     (is (< (:milliseconds body) 200))
                     body))
                 [empty-forge one-project many-projects])]
    (is (= 1 (count (set (map #(json/generate-string %) (map :body results))))))
    (is (every? #(= {} (:metrics %)) results))
    (is (every? #(= 23 (:bytes %)) results))
    (is (= {:ok true :live true} (:body (first results))))))

(deftest dashboard-health-remains-available-during-slow-state
  ;; Given a deliberately slow authoritative state scan
  ;; When health runs while that scan is pending
  ;; Then health still returns independently without state work
  (let [root (doto (tmp-dir) setup-pack!)
        result (pack-web root true "--test-health-during-slow-state" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:health_ms body) 200))
    (is (true? (:state_pending_at_health body)))
    (is (= {:ok true :live true} (:health_body body)))
    (is (= {:slow_state true} (:state_body body)))
    (is (= {} (:metrics body)))))

(deftest dashboard-state-reuses-one-task-view-per-project
  ;; Given a measured forge snapshot
  ;; When its state is assembled
  ;; Then each project board and task view is built once
  (let [root (doto (tmp-dir) setup-pack!)
        _ (create-task root "first-card" "specifier")
        result (pack-web root true "--test-dashboard-benchmark" (str root) "1")
        body (json/parse-string (:out result) true)
        metrics (:metrics body)]
    (is (zero? (:exit result)))
    (is (= 1 (:board-task-reads metrics)))
    (is (= 1 (:task-view-builds metrics)))
    (is (= 1 (:work-view-builds metrics)))
    (is (zero? (long (or (:board-subprocesses metrics) 0))))
    (is (pos? (first (:state_bytes body))))
    (is (< (:health_p95_ms body) 200))))

(deftest dashboard-state-meets-saibill-scale-budget
  ;; Given 13 projects, 420 tasks and 450 chat records
  ;; When health and full state are measured repeatedly
  ;; Then health stays probe-safe and full state stays near the one-second budget
  (let [forge (doto (tmp-dir) seed-dashboard-scale-fixture!)
        result (pack-web forge true "--test-dashboard-benchmark" (str forge) "7")
        body (json/parse-string (:out result) true)
        state (web-state forge)
        projects (:projects state)]
    (is (zero? (:exit result)))
    (is (= 7 (:iterations body)))
    (is (< (:health_p95_ms body) 200))
    (is (< (:state_p95_ms body) 1000)
        (str "state latencies: " (:state_ms body)))
    (is (every? pos? (:state_bytes body)))
    (is (<= 900000 (apply min (:state_bytes body))))
    (is (= 13 (get-in body [:metrics :task-view-builds])))
    (is (= 13 (get-in body [:metrics :board-task-reads])))
    (is (= 450 (get-in body [:metrics :chat-file-reads])))
    (is (zero? (long (or (get-in body [:metrics :board-subprocesses]) 0))))
    (is (= 13 (count projects)))
    (is (= 420 (reduce + (map (comp count :tasks) projects))))
    (is (= 450 (count (:chat state))))
    (is (= (mapv #(format "project-%02d" %) (range 13))
           (mapv :name projects)))))

(deftest dashboard-state-fails-whole-instead-of-returning-partial-projects
  ;; Given one malformed open project among otherwise valid projects
  ;; When the authoritative state is requested
  ;; Then it returns a bounded error rather than a partial snapshot
  (let [forge (tmp-dir)]
    (setup-pack! forge ["lieutenant"])
    (fs/create-dirs (fs/path forge "projects"))
    (doseq [name ["alpha" "broken"]]
      (let [root (fs/path forge "projects" name)]
        (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
        (write-file (fs/path root ".swarmforge" "roles.tsv")
                    (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                            root))
        (write-file (fs/path root "stages.edn")
                    (if (= name "broken")
                      "{:task :broken :stages ["
                      (pr-str {:task name
                               :stages [{:name "spec"} {:name "code"}]})))
        (write-file (fs/path root ".swarmforge" "board" "tasks.tsv") "")))
    (write-file (fs/path forge ".swarmforge" "open-projects")
                "alpha\nbroken\n")
    (let [result (run {:dir forge :ok? false}
                      (script "pack_web.sh")
                      "--test-state-query" (str forge) "")
          output (str (:err result) (:out result))]
      (is (not (zero? (:exit result))))
      (is (< (count output) 2000))
      (is (not (str/includes? output "\"projects\""))))))







(deftest pack-web-post-task-creates-a-card-in-the-master-lane
  ;; Given a pack whose master role is coder
  ;; When POST /api/tasks records name and text
  ;; Then the card sits in lane coder with that body
  (let [root (tmp-dir)
        text "Integrate HTW stories"]
    (setup-pack! root ["coder" "cleaner"])
    (let [result (pack-web root true "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit result)))
      (is (= "coder" (task-lane root "htw-console-app")))
      (is (= text body)))))

(deftest handoffd-delivers-new-task-note-without-moving-the-card
  ;; Given a New Task note in the project outbox
  ;; When handoffd --once
  ;; Then specifier inbox has it, the card stays in specifier, and sent is on master
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (pack-web root true "--test-post-task" (str root) "HTW" "Print hello")
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "specifier" (task-lane root "HTW")))
      (is (= [] (pending-names root)))
      (is (seq (inbox-names root roles "specifier")))
      (is (seq (handoff-names (fs/path root ".swarmforge/handoffs/sent"))))
      (is (empty? (handoff-names (fs/path (pack-worktree root roles "coder")
                                         ".swarmforge/handoffs/sent"))))
      (finally
        (stop-tmux! sock)))))

(deftest specifier-git-handoff-waits-for-attention
  ;; Given six-pack-shaped roles + card in specifier
  ;; When specifier→coder is queued and handoffd --once
  ;; Then file is in pending_approval, coder inbox empty, pack_web --test-state approvals has the task
  (let [root (tmp-dir)
        artifacts "features/console.feature,qa/console.md"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts artifacts})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [state (web-state root)]
        (is (= ["50_from_specifier_to_coder.handoff"] (pending-names root)))
        (is (= [] (inbox-names root six-pack-roles "coder")))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (first (:tasks state)))))
        (is (= [{:id "50_from_specifier_to_coder"
                 :gate "spec → coder"
                 :task_id "htw-console-app"
                 :task "htw-console-app"
                 :artifacts []
                 :reviews {}}]
               (:approvals state))))
      (finally
        (stop-tmux! sock)))))

(deftest two-pack-git-handoff-does-not-wait
  ;; Given coder master, cleaner next, no specifier
  ;; When coder→cleaner queued + --once
  ;; Then delivered to cleaner; approvals empty
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "cleaner")))
      (is (= [] (pending-names root)))
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= [] (:approvals (web-state root))))
      (finally
        (stop-tmux! sock)))))

(deftest two-pack-end-broadcast-marks-the-card-done
  ;; Given two-pack, card in cleaner
  ;; When cleaner queues git_handoff to coder (every other role)
  ;; Then the card is done and coder inbox has the file
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "coder")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-end-broadcast-marks-the-card-done
  ;; Given four-pack, card in architect
  ;; When architect queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier,coder,refactorer"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "specifier")))
      (is (seq (inbox-names root roles "coder")))
      (is (seq (inbox-names root roles "refactorer")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-last-role-git-handoff-is-done
  ;; Given four-pack, card in architect
  ;; When architect queues git_handoff to specifier,coder (not every other role)
  ;; Then the card is done because architect is last
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier,coder"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-one-recipient-non-forwarding-is-done
  ;; Given four-pack, card in architect
  ;; When architect queues a non-forwarding git_handoff to specifier only
  ;; Then the card is done, not moved to specifier
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier"
                                       :task "HTW"
                                       :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (seq (inbox-names root roles "specifier")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-dones-finished-batch-cards-in-sender-lane
  ;; Given two-pack, Command syntax and validation in cleaner, those names in a
  ;; completed cleaner batch, HTW still in cleaner but not in that batch
  ;; When cleaner queues a terminal git_handoff named HTW
  ;; Then Command syntax and validation are done and HTW is done
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (pack-worktree root roles "cleaner")
                       ".swarmforge/handoffs/inbox/completed"
                       "batch_20260824T150500Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (create-task root "validation" "cleaner")
                 (write-file (fs/path batch "50_command.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
                 (write-file (fs/path batch "50_validation.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "done" (task-lane root "Command syntax")))
      (is (= "done" (task-lane root "validation")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-leaves-unfinished-lane-cards
  ;; Given two-pack, HTW finished in a completed batch, Command syntax only in the lane
  ;; When cleaner terminals with task HTW
  ;; Then HTW is done and Command syntax stays in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        done (fs/path (pack-worktree root roles "cleaner")
                      ".swarmforge/handoffs/inbox/completed")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path done "50_htw.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-dones-in-process-batch-cards
  ;; Given two-pack, one liners/validate/HHG in an in-process cleaner batch,
  ;; and Command syntax in cleaner but not in that batch
  ;; When cleaner terminals with task one liners before done_with_current
  ;; Then the three batch cards are done and Command syntax stays in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (in-process-dir root roles "cleaner")
                       "batch_20260824T202830Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "one liners" "cleaner")
                 (create-task root "validate" "cleaner")
                 (create-task root "Holy Hand Grenade" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path batch "50_oneliners.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: one liners\n\npayload\n")
                 (write-file (fs/path batch "50_validate.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validate\n\npayload\n")
                 (write-file (fs/path batch "50_hhg.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Holy Hand Grenade\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "one liners"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "one liners")))
      (is (= "done" (task-lane root "validate")))
      (is (= "done" (task-lane root "Holy Hand Grenade")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))

(deftest six-pack-qa-broadcast-marks-the-card-done
  ;; Given six-pack, card in QA
  ;; When QA queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        others "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to others :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root six-pack-roles "specifier")))
      (is (seq (inbox-names root six-pack-roles "hardender")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest attention-approve-delivers-the-handoff
  ;; Given pending approval
  ;; When pack_web --test-approve <root> <id>
  ;; Then coder inbox has the file, card lane coder (handoffd --once after approve)
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts "features/console.feature"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))]
        (pack-web root true "--test-approve" (str root) id)
        (handoffd-once root)
        (is (seq (inbox-names root six-pack-roles "coder")))
        (is (= "coder" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (= [] (pending-names root)))
        (is (= [] (:approvals (web-state root)))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-resolution-is-written-down-how-it-ended
  ;; Given a pending approval
  ;; When the dashboard approves it away from the room
  ;; Then the state says how it ended, worded the way the bridge words it
  ;; (approved), because a reader that did not resolve it - the bridge's room - has
  ;; nothing else to report from and says "resolved on the desktop" for both endings
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :task-id (:id (task-card root "htw-console-app"))})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= [] (:resolved_approvals (web-state root))))
      (let [id (:id (first (:approvals (web-state root))))]
        (pack-web root true "--test-approve" (str root) id)
        (let [resolved (:resolved_approvals (web-state root))]
          (is (= [id] (mapv :id resolved)))
          (is (= ["htw-console-app"] (mapv :task resolved)))
          (is (= ["approved"] (mapv :resolution resolved)))
          (is (seq (:at (first resolved))))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-send-back-is-written-down-how-it-ended
  ;; Given a pending approval
  ;; When the dashboard sends it back with words
  ;; Then the state says so, worded as the bridge words it (sent_back), so the room can
  ;; answer with the left arrow rather than saying it was resolved on the desktop
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :task-id (:id (task-card root "htw-console-app"))})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))]
        (pack-web root true "--test-retry-task" (str root) id "not this way")
        (let [resolved (:resolved_approvals (web-state root))]
          (is (= [id] (mapv :id resolved)))
          (is (= ["sent_back"] (mapv :resolution resolved)))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-reject-returns-to-master
  ;; Given pending
  ;; When --test-reject
  ;; Then the pending approval is unchanged because Reject only opens the dialog
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))
            result (pack-web root false "--test-reject" (str root) id)]
        (is (not (zero? (:exit result))))
        (is (seq (pending-names root)))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (seq (:approvals (web-state root))))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app")))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-reject-preserves-branch-and-rolls-back-head
  ;; Given a pending approval with task identity and a task base commit
  ;; When it is rejected
  ;; Then rejected work is preserved off the active branch and pending state is cleared
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root six-pack-roles)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (first (:tasks (web-state root))))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-pending-audit! root task-id)
      (write-pending-audit! root "unrelated-id")
      (write-file (fs/path root "story.md") "rejected\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Rejected work")
      (let [rejected (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")]
        (write-file pending
                    (str "from: specifier\n"
                         "to: coder\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task_id: " task-id "\n"
                         "task: htw-console-app\n"
                         "commit: " rejected "\n"
                         "task_base_commit: " base "\n"
                         "\n"
                         "payload\n"))
        (let [result (pack-web root false "--test-delete-approval" (str root) "50_from_specifier_to_coder")
              head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (zero? (:exit result)))
          (is (= base head))
          (is (str/includes? branches (str "rejected/" task-id "/latest")))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (not (fs/exists? pending)))
          (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
          (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id)))
          (is (nil? (task-lane root "htw-console-app"))))))))

(def example-task-text
  "Integrate the stories in ~/junk/htw-stories into one console application.")

(def example-task-payload
  (str "Task: htw-console-app\n\n" example-task-text))

(deftest inject-payload-formats-task-name-and-body
  ;; Given the New Task example name and body
  ;; When pack_web --test-inject-payload
  ;; Then it prints Task: name, a blank line, and the body
  (let [result (pack-web (tmp-dir) false "--test-inject-payload")]
    (is (zero? (:exit result)))
    (is (= (str example-task-payload "\n") (:out result)))))

(deftest pack-web-post-task-creates-a-card-when-tmux-is-missing
  ;; Given no tmux socket or live session
  ;; When POST /api/tasks via --test-post-task
  ;; Then inject failure is ignored and the card is still created
  (let [root (tmp-dir)
        text example-task-text]
    (setup-pack! root ["coder" "cleaner"])
    (let [result (pack-web root false "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit result)))
      (is (= "coder" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (str/includes? (slurp (str (fs/path root "tasks/htw-console-app.md")))
                         text)))))

(deftest pack-web-inject-failure-logs-the-role
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-post-chat" (str root) "hello master")]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:err result)) "inject failed"))
      (is (str/includes? (str (:err result)) "coder")))))

(deftest inject-master-records-the-wake-argv
  ;; Given master session swarmforge-specifier in roles.tsv
  ;; When --test-inject-argv records the would-be tmux argv
  ;; Then the wake goes in as one paste of that text, then one Enter, and it is
  ;; never typed - a typed wake is what a terminal still reading it swallows the
  ;; Enter of, which leaves the whole request in the composer unsent
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "hello from operator"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web root false "--test-inject-argv" (str root) argv-file text)
          argv (read-argv argv-file)
          stdin (slurp (str argv-file ".stdin"))]
      (is (zero? (:exit result)))
      (is (= ["tmux" "-S" sock "load-buffer" "-b" "swarmforge-wake" "-"]
             (first argv)))
      (is (= text stdin))
      (is (= ["tmux" "-S" sock "paste-buffer" "-d" "-p" "-b" "swarmforge-wake"
              "-t" "swarmforge-specifier:Specifier.0"]
             (second argv)))
      (is (= ["tmux" "-S" sock "send-keys" "-t" "swarmforge-specifier:Specifier.0" "C-m"]
             (nth argv 2)))
      (is (= 3 (count argv)))
      (is (not-any? #(str/includes? (str %) text) argv))
      (is (empty? (filter #(some #{"-l"} %) argv))))))

(deftest pack-web-post-task-queues-a-note-for-master
  ;; Given a specifier pack and a tmux argv stub
  ;; When POST /api/tasks records name and text
  ;; Then the card is in specifier, a (New Task) note is in the outbox, and the pane is not injected
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text example-task-text]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-task" (str root) "htw-console-app" text)
          queued (handoff-names (fs/path root ".swarmforge/handoffs/outbox"))
          content (when (seq queued)
                    (slurp (str (fs/path root ".swarmforge/handoffs/outbox" (first queued)))))]
      (is (zero? (:exit result)))
      (is (= "specifier" (task-lane root "htw-console-app")))
      (is (= 1 (count queued)))
      (is (str/includes? (str content) "from: (New Task)\n"))
      (is (str/includes? (str content) "to: specifier\n"))
      (is (str/includes? (str content) "type: note\n"))
      (is (str/includes? (str content) "task: htw-console-app\n"))
      (is (str/includes? (str content) text))
      (is (empty? (read-argv argv-file))))))

(deftest pack-web-post-chat-injects-text-as-is
  ;; Given a tmux argv stub
  ;; When POST /api/chat {text}
  ;; Then inject-master! send-keys that text, not a Task payload
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "Please add a --help flag"]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-chat" (str root) text)
          argv (read-argv argv-file)
          stdin (slurp (str argv-file ".stdin"))]
      (is (zero? (:exit result)))
      (is (str/includes? stdin text))
      (is (re-find #"\[req-" stdin))
      (is (not (str/starts-with? stdin "Task:")))
      (is (= "load-buffer" (nth (first argv) 3)))
      (is (not-any? #(str/includes? (str %) text) argv))
      (is (= "paste-buffer" (nth (second argv) 3)))
      (is (= "C-m" (last (nth argv 2))))
      (is (= 3 (count argv)))
      (is (str/starts-with? (:request_id (json/parse-string (:out result) true))
                            "req-")))))

(deftest pack-web-chat-accepts-idempotent-report-metadata-and-wakes
  ;; Given a report request with deterministic request, thread and source metadata
  ;; When it is submitted and retried unchanged
  ;; Then one pending request exists, one master wake occurs, and state exposes it
  (let [root (tmp-dir)
        argv-file (fs/path root "tmux.argv")
        sock (fs/path root "tmux.sock")
        request {:text "Verify the completed cards."
                 :request_id "req-report-0123456789abcdef0123456789abcdef"
                 :thread_id "thread-0123456789abcdef0123456789abcdef"
                 :source "app.completed-card-report"}
        post! (fn []
                (pack-web-env root {"SWARMFORGE_TMUX_STUB" (str argv-file)}
                              "--test-post-chat-json" (str root)
                              (json/generate-string request)))]
    (setup-pack! root ["specifier"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [first-result (post!)
          first-argv (read-argv argv-file)
          first-response (json/parse-string (:out first-result) true)
          request-file (fs/path root ".swarmforge/dashboard/requests/pending"
                                "req-report-0123456789abcdef0123456789abcdef.request")
          stored (slurp (str request-file))
          chat (into {} (map (juxt :id identity) (:chat (web-state root))))
          second-result (post!)
          second-response (json/parse-string (:out second-result) true)
          second-argv (read-argv argv-file)]
      (is (= "req-report-0123456789abcdef0123456789abcdef"
             (:request_id first-response)))
      (is (str/includes? stored "thread_id: thread-0123456789abcdef0123456789abcdef\n"))
      (is (str/includes? stored "source: app.completed-card-report\n"))
      (is (= "thread-0123456789abcdef0123456789abcdef"
             (get-in chat ["req-report-0123456789abcdef0123456789abcdef" :thread_id])))
      (is (= "app.completed-card-report"
             (get-in chat ["req-report-0123456789abcdef0123456789abcdef" :source])))
      (is (= 3 (count first-argv)))
      (is (= first-response second-response))
      (is (= first-argv second-argv))
      (is (= 1 (count (fs/list-dir (fs/path root ".swarmforge/dashboard/requests/pending"))))))))

(deftest pack-web-chat-refuses-conflicting-request-ids
  ;; Given one accepted report request
  ;; When the id is reused with different content
  ;; Then it is refused without another write or wake
  (let [root (tmp-dir)
        argv-file (fs/path root "tmux.argv")
        sock (fs/path root "tmux.sock")
        id "req-report-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        base {:text "Verify the completed cards."
              :request_id id
              :thread_id "thread-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :source "app.completed-card-report"}
        submit! (fn [request ok?]
                  (run {:dir root
                        :ok? ok?
                        :env {"SWARMFORGE_TMUX_STUB" (str argv-file)}}
                       (script "pack_web.sh")
                       "--test-post-chat-json" (str root)
                       (json/generate-string request)))]
    (setup-pack! root ["specifier"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (submit! base true)
    (let [argv-before (read-argv argv-file)
          conflict (submit! (assoc base :text "Different report.") false)]
      (is (= 1 (:exit conflict)))
      (is (str/includes? (str (:out conflict) (:err conflict))
                         "Request id was reused with different content."))
      (is (not (str/includes? (str (:out conflict) (:err conflict)) (str root))))
      (is (= argv-before (read-argv argv-file)))
      (is (= 1 (count (fs/list-dir
                       (fs/path root ".swarmforge/dashboard/requests/pending"))))))))

(deftest completed-card-report-beyond-arg-max-uses-stdin-only
  ;; Given a report body larger than ARG_MAX
  ;; When the wake is staged through the tmux stub
  ;; Then the full body is on stdin and no tmux argument contains it
  (let [root (tmp-dir)
        argv-file (fs/path root "tmux.argv")
        sock (fs/path root "tmux.sock")
        arg-max (Long/parseLong
                 (str/trim (:out (run {:dir root} "getconf" "ARG_MAX"))))
        filler (apply str (repeat 7000 "x"))
        report (str "Completed cards:\n"
                    (str/join
                     "\n"
                     (map (fn [n]
                            (format (str "- project: huge-app; task_id: task-%03d; "
                                         "task_name: card %03d; completed_at: "
                                         "2026-11-09T20:00:00Z; notes: %s")
                                    n n filler))
                          (range 178))))
        request-id "req-report-99999999999999999999999999999999"
        request {:text report
                 :request_id request-id
                 :thread_id "thread-99999999999999999999999999999999"
                 :source "app.completed-card-report"}
        request-file (fs/path root "tmp" "arg-max-report.json")
        wake (str "[" request-id "]\n" report "\n"
                  "Answer with: pack_dashboard_request.sh answer " request-id
                  " ./tmp/answer.txt (a reply only in this pane reaches nobody).")]
    (setup-pack! root ["specifier"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file request-file (json/generate-string request))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" (str argv-file)}
                               "--test-post-chat-file" (str root)
                               (str request-file))
          argv (read-argv argv-file)
          stdin (slurp (str argv-file ".stdin"))]
      (is (> (count report) arg-max))
      (is (= request-id (:request_id (json/parse-string (:out result) true))))
      (is (= wake stdin))
      (is (= 3 (count argv)))
      (is (= "load-buffer" (nth (first argv) 3)))
      (is (not-any? #(str/includes? (str %) "- project: huge-app") argv)))))

(deftest pack-web-chat-refuses-malformed-report-metadata
  ;; Given malformed request, thread and source values
  ;; When each is submitted
  ;; Then every call is refused without a request or wake and without a path
  (let [root (tmp-dir)
        argv-file (fs/path root "tmux.argv")
        sock (fs/path root "tmux.sock")
        valid {:text "Verify the completed cards."
               :request_id "req-report-valid"
               :thread_id "thread-valid"
               :source "app.completed-card-report"}
        submit! (fn [request]
                  (run {:dir root
                        :ok? false
                        :env {"SWARMFORGE_TMUX_STUB" (str argv-file)}}
                       (script "pack_web.sh")
                       "--test-post-chat-json" (str root)
                       (json/generate-string request)))]
    (setup-pack! root ["specifier"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (doseq [request [(assoc valid :request_id "../escape")
                     (assoc valid :thread_id "/Users/operator/thread")
                     (assoc valid :source (str (fs/path root "private" "source")))
                     (assoc valid :source
                            "ghp_1234567890abcdefghijklmnopqrstuvwxyz")
                     (assoc valid :source "")]]
      (let [result (submit! request)]
        (is (= 1 (:exit result)))
        (is (not (str/includes? (str (:out result) (:err result)) (str root))))))
    (is (not (fs/directory?
              (fs/path root ".swarmforge/dashboard/requests/pending"))))
    (is (not (fs/exists? argv-file)))))

(deftest pack-web-chat-rolls-back-a-failed-metadata-wake
  ;; Given no tmux target for the master wake
  ;; When a metadata-bearing chat request is submitted
  ;; Then the failure is reported and no pending request remains
  (let [root (tmp-dir)
        argv-file (fs/path root "tmux.argv")
        request {:text "Verify the completed cards."
                 :request_id "req-report-wake-failure"
                 :thread_id "thread-wake-failure"
                 :source "app.completed-card-report"}]
    (setup-pack! root ["specifier"])
    (let [result (run {:dir root
                       :ok? false
                       :env {"SWARMFORGE_TMUX_STUB" (str argv-file)}}
                      (script "pack_web.sh")
                      "--test-post-chat-json" (str root)
                      (json/generate-string request))]
      (is (= 1 (:exit result)))
      (is (str/includes? (str (:out result) (:err result)) "Master wake failed."))
      (is (not (str/includes? (str (:out result) (:err result)) (str root))))
      (is (not (fs/exists?
                (fs/path root ".swarmforge/dashboard/requests/pending"
                         "req-report-wake-failure.request"))))
      (is (not (fs/exists? argv-file))))))

(deftest completed-card-report-beyond-argv-limit-reaches-a-real-tmux-pane
  ;; Given a completed-card report larger than the OS argument limit
  ;; When it is submitted through the metadata-bearing chat endpoint
  ;; Then stdin loads the exact wake into a real pane, and retry/conflict do not wake again
  (let [root (tmp-dir)
        capture (fs/path root "captured-wake.txt")
        request-id "req-report-e7758be367cfd93c5bb338d95c6b791b"
        thread-id "thread-fb9f3f9a9affc19f5c192e2d17352894"
        filler (apply str (repeat 140 "x"))
        report (str "Completed cards:\n"
                    (str/join
                     "\n"
                     (map (fn [n]
                            (format (str "- project: large-app; task_id: task-%03d; "
                                         "task_name: completed card %03d; "
                                         "completed_at: 2026-11-09T20:%02d:00Z; notes: %s")
                                    n n (mod n 60) filler))
                          (range 178))))
        request {:text report
                 :request_id request-id
                 :thread_id thread-id
                 :source "app.completed-card-report"}
        request-file (fs/path root "tmp" "large-report.json")
        conflict-file (fs/path root "tmp" "large-report-conflict.json")
        wake (str "[" request-id "]\n" report "\n"
                  "Answer with: pack_dashboard_request.sh answer " request-id
                  " ./tmp/answer.txt (a reply only in this pane reaches nobody).\n")
        sock (atom nil)]
    (setup-pack! root ["specifier"])
    (write-file request-file (json/generate-string request))
    (write-file conflict-file
                (json/generate-string (assoc request :text "Different report.")))
    (try
      (reset! sock (start-tmux-command!
                    root "specifier" "Specifier"
                    (str "cat > " (str capture))))
      (let [first-result (pack-web root true "--test-post-chat-file"
                                   (str root) (str request-file))]
        (is (zero? (:exit first-result)))
        (is (>= (count (re-seq #"(?m)^- project:" report)) 178))
        (is (> (count report) 29000))
        (wait-for-file-size capture (count (.getBytes wake "UTF-8")))
        (is (= wake (slurp (str capture))))
        (pack-web root true "--test-post-chat-file" (str root) (str request-file))
        (Thread/sleep 200)
        (is (= wake (slurp (str capture))))
        (let [conflict (pack-web root false "--test-post-chat-file"
                                 (str root) (str conflict-file))]
          (is (= 1 (:exit conflict)))
          (is (str/includes? (str (:out conflict) (:err conflict))
                             "Request id was reused with different content."))
          (is (not (str/includes? (str (:out conflict) (:err conflict))
                                  (str root)))))
        (Thread/sleep 200)
        (is (= wake (slurp (str capture))))
        (is (= 1 (count (fs/list-dir
                         (fs/path root ".swarmforge/dashboard/requests/pending"))))))
      (finally
        (when @sock (stop-tmux! @sock))
        (fs/delete-tree root)))))

(deftest attention-reject-injects-a-message-to-master
  ;; Given a pending approval and a tmux argv stub
  ;; When retry with comments
  ;; Then master receives those comments and no New Task note is queued
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root six-pack-roles)
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (task-card root "htw-console-app"))]
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
       (str "from: specifier\n"
            "to: coder\n"
            "priority: 50\n"
            "type: git_handoff\n"
            "task_id: " task-id "\n"
            "task: htw-console-app\n"
            "\n"
            "payload\n"))
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root)
                                 "50_from_specifier_to_coder"
                                 "use an RNG")
            argv (read-argv argv-file)
            notes (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))]
        (is (zero? (:exit result)))
        (is (= [] (pending-names root)))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app"))))
        (is (str/includes? (slurp (str argv-file ".stdin")) "use an RNG"))
        (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))
        (is (= "load-buffer" (nth (first argv) 3)))
        (is (not-any? #(str/includes? (str %) "use an RNG") argv))
        (is (= "paste-buffer" (nth (second argv) 3)))
        (is (= "C-m" (last (nth argv 2))))
        (is (= 3 (count argv)))))))

(deftest pack-web-lists-every-role-in-the-work-queue
  ;; Given a six-pack with no in_process mail
  ;; When pack_web --test-state
  ;; Then work_in_flight has one row per conf role
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        wif (:work_in_flight (web-state root))]
    (is (= six-pack-roles (mapv :role wif)))
    (is (every? #(= "no_session" (:state %)) wif))
    (is (every? #(= 0 (:activity %)) wif))))

(deftest pack-web-lists-in-process-work-in-flight
  ;; Given in_process handoff for coder task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
    (let [wif (:work_in_flight (web-state root))
          row (some #(when (= "coder" (:role %)) %) wif)]
      (is (= roles (mapv :role wif)))
      (is (= "cave-walk" (:task row)))
      (is (= "coder" (:role row)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (or (:updated_at row) ""))))))

(deftest pack-web-marks-in-process-roles-live-when-session-exists
  ;; Given coder in_process and live tmux sessions
  ;; When pack_web --test-state
  ;; Then coder is live with that task and specifier is idle
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        sock (do (setup-pack! root roles)
                 (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
                 (start-tmux! root roles))]
    (try
      (let [wif (:work_in_flight (web-state root))
            by-role (into {} (map (juxt :role identity) wif))]
        (is (= "idle" (:state (get by-role "specifier"))))
        (is (= "live" (:state (get by-role "coder"))))
        (is (= "cave-walk" (:task (get by-role "coder"))))
        (is (= "" (:task (get by-role "specifier")))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-lists-batch-in-process-in-work-in-flight
  ;; Given a batch dir in coder in_process for task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder"
                     {:from "specifier"
                      :task "cave-walk"
                      :filename "batch_20260615T000001Z_000001/50_from_specifier_to_coder.handoff"})
    (let [wif (:work_in_flight (web-state root))]
      (is (some #(and (= "cave-walk" (:task %)) (= "coder" (:role %))) wif)))))



(deftest pack-agent-page-polls-live-pane
  ;; When serving the agent session window
  ;; Then it polls /api/agents/<role>/pane
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane"))
    (is (not (str/includes? (:out result) "?project=")))
    (is (str/includes? (:out result) "setInterval(refresh"))
    (is (str/includes? (:out result) "toEndSoon"))
    (is (str/includes? (:out result) "stickBottom"))))

(deftest pack-agent-page-polls-project-pane
  ;; Given a forge project agent window
  ;; When serving the agent session page
  ;; Then it polls /api/agents/<role>/pane?project=<name>
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier" "HTW")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane?project=HTW"))))

(deftest pack-web-test-pane-prints-recorded-pane
  ;; Given a recorded pane.txt for coder task cave-walk
  ;; When pack_web --test-pane
  ;; Then it prints that text
  (let [root (tmp-dir)
        text "coder pane snapshot\n"]
    (setup-pack! root ["specifier" "coder"])
    (write-file (fs/path root ".swarmforge/sessions/coder/pane.txt") text)
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "coder pane snapshot")))))

(deftest pack-web-pane-capture-of-missing-session-is-quiet
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/blank? (str/trim (str (:err result))))))))

(deftest pack-web-teardown-throw-is-not-a-clean-success
  (let [root (tmp-dir)]
    (setup-pack! root)
    (let [result (pack-web root false "--test-teardown-throw" (str root))]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result)) "teardown failed"))
      (is (str/includes? (str (:err result)) (str root))))))

(deftest handoffd-archives-sender-pane-when-task-moves
  ;; Given card and specifier→coder handoff (two-pack coder→cleaner to skip attention)
  ;; When delivered
  ;; Then .swarmforge/sessions/<from>/<task>/pane.txt exists
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root {"SWARMFORGE_PANE_STUB" "pane\n"})
      (let [pane (role-pane-path root "coder")]
        (is (fs/exists? pane))
        (is (= "pane\n" (slurp (str pane))))
        (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-board-archives-live-role-panes
  ;; Given a two-pack with a live card in coder and a done card
  ;; When pack_board archive-all with SWARMFORGE_PANE_STUB
  ;; Then coder's pane.txt exists and the done card is skipped
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (create-task root "already-done" "done")
    (let [result (run {:dir root :env {"SWARMFORGE_PANE_STUB" "pane\n"}}
                      (script "pack_board.sh")
                      "archive-all" "--root" (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder")))))
      (is (not (fs/exists? (role-pane-path root "done"))))
      (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))))

(deftest close-swarm-archives-live-role-panes
  ;; Given a two-pack with a live card in coder
  ;; When close-swarm
  ;; Then coder's pane.txt is archived
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (write-file (fs/path root ".swarmforge/tmux-socket")
                (str (fs/path root "tmux.sock") "\n"))
    (write-file (fs/path root ".swarmforge/window-ids") "")
    (let [result (run {:dir root
                       :env {"SWARMFORGE_TERMINAL_BACKEND" "none"
                             "SWARMFORGE_PANE_STUB" "pane\n"}}
                      (str (fs/path repo-root "close-swarm"))
                      (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder"))))))))

(defn wait-file [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn wait-lines [path expected-count timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [lines (when (fs/regular-file? path)
                    (str/split-lines (slurp (str path))))]
        (cond
          (>= (count lines) expected-count) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 50) (recur)))))))

(deftest pack-web-serve-writes-dashboard-url-and-binds-localhost
  ;; Given a pack root
  ;; When pack_web --serve <root>
  ;; Then dashboard-url is a localhost URL and GET / serves the dashboard
  (let [root (tmp-dir)
        url-file (fs/path root ".swarmforge/dashboard-url")
        pb (doto (java.lang.ProcessBuilder. [(script "pack_web.sh") "--serve" (str root)])
             (.directory (java.io.File. (str root))))
        _ (doto (.environment pb)
            (.put "PATH" (System/getenv "PATH"))
            (.put "GIT_CONFIG_NOSYSTEM" "1"))
        proc (.start pb)]
    (try
      (is (wait-file url-file 5000) "dashboard-url was written")
      (let [pid-file (fs/path root ".swarmforge/pack_web.pid")]
        (is (wait-file pid-file 5000) "pack_web.pid was written")
        (is (= (str (.pid proc)) (str/trim (slurp (str pid-file))))))
      (when (fs/exists? url-file)
        (let [url (str/trim (slurp (str url-file)))
              html (slurp url)
              health (json/parse-string (slurp (str url "/api/health")) true)]
          (is (re-find #"^http://127\.0\.0\.1:\d+$" url))
          (is (str/includes? html "New Task"))
          (is (= {:ok true :live true} health))))
      (finally
        (.destroyForcibly proc)
        (.waitFor proc)))))

(deftest pack-web-thermometer-heat-is-per-agent
  ;; Given two pack roots each with a specifier
  ;; When one pane changes and the other stays put
  ;; Then only the changed agent's thermometer rises
  (let [root-a (tmp-dir)
        root-b (tmp-dir)
        _ (setup-pack! root-a ["specifier"])
        _ (setup-pack! root-b ["specifier"])
        result (pack-web root-a false "--test-heat-isolation" (str root-a) (str root-b))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (pos? (:changed body)))
    (is (zero? (:stable body)))))

(deftest pack-web-thermometer-heat-rises-when-pane-changes
  ;; Given a specifier row
  ;; When --test-heat samples two different pane texts
  ;; Then activity after is greater than activity before
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))
    (is (<= 0 (:before body)))
    (is (<= (:after body) 6))))

(deftest pack-web-thermometer-heats-on-codex-working-timer
  ;; Given a Codex specifier pane whose only change is the working timer
  ;; When --test-heat-codex samples both
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-codex" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-teardown-requires-confirm
  ;; Given a pack root
  ;; When POST /api/teardown without confirm
  ;; Then it is rejected
  (let [root (tmp-dir)
        result (pack-web root false "--test-teardown" (str root))]
    (is (= 2 (:exit result)))
    (is (str/includes? (str (:err result) (:out result)) "TEARDOWN"))))

(deftest pack-web-teardown-kills-sessions-and-handoffd
  ;; Given a live tmux session and a fake handoffd pid
  ;; When teardown is confirmed
  ;; Then the tmux server is dead and the daemon pid is gone
  (let [root (tmp-dir)
        _ (setup-pack! root ["coder" "cleaner"])
        sock (start-tmux! root ["coder" "cleaner"])
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))
        pack-web-proc (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pack-web-pid (str (.pid pack-web-proc))]
    (try
      (write-file (fs/path root ".swarmforge/daemon/handoffd.pid") (str pid "\n"))
      (write-file (fs/path root ".swarmforge/pack_web.pid") (str pack-web-pid "\n"))
      (let [result (pack-web root false "--test-teardown" (str root) "TEARDOWN")]
        (is (zero? (:exit result)))
        (is (str/includes? (:out result) "teardown_started"))
        (is (not= 0 (:exit (run {:dir root :ok? false} "tmux" "-S" sock "list-sessions"))))
        (is (false? (.isAlive daemon)))
        (is (false? (.isAlive pack-web-proc)))
        (is (not (fs/exists? (fs/path root ".swarmforge/daemon/handoffd.pid"))))
        (is (not (fs/exists? (fs/path root ".swarmforge/pack_web.pid")))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (when (.isAlive pack-web-proc)
          (.destroyForcibly pack-web-proc))
        (stop-tmux! sock)))))

(deftest pack-board-move-matches-task-name-ignoring-case
  ;; Given board card HTW
  ;; When pack_board move --name htw --lane coder
  ;; Then the card HTW is in coder
  (let [root (tmp-dir)]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (pack-board root true "move" "--root" (str root) "--name" "htw" "--lane" "coder")
    (is (= "coder" (task-lane root "HTW")))))

(deftest handoffd-moves-card-when-handoff-task-case-differs
  ;; Given card HTW in coder
  ;; When git_handoff coder→cleaner task htw is delivered
  ;; Then HTW is in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-does-not-deliver-when-board-task-is-unknown
  ;; Given card HTW and a handoff for other-task
  ;; When delivered
  ;; Then coder inbox stays empty and HTW stays in specifier
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "other-task"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "coder" (task-lane root "HTW")))
      (is (= [] (inbox-names root roles "cleaner")))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-shows-board-card-as-live-work
  ;; Given card HTW in specifier and a live specifier session
  ;; When pack_web --test-state
  ;; Then specifier row is live with task HTW
  (let [root (tmp-dir)
        sock (do (setup-pack! root)
                 (create-task root "HTW" "specifier")
                 (start-tmux! root ["specifier"]))]
    (try
      (let [row (some #(when (= "specifier" (:role %)) %)
                      (:work_in_flight (web-state root)))]
        (is (= "HTW" (:task row)))
        (is (= "live" (:state row))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-chat-persists-and-answers
  ;; Given a pack root
  ;; When POST /api/chat then pack_dashboard_request answer
  ;; Then /api/state chat has the body and response
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        answer (fs/path root "tmp" "answer.txt")]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file answer "the spec is ready\nwith two documents\n")
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                  "--test-post-chat" (str root) "status?")
    (let [listed (run {:dir root}
                      (script "pack_dashboard_request.sh")
                      "list" "--root" (str root))
          id (first (str/split (str/trim (:out listed)) #"\t"))]
      (is (str/starts-with? id "req-"))
      (run {:dir root} (script "pack_dashboard_request.sh") "answer" id (str answer))
      (let [chat (:chat (web-state root))
            row (first chat)
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/requests/done")))))]
        (is (= "status?" (str/trim (:body row))))
        (is (= "the spec is ready\nwith two documents" (:response row)))
        (is (str/includes? stored "response: the spec is ready\\nwith two documents\n"))
        (is (= "done" (:status row)))))))

(deftest answering-a-chat-request-preserves-safe-conversation-metadata
  ;; Given a public chat request carrying request, thread, source and proposal metadata
  ;; When it is answered and re-answered idempotently
  ;; Then the done record and state projection retain every safe field and escaped body
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        id "req-conversation-metadata"
        thread-id "conversation-0c9bf287692a6a6a82c3b3d251bbbd5c"
        source "app.lieutenant-chat"
        proposal-id "proposal-20261110T100110Z"
        body "First body line\nSecond body line"
        answer-text "First answer line\nSecond answer line"
        answer (fs/path root "tmp" "answer.txt")
        done-file (fs/path root ".swarmforge/dashboard/requests/done"
                            (str id ".request"))]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file answer answer-text)
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                  "--test-post-chat-json" (str root)
                  (json/generate-string
                   {:text body
                    :request_id id
                    :thread_id thread-id
                    :source source
                    :proposal_id proposal-id}))
    (let [first-answer (run {:dir root} (script "pack_dashboard_request.sh")
                            "answer" id (str answer))
          stored (slurp (str done-file))
          row (get (into {} (map (juxt :id identity) (:chat (web-state root)))) id)
          stored-after-first stored
          second-answer (run {:dir root} (script "pack_dashboard_request.sh")
                             "answer" id (str answer))]
      (is (zero? (:exit first-answer)))
      (is (str/includes? stored (str "thread_id: " thread-id "\n")))
      (is (str/includes? stored (str "source: " source "\n")))
      (is (str/includes? stored (str "proposal_id: " proposal-id "\n")))
      (is (str/includes? stored
                         "response: First answer line\\nSecond answer line\n"))
      (is (= thread-id (:thread_id row)))
      (is (= source (:source row)))
      (is (= proposal-id (:proposal_id row)))
      (is (= body (str/trim (:body row))))
      (is (= answer-text (:response row)))
      (is (zero? (:exit second-answer)))
      (is (= stored-after-first (slurp (str done-file))))
      (let [different (fs/path root "tmp" "different-answer.txt")
            _ (write-file different "A different answer")
            refused (run {:dir root :ok? false}
                         (script "pack_dashboard_request.sh")
                         "answer" id (str different))]
        (is (not (zero? (:exit refused))))
        (is (str/includes? (str (:err refused) (:out refused))
                           "already answered with a different response"))
        (is (= stored-after-first (slurp (str done-file))))))))

(deftest answering-drops-malformed-or-unsafe-chat-metadata
  ;; Given legacy pending chat metadata that the dashboard reader would omit
  ;; When it is answered
  ;; Then neither the done record nor state exposes the unsafe values
  (let [root (tmp-dir)
        pending (fs/path root ".swarmforge/dashboard/requests/pending")
        answer (fs/path root "tmp" "answer.txt")
        unsafe-source (str (fs/path root "private" "source"))
        token "ghp_1234567890abcdefghijklmnopqrstuvwxyz"]
    (setup-pack! root)
    (write-file
     (fs/path pending "req-unsafe-metadata.request")
     (str "id: req-unsafe-metadata\n"
          "status: pending\n"
          "thread_id: ../not-a-thread\n"
          "source: " unsafe-source "\n"
          "proposal_id: " token "\n"
          "created_at: 2026-11-10T10:00:00Z\n\n"
          "Unsafe metadata body\n"))
    (write-file answer "Safe answer\n")
    (let [answered (run {:dir root} (script "pack_dashboard_request.sh")
                        "answer" "req-unsafe-metadata" (str answer))
          stored (slurp (str (fs/path root ".swarmforge/dashboard/requests/done"
                                      "req-unsafe-metadata.request")))
          row (get (into {} (map (juxt :id identity) (:chat (web-state root))))
                   "req-unsafe-metadata")]
      (is (zero? (:exit answered)))
      (is (not (str/includes? stored "thread_id:")))
      (is (not (str/includes? stored "source:")))
      (is (not (str/includes? stored "proposal_id:")))
      (is (not (str/includes? stored unsafe-source)))
      (is (not (str/includes? stored token)))
      (is (not (contains? row :thread_id)))
      (is (not (contains? row :source)))
      (is (not (contains? row :proposal_id)))
      (is (= "Unsafe metadata body" (str/trim (:body row))))
      (is (= "Safe answer" (:response row))))))

(deftest answered-report-and-proposal-requests-keep-their-projection
  ;; Given a completed-card report thread and a proposal discussion
  ;; When both are answered
  ;; Then state still projects the report thread and proposal linkage
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        answer (fs/path root "tmp" "answer.txt")]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file answer "Verified\n")
    (doseq [{:keys [request_id] :as request}
            [{:request_id "req-report-projection"
              :thread_id "thread-fb9f3f9a9affc19f5c192e2d17352894"
              :source "app.completed-card-report"
              :text "Verify the completed cards."}
             {:request_id "req-proposal-projection"
              :source "app.lieutenant-chat"
              :proposal_id "proposal-20261110T100110Z"
              :text "Continue the proposal discussion."}]]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" (str argv-file)}
                    "--test-post-chat-json" (str root)
                    (json/generate-string request))
      (run {:dir root} (script "pack_dashboard_request.sh")
           "answer" request_id (str answer)))
    (let [chat (into {} (map (juxt :id identity) (:chat (web-state root))))
          report (get chat "req-report-projection")
          proposal (get chat "req-proposal-projection")]
      (is (= "thread-fb9f3f9a9affc19f5c192e2d17352894" (:thread_id report)))
      (is (= "app.completed-card-report" (:source report)))
      (is (= "proposal-20261110T100110Z" (:proposal_id proposal)))
      (is (= "app.lieutenant-chat" (:source proposal)))
      (is (= "done" (:status report)))
      (is (= "done" (:status proposal)))
      (is (not (contains? report :actions)))
      (is (not (contains? proposal :actions))))))

(defn route-proposal!
  [forge project name]
  (let [project-root (fs/path forge "projects" project)
        note (fs/path forge "tmp" (str name ".md"))]
    (write-file (fs/path project-root ".swarmforge" "single-agent") "agent\n")
    (write-file note (str "# " name "\n\n" name " card.\n"))
    (let [result (run {:dir forge}
                      (script "route_card.sh")
                      "propose" (str project-root) name (str note)
                      "--forge-root" (str forge)
                      "--operator-said" "yes")]
      (is (zero? (:exit result)) (:err result))
      (second (re-find #"PROPOSED: (\S+)" (:out result))))))

(defn write-pending-chat!
  [root {:keys [id body thread-id source proposal-id created-at]}]
  (let [file (fs/path root ".swarmforge/dashboard/requests/pending"
                      (str id ".request"))]
    (write-file file
                (str "id: " id "\n"
                     "status: pending\n"
                     "role: lieutenant\n"
                     (when thread-id (str "thread_id: " thread-id "\n"))
                     (when source (str "source: " source "\n"))
                     (when proposal-id (str "proposal_id: " proposal-id "\n"))
                     "created_at: " (or created-at "2026-11-10T20:30:00Z") "\n\n"
                     (or body "Operator request body.\n")))
    file))

(deftest answering-challenges-an-unspecified-pending-route-proposal
  ;; Given a response that names one pending local route-card proposal
  ;; When it omits the structured action
  ;; Then the answer is challenged without moving or echoing sensitive request data
  (let [forge (tmp-dir)
        answer (fs/path forge "tmp" "answer.txt")
        request-id "req-route-guard"
        thread-id "conversation-route-guard"
        source "app.lieutenant-chat"
        secret-path "/Users/operator/private/project"
        token "ghp_1234567890abcdefghijklmnopqrstuvwxyz"
        raw-body "UNRELATED_DASHBOARD_BODY"
        raw-note "PRIVATE_PROPOSAL_NOTE"]
    (setup-pack! forge ["specifier"])
    (fs/create-dirs (fs/path forge "projects" "alpha-app"))
    (let [proposal-id (route-proposal! forge "alpha-app" "next-card")
          proposal-file (fs/path forge ".swarmforge" "route-proposals"
                                 (str proposal-id ".edn"))
          proposal (edn/read-string (slurp (str proposal-file)))
          _ (write-file proposal-file (pr-str (assoc proposal :note raw-note)))
          pending (write-pending-chat!
                   forge
                   {:id request-id
                    :body (str raw-body "\n")
                    :thread-id thread-id
                    :source source})
          before (slurp (str pending))
          _ (write-file
             answer
             (str "Proposed `" proposal-id "` for next-card.\n"
                  "Reply **approve**.\n"
                  "Debug " secret-path " " token "\n"))
          challenged (run {:dir forge :ok? false}
                          (script "pack_dashboard_request.sh")
                          "answer" request-id (str answer))
          diagnostic (str (:err challenged) (:out challenged))
          done (fs/path forge ".swarmforge/dashboard/requests/done"
                         (str request-id ".request"))]
      (is (not (zero? (:exit challenged))))
      (is (str/includes? diagnostic proposal-id))
      (is (str/includes? diagnostic "next-card"))
      (is (str/includes? diagnostic
                         (str "--approve-route-card " proposal-id)))
      (is (str/includes? diagnostic "--without-route-card-action"))
      (is (< (count diagnostic) 2000))
      (is (not (str/includes? diagnostic (str forge))))
      (is (not (str/includes? diagnostic secret-path)))
      (is (not (str/includes? diagnostic token)))
      (is (not (str/includes? diagnostic raw-body)))
      (is (not (str/includes? diagnostic raw-note)))
      (is (= before (slurp (str pending))))
      (is (not (fs/exists? done)))
      (let [allowed (run {:dir forge}
                         (script "pack_dashboard_request.sh")
                         "answer" request-id (str answer)
                         "--without-route-card-action")
            stored (slurp (str done))
            row (get (into {} (map (juxt :id identity)
                                   (:chat (web-state forge))))
                     request-id)]
        (is (zero? (:exit allowed)) (:err allowed))
        (is (not (str/includes? stored "action: approve-route-card")))
        (is (= thread-id (:thread_id row)))
        (is (= source (:source row)))
        (is (= raw-body (str/trim (:body row))))
        (is (not (contains? row :actions)))))))

(deftest answering-multiple-route-proposals-requires-one-explicit-choice
  ;; Given a response naming two pending local proposals
  ;; When no action or one of them is selected
  ;; Then only the explicit choice can complete the answer
  (let [forge (tmp-dir)
        answer (fs/path forge "tmp" "answer.txt")]
    (setup-pack! forge ["specifier"])
    (fs/create-dirs (fs/path forge "projects" "alpha-app"))
    (let [first-id (route-proposal! forge "alpha-app" "first-card")
          second-id (route-proposal! forge "alpha-app" "second-card")
          text (str "Choose between `" first-id "` and `" second-id "`.\n")
          _ (write-file answer text)]
      (write-pending-chat! forge {:id "req-multiple-route-proposals"})
      (let [challenged
            (run {:dir forge :ok? false}
                 (script "pack_dashboard_request.sh")
                 "answer" "req-multiple-route-proposals" (str answer))
            diagnostic (str (:err challenged) (:out challenged))]
        (is (not (zero? (:exit challenged))))
        (is (str/includes? diagnostic first-id))
        (is (str/includes? diagnostic second-id))
        (is (fs/regular-file?
             (fs/path forge ".swarmforge/dashboard/requests/pending"
                      "req-multiple-route-proposals.request"))))
      (write-pending-chat! forge {:id "req-selected-route-proposal"})
      (let [answered
            (run {:dir forge}
                 (script "pack_dashboard_request.sh")
                 "answer" "req-selected-route-proposal" (str answer)
                 "--approve-route-card" first-id)
            row (get (into {} (map (juxt :id identity)
                                   (:chat (web-state forge))))
                     "req-selected-route-proposal")]
        (is (zero? (:exit answered)) (:err answered))
        (is (= first-id (get-in row [:actions 0 :proposal_id])))))))

(deftest answering-ignores-non-pending-malformed-and-foreign-proposal-tokens
  ;; Given tokens for consumed, superseded, malformed, unknown and other-Family proposals
  ;; When none is a pending local route-card proposal
  ;; Then the response answers normally without attaching an action
  (let [forge (tmp-dir)
        other-forge (tmp-dir)
        answer (fs/path forge "tmp" "answer.txt")
        malformed-id "proposal-20261110T203500Z"]
    (setup-pack! forge ["specifier"])
    (setup-pack! other-forge ["specifier"])
    (doseq [root [forge other-forge]]
      (fs/create-dirs (fs/path root "projects" "alpha-app")))
    (let [consumed-id (route-proposal! forge "alpha-app" "consumed-card")
          superseded-id (route-proposal! forge "alpha-app" "superseded-card")
          foreign-id (route-proposal! other-forge "alpha-app" "foreign-card")
          consumed-file (fs/path forge ".swarmforge/route-proposals"
                                 (str consumed-id ".edn"))
          superseded-file (fs/path forge ".swarmforge/route-proposals"
                                   (str superseded-id ".edn"))
          consumed (edn/read-string (slurp (str consumed-file)))
          superseded (edn/read-string (slurp (str superseded-file)))]
      (write-file consumed-file
                  (pr-str (assoc consumed :status :consumed
                                 :consumed-at "2026-11-10T20:35:01Z")))
      (write-file superseded-file
                  (pr-str (assoc superseded :status :superseded
                                 :superseded-at "2026-11-10T20:35:02Z")))
      (write-file (fs/path forge ".swarmforge/route-proposals"
                            (str malformed-id ".edn"))
                  "not an edn map\n")
      (write-file
       answer
       (str "Earlier " consumed-id ", " superseded-id ", " foreign-id
            ", " malformed-id ", and proposal-20261110T203599Z.\n"
            "Malformed proposal-../escape is only prose.\n"))
      (write-pending-chat! forge {:id "req-ignore-route-tokens"})
      (let [answered
            (run {:dir forge}
                 (script "pack_dashboard_request.sh")
                 "answer" "req-ignore-route-tokens" (str answer))
            row (get (into {} (map (juxt :id identity)
                                   (:chat (web-state forge))))
                     "req-ignore-route-tokens")]
        (is (zero? (:exit answered)) (:err answered))
        (is (= "done" (:status row)))
        (is (not (contains? row :actions)))))))

(deftest answering-refuses-an-action-absent-from-the-response
  ;; Given a valid pending proposal that the response does not name
  ;; When the caller supplies its action
  ;; Then the answer remains pending and no action metadata is written
  (let [forge (tmp-dir)
        answer (fs/path forge "tmp" "answer.txt")]
    (setup-pack! forge ["specifier"])
    (fs/create-dirs (fs/path forge "projects" "alpha-app"))
    (let [proposal-id (route-proposal! forge "alpha-app" "next-card")
          pending (write-pending-chat! forge {:id "req-action-not-in-response"})]
      (write-file answer "A response that discusses no proposal id.\n")
      (let [refused
            (run {:dir forge :ok? false}
                 (script "pack_dashboard_request.sh")
                 "answer" "req-action-not-in-response" (str answer)
                 "--approve-route-card" proposal-id)
            diagnostic (str (:err refused) (:out refused))]
        (is (not (zero? (:exit refused))))
        (is (str/includes? diagnostic "does not appear in the exact response"))
        (is (fs/regular-file? pending))
        (is (not (fs/exists?
                  (fs/path forge ".swarmforge/dashboard/requests/done"
                           "req-action-not-in-response.request"))))
        (let [missing-value
              (run {:dir forge :ok? false}
                   (script "pack_dashboard_request.sh")
                   "answer" "req-action-not-in-response" (str answer)
                   "--approve-route-card")]
          (is (not (zero? (:exit missing-value))))
          (is (str/includes? (str (:err missing-value) (:out missing-value))
                             "Missing route-card proposal id"))
          (is (fs/regular-file? pending)))))))

(deftest completed-text-only-answer-can-backfill-one-route-action
  ;; Given a completed response with no action and pending local proposal
  ;; When the action is backfilled and retried
  ;; Then only the action is added, original content stays fixed, and the retry is idempotent
  (let [forge (tmp-dir)
        answer (fs/path forge "tmp" "answer.txt")]
    (setup-pack! forge ["specifier"])
    (fs/create-dirs (fs/path forge "projects" "alpha-app"))
    (let [proposal-id (route-proposal! forge "alpha-app" "next-card")
          text (str "Proposed `" proposal-id "`; reply approve.\n")
          _ (write-file answer text)
          request-id "req-legacy-text-only"]
      (write-pending-chat!
       forge
       {:id request-id
        :thread-id "conversation-legacy-action"
        :source "app.lieutenant-chat"
        :proposal-id "proposal-20261110T202000Z"
        :body "Original body\nSecond line\n"})
      (let [completed (run {:dir forge}
                           (script "pack_dashboard_request.sh")
                           "answer" request-id (str answer)
                           "--without-route-card-action")
            done (fs/path forge ".swarmforge/dashboard/requests/done"
                           (str request-id ".request"))
            before (slurp (str done))
            header (fn [name]
                     (second (re-find (re-pattern (str "(?m)^" name ": (.+)$"))
                                      before)))
            original-timestamps (select-keys
                                 {"created_at" (header "created_at")
                                  "updated_at" (header "updated_at")}
                                 ["created_at" "updated_at"])
            backfilled (run {:dir forge}
                            (script "pack_dashboard_request.sh")
                            "answer" request-id (str answer)
                            "--approve-route-card" proposal-id)
            after (slurp (str done))
            row (get (into {} (map (juxt :id identity)
                                   (:chat (web-state forge))))
                     request-id)]
        (is (zero? (:exit completed)) (:err completed))
        (is (not (str/includes? before "action: approve-route-card")))
        (is (zero? (:exit backfilled)) (:err backfilled))
        (is (str/includes? after (str "action: approve-route-card\n"
                                      "action_proposal_id: " proposal-id "\n")))
        (is (= (header "created_at") (:created_at row)))
        (is (str/includes? after (str "updated_at: " (header "updated_at") "\n")))
        (is (= original-timestamps
               {"created_at" (second (re-find #"(?m)^created_at: (.+)$" after))
                "updated_at" (second (re-find #"(?m)^updated_at: (.+)$" after))}))
        (is (= "conversation-legacy-action" (:thread_id row)))
        (is (= "app.lieutenant-chat" (:source row)))
        (is (= "proposal-20261110T202000Z" (:proposal_id row)))
        (is (= (str/trim text) (:response row)))
        (let [retry (run {:dir forge}
                         (script "pack_dashboard_request.sh")
                         "answer" request-id (str answer)
                         "--approve-route-card" proposal-id)]
          (is (zero? (:exit retry)) (:err retry))
          (is (= after (slurp (str done)))))))))

(deftest answered-chat-projects-a-route-card-approval-action
  ;; Given a pending route-card proposal and a metadata-bearing chat request
  ;; When the answer attaches its approval action
  ;; Then state exposes one safe descriptor and later reflects consumed status
  (let [forge (tmp-dir)
        argv-file (str (fs/path forge "tmux.argv"))
        answer (fs/path forge "tmp" "answer.txt")
        request-id "req-route-action"
        thread-id "conversation-route-action"
        source "app.lieutenant-chat"]
    (setup-pack! forge ["specifier"])
    (fs/create-dirs (fs/path forge "projects" "alpha-app"))
    (write-file (fs/path forge ".swarmforge/tmux-socket")
                (str (fs/path forge "tmux.sock") "\n"))
    (let [proposal-id (route-proposal! forge "alpha-app" "next-card")
          _ (write-file answer (str "I proposed `" proposal-id
                                    "` for the next card.\n"))
          _ (pack-web-env forge {"SWARMFORGE_TMUX_STUB" argv-file}
                          "--test-post-chat-json" (str forge)
                          (json/generate-string
                           {:text "What should we do next?"
                            :request_id request-id
                            :thread_id thread-id
                            :source source
                            :proposal_id proposal-id}))
          answered (run {:dir forge} (script "pack_dashboard_request.sh")
                        "answer" request-id (str answer)
                        "--approve-route-card" proposal-id)
          row (get (into {} (map (juxt :id identity)
                                 (:chat (web-state forge))))
                   request-id)
          action (first (:actions row))
          proposal-file (fs/path forge ".swarmforge/route-proposals"
                                  (str proposal-id ".edn"))]
      (is (zero? (:exit answered)) (:err answered))
      (is (= thread-id (:thread_id row)))
      (is (= source (:source row)))
      (is (= proposal-id (:proposal_id row)))
      (is (= {:type "approve-route-card"
              :proposal_id proposal-id
              :family (str (fs/file-name forge))
              :project "alpha-app"
              :task_name "next-card"
              :label "Approve next-card"
              :status "pending"
              :available true}
             action))
      (is (= action
             (first (:actions
                     (get (into {} (map (juxt :id identity)
                                        (:chat (web-state forge))))
                          request-id)))))
      (let [proposal (edn/read-string (slurp (str proposal-file)))]
        (write-file proposal-file
                    (pr-str (assoc proposal
                                   :status :consumed
                                   :consumed-at "2026-11-10T10:30:00Z"))))
      (let [consumed (first
                      (:actions
                       (get (into {} (map (juxt :id identity)
                                          (:chat (web-state forge))))
                            request-id)))]
        (is (= "consumed" (:status consumed)))
        (is (false? (:available consumed))))
      (let [retry (run {:dir forge} (script "pack_dashboard_request.sh")
                        "answer" request-id (str answer)
                        "--approve-route-card" proposal-id)]
        (is (zero? (:exit retry)) (:err retry))))))

(deftest answering-rejects-invalid-route-card-approval-actions
  ;; Given missing, malformed, consumed and cross-Family proposals
  ;; When an action is attached
  ;; Then every refusal leaves the pending chat request untouched
  (let [forge (tmp-dir)
        other-forge (tmp-dir)
        argv-file (str (fs/path forge "tmux.argv"))
        answer (fs/path forge "tmp" "answer.txt")]
    (setup-pack! forge ["specifier"])
    (setup-pack! other-forge ["specifier"])
    (doseq [root [forge other-forge]]
      (fs/create-dirs (fs/path root "projects" "alpha-app"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  (str (fs/path root "tmux.sock") "\n")))
    (write-file answer "Answer.\n")
    (let [pending (fs/path forge ".swarmforge/dashboard/requests/pending")
          id "req-route-action-reject"
          _ (write-file
             (fs/path pending (str id ".request"))
             (str "id: " id "\n"
                  "status: pending\n"
                  "proposal_id: proposal-20261110T103000Z\n"
                  "created_at: 2026-11-10T10:30:00Z\n\n"
                  "Approve the next card?\n"))
          cross-id (route-proposal! other-forge "alpha-app" "cross-family")
          consumed-id (route-proposal! forge "alpha-app" "consumed-card")
          consumed-file (fs/path forge ".swarmforge/route-proposals"
                                 (str consumed-id ".edn"))
          consumed (edn/read-string (slurp (str consumed-file)))
          malformed-id "proposal-20261110T103001Z"
          malformed-file (fs/path forge ".swarmforge/route-proposals"
                                  (str malformed-id ".edn"))
          answer! (fn [proposal-id]
                    (run {:dir forge :ok? false}
                         (script "pack_dashboard_request.sh")
                         "answer" id (str answer)
                         "--approve-route-card" proposal-id))]
      (write-file consumed-file
                  (pr-str (assoc consumed :consumed-at "2026-11-10T10:31:00Z")))
      (write-file malformed-file "not an edn map\n")
      (doseq [[proposal-id expected]
              [["proposal-20261110T103999Z" "not found"]
               ["../not-a-proposal" "invalid"]
               [cross-id "not found"]
               [consumed-id "consumed"]
               [malformed-id "malformed"]]]
        (let [result (answer! proposal-id)]
          (is (not (zero? (:exit result))))
          (is (str/includes? (str (:err result) (:out result)) expected))
          (is (fs/regular-file? (fs/path pending (str id ".request"))))
          (is (not (fs/exists?
                    (fs/path forge ".swarmforge/dashboard/requests/done"
                             (str id ".request"))))))))))

(deftest pack-web-chat-preserves-an-optional-proposal-id
  (let [root (tmp-dir)]
    (setup-pack! root)
    (write-file
     (fs/path root ".swarmforge/dashboard/requests/pending/req-proposal.request")
     (str "id: req-proposal\n"
          "status: pending\n"
          "proposal_id: proposal-20261108T130900Z\n"
          "created_at: 2026-11-08T13:10:00Z\n\n"
          "Continue the proposal discussion.\n"))
    (write-file
     (fs/path root ".swarmforge/dashboard/requests/done/req-ordinary.request")
     (str "id: req-ordinary\n"
          "status: done\n"
          "response: ordinary answer\n"
          "created_at: 2026-11-08T13:09:00Z\n\n"
          "Ordinary chat.\n"))
    (let [chat (into {} (map (juxt :id identity) (:chat (web-state root))))
          proposal (get chat "req-proposal")
          ordinary (get chat "req-ordinary")]
      (is (= "proposal-20261108T130900Z" (:proposal_id proposal)))
      (is (not (contains? ordinary :proposal_id)))
      (is (= "Ordinary chat." (str/trim (:body ordinary))))
      (is (= "ordinary answer" (:response ordinary))))))

(deftest pack-web-chat-preserves-safe-thread-ids-per-turn
  (let [root (tmp-dir)
        pending (fs/path root ".swarmforge/dashboard/requests/pending")
        done (fs/path root ".swarmforge/dashboard/requests/done")]
    (setup-pack! root)
    (write-file
     (fs/path pending "req-thread-a.request")
     (str "id: req-thread-a\n"
          "status: pending\n"
          "thread_id: thread-completed-report-42\n"
          "created_at: 2026-11-08T14:20:00Z\n\n"
          "First turn.\n"))
    (write-file
     (fs/path done "req-thread-b.request")
     (str "id: req-thread-b\n"
          "status: done\n"
          "thread_id: thread-completed-report-42\n"
          "response: Second answer\n"
          "created_at: 2026-11-08T14:21:00Z\n\n"
          "Second turn.\n"))
    (write-file
     (fs/path pending "req-ordinary.request")
     (str "id: req-ordinary\n"
          "status: pending\n"
          "created_at: 2026-11-08T14:22:00Z\n\n"
          "Ordinary turn.\n"))
    (write-file
     (fs/path pending "req-malformed.request")
     (str "id: req-malformed\n"
          "status: pending\n"
          "thread_id: ../not-a-thread\n"
          (str "source: " (fs/path root "unsafe" "source") "\n")
          "created_at: 2026-11-08T14:23:00Z\n\n"
          "Malformed thread remains an ordinary turn.\n"))
    (let [chat (into {} (map (juxt :id identity) (:chat (web-state root))))]
      (is (= 4 (count chat)))
      (is (= "thread-completed-report-42" (get-in chat ["req-thread-a" :thread_id])))
      (is (= "thread-completed-report-42" (get-in chat ["req-thread-b" :thread_id])))
      (is (not (contains? (get chat "req-ordinary") :thread_id)))
      (is (not (contains? (get chat "req-malformed") :thread_id)))
      (is (not (contains? (get chat "req-malformed") :source)))
      (is (= "pending" (get-in chat ["req-thread-a" :status])))
      (is (= "done" (get-in chat ["req-thread-b" :status])))
      (is (= "Second answer" (get-in chat ["req-thread-b" :response])))
      (is (= "2026-11-08T14:23:00Z"
             (get-in chat ["req-malformed" :created_at]))))))

(deftest dashboard-state-pages-general-chat-with-a-stable-cursor
  ;; Given more general Lieutenant turns than one page
  ;; When the newest and consecutive older pages are requested
  ;; Then ordering is chronological within pages and insertion/deletion do not shift older history
  (let [root (tmp-dir)
        thread-id "conversation-paged"
        pending-id "req-page-pending"
        pending-file (fs/path root ".swarmforge/dashboard/requests/pending"
                               (str pending-id ".request"))]
    (setup-pack! root)
    (doseq [n (range 12)]
      (write-chat-turn! root {:id (format "req-page-%02d" n)
                              :status "done"
                              :created-at (chat-timestamp n)
                              :thread-id thread-id
                              :source "app.lieutenant-chat"
                              :body (str "body " n "\nsecond line")}))
    (write-chat-turn! root {:id pending-id
                            :status "pending"
                            :created-at (chat-timestamp 12)
                            :thread-id thread-id
                            :source "app.lieutenant-chat"
                            :body "pending body"})
    (let [first-page (paged-web-state
                      root
                      (str "chat_thread_id=" thread-id "&chat_limit=5"))
          first-ids (mapv :id (:chat first-page))
          cursor (get-in first-page [:chat_page :next_cursor])]
      (is (= ["req-page-08" "req-page-09" "req-page-10" "req-page-11"
              pending-id]
             first-ids))
      (is (true? (get-in first-page [:chat_page :has_more])))
      (is (string? cursor))
      (is (= 1 (get-in first-page [:chat_page :conversation :pending_count])))
      (is (= [] (get-in first-page [:chat_page :pending_turns])))

      ;; Newer insertion and deletion of one later turn do not shift the older cursor.
      (write-chat-turn! root {:id "req-page-newer"
                              :status "done"
                              :created-at (chat-timestamp 13)
                              :thread-id thread-id
                              :source "app.lieutenant-chat"
                              :body "newer"})
      (fs/delete-if-exists
       (fs/path root ".swarmforge/dashboard/requests/done"
                "req-page-09.request"))
      (let [second-page (paged-web-state
                         root
                         (str "chat_thread_id=" thread-id "&chat_limit=5"
                              "&chat_cursor=" cursor))
            second-ids (mapv :id (:chat second-page))
            second-cursor (get-in second-page [:chat_page :next_cursor])]
        (is (= ["req-page-03" "req-page-04" "req-page-05" "req-page-06"
                "req-page-07"]
               second-ids))
        (is (true? (get-in second-page [:chat_page :has_more])))
        (is (empty? (set/intersection (set first-ids) (set second-ids))))
        (let [third-page (paged-web-state
                          root
                          (str "chat_thread_id=" thread-id "&chat_limit=5"
                               "&chat_cursor=" second-cursor))
              third-ids (mapv :id (:chat third-page))]
          (is (= ["req-page-00" "req-page-01" "req-page-02"] third-ids))
          (is (false? (get-in third-page [:chat_page :has_more])))
          (is (nil? (get-in third-page [:chat_page :next_cursor])))
          (is (= (conj (set (map #(format "req-page-%02d" %) (range 12)))
                       pending-id)
                 (set (concat first-ids second-ids third-ids)))))))))

(deftest dashboard-chat-pages-expose-summaries-pending-and-isolate-other-chat
  ;; Given empty, exact-boundary, suspended pending and non-general histories
  ;; When a page is requested
  ;; Then summaries and pending stay discoverable while report/proposal/other chat stay out
  (let [root (tmp-dir)
        thread-id "conversation-boundary"
        empty-thread "conversation-empty"]
    (setup-pack! root)
    (doseq [n (range 5)]
      (write-chat-turn! root {:id (format "req-boundary-%d" n)
                              :status "done"
                              :created-at (chat-timestamp n)
                              :thread-id thread-id
                              :source "app.lieutenant-chat"}))
    (write-chat-turn! root {:id "req-boundary-pending"
                            :status "pending"
                            :created-at (chat-timestamp 1)
                            :thread-id "conversation-old-pending"
                            :source "app.lieutenant-chat"
                            :body "old pending"})
    (doseq [n (range 2 7)]
      (write-chat-turn! root {:id (format "req-old-new-%d" n)
                              :status "done"
                              :created-at (chat-timestamp n)
                              :thread-id "conversation-old-pending"
                              :source "app.lieutenant-chat"}))
    (write-chat-turn! root {:id "req-report-only"
                            :status "done"
                            :created-at (chat-timestamp 20)
                            :thread-id "thread-report"
                            :source "app.completed-card-report"})
    (write-chat-turn! root {:id "req-proposal-only"
                            :status "done"
                            :created-at (chat-timestamp 21)
                            :thread-id thread-id
                            :source "app.lieutenant-chat"
                            :proposal-id "proposal-20261110T110000Z"})
    (write-chat-turn! root {:id "req-unrelated-source"
                            :status "done"
                            :created-at (chat-timestamp 22)
                            :thread-id "conversation-unrelated"
                            :source "bridge.other"})
    (let [boundary (paged-web-state
                    root
                    (str "chat_thread_id=" thread-id "&chat_limit=5"))
          old-pending (paged-web-state
                       root
                       "chat_thread_id=conversation-old-pending&chat_limit=2")
          empty (paged-web-state
                 root
                 (str "chat_thread_id=" empty-thread "&chat_limit=5"))
          summaries (into {} (map (juxt :thread_id identity))
                          (:chat_conversations boundary))]
      (is (= 5 (count (:chat boundary))))
      (is (false? (get-in boundary [:chat_page :has_more])))
      (is (nil? (get-in boundary [:chat_page :next_cursor])))
      (is (= 1 (get-in old-pending [:chat_page :conversation :pending_count])))
      (is (= ["req-boundary-pending"]
             (mapv :id (get-in old-pending [:chat_page :pending_turns]))))
      (is (empty? (:chat empty)))
      (is (false? (get-in empty [:chat_page :has_more])))
      (is (nil? (get-in empty [:chat_page :conversation :latest])))
      (is (= #{thread-id "conversation-old-pending"}
             (set (keys summaries))))
      (is (not (contains? summaries "thread-report")))
      (is (not (contains? summaries "conversation-unrelated")))
      (is (= 14 (count (:chat (web-state root)))))
      (is (not (contains? (web-state root) :chat_page))))))

(deftest dashboard-chat-pagination-rejects-invalid-parameters-and-history
  ;; Given malformed limits, cursors, unsupported parameters and corrupt history
  ;; When a paged state is requested
  ;; Then it returns bounded errors and foreign cursors cannot cross conversations
  (let [root (tmp-dir)]
    (setup-pack! root)
    (doseq [n (range 6)]
      (write-chat-turn! root {:id (format "req-error-a-%d" n)
                              :status "done"
                              :created-at (chat-timestamp n)
                              :thread-id "conversation-a"
                              :source "app.lieutenant-chat"}))
    (write-chat-turn! root {:id "req-error-b-0"
                            :status "done"
                            :created-at (chat-timestamp 10)
                            :thread-id "conversation-b"
                            :source "app.lieutenant-chat"})
    (let [cursor (get-in (paged-web-state root "chat_thread_id=conversation-a&chat_limit=2")
                         [:chat_page :next_cursor])
          error! (fn [query]
                   (let [result (pack-web root false "--test-state-query"
                                          (str root) query)]
                     (is (= 1 (:exit result)))
                     (str (:out result) (:err result))))]
      (is (str/includes? (error! "chat_thread_id=bad/path&chat_limit=2")
                         "conversation id is invalid"))
      (is (str/includes? (error! "chat_thread_id=conversation-a&chat_limit=0")
                         "between 1 and 100"))
      (is (str/includes? (error! "chat_thread_id=conversation-a&chat_limit=101")
                         "between 1 and 100"))
      (is (str/includes? (error! "chat_limit=2") "conversation id is invalid"))
      (is (str/includes? (error! "chat_thread_id=conversation-a&chat_before=x")
                         "Unsupported chat pagination parameter"))
      (is (str/includes? (error! "chat_thread_id=conversation-a&chat_cursor=not-base64")
                         "cursor is malformed"))
      (is (str/includes?
           (error! (str "chat_thread_id=conversation-b&chat_limit=2&chat_cursor="
                        cursor))
           "cursor is invalid"))
      (write-chat-turn! root {:id "req-corrupt"
                              :status "done"
                              :created-at ""
                              :thread-id "conversation-a"
                              :source "app.lieutenant-chat"})
      (is (str/includes? (error! "chat_thread_id=conversation-a&chat_limit=2")
                         "malformed general turn")))))

(deftest dashboard-chat-pagination-bounds-a-large-history
  ;; Given a large general conversation
  ;; When the newest page is requested
  ;; Then response size stays bounded and processing remains practical
  (let [root (tmp-dir)
        thread-id "conversation-large"
        body (apply str (repeat 200 "x"))]
    (setup-pack! root)
    (fs/create-dirs (fs/path root ".swarmforge/dashboard/requests/done"))
    (doseq [n (range 1000)]
      (write-chat-turn! root {:id (format "req-large-%04d" n)
                              :status "done"
                              :created-at (chat-timestamp n)
                              :thread-id thread-id
                              :source "app.lieutenant-chat"
                              :body body}))
    (let [started (System/nanoTime)
          paged-result (pack-web root true "--test-state-query" (str root)
                                 (str "chat_thread_id=" thread-id
                                      "&chat_limit=40"))
          elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
          paged (json/parse-string (:out paged-result) true)
          legacy (pack-web root true "--test-state" (str root))]
      (is (= 40 (count (:chat paged))))
      (is (true? (get-in paged [:chat_page :has_more])))
      (is (< (count (:out paged-result)) 100000))
      (is (< (count (:out paged-result)) (count (:out legacy))))
      (is (< elapsed-ms 15000.0)))))

















(deftest pack-web-state-groups-in-process-batch-cards
  ;; Given two-pack and two cleaner in-process handoffs in one batch dir
  ;; When --test-state
  ;; Then those tasks share a batch id
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        _ (setup-pack! root roles)
        _ (create-task root "Command syntax" "cleaner")
        _ (create-task root "validation" "cleaner")
        batch "batch_20260824T150500Z_000001"
        dir (fs/path (in-process-dir root roles "cleaner") batch)]
    (write-file (fs/path dir "50_command.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
    (write-file (fs/path dir "50_validation.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
    (let [by-name (into {} (map (juxt :name identity) (:tasks (web-state root))))]
      (is (= (get-in by-name ["Command syntax" :batch])
             (get-in by-name ["validation" :batch])))
      (is (some? (get-in by-name ["Command syntax" :batch]))))))









(deftest pack-web-thermometer-ignores-reordered-tail
  ;; Given a pane whose last 20 lines are the same bag in a new order
  ;; When --test-heat-reorder samples both
  ;; Then activity does not rise
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-reorder" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))

(deftest pack-web-thermometer-uses-last-twenty-line-bag
  ;; Given a 25-line pane whose first five lines then change
  ;; When --test-heat-head samples both
  ;; Then activity stays at the baseline (tail bag unchanged)
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-head" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))

(deftest pack-web-card-status-is-last-im-sentence
  ;; Given a specifier card and a pane tail with an I'm sentence
  ;; When --test-state
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'm idle, so I'm running ready_for_next.sh.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (= "HTW" (:name card)))
      (is (str/includes? (str (:status card)) "I'm idle, so I'm running ready_for_next.sh")))))

(deftest pack-web-card-status-includes-codex-summaries
  (doseq [sentence ["Received task extras from the board."
                    "The HHG rules are now settled."
                    "The operator resolved the throw messages."
                    "Completed extras and queued the coder handoff."
                    "The exact-commit audit found no remaining gaps."]]
    (let [root (tmp-dir)
          _ (setup-pack! root)
          _ (create-task root "HTW" "specifier")
          result (pack-web-env root {} "--test-status-pane" (str root)
                               (str sentence "\nesc to interrupt · 3s\n"))
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) sentence)))))

(deftest pack-web-card-status-ignores-tool-trace-lines
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec.\n"
                                  "• Ran 7 commands\n"
                                  "• Edited features/003.feature\n"
                                  "• Added tmp/htw-handoff.txt\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "Ran 7")))))

(deftest pack-web-card-status-includes-continue-sentences
  ;; Given a specifier card and a pane tail whose last matching sentence uses continue
  ;; When --test-status-pane
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'll continue with the cave map.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map.")))))

(deftest pack-web-card-status-joins-wrapped-pane-lines
  ;; Given an I'll sentence split across two pane lines
  ;; When --test-status-pane
  ;; Then status is the full sentence with a space at the wrap
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "I'll continue with the\ncave map for HTW.\n")
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map for HTW.")))))

(deftest pack-web-card-status-ignores-transcript-and-helper-chrome
  ;; Given an I'll sentence then a collapsed transcript line and helper audit copy
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the chrome
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll write the cave stories.\n"
                                  "… +15 lines (ctrl + t to view transcript)\n"
                                  "Fix every finding, commit the corrections, rerun applicable checks, and repeat "
                                  "this audit against the revised candidate before running the handoff command again.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))
    (is (not (str/includes? (str (:status card)) "view transcript")))
    (is (not (str/includes? (str (:status card)) "running the handoff command again")))))

(deftest pack-web-codex-status-is-last-work-bullet
  ;; Given a Codex pane of throwaway bullets then a work bullet with no I'll
  ;; When --test-status-pane
  ;; Then status is that work bullet
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "• You have 1 usage limit reset available. Run /usage to use one.\n"
                                  "• Ran 4 commands · ctrl + t to view transcript\n"
                                  "• Edited features/htw.feature\n"
                                  "• Added tmp/htw_handoff.txt\n"
                                  "• Searching the web\n"
                                  "• Searched the web for yob\n"
                                  "• Working (3s • esc to interrupt)\n"
                                  "• The specification now makes every random domain explicit.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "specification now makes every random domain explicit"))
    (is (not (str/includes? (str (:status card)) "Ran 4")))
    (is (not (str/includes? (str (:status card)) "Working")))))

(deftest pack-web-non-codex-status-still-uses-ill
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'll write the cave stories.\n")
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))))

(deftest pack-web-card-status-ignores-handoff-mail-banner
  ;; Given an I'll sentence and a later If idle, run ready_for_next.sh banner
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the mail line
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "esc to interrupt · 1s\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))))

(deftest pack-web-grok-card-status-uses-work-not-chrome
  ;; Given a Grok pane with an I'll sentence under mail and chrome
  ;; When --test-status-pane
  ;; Then status is the I'll sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "always-approve  shift+tab\n"
                                  "Waiting for response...\n"
                                  "enter:send  Esc:cancel\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))
    (is (not (str/includes? (str (:status card)) "Waiting for response")))))

(deftest pack-web-waiting-cards-say-waiting-in-queue
  ;; Given two specifier cards and a pane I'm sentence
  ;; When --test-status-pane
  ;; Then both cards say waiting in queue and the work row is not marked as a batch
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Holy Hand Grenade" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm specifying HTW.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))
        specifier-row (some #(when (= "specifier" (:role %)) %)
                            (:work_in_flight state))]
    (is (zero? (:exit result)))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))
    (is (= "waiting in queue" (:status (get by-name "Holy Hand Grenade"))))
    (is (= ["HTW" "Holy Hand Grenade"] (:tasks specifier-row)))
    (is (= [] (:batch_tasks specifier-row)))))

(deftest pack-web-in-process-card-gets-pane-status
  ;; Given two coder cards and in-process mail for Holy Hand Grenade
  ;; When --test-status-pane
  ;; Then Holy Hand Grenade has the pane sentence and HTW says waiting in queue
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "HTW" "coder")
        _ (create-task root "Holy Hand Grenade" "coder")
        _ (put-in-process! root roles "coder"
                           {:from "specifier" :task "Holy Hand Grenade"})
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm merging the grenade.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status (get by-name "Holy Hand Grenade")))
                       "I'm merging the grenade"))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))))

(deftest pack-web-shows-yellow-merging-card-for-handback
  ;; Given htw in architect and jump in coder, plus a reverse htw copy in coder in_process
  ;; When --test-status-pane
  ;; Then a merging card for htw is in coder with Merging refactorer, jump waits, real htw stays architect
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        _ (write-file
           (fs/path (in-process-dir root roles "coder")
                    "00_from_refactorer_to_coder.handoff")
           (str "from: refactorer\nto: coder\npriority: 00\ntype: git_handoff\n"
                "task: htw\nnon-forwarding: true\n\nmerge\n"))
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "• The reverse handoff is structurally reconciled.\n")
        state (json/parse-string (:out result) true)
        cards (:tasks state)
        merging (filterv :merging cards)
        jump-card (first (filter #(= "jump" (:name %)) cards))
        htw-card (first (filter #(and (= "htw" (:name %)) (= "architect" (:lane %))) cards))]
    (is (zero? (:exit result)))
    (is (= ["htw" "htw" "jump"] (mapv :name cards)))
    (is (= 1 (count merging)))
    (is (= "coder" (:lane (first merging))))
    (is (= "htw" (:name (first merging))))
    (is (= "Merging refactorer" (:status (first merging))))
    (is (= "waiting in queue" (:status jump-card)))
    (is (= "architect" (:lane htw-card))))
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        state (web-state root)
        merging (filterv :merging (:tasks state))]
    (is (= [] merging))
    (is (= "architect" (task-lane root "htw")))))

(deftest pack-web-pending-approval-card-says-waiting-for-approval
  ;; Given HTW in specifier and a pending specifier→coder git_handoff for HTW
  ;; When --test-state
  ;; Then HTW status is Waiting for approval
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Command Syntax" "specifier")]
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
     "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (web-state root)
          by-name (into {} (map (juxt :name identity) (:tasks state)))]
      (is (= "Waiting for approval" (:status (get by-name "HTW"))))
      (is (= "waiting in queue" (:status (get by-name "Command Syntax")))))))

(deftest pack-web-rejected-card-says-rejected
  ;; Given HTW is rejected
  ;; When --test-state
  ;; Then HTW status is REJECTED
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
    (let [card (first (:tasks (web-state root)))]
      (is (= "REJECTED" (:status card))))))

(deftest pack-web-delete-removes-a-rejected-card
  ;; Given a rejected HTW card
  ;; When POST /api/tasks/delete
  ;; Then the card is gone from the board
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        old-id (:id (task-card root "HTW"))
        _ (increment-audit! root old-id)
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? (fs/path root ".swarmforge/board/HTW.txt"))))
    (is (fs/exists? (fs/path root "tasks/HTW.md")))
    (create-task root "HTW" "specifier")
    (let [replacement (task-card root "HTW")]
      (is (not= old-id (:id replacement)))
      (is (= 0 (:audit_count replacement))))))

(deftest pack-web-delete-rejected-purges-handoffs-into-rejected-tasks
  ;; Given a rejected HTW card with a pending git_handoff
  ;; When POST /api/tasks/delete
  ;; Then the card, notify, and handoff are gone and rejected-tasks keeps the set
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (increment-audit! root (:id (task-card root "HTW")))
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
        _ (write-file pending
                      "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
        _ (write-pending-audit! root "HTW")
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-HTW"))))
    (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks")))))

(deftest pack-web-retry-moves-a-completed-retry-note-back-to-in-process
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "completed_at: 2026-08-26T22:45:36.178441Z\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (let [result (pack-web root false "--test-retry-task" (str root) "50_hello" "use an RNG")]
      (is (zero? (:exit result)))
      (is (fs/exists? in-process))
      (is (not (fs/exists? completed)))
      (is (str/includes? (slurp (str in-process)) (str "task_id: " task-id))))))

(deftest pack-web-second-retry-does-not-leave-copies-in-both-inboxes
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "first"))))
    (is (zero? (:exit (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"}}
                           (script "done_with_current.sh")))))
    (is (fs/exists? completed))
    (is (not (fs/exists? in-process)))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_second" "second"))))
    (is (fs/exists? in-process))
    (is (not (fs/exists? completed)))))

(deftest pack-web-retry-rejected-queues-a-master-note
  ;; Given a pending git_handoff
  ;; When POST /api/tasks/retry with comments
  ;; Then the card stays, original body is unchanged, audit_count increases, and no New Task note is queued
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        _ (increment-audit! root task-id)
        original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
        _ (write-file pending
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n\nold\n"))
        _ (write-pending-audit! root task-id)
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-retry-task" (str root)
                         "50_hello" "use an RNG")
        card (first (:tasks (web-state root)))
        notes (if (fs/directory? (fs/path root ".swarmforge/handoffs/outbox"))
                (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))
                [])]
    (is (zero? (:exit result)))
    (is (= "specifier" (:lane card)))
    (is (= 2 (:audit_count card)))
    (is (not= "REJECTED" (:status card)))
    (is (= original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))))

(deftest pack-web-retry-snapshots-rejected-branches-without-reset
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "story.md") "offer-1\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer 1")
      (let [first-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " first-sha "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_first" "first comments"))))
        (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (= first-sha head))
          (is (not= base head))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (str/includes? branches (str "rejected/" task-id "/latest"))))
        (write-file (fs/path root "story.md") "offer-2\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Offer 2")
        (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              pending2 (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")]
          (write-file pending2
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n"
                           "commit: " second-sha "\n"
                           "task_base_commit: " base "\n\n"
                           "payload\n"))
          (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                      "50_second" "second comments"))))
          (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
                branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
            (is (= second-sha head))
            (is (str/includes? branches (str "rejected/" task-id "/1")))
            (is (str/includes? branches (str "rejected/" task-id "/2")))
            (is (str/includes? branches (str "rejected/" task-id "/latest")))
            (is (= 2 (:audit_count (task-card root "HTW"))))))))))

(deftest pack-web-retry-restores-wandered-head
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))]
      (write-file (fs/path root "story.md") "offer\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n\npayload\n"))
        (write-file (fs/path root "story.md") "wander\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Wander")
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "stay on the offer"))))
        (is (= offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))))))))

(deftest pack-web-retry-keeps-task-base-for-the-next-git-handoff
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (write-file (fs/path root "README.md") "initial\n")
    (run {:dir root} "git" "add" "README.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Initial")
    (setup-pack! root ["specifier" "coder"])
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "tasks/HTW.md") "# HTW\n\nImplement the stories.\n")
      (write-file (fs/path root "extra.md") "first offer\n")
      (run {:dir root} "git" "add" "tasks/HTW.md" "extra.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "use an RNG"))))
        (write-file (fs/path root "more.md") "stacked\n")
        (run {:dir root} "git" "add" "more.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Stacked")
        (write-file (fs/path root "tmp/retry.handoff")
                    "type: git_handoff\nto: coder\npriority: 50\ntask: HTW\n")
        (let [opts {:dir root :env {"SWARMFORGE_ROLE" "specifier"} :ok? false}
              first-call (run opts (script "swarm_handoff.sh")
                              (str (fs/path root "tmp/retry.handoff")))]
          (is (zero? (:exit first-call)))
          (is (str/includes? (:out first-call) "AUDIT_REQUIRED"))
          (let [queued (run (assoc opts :ok? true) (script "swarm_handoff.sh")
                            (str (fs/path root "tmp/retry.handoff")))
                outbox (fs/glob (fs/path root ".swarmforge/handoffs/outbox") "*.handoff")
                content (slurp (str (first outbox)))]
            (is (zero? (:exit queued)))
            (is (str/includes? content "artifacts:"))
            (is (str/includes? content "extra.md"))
            (is (str/includes? content "more.md"))
            (is (str/includes? content "tasks/HTW.md"))))))))

(deftest pack-web-serves-a-document
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web root false "--test-doc" (str root) "tasks/HTW.md")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "HTW"))
    (is (str/includes? (:out result) "Integrate HTW stories"))))

(defn raw-state [root]
  (json/parse-string (:out (pack-web root true "--test-state" (str root)))))

(defn write-pending-approval! [root {:keys [id task task-id artifacts body]}]
  (write-file
   (fs/path root ".swarmforge/handoffs/pending_approval" (str id ".handoff"))
   (str "from: specifier\n"
        "to: coder\n"
        "type: git_handoff\n"
        "task_id: " (or task-id task) "\n"
        "task: " task "\n"
        (when artifacts (str "artifacts: " artifacts "\n"))
        "\n"
        (or body "payload\n"))))

(deftest pack-web-saves-remedial-comments-on-the-pending-approval
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"
                                         :artifacts "features/console.feature"})
        saved (pack-web root false "--test-save-comments" (str root)
                        "50_hello" "features/console.feature" "use an RNG")
        reviews (get (first (get (raw-state root) "approvals")) "reviews")]
    (is (zero? (:exit saved)))
    (is (= "use an RNG" (get reviews "features/console.feature")))
    (let [blanked (pack-web root false "--test-save-comments" (str root)
                            "50_hello" "features/console.feature" "  \n")
          after (get (first (get (raw-state root) "approvals")) "reviews")]
      (is (zero? (:exit blanked)))
      (is (= "" (get after "features/console.feature")))
      (is (contains? after "features/console.feature")))))

(deftest pack-web-document-api-keeps-comment-history-and-last-diff
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        first-sha (do (write-file (fs/path root "features/console.feature")
                                  "Feature: cave\n---\n  Scenario: one\n")
                      (run {:dir root} "git" "add" "features/console.feature")
                      (run {:dir root} "git" "commit" "-q" "-m" "offer-1")
                      (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))
        _ (write-file
           (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
           (str "from: specifier\nto: coder\ntype: git_handoff\n"
                "task_id: " task-id "\ntask: HTW\n"
                "commit: " first-sha "\n"
                "artifacts: features/console.feature\n\npayload\n"))
        first-doc (json/parse-string
                   (:out (pack-web root true "--test-api-doc" (str root)
                                   "features/console.feature" "50_first"))
                   true)]
    (is (false? (:has_diff first-doc)))
    (is (= [] (:history first-doc)))
    (is (str/includes? (:text first-doc) "Feature: cave"))
    (pack-web root true "--test-save-comments" (str root)
              "50_first" "features/console.feature" "needs an RNG")
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "retry the spec"))))
    (write-file (fs/path root "features/console.feature") "Feature: cave\n  Scenario: two\n")
    (run {:dir root} "git" "add" "features/console.feature")
    (run {:dir root} "git" "commit" "-q" "-m" "offer-2")
    (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
       (str "from: specifier\nto: coder\ntype: git_handoff\n"
            "task_id: " task-id "\ntask: HTW\n"
            "commit: " second-sha "\n"
            "artifacts: features/console.feature\n\npayload\n"))
      (let [doc (json/parse-string
                 (:out (pack-web root true "--test-api-doc" (str root)
                                 "features/console.feature" "50_second"))
                 true)
            hist (vec (:history doc))]
        (is (true? (:has_diff doc)))
        (is (= 2 (count hist)))
        (is (= "needs an RNG" (:text (first hist))))
        (is (= "retry the spec" (:text (second hist))))
        (is (not (str/blank? (:at (first hist)))))
        (is (not (str/blank? (:at (second hist)))))
        (is (some #(and (= "del" (:type %)) (str/includes? (str (:text %)) "one"))
                  (:lines doc)))
        (is (some #(and (= "del" (:type %)) (= "---" (:text %)))
                  (:lines doc)))
        (is (some #(and (= "add" (:type %)) (str/includes? (str (:text %)) "two"))
                  (:lines doc)))
        (is (some #(and (= "same" (:type %)) (str/includes? (str (:text %)) "Feature: cave"))
                  (:lines doc)))))
    (pack-web root true "--test-approve" (str root) "50_second")
    (is (not (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id "reviews.json"))))))

(deftest pack-web-document-api-hides-diff-when-git-fails
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
    (run {:dir root} "git" "branch" "-f" (str "rejected/" task-id "/latest") sha)
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
     (str "from: specifier\nto: coder\ntype: git_handoff\n"
          "task_id: " task-id "\ntask: HTW\n"
          "commit: notacommit\n"
          "artifacts: features/console.feature\n\npayload\n"))
    (let [doc (json/parse-string
               (:out (pack-web root true "--test-api-doc" (str root)
                               "features/console.feature" "50_hello"))
               true)]
      (is (false? (:has_diff doc)))
      (is (= [] (:lines doc))))))

(deftest pack-web-approve-discards-remedial-comments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"})
        _ (pack-web root false "--test-save-comments" (str root)
                    "50_hello" "features/console.feature" "use an RNG")
        result (pack-web root false "--test-approve" (str root) "50_hello")]
    (is (zero? (:exit result)))
    (is (= [] (pending-names root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))

(deftest pack-web-retry-delivers-remedial-comments-to-master
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [task-id (:id (task-card root "HTW"))]
      (write-pending-approval! root {:id "50_hello" :task "HTW" :task-id task-id})
      (pack-web root false "--test-save-comments" (str root)
                "50_hello" "features/console.feature" "use an RNG")
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root) "50_hello" "")
            argv (read-argv argv-file)
            injected (slurp (str argv-file ".stdin"))]
        (is (zero? (:exit result)))
        (is (str/includes? injected "features/console.feature"))
        (is (str/includes? injected "use an RNG"))
        (is (not (str/includes? injected "New Task")))
        (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))))

(deftest pack-web-post-task-duplicate-keeps-the-server
  ;; Given a card named HTW
  ;; When POST /api/tasks uses HTW again
  ;; Then it reports Duplicate and does not create a second card
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (pack-web root true "--test-post-task" (str root) "HTW" "first")
        duplicate (pack-web root false "--test-post-task" (str root) "HTW" "second")
        listed (:out (list-tasks root))
        htw-rows (filter #(str/starts-with? % "HTW\t") (str/split-lines listed))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= 1 (count htw-rows)))))

(deftest pack-web-unknown-approval-keeps-the-server
  ;; Given a pack with no pending approval
  ;; When POST /api/approvals/missing/approve
  ;; Then it reports Unknown approval and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-approve" (str root) "no-such-id")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown approval"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))

(deftest pack-web-unknown-clarification-keeps-the-server
  ;; Given a pack with no pending clarification
  ;; When POST /api/clarifications/missing/answer
  ;; Then it reports Unknown clarification and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-answer-clarification"
                         (str root) "no-such-id" "nope")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown clarification"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))

(deftest pack-web-clarification-posts-to-attention-and-answers-into-the-role
  ;; Given QA posts a clarification question
  ;; When the operator answers
  ;; Then /api/state listed it and the answer is injected into QA with the durable id
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))
          pending (web-state root)
          item (first (:clarifications pending))]
      (is (zero? (:exit created)))
      (is (str/starts-with? id "clar-"))
      (is (= "QA" (:role item)))
      (is (str/includes? (:body item) "Does the bat drop to any of 20 rooms?"))
      (is (= "pending" (:status item)))
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.\nUse all rooms.")
      (let [argv (read-argv argv-file)
            stdin (slurp (str argv-file ".stdin"))
            done (first (:clarifications (web-state root)))
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/clarifications/done")))))]
        (is (str/includes? stdin id))
        (is (str/includes? stdin "Yes, 1 to 20."))
        (is (str/includes? stdin "Use all rooms."))
        (is (not-any? #(str/includes? (str %) "Use all rooms.") argv))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20.\nUse all rooms." (:response done)))
        (is (str/includes? stored "response: Yes, 1 to 20.\\nUse all rooms.\n"))))))

(deftest pack-dashboard-request-accepts-an-already-answered-clarification
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")
        ack (fs/path root "tmp" "answer.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (write-file ack "ignored local ack\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [acked (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "answer" id (str ack))
            done (first (:clarifications (web-state root)))
            pending-requests (fs/path root ".swarmforge/dashboard/requests/pending")
            done-file (first (fs/list-dir
                              (fs/path root ".swarmforge/dashboard/clarifications/done")))]
        (is (zero? (:exit acked)))
        (is (str/includes? (:out acked) (str "ANSWERED: " id)))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20." (:response done)))
        (is (str/includes? (slurp (str done-file)) "response: Yes, 1 to 20.\n"))
        (is (or (not (fs/directory? pending-requests))
                (empty? (fs/list-dir pending-requests)))))
      (let [unknown (run {:dir root :env {"SWARMFORGE_ROLE" "QA"} :ok? false}
                         (script "pack_dashboard_request.sh")
                         "answer" "clar-missing" (str ack))]
        (is (not (zero? (:exit unknown))))
        (is (str/includes? (str (:err unknown) (:out unknown))
                           "Unknown pending request"))))))

(deftest pack-web-serves-the-task-body
  ;; Given New Task HTW with body
  ;; When pack_web --test-task HTW
  ;; Then it prints the name and body
  (let [root (tmp-dir)
        text "Find the stories in ~/junk/htw-stories and implement them."]
    (setup-pack! root)
    (pack-board root true
                "create" "--root" (str root)
                "--name" "HTW" "--lane" "specifier" "--text" text)
    (let [result (pack-web root false "--test-task" (str root) "HTW")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "HTW"))
      (is (str/includes? (:out result) text)))))







(deftest pack-web-card-status-matches-unicode-im-and-i-keywords
  ;; Given a pane with Unicode I’m and let me
  ;; When --test-status-pane
  ;; Then those sentences can be card status
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        im (pack-web-env root {} "--test-status-pane" (str root)
                         "I’m merging the QA handoff.\nesc to interrupt · 1s\n")
        let-me (pack-web-env root {} "--test-status-pane" (str root)
                             "Let me inspect the conflicts.\nesc to interrupt · 1s\n")
        handoff (pack-web-env root {} "--test-status-pane" (str root)
                              "HANDOFF queued to cleaner.\nesc to interrupt · 1s\n")]
    (is (str/includes? (:out im) "merging the QA handoff"))
    (is (str/includes? (:out let-me) "Let me inspect the conflicts"))
    (is (str/includes? (:out handoff) "HANDOFF queued to cleaner"))))

(deftest pack-web-card-status-stays-until-replaced
  ;; Given a status sentence then a pane with no status keywords
  ;; When --test-status-persist
  ;; Then the first sentence remains
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-persist" (str root)
                             "I'm working on HTW.\n"
                             "esc to interrupt · 9s\n")
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:first body)) "I'm working on HTW"))
    (is (= (:first body) (:second body)))))

(deftest pack-web-work-queue-lists-every-in-process-task
  ;; Given two in-process handoffs on architect
  ;; When --test-state
  ;; Then the row's task is the first name and tasks lists both
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row))))))

(deftest pack-web-work-queue-marks-only-real-batches
  ;; Given a real in-process batch on architect
  ;; When --test-state
  ;; Then the batch task names are exposed for the dashboard + indicator
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "batch_20260615T000001Z_000001/10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "batch_20260615T000001Z_000001/11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row)))
      (is (= ["HTW" "Command Syntax"] (:batch_tasks row))))))



(deftest pack-web-thermometer-heats-on-work-after-handoff-mail
  ;; Given a Codex pane whose only cut-point used to be an old › mail line
  ;; When later transcript lines change
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-mail" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-grok-thermometer-heats-on-waiting-timer
  ;; Given a Grok pane whose only change is Waiting for response Ns
  ;; When --test-heat-grok
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        _ (set-backend! root "grok")
        result (pack-web root false "--test-heat-grok" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-thermometer-heats-on-collapsed-transcript-counts
  ;; Given Codex collapsed output whose +N line changes
  ;; When --test-heat-collapse
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-collapse" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-clarification-answer-echoes-the-question
  ;; Given QA asked a clarification
  ;; When the operator answers
  ;; Then the injected pane text includes the question and Clarification requested from
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [id (str/trim (:out (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                                  (script "pack_dashboard_request.sh")
                                  "clarify" (str question))))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [argv (read-argv argv-file)
            stdin (slurp (str argv-file ".stdin"))]
        (is (str/includes? stdin "Clarification requested from: QA"))
        (is (str/includes? stdin "Does the bat drop to any of 20 rooms?"))
        (is (str/includes? stdin "Yes, 1 to 20."))
        (is (not-any? #(str/includes? (str %) "Yes, 1 to 20.") argv))))))

(defn seed-mini-forge! [root]
  (fs/create-dirs (fs/path root "projects"))
  (write-file (fs/path root "swarmforge/scripts/keep.sh") "#!/bin/sh\n")
  (write-file (fs/path root "swarmforge/constitution/articles/engineering.prompt") "eng\n")
  (write-file (fs/path root "swarmforge/constitution/articles/workflow.prompt") "wf\n")
  (write-file (fs/path root "swarmforge/constitution/articles/handoffs.prompt") "ho\n")
  (doseq [pack ["two-pack" "four-pack" "six-pack"]]
    (write-file (fs/path root "packs" pack "swarmforge/swarmforge.conf")
                "window specifier grok master\nwindow coder grok coder\n")
    (write-file (fs/path root "packs" pack "swarmforge/constitution.prompt") "pack-const\n")
    (write-file (fs/path root "packs" pack "swarmforge/roles/specifier.prompt") "spec\n")
    (write-file (fs/path root "packs" pack "swarmforge/roles/coder.prompt") "coder\n")))

(defn use-full-forge-scripts! [root]
  (let [dest (fs/path root "swarmforge" "scripts")]
    (fs/delete-tree dest)
    (fs/copy-tree (fs/path repo-root "swarmforge" "scripts") dest)))

(defn tmux-sessions [socket]
  (if (fs/exists? socket)
    (let [result (run {:dir repo-root :ok? false}
                      "tmux" "-S" (str socket)
                      "list-sessions" "-F" "#{session_name}")]
      (if (zero? (:exit result))
        (->> (str/split-lines (:out result))
             (remove str/blank?)
             sort
             vec)
        []))
    []))

(def supervised-fake-agent
  "sh -c 'if [ -n \"${SWARMFORGE_TEST_RUNTIME_ENV_LOG:-}\" ]; then printf \"%s\\n\" \"${SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY:-}\" >> \"$SWARMFORGE_TEST_RUNTIME_ENV_LOG\"; fi; echo \"<<AGENT READY>>\"; while IFS= read -r line; do case \"$line\" in \"[phone \"*) id=${line#\\[phone }; id=${id%%]*}; mkdir -p tmp; printf \"fake answer from %s\\n\" \"$id\" > tmp/answer.txt; ./swarmforge/scripts/pack_dashboard_request.sh answer \"$id\" ./tmp/answer.txt; echo \"<<AGENT READY>>\";; esac; done'")

(deftest forge-infers-github-project-name
  (let [result (pack-web (tmp-dir) true "--test-inferred-name" "unclebob/swarm-forge" "github")]
    (is (zero? (:exit result)))
    (is (= "swarm-forge" (str/trim (:out result))))))

(deftest forge-new-project-writes-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
          dest (fs/path root "projects/cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? dest))
      (is (= "Hunt the wumpus\n" (slurp (str (fs/path dest "mission.md")))))
      (is (fs/exists? (fs/path dest "swarmforge/swarmforge.conf")))
      (is (fs/exists? (fs/path dest "swarmforge/roles/specifier.prompt")))
      (is (str/includes? (slurp (str (fs/path dest ".swarmforge/pack"))) "four-pack"))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave")))))

(deftest forge-new-project-takes-the-packs-ignore-lines
  ;; Given a pack that carries the lines its language needs git to leave alone
  ;; When a project is composed from it and opened again later
  ;; Then the project ignores those lines, keeps its own, and adds none twice
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root "packs/six-pack/gitignore") ".idea/\nbuild/\n.gradle/\n")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "six-pack" "m")
          ignore (fs/path root "projects/cave/.gitignore")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/exists? ignore))
      (let [lines (set (str/split-lines (slurp (str ignore))))]
        (is (contains? lines ".idea/"))
        (is (contains? lines "build/"))
        (is (contains? lines ".gradle/")))
      ;; The project writes a line of its own, then the pack is overlaid again.
      (spit (str ignore) (str (slurp (str ignore)) "coverage/\n"))
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (let [lines (str/split-lines (slurp (str ignore)))]
        (is (contains? (set lines) "coverage/"))
        (is (= 1 (count (filter #{".idea/"} lines))))
        (is (= 1 (count (filter #{"build/"} lines))))))))

(deftest a-projects-language-is-its-own-not-its-packs
  ;; Given a pack carrying a language.conf of its own
  ;; When a project is composed from it and later says what it is written in
  ;; Then the pack decides nothing: a project that has not said has no language yet, and
  ;; the answer it does write is its own and survives every refresh
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root "packs/six-pack/language.conf") "Kotlin\n")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "six-pack" "m")
          language (fs/path root "projects/cave/swarmforge/language.conf")]
      (is (zero? (:exit result)) (:err result))
      (is (not (fs/exists? language))
          "a pack's language.conf must not decide what a project is written in")
      (write-file language "Go\n")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (is (= "Go\n" (slurp (str language)))
          "the project's own answer survives the refresh"))))

(deftest forge-new-project-writes-no-identity-of-its-own
  ;; Given a machine with an identity of its own, and a machine with none
  ;; When a project is created under each
  ;; Then the project's repository claims no identity, its scaffold commit is
  ;; there, and the loan is the machine's own or a one-off - never a name written
  ;; into the repository that would claim every later commit too
  (let [root (tmp-dir)
        home-with (fs/path root "home-with")
        home-without (fs/path root "home-without")]
    (seed-mini-forge! root)
    (write-file (fs/path home-with ".gitconfig")
                "[user]\n\tname = Operator\n\temail = operator@example.com\n")
    (fs/create-dirs home-without)
    (doseq [[label home expected] [["with" home-with "Operator <operator@example.com>"]
                                   ["without" home-without "SwarmForge Scaffold <swarmforge@localhost>"]]]
      (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1" "HOME" (str home)}
                                 "--test-new-project" (str root)
                                 (str "cave-" label) "two-pack" "m")
            dest (fs/path root "projects" (str "cave-" label))]
        (is (zero? (:exit result)) (:err result))
        (let [asked (fn [key]
                      (str/trim (:out (run {:dir dest :ok? false}
                                           "git" "config" "--local" "--get" key))))]
          (is (str/blank? (asked "user.name"))
              "the project's repository claims a name of its own")
          (is (str/blank? (asked "user.email"))
              "the project's repository claims an email of its own"))
        (let [author (str/trim (:out (run {:dir dest}
                                          "git" "log" "-1" "--format=%an <%ae>")))]
          (is (= expected author)))))))

(deftest forge-new-project-rejects-existing-name
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "one")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-new-project" (str root) "cave" "two-pack" "two")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already exists")))))

(deftest forge-open-already-open-alerts
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "cave")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already open")))))

(deftest forge-close-leaves-the-directory
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-close-project" (str root) "cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? (fs/path root "projects/cave")))
      (is (fs/exists? (fs/path root "projects/cave/mission.md")))
      (is (not (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave"))))))

(deftest forge-open-without-pack-file-is-rejected
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects/orphan"))
    (init-project-git! (fs/path root "projects/orphan"))
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "orphan")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "No pack recorded")))))

(deftest forge-opens-and-closes-a-single-agent-project-without-a-pack
  ;; Given a project carrying the single-agent marker and no pack file
  ;; When the forge opens and closes it
  ;; Then it is accepted, refreshed with the shared scripts, and marked open
  (let [root (tmp-dir)
        project (fs/path root "projects/agent")]
    (seed-mini-forge! root)
    (write-file (fs/path root "swarmforge/scripts/agent-runner.bb")
                "#!/usr/bin/env bb\n(println \"stop\")\n")
    (fs/create-dirs (fs/path project ".swarmforge"))
    (write-file (fs/path project ".swarmforge/single-agent") "agent\n")
    (write-file (fs/path project ".swarmforge/roles.tsv")
                (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                        project))
    (init-project-git! project)
    (let [open (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                             "--test-open-project" (str root) "agent")]
      (is (zero? (:exit open)) (:err open))
      (is (fs/regular-file? (fs/path project "swarmforge/scripts/agent-runner.bb")))
      (is (re-find #"(?m)^agent\tmaster\t.*\tswarmforge-agent\tAgent\tcodex\ttask$"
                   (slurp (str (fs/path project ".swarmforge/roles.tsv")))))
      (is (str/includes?
           (slurp (str (fs/path root ".swarmforge" "open-projects")))
           "agent")))
    (let [close (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                              "--test-close-project" (str root) "agent")]
      (is (zero? (:exit close)) (:err close))
      (is (not (str/includes?
                (slurp (str (fs/path root ".swarmforge" "open-projects")))
                "agent"))))))

(deftest a-single-agent-open-is-supervised-and-recovers
  ;; Given a composed single-agent project
  ;; When the forge opens it, its relay disappears, and it is opened again
  ;; Then the dashboard and agent live under one project relay, recovery replaces
  ;; that relay, and close removes both the relay and its children
  (let [root (tmp-dir)
        project (fs/path root "projects/agent")
        runtime-socket (fs/path project ".swarmforge/runtime-tmux.sock")
        agent-socket (fs/path project ".swarmforge/agent-tmux.sock")
        url-file (fs/path project ".swarmforge/dashboard-url")
        runtime-env (fs/path root "private" "project-runtime.env")
        runtime-env-log (fs/path root "runtime-env.log")
        open-env {"SWARMFORGE_AGENT_COMMAND" supervised-fake-agent
                  "SWARMFORGE_PROJECT_RUNTIME_ENV" (str runtime-env)
                  "SWARMFORGE_TEST_RUNTIME_ENV_LOG" (str runtime-env-log)
                  "SWARMFORGE_OPEN_BROWSER" "0"
                  "SWARMFORGE_PREVENT_SLEEP" "0"
                  "SWARMFORGE_RUNTIME_READY_TIMEOUT_MS" "10000"}]
    (try
      (seed-mini-forge! root)
      (use-full-forge-scripts! root)
      (write-file runtime-env
                  "SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY='fixture-snapshots::default::file:///tmp/forge-runtime-snapshots'\n")
      (write-file (fs/path project ".swarmforge/single-agent") "agent\n")
      (write-file (fs/path project ".swarmforge/roles.tsv")
                  (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                          project))
      (init-project-git! project)
      (let [opened (pack-web-env root open-env
                                 "--test-open-project" (str root) "agent")
            url (str/trim (slurp (str url-file)))]
        (is (zero? (:exit opened)) (:err opened))
        (is (str/includes?
             (slurp (str (fs/path root ".swarmforge" "open-projects")))
             "agent"))
        (is (= ["swarmforge-runtime"] (tmux-sessions runtime-socket)))
        (is (= ["swarmforge-agent"] (tmux-sessions agent-socket)))
        (is (= ["fixture-snapshots::default::file:///tmp/forge-runtime-snapshots"]
               (str/split-lines (slurp (str runtime-env-log)))))
        (is (str/includes? (slurp url) "SwarmForge"))
        (is (str/includes? (slurp (str url "/api/state")) "\"lanes\""))
        (is (fs/regular-file? (fs/path project ".swarmforge/runtime.edn")))
        (let [duplicate (run {:dir root :env open-env :ok? false}
                             (script "pack_web.sh")
                             "--test-open-project" (str root) "agent")]
          (is (not (zero? (:exit duplicate))))
          (is (str/includes? (str (:err duplicate) (:out duplicate))
                             "already open"))
          (is (= ["swarmforge-runtime"] (tmux-sessions runtime-socket)))))

      (run {:dir root} "tmux" "-S" (str runtime-socket)
           "kill-session" "-t" "=swarmforge-runtime")
      (run {:dir root :ok? false} "tmux" "-S" (str agent-socket) "kill-server")
      (is (= [] (tmux-sessions runtime-socket)))
      (is (= [] (tmux-sessions agent-socket)))
      (let [recovered (pack-web-env root open-env
                                    "--test-open-project" (str root) "agent")]
        (is (zero? (:exit recovered)) (:err recovered))
        (is (= ["swarmforge-runtime"] (tmux-sessions runtime-socket)))
        (is (= ["swarmforge-agent"] (tmux-sessions agent-socket)))
        (is (wait-lines runtime-env-log 2 3000))
        (is (= ["fixture-snapshots::default::file:///tmp/forge-runtime-snapshots"
                "fixture-snapshots::default::file:///tmp/forge-runtime-snapshots"]
               (str/split-lines (slurp (str runtime-env-log))))))

      (let [closed (pack-web-env root {}
                                 "--test-close-project" (str root) "agent")
            open-file (fs/path root ".swarmforge/open-projects")]
        (is (zero? (:exit closed)) (:err closed))
        (is (= [] (tmux-sessions runtime-socket)))
        (is (= [] (tmux-sessions agent-socket)))
        (is (not (fs/exists? url-file)))
        (is (not (fs/exists?
                  (fs/path project ".swarmforge/runtime.edn"))))
        (is (not (str/includes? (slurp (str open-file)) "agent"))))
      (finally
        (run {:dir root :ok? false} (script "pack_web.sh")
             "--test-close-project" (str root) "agent")
        (when (fs/exists? runtime-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str runtime-socket)
               "kill-server"))
        (when (fs/exists? agent-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str agent-socket)
               "kill-server"))))))

(deftest a-failed-single-agent-start-leaves-the-project-closed
  ;; Given a project whose agent runner exits immediately
  ;; When the forge tries to open it
  ;; Then no runtime is left behind and the forge does not record it as open
  (let [root (tmp-dir)
        project (fs/path root "projects/agent")
        runtime-socket (fs/path project ".swarmforge/runtime-tmux.sock")]
    (try
      (seed-mini-forge! root)
      (use-full-forge-scripts! root)
      (write-file (fs/path project ".swarmforge/single-agent") "agent\n")
      (write-file (fs/path project ".swarmforge/roles.tsv")
                  (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                          project))
      (init-project-git! project)
      (write-file (fs/path root "swarmforge/scripts/agent-runner.bb")
                  "#!/usr/bin/env bb\n(println \"runner failed\")\n(System/exit 9)\n")
      (let [opened (run {:dir root
                         :env {"SWARMFORGE_PREVENT_SLEEP" "0"
                               "SWARMFORGE_RUNTIME_READY_TIMEOUT_MS" "3000"}
                         :ok? false}
                        (script "pack_web.sh")
                        "--test-open-project" (str root) "agent")
            open-file (fs/path root ".swarmforge/open-projects")]
        (is (not (zero? (:exit opened))))
        (is (str/includes? (str (:err opened) (:out opened))
                           "Project runtime did not become ready"))
        (is (= [] (tmux-sessions runtime-socket)))
        (is (or (not (fs/exists? open-file))
                (not (str/includes? (slurp (str open-file)) "agent")))))
      (finally
        (when (fs/exists? runtime-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str runtime-socket)
               "kill-server"))))))

(deftest a-pack-open-is-supervised-and-closes-only-its-runtime
  ;; Given a pack project whose runner starts one role session
  ;; When the forge opens it and later closes it
  ;; Then the role and its relay survive the opening command, and close removes
  ;; exactly that project's relay and role session
  (let [root (tmp-dir)
        project (fs/path root "projects/pack")
        runtime-socket (fs/path project ".swarmforge/runtime-tmux.sock")
        project-socket (fs/path project ".swarmforge/role-tmux.sock")
        project-socket-file (fs/path project ".swarmforge/tmux-socket")
        neighbour-socket (fs/path root "neighbour.sock")]
    (try
      (seed-mini-forge! root)
      (write-file (fs/path root "swarmforge/scripts/swarmforge.bb")
                  (str "#!/usr/bin/env bb\n"
                       "(require '[babashka.fs :as fs] '[babashka.process :as process])\n"
                       "(let [[action root] *command-line-args*\n"
                       "      state (fs/path root \".swarmforge\")\n"
                       "      socket-file (fs/path state \"tmux-socket\")\n"
                       "      socket (fs/path state \"role-tmux.sock\")]\n"
                       "  (cond\n"
                       "    (= action \"--start-project\")\n"
                       "    (do\n"
                       "      (fs/create-dirs state)\n"
                       "      (spit (str socket-file) (str socket \"\\n\"))\n"
                       "      (process/sh \"tmux\" \"-S\" (str socket) \"new-session\" \"-d\"\n"
                       "                  \"-s\" \"swarmforge-specifier\" \"sleep\" \"120\")\n"
                       "      (spit (str (fs/path state \"sessions.tsv\"))\n"
                       "            \"1\\tspecifier\\tswarmforge-specifier\\tSpecifier\\tcodex\\n\"))\n"
                       "    (= action \"--stop-project\")\n"
                       "    (process/sh {:continue true} \"tmux\" \"-S\" (str socket) \"kill-server\")\n"
                       "    :else (System/exit 2)))\n"))
      (write-file (fs/path project ".swarmforge/pack") "two-pack\n")
      (init-project-git! project)
      (run {:dir root} "tmux" "-S" (str neighbour-socket)
           "new-session" "-d" "-s" "neighbour" "sleep" "120")
      (let [opened (pack-web-env root {"SWARMFORGE_PREVENT_SLEEP" "0"
                                      "SWARMFORGE_RUNTIME_READY_TIMEOUT_MS" "10000"}
                                 "--test-open-project" (str root) "pack")]
        (is (zero? (:exit opened)) (:err opened))
        (is (= ["swarmforge-runtime"] (tmux-sessions runtime-socket)))
        (is (= ["swarmforge-specifier"] (tmux-sessions project-socket)))
        (is (str/includes?
             (slurp (str (fs/path root ".swarmforge/open-projects")))
             "pack")))
      (let [closed (pack-web-env root {}
                                 "--test-close-project" (str root) "pack")]
        (is (zero? (:exit closed)) (:err closed))
        (is (= [] (tmux-sessions runtime-socket)))
        (is (= [] (tmux-sessions project-socket)))
        (is (fs/regular-file? project-socket-file))
        (is (= ["neighbour"] (tmux-sessions neighbour-socket))))
      (finally
        (run {:dir root :ok? false} (script "pack_web.sh")
             "--test-close-project" (str root) "pack")
        (when (fs/exists? runtime-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str runtime-socket)
               "kill-server"))
        (when (fs/exists? project-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str project-socket)
               "kill-server"))
        (when (fs/exists? neighbour-socket)
          (run {:dir root :ok? false} "tmux" "-S" (str neighbour-socket)
               "kill-server"))))))

(deftest forge-refresh-keeps-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "keep me")
    (let [conf (fs/path root "projects/cave/swarmforge/swarmforge.conf")]
      (spit (str conf) "window specifier grok master extra-flag\n")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (is (= "keep me\n" (slurp (str (fs/path root "projects/cave/mission.md")))))
      (is (str/includes? (slurp (str conf)) "extra-flag")))))

(deftest forge-state-only-lists-managed-project-roots
  (let [root (tmp-dir)
        alpha (fs/path root "projects/alpha-agent")
        middle (fs/path root "projects/middle-pack")
        zeta (fs/path root "projects/zeta-legacy")
        bridge (fs/path root "projects/forgelet-bridge")
        artifact (fs/path root "projects/target")]
    (seed-mini-forge! root)
    (write-file (fs/path alpha ".swarmforge/single-agent") "agent\n")
    (write-file (fs/path middle ".swarmforge/pack") "two-pack\n")
    (write-file (fs/path zeta ".swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\n" zeta))
    (write-file (fs/path bridge "swarmforge/rules/stale.md") "stale bridge rules\n")
    (write-file (fs/path artifact "artifact.txt") "build output\n")
    (write-file (fs/path root ".swarmforge/open-projects")
                "zeta-legacy\nalpha-agent\n")

    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (= ["alpha-agent" "middle-pack" "zeta-legacy"]
             (:all_projects state)))
      (is (= ["zeta-legacy" "alpha-agent"] (:open_projects state))))

    ;; Identity comes from the project marker, never from the directory name.
    (write-file (fs/path bridge ".swarmforge/pack") "six-pack\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (= ["alpha-agent" "forgelet-bridge" "middle-pack" "zeta-legacy"]
             (:all_projects state)))
      (is (= ["zeta-legacy" "alpha-agent"] (:open_projects state))))))

(deftest forge-state-tags-attention-with-project
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path root "projects/cave/.swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tspecifier\tSpecifier\tcodex\ttask\n"
                        (fs/path root "projects/cave")))
    (fs/create-dirs (fs/path root "projects/cave/.swarmforge/board"))
    (write-file (fs/path root "projects/cave/.swarmforge/handoffs/pending_approval/50_hello.handoff")
                "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          approval (first (:approvals state))]
      (is (true? (:forge state)))
      (is (= "cave" (:project approval)))
      (is (= ["cave"] (:open_projects state)))
      (is (= "cave" (:name (first (:projects state))))))))

(deftest forge-state-marks-a-single-agent-project
  ;; Given an open project carrying the single-agent marker and its own dashboard
  ;; When the forge dashboard state is read
  ;; Then the project row carries both the marker and the dashboard link
  (let [root (tmp-dir)
        project (fs/path root "projects/agent")]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path project ".swarmforge"))
    (write-file (fs/path project ".swarmforge/single-agent") "agent\n")
    (write-file (fs/path project ".swarmforge/dashboard-url")
                "http://127.0.0.1:9999\n")
    (write-file (fs/path root ".swarmforge/open-projects") "agent\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          project-state (first (:projects state))]
      (is (true? (:single_agent project-state)))
      (is (= "http://127.0.0.1:9999" (:dashboard_url project-state))))))

(deftest forge-project-completion-stats-are-null-without-valid-completions
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (let [project (completion-project! forge "stats-app")]
      (write-completion-board!
       project
       [(completion-row "stats-app" "spec"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T01:00:00Z"
                        "project-placeholder")
        (completion-row "live-card" "spec"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T01:00:00Z"
                        "live-card")
        (completion-row "bad-time" "done"
                        "not-a-time"
                        "2026-01-01T01:00:00Z"
                        "bad-time")
        (completion-row "negative" "done"
                        "2026-01-01T02:00:00Z"
                        "2026-01-01T01:00:00Z"
                        "negative")
        (completion-row "bad-activation" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T01:00:00Z"
                        "bad-activation")])
      (write-file
       (fs/path project ".swarmforge/card-activations/bad-activation.edn")
       "{:activated_at}\n")
      (let [stats (-> (web-state forge) :projects first :completion_stats)]
        (is (= {:completed_cards 0
                :median_seconds nil
                :mean_seconds nil
                :min_seconds nil
                :max_seconds nil}
               stats))
        (is (= #{:completed_cards :median_seconds :mean_seconds
                 :min_seconds :max_seconds}
               (set (keys stats))))))))

(deftest forge-project-completion-stats-handle-one-completed-card
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (let [project (completion-project! forge "one-app")]
      (write-completion-board!
       project
       [(completion-row "one-card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:22:00Z"
                        "one-card")])
      (is (= {:completed_cards 1
              :median_seconds 1320
              :mean_seconds 1320
              :min_seconds 1320
              :max_seconds 1320}
             (-> (web-state forge) :projects first :completion_stats))))))

(deftest forge-project-completion-stats-use-active-time-and-stay-deterministic
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (let [project (completion-project! forge "mixed-app")]
      (write-completion-board!
       project
       [(completion-row "mixed-app" "spec"
                        "2026-01-01T00:00:00Z"
                        "2026-01-02T00:00:00Z"
                        "project-placeholder")
        (completion-row "legacy" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:10:00Z"
                        "legacy")
        (completion-row "queued" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T03:00:00Z"
                        "queued")
        (completion-row "reopened" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T04:30:00Z"
                        "reopened")
        (completion-row "long-tail" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-02T09:00:00Z"
                        "long-tail")
        (completion-row "still-active" "code"
                        "2026-01-01T00:00:00Z"
                        "2026-01-03T00:00:00Z"
                        "still-active")])
      (write-activation! project "queued" "2026-01-01T02:30:00Z")
      (write-activation! project "reopened" "2026-01-01T04:00:00Z")
      (write-activation! project "long-tail" "2026-01-01T05:00:00Z")
      (is (= {:completed_cards 4
              :median_seconds 1800
              :mean_seconds 26250
              :min_seconds 600
              :max_seconds 100800}
             (-> (web-state forge) :projects first :completion_stats))))))

(deftest completed-card-report-preserves-completion-stats-after-cleanup
  ;; Given a verified done card whose active duration is complete evidence
  ;; When the report records it and removes its ignored board row
  ;; Then cumulative completion statistics stay unchanged and progress clears
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (write-file (fs/path forge ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                        forge))
    (let [project (completion-project! forge "history-app")
          task-note (fs/path project "tasks" "one-card.md")
          board-copy (fs/path project ".swarmforge/board/one-card.txt")]
      (write-completion-board!
       project
       [(completion-row "one-card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:22:00Z"
                        "one-card")])
      (write-file task-note "# one-card\n\nCommitted note.\n")
      (write-file board-copy "Local board copy.\n")
      (let [before (web-state forge)
            stats-before (:completion_stats (first (:projects before)))
            input (write-completed-card-report!
                   forge
                   "req-report-11111111111111111111111111111111"
                   [{:project "history-app"
                     :task_id "one-card"
                     :verification "verified"}])
            recorded (completed-card-report
                      forge true "record" "--root" (str forge) (str input))]
        (is (str/includes? (:out recorded) "RECORDED:"))
        (let [record-file (fs/path forge "completed-card-reports"
                                   "req-report-11111111111111111111111111111111.edn")
              record (edn/read-string (slurp (str record-file)))
              backlog (slurp (str (fs/path forge "backlog.md")))]
          (is (= "verified" (:verification_result record)))
          (is (= 1320 (-> record :cards first :active_seconds)))
          (is (= "one-card" (-> record :cards first :card_name)))
          (is (= [] (:retained_cards record)))
          (is (str/includes? backlog "Completed-card report"))
          (is (str/includes? backlog "| history-app | one-card |")))
        (let [removed (completed-card-report
                       forge true "remove" "--root" (str forge)
                       "req-report-11111111111111111111111111111111")
              after (web-state forge)]
          (is (str/includes? (:out removed) "REMOVED:1:0"))
          (is (= stats-before (:completion_stats (first (:projects after)))))
          (is (= 0 (done-count after "history-app")))
          (is (not (fs/exists? board-copy)))
          (is (fs/regular-file? task-note)))))))

(deftest completed-card-report-retains-partial-cards-and-reconstructs-stats
  ;; Given verified, retained and not-yet-reported done cards
  ;; When the report is recorded and cleaned
  ;; Then only the verified row disappears, retained evidence stays explicit
  ;; And another report can later verify the retained card without double counting
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (write-file (fs/path forge ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                        forge))
    (let [project (completion-project! forge "partial-app")
          token "ghp_1234567890abcdefghijklmnopqrstuvwxyz"
          secret-path (str (fs/path forge "private" "reason.txt"))]
      (write-completion-board!
       project
       [(completion-row "verified-card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:10:00Z"
                        "verified-card")
        (completion-row "retained-card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:15:00Z"
                        "retained-card")
        (completion-row "unreported-card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:05:00Z"
                        "unreported-card")])
      (let [input (write-completed-card-report!
                   forge
                   "req-report-22222222222222222222222222222222"
                   [{:project "partial-app"
                     :task_id "verified-card"
                     :verification "verified"}
                    {:project "partial-app"
                     :task_id "retained-card"
                     :verification "retained"
                     :retained_reason (str "Needs " secret-path " token " token)}])]
        (completed-card-report forge true "record" "--root" (str forge) (str input))
        (let [record-file (fs/path forge "completed-card-reports"
                                   "req-report-22222222222222222222222222222222.edn")
              record-text (slurp (str record-file))
              record (edn/read-string record-text)]
          (is (= "partial" (:verification_result record)))
          (is (= ["retained-card"] (mapv :task_id (:retained_cards record))))
          (is (str/includes? record-text "[redacted-path]"))
          (is (str/includes? record-text "[redacted-token]"))
          (is (not (str/includes? record-text secret-path)))
          (is (not (str/includes? record-text token))))
        (completed-card-report
         forge true "remove" "--root" (str forge)
         "req-report-22222222222222222222222222222222")
        (let [after-first (web-state forge)
              stats (-> after-first :projects first :completion_stats)]
          (is (= 2 (done-count after-first "partial-app")))
          (is (= 2 (:completed_cards stats)))
          (is (= 450 (:mean_seconds stats))))
        (let [input (write-completed-card-report!
                     forge
                     "req-report-33333333333333333333333333333333"
                     [{:project "partial-app"
                       :task_id "retained-card"
                       :verification "verified"}])]
          (completed-card-report forge true "record" "--root" (str forge) (str input))
          (completed-card-report
           forge true "remove" "--root" (str forge)
           "req-report-33333333333333333333333333333333"))
        (let [after-second (web-state forge)]
          (is (= 1 (done-count after-second "partial-app")))
          (is (= {:completed_cards 3
                  :median_seconds 600
                  :mean_seconds 600
                  :min_seconds 300
                  :max_seconds 900}
                 (-> after-second :projects first :completion_stats))))))))

(deftest completed-card-report-rejects-duplicates-without-changing-evidence
  ;; Given a recorded report
  ;; When the same report id is submitted with different verification
  ;; Then it is rejected and both the machine record and backlog stay unchanged
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (write-file (fs/path forge ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                        forge))
    (let [project (completion-project! forge "duplicate-app")]
      (write-completion-board!
       project
       [(completion-row "card" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:10:00Z"
                        "card")])
      (let [id "req-report-44444444444444444444444444444444"
            first-input (write-completed-card-report!
                         forge id
                         [{:project "duplicate-app"
                           :task_id "card"
                           :verification "verified"}])]
        (completed-card-report forge true "record" "--root" (str forge)
                               (str first-input))
        (let [record-file (fs/path forge "completed-card-reports" (str id ".edn"))
              backlog-file (fs/path forge "backlog.md")
              record-before (slurp (str record-file))
              backlog-before (slurp (str backlog-file))
              retry-input (write-completed-card-report!
                           forge id
                           [{:project "duplicate-app"
                             :task_id "card"
                             :verification "retained"}])
              duplicate (completed-card-report
                         forge false "record" "--root" (str forge)
                         (str retry-input))]
          (is (= 1 (:exit duplicate)))
          (is (str/includes? (:err duplicate) "Duplicate report id"))
          (is (= record-before (slurp (str record-file))))
          (is (= backlog-before (slurp (str backlog-file)))))))))

(deftest completion-stats-ignore-malformed-history-and-merge-current-done
  ;; Given a valid historical report, malformed history files and one unreported card
  ;; When statistics are read
  ;; Then malformed evidence is skipped and current plus historical durations merge
  (let [forge (tmp-dir)]
    (seed-mini-forge! forge)
    (write-file (fs/path forge ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                        forge))
    (let [project (completion-project! forge "merge-app")]
      (write-completion-board!
       project
       [(completion-row "historic" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:10:00Z"
                        "historic")
        (completion-row "current" "done"
                        "2026-01-01T00:00:00Z"
                        "2026-01-01T00:05:00Z"
                        "current")])
      (let [input (write-completed-card-report!
                   forge
                   "req-report-55555555555555555555555555555555"
                   [{:project "merge-app"
                     :task_id "historic"
                     :verification "verified"}])]
        (completed-card-report forge true "record" "--root" (str forge) (str input)))
      (write-file (fs/path forge "completed-card-reports" "broken.edn")
                  "{:version 1")
      (write-file (fs/path forge "completed-card-reports" "unsafe.edn")
                  (pr-str {:version 1
                           :report_id "unsafe"
                           :reported_at "2026-01-01T00:00:00Z"
                           :family "forgelet"
                           :lieutenant "lieutenant"
                           :verification_result "verified"
                           :cards [{:project "merge-app"
                                    :task_id "current"
                                    :card_name (str (fs/path forge "secret"))
                                    :completed_at "2026-01-01T00:05:00Z"
                                    :active_seconds 300
                                    :verification "verified"}]
                           :retained_cards []}))
      (is (= {:completed_cards 2
              :median_seconds 450
              :mean_seconds 450
              :min_seconds 300
              :max_seconds 600}
             (-> (web-state forge) :projects first :completion_stats))))))

(deftest forge-pane-capture-uses-project-root
  ;; Given a forge with an open project and a recorded specifier pane
  ;; When GET /api/agents/specifier/pane?project=cave
  ;; Then it prints that pane, not the host miss
  (let [root (tmp-dir)
        dest (fs/path root "projects/cave")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path dest ".swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"
                        dest))
    (write-file (fs/path dest ".swarmforge/sessions/specifier/pane.txt")
                "cave specifier pane\n")
    (let [host (pack-web root true "--test-pane" (str root) "specifier")
          project (pack-web root true "--test-pane" (str root) "specifier" "cave")]
      (is (str/includes? (:out host) "(no pane capture for specifier)"))
      (is (str/includes? (:out project) "cave specifier pane")))))

(deftest forge-mission-reads-project-mission-md
  ;; Given an open forge project with mission.md
  ;; When GET /api/mission?project=cave
  ;; Then it prints that mission
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
    (let [result (pack-web root true "--test-mission" (str root) "cave")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "Hunt the wumpus")))))

(deftest forge-card-status-is-per-project
  ;; Given two open projects each with a specifier card and its own pane
  ;; When --test-state
  ;; Then each card keeps the status from its own project, not the other
  (let [root (tmp-dir)
        cave (fs/path root "projects/cave")
        dice (fs/path root "projects/dice")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "m")
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "dice" "four-pack" "m")
    (setup-pack! cave ["specifier"])
    (setup-pack! dice ["specifier"])
    (create-task cave "htw" "specifier")
    (create-task dice "begin" "specifier")
    (write-file (fs/path cave ".swarmforge/sessions/specifier/pane.txt")
                "I'm specifying Hunt the Wumpus.\n")
    (write-file (fs/path dice ".swarmforge/sessions/specifier/pane.txt")
                "I'll implement begin.\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          by (into {} (map (juxt :name identity) (:projects state)))
          cave-status (:status (first (:tasks (get by "cave"))))
          dice-status (:status (first (:tasks (get by "dice"))))]
      (is (str/includes? (str cave-status) "Hunt the Wumpus"))
      (is (str/includes? (str dice-status) "I'll implement begin"))
      (is (not (str/includes? (str cave-status) "begin")))
      (is (not (str/includes? (str dice-status) "Hunt the Wumpus"))))))

(deftest forge-state-includes-lieutenant-status-lines
  ;; Given a forge lieutenant pane with two status sentences
  ;; When --test-state
  ;; Then lieutenant_status is those two lines
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects"))
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/sessions/lieutenant/pane.txt")
                (str "I'm listing the open projects.\n"
                     "I'll summarize HTW next.\n"))
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (true? (:forge state)))
      (is (= ["I'm listing the open projects." "I'll summarize HTW next."]
             (:lieutenant_status state))))))

(defn lieutenant-activity-state [root alive command text]
  (json/parse-string
   (:out (pack-web-env root {}
                       "--test-lieutenant-activity"
                       (str root) alive command text))
   true))

(deftest forge-lieutenant-runtime-activity-classifies-current-evidence
  ;; Given a configured forge Lieutenant
  ;; When its live pane is working, idle, unknown, gone or at a fallback shell
  ;; Then dashboard activity reports only evidence from the current live pane
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tclaude\ttask\tforward-only\n"
                        root))
    (let [working (:activity
                   (lieutenant-activity-state
                    root "alive" "claude"
                    "I'm reviewing the completed cards.\nesc to interrupt · 2s\n"))
          idle (:activity
                (lieutenant-activity-state
                 root "alive" "claude"
                 "(? for shortcuts unavailable)\n? for shortcuts\n"))
          unknown (:activity
                   (lieutenant-activity-state
                    root "alive" "claude"
                    "An unrelated chunk of the transcript.\n"))
          missing (:activity
                   (lieutenant-activity-state root "missing" "" ""))
          fallback (:activity
                    (lieutenant-activity-state
                     root "alive" "/bin/zsh"
                     "I'm reviewing old work.\nesc to interrupt · 9s\n"))]
      (is (= {:alive true :state "working"}
             (select-keys working [:alive :state])))
      (is (= {:alive true :state "idle"}
             (select-keys idle [:alive :state])))
      (is (= {:alive true :state "unknown"}
             (select-keys unknown [:alive :state])))
      (is (= {:alive false :state "unavailable" :heat 0}
             (select-keys missing [:alive :state :heat])))
      (is (= {:alive true :state "unavailable" :heat 0}
             (select-keys fallback [:alive :state :heat])))
      (is (nil? (:status_line missing)))
      (is (nil? (:status_line fallback)))
      (is (re-matches #"\d{4}-\d\d-\d\dT.+Z" (:last_changed_at working))))))

(deftest forge-lieutenant-runtime-activity-bounds-and-redacts-status
  ;; Given recognized status text carrying controls, paths, tokens and an unsafe length
  ;; When it is projected into the dashboard activity fact
  ;; Then only one short safe line remains
  (let [root (tmp-dir)
        unsafe (str "I'm checking /Users/operator/private/project and "
                    "ghp_abcdefghijklmnopqrstuvwxyz0123 for "
                    "swarmforge-private-socket "
                    (apply str (repeat 300 "x"))
                    ".\u0007\nesc to interrupt · 1s\n")]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tclaude\ttask\tforward-only\n"
                        root))
    (let [activity (:activity
                    (lieutenant-activity-state root "alive" "claude" unsafe))
          line (:status_line activity)
          serialized (json/generate-string activity)]
      (is (= "working" (:state activity)))
      (is (string? line))
      (is (<= (count line) 120))
      (is (not (str/includes? line "\n")))
      (is (str/includes? line "[path]"))
      (is (str/includes? line "[redacted]"))
      (is (str/includes? line "[socket]"))
      (is (not (str/includes? serialized "/Users/operator")))
      (is (not (str/includes? serialized "ghp_abcdefghijklmnopqrstuvwxyz0123")))
      (is (not (str/includes? serialized "swarmforge-private-socket")))
      (is (not (str/includes? serialized "\u0007"))))))

(deftest forge-lieutenant-runtime-activity-tracks-heat-and-structured-changes
  ;; Given a working Lieutenant sampled repeatedly
  ;; When output and classification change, then remain unchanged
  ;; Then measured heat and last_changed_at change only with their respective evidence
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tclaude\ttask\tforward-only\n"
                        root))
    (let [timeline (json/parse-string
                    (:out (pack-web-env
                           root {}
                           "--test-lieutenant-activity-timeline" (str root)))
                    true)]
      (is (= "working" (get-in timeline [:first :state])))
      (is (= 0 (get-in timeline [:same :heat])))
      (is (pos? (get-in timeline [:changed :heat])))
      (is (= (get-in timeline [:first :last_changed_at])
             (get-in timeline [:same :last_changed_at])))
      (is (not= (get-in timeline [:same :last_changed_at])
                (get-in timeline [:changed :last_changed_at])))
      (is (= "idle" (get-in timeline [:idle :state])))
      (is (= {:alive false :state "unavailable"}
             (select-keys (:stale timeline) [:alive :state])))
      (is (not= (get-in timeline [:first :state])
                (get-in timeline [:idle :state]))))))

(deftest forge-lieutenant-runtime-activity-strengthens-dashboard-fingerprint
  ;; Given otherwise identical dashboard observations with different Lieutenant evidence
  ;; When each activity is serialized
  ;; Then the activity fingerprint differs and the existing facts remain present
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tclaude\ttask\tforward-only\n"
                        root))
    (let [working (lieutenant-activity-state
                   root "alive" "claude"
                   "I'm reviewing the cards.\nesc to interrupt · 2s\n")
          idle (lieutenant-activity-state
                root "alive" "claude"
                "I'm waiting for the next request.\n? for shortcuts\n")
          missing (lieutenant-activity-state root "missing" "" "")
          dashboard (json/parse-string
                     (:out (pack-web-env root {} "--test-state" (str root)))
                     true)]
      (is (not= (:fingerprint working) (:fingerprint idle)))
      (is (not= (:fingerprint idle) (:fingerprint missing)))
      (is (not= (json/generate-string (:activity working))
                (json/generate-string (:activity idle))))
      (is (contains? dashboard :lieutenant_status))
      (is (contains? dashboard :work_in_flight))
      (is (= {:alive false :state "unavailable" :heat 0}
             (select-keys (:lieutenant_activity dashboard)
                          [:alive :state :heat]))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.pack-ui-test)]
    (System/exit (+ fail error))))

(deftest forge-new-project-takes-the-forges-own-articles
  ;; Given a forge carrying the family's privacy article, and a pack carrying one
  ;; of its own
  ;; When a project is composed from that pack
  ;; Then the project reads the forge's copy - so a rule that applies to every
  ;; project arrives whichever pack it was composed from, and a stale copy in a
  ;; pack does not shadow it
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root "swarmforge/constitution/articles/privacy.prompt") "FORGE-PRIVACY\n")
    (write-file (fs/path root "packs/six-pack/swarmforge/constitution/articles/privacy.prompt")
                "PACK-PRIVACY\n")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "six-pack" "m")
          article (fs/path root "projects/cave/swarmforge/constitution/articles/privacy.prompt")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/exists? article))
      (is (= "FORGE-PRIVACY\n" (slurp (str article)))))))
