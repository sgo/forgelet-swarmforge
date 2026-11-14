(ns swarmforge.card-lifecycle-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [route-card :as route-card]))

(def repo-root (fs/cwd))
(def scripts (fs/path repo-root "swarmforge" "scripts"))
(def completed-report-script
  (fs/path scripts "completed_card_report.sh"))
(def fake-agent-command
  (str "sh -c 'echo \"<<AGENT READY>>\"; while IFS= read -r line; do "
       "case \"$line\" in \"[phone \"*) "
       "id=${line#\\[phone }; id=${id%%]*}; mkdir -p tmp; "
       "printf \"processed %s\\n\" \"$id\" > tmp/answer.txt; "
       (fs/path scripts "pack_dashboard_request.sh")
       " answer \"$id\" ./tmp/answer.txt; "
       "echo \"<<AGENT READY>>\";; esac; done'"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn init-project! [root]
  (write-file (fs/path root ".swarmforge/single-agent") "agent\n")
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                      root))
  (write-file (fs/path root "stages.edn")
              (pr-str {:task "agent"
                       :stages [{:name "spec"
                                 :approval :operator
                                 :spec ["tasks/*.md"]}
                                {:name "code"
                                 :runs [["true"]]
                                 :stamp [:whole-tree]}]}))
  (fs/create-dirs (fs/path root "tasks"))
  root)

(defn tmp-project []
  (init-project!
   (fs/create-temp-dir {:dir "/tmp" :prefix "sf-card-lifecycle."})))

(defn tmp-forge []
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-route-forge."})]
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                        root))
    (fs/create-dirs (fs/path root "projects"))
    root))

(defn route-result [& args]
  (apply sh/sh "bb" (str (fs/path scripts "route_card.bb")) args))

(defn route-json [& args]
  (let [result (apply route-result args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "route-card command failed"
                      {:result result :args args})))
    (json/parse-string (:out result) true)))

(defn propose! [forge project name note]
  (let [file (fs/path forge (str name ".md"))]
    (write-file file note)
    (let [result (route-result "propose" (str project) name (str file)
                               "--forge-root" (str forge))]
      (is (zero? (:exit result)) (:err result))
      (second (re-find #"PROPOSED: (\S+)" (:out result))))))

(defn propose-dependent! [forge project name note prerequisite]
  (let [file (fs/path forge (str name ".md"))]
    (write-file file note)
    (let [result (route-result "propose" (str project) name (str file)
                               "--depends-on" prerequisite
                               "--forge-root" (str forge))]
      (is (zero? (:exit result)) (:err result))
      (second (re-find #"PROPOSED: (\S+)" (:out result))))))

(defn approve-proposal! [forge id]
  (route-json "approve" id "--operator-said" (str "approve " id)
              "--forge-root" (str forge) "--json"))

(defn completed-report [& args]
  (apply sh/sh (str completed-report-script) args))

(defn board [root & args]
  (apply sh/sh
         (concat ["bb" (str (fs/path scripts "pack_board.bb"))]
                 args
                 ["--root" (str root)])))

(defn rows [root]
  (let [file (fs/path root ".swarmforge/board/tasks.tsv")]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [[name lane created updated task-id audit]
                         (str/split line #"\t" -1)]
                     {:name name :lane lane :created created :updated updated
                      :task-id task-id :audit audit}))))
      [])))

(defn mark-card-done! [project name]
  (let [result (board project "done" "--name" name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Could not mark fixture card done" result)))))

(defn verified-cleanup!
  [forge project-name task-id report-id]
  (let [input (fs/path forge "tmp" (str report-id ".edn"))]
    (write-file input
                (pr-str {:report_id report-id
                         :family "test"
                         :lieutenant "lieutenant"
                         :cards [{:project project-name
                                  :task_id task-id
                                  :verification "verified"}]}))
    (let [recorded (completed-report "record" "--root" (str forge) (str input))]
      (when-not (zero? (:exit recorded))
        (throw (ex-info "Could not record completed-card fixture" recorded))))
    (completed-report "remove" "--root" (str forge) report-id)))

(defn route! [root name task-id]
  (board root "route" "--name" name "--task-id" task-id
         "--text" (str name " card\n")))

(defn request-files [root]
  (let [dir (fs/path root ".swarmforge/dashboard/requests/pending")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (fs/file-name %) ".request"))
           sort
           vec)
      [])))

(defn proposal-record [forge id]
  (edn/read-string
   (slurp (str (fs/path forge ".swarmforge" "route-proposals"
                       (str id ".edn"))))))

(deftest proposals-are-presented-before-their-specific-approval
  (let [forge (tmp-forge)
        project (init-project! (fs/path forge "projects" "approval-app"))
        note (fs/path forge "approval-note.md")]
    (try
      (write-file note "# Approval card\n\nRequire exact approval.\n")
      (doseq [flag ["--operator-said" "--approval"]]
        (let [preapproved
              (route-result "propose" (str project) "preapproved-card"
                            (str note) "--forge-root" (str forge)
                            flag "approve proposal-20261114T000000Z")]
          (is (not (zero? (:exit preapproved))) flag)
          (is (str/includes? (str (:err preapproved) (:out preapproved))
                             "not accepted by propose")
              flag)))
      (is (not (fs/directory?
                (fs/path forge ".swarmforge" "route-proposals"))))

      (let [id (propose! forge project "approval-card"
                         "# Approval card\n\nRequire exact approval.\n")
            pending (route-json "detail" id
                                "--forge-root" (str forge) "--json")
            record (proposal-record forge id)]
        (is (= "pending" (:status pending)))
        (is (= (:presented_at pending) (:presented-at record)))
        (is (.isBefore (java.time.Instant/parse (:presented-at record))
                       (java.time.Instant/now)))
        (is (empty? (rows project)))
        (let [no-source (route-result "commit" id
                                      "--forge-root" (str forge))]
          (is (not (zero? (:exit no-source))))
          (is (str/includes? (str (:err no-source) (:out no-source))
                             "No approval source"))
          (is (empty? (rows project))))

        (doseq [words ["sounds good let's start with that"
                       "start"
                       "go ahead with that"
                       "let's do that"
                       "approval-card"
                       (str "approve " id ", no wait")]]
          (let [refused (route-result "commit" id
                                      "--operator-said" words
                                      "--forge-root" (str forge))]
            (is (not (zero? (:exit refused))) words)
            (is (empty? (rows project)) words)
            (is (= "pending"
                   (route-card/proposal-status
                    (proposal-record forge id)))
                words)))

        (let [approved (route-result "commit" id
                                     "--operator-said" (str "approve " id)
                                     "--forge-root" (str forge))
              stored (proposal-record forge id)
              detail (route-json "detail" id
                                 "--forge-root" (str forge) "--json")]
          (is (zero? (:exit approved)) (str (:err approved) (:out approved)))
          (is (= "consumed" (:status detail)))
          (is (= (:presented-at stored) (:presented_at detail)))
          (is (not (str/blank? (:approved-at stored))))
          (is (= "chat transcription"
                 (get-in stored [:approval-source :kind])))
          (is (= "operator words in chat"
                 (get-in stored [:approval-source :via])))
          (is (= "chat transcription"
                 (get-in detail [:approval_source :kind])))
          (is (= 1 (count (rows project))))))
      (finally
        (fs/delete-tree forge)))))

(deftest dashboard-approval-must-be-tied-to-the-addressed-proposal
  (let [forge (tmp-forge)
        project (init-project! (fs/path forge "projects" "tied-app"))
        done-dir (fs/path project ".swarmforge/dashboard/requests/done")]
    (try
      (let [first-id (propose! forge project "first-card" "# First\n")
            second-id (propose! forge project "second-card" "# Second\n")
            tied-file (fs/path done-dir "req-tied-first.request")
            _ (write-file
               tied-file
               (str "id: req-tied-first\n"
                    "status: done\n"
                    "proposal_id: " first-id "\n"
                    "created_at: 2026-11-14T00:00:00Z\n"
                    "response: approve\n\n"
                    "Approve this proposal?\n"))
            tied (route-result "commit" first-id
                               "--approval" "req-tied-first"
                               "--forge-root" (str forge))]
        (is (zero? (:exit tied)) (str (:err tied) (:out tied)))
        (is (= "consumed"
               (route-card/proposal-status (proposal-record forge first-id))))
        (is (= "pending"
               (route-card/proposal-status (proposal-record forge second-id))))

        (let [_ (write-file
                 (fs/path done-dir "req-conflicting.request")
                 (str "id: req-conflicting\n"
                      "status: done\n"
                      "proposal_id: " second-id "\n"
                      "created_at: 2026-11-14T00:00:01Z\n"
                      "response: approve " first-id "\n\n"
                      "Approve the wrong proposal.\n"))
              refused (route-result "commit" second-id
                                    "--approval" "req-conflicting"
                                    "--forge-root" (str forge))]
          (is (not (zero? (:exit refused))))
          (is (= "pending"
                 (route-card/proposal-status
                  (proposal-record forge second-id))))
          (is (= 1 (count (rows project)))))

        (let [untied-file (fs/path done-dir "req-untied.request")
              _ (write-file
                 untied-file
                 (str "id: req-untied\n"
                      "status: done\n"
                      "created_at: 2026-11-14T00:00:02Z\n"
                      "response: approve\n\n"
                      "A bare approval with no proposal tie.\n"))
              refused (route-result "commit" second-id
                                    "--approval" "req-untied"
                                    "--forge-root" (str forge))]
          (is (not (zero? (:exit refused))))
          (is (= "pending"
                 (route-card/proposal-status
                  (proposal-record forge second-id)))))

        (let [named (route-result "commit" second-id
                                  "--operator-said" "yes second-card"
                                  "--forge-root" (str forge))]
          (is (zero? (:exit named)) (str (:err named) (:out named)))
          (is (= 2 (count (rows project))))))
      (finally
        (fs/delete-tree forge)))))

(deftest invalidated-approval-removes-only-the-active-board-row
  (let [forge (tmp-forge)
        project (init-project! (fs/path forge "projects" "invalid-app"))
        source (fs/path project "src" "work.txt")
        task-note (fs/path project "tasks" "withdrawn-card.md")]
    (try
      (write-file source "recoverable work\n")
      (let [id (propose! forge project "withdrawn-card"
                         "# Withdrawn card\n\nUnauthorized approval.\n")
            approved (route-result "commit" id
                                   "--operator-said" (str "approve " id)
                                   "--forge-root" (str forge))]
        (is (zero? (:exit approved)) (str (:err approved) (:out approved)))
        (is (= 1 (count (rows project))))
        (is (fs/regular-file? task-note))
        (let [invalidated
              (route-result "invalidate" id
                            "--reason" "approval did not identify this proposal"
                            "--forge-root" (str forge))
              stored (proposal-record forge id)
              detail (route-json "detail" id
                                 "--forge-root" (str forge) "--json")]
          (is (zero? (:exit invalidated)) (str (:err invalidated) (:out invalidated)))
          (is (= "invalidated" (:status detail)))
          (is (= "approval did not identify this proposal"
                 (:invalidated_reason detail)))
          (is (true? (:invalidated_board_removed detail)))
          (is (= "invalidated" (route-card/proposal-status stored)))
          (is (not (str/blank? (:invalidated-at stored))))
          (is (empty? (rows project)))
          (is (= "recoverable work\n" (slurp (str source))))
          (is (fs/regular-file? task-note))
          (let [again
                (route-result "invalidate" id
                              "--reason" "approval did not identify this proposal"
                              "--forge-root" (str forge))
                refused
                (route-result "commit" id
                              "--operator-said" (str "approve " id)
                              "--forge-root" (str forge))]
            (is (zero? (:exit again)) (str (:err again) (:out again)))
            (is (not (zero? (:exit refused))))
            (is (str/includes? (str (:err refused) (:out refused))
                               "invalidated"))
            (is (empty? (rows project))))))
      (finally
        (fs/delete-tree forge)))))

(deftest single-agent-cards-activate-queue-and-promote-atomically
  (let [root (tmp-project)
        ids (into {} (map (fn [name] [name (str "task-" name)]))
                  ["active" "second" "third"])]
    (try
      (let [first (route! root "active" (get ids "active"))]
        (is (zero? (:exit first)) (:err first))
        (is (str/includes? (:out first) "ACTIVE:active:task-active:")))
      (let [active-row (first (filter #(= "active" (:name %)) (rows root)))
            activation (edn/read-string
                        (slurp (str (fs/path root ".swarmforge/card-activations"
                                            "task-active.edn"))))]
        (is (= (:created active-row) (:activated_at activation)))
        (is (= "task-active" (:task_id activation))))
      (is (= 1 (count (request-files root))))

      (doseq [name ["second" "third"]]
        (let [result (route! root name (get ids name))]
          (is (zero? (:exit result)) (:err result))
          (is (str/includes? (:out result) "QUEUED:"))))
      (is (= ["spec" "queued" "queued"]
             (mapv :lane (rows root))))
      (is (= ["req-promote-task-active.request"]
             (mapv fs/file-name (request-files root))))
      (let [done (fs/path root ".swarmforge/dashboard/requests/done/req-promote-task-active.request")]
        (fs/create-dirs (fs/parent done))
        (fs/move (first (request-files root)) done))

      (let [complete (board root "complete-promote" "--name" "active")]
        (is (zero? (:exit complete)) (:err complete))
        (is (str/includes? (:out complete) "PROMOTED:second:task-second:")))
      (is (= "task-second"
             (:task_id
              (edn/read-string
               (slurp (str (fs/path root ".swarmforge/card-activations"
                                    "task-second.edn")))))))
      (is (= {"active" "done" "second" "spec" "third" "queued"}
             (into {} (map (juxt :name :lane)) (rows root))))
      (is (= ["req-promote-task-second.request"]
             (mapv fs/file-name (request-files root))))

      (let [before (mapv #(slurp (str %)) (request-files root))
            duplicate (board root "complete-promote" "--name" "active")]
        (is (zero? (:exit duplicate)) (:err duplicate))
        (is (= before (mapv #(slurp (str %)) (request-files root))))
        (is (= 1 (count (request-files root)))))

      (let [promotion (edn/read-string
                       (slurp (str (fs/path root ".swarmforge/card-promotions/task-active.edn"))))]
        (is (= :complete (:status promotion)))
        (is (= "second" (:promoted-name promotion)))
        (is (= "task-second" (:promoted-id promotion))))
      (finally
        (fs/delete-tree root)))))

(deftest queued-order-concurrency-and-idle-are-durable
  (let [root (tmp-project)]
    (try
      (let [results (doall
                     (mapv deref
                           [(future (route! root "alpha" "task-z-alpha"))
                            (future (route! root "beta" "task-a-beta"))]))]
        (doseq [result results]
          (is (zero? (:exit result)) (:err result)))
        (is (= [1 1] [(count (filter #(= "spec" (:lane %)) (rows root)))
                      (count (filter #(= "queued" (:lane %)) (rows root)))]))
        (is (= 1 (count (request-files root)))))

      (let [file (fs/path root ".swarmforge/board/tasks.tsv")
            ties (mapv (fn [line]
                         (let [[name lane _ updated task-id audit]
                               (str/split line #"\t" -1)]
                           (if (= lane "queued")
                             (str/join "\t" [name lane "2026-11-07T00:00:00Z"
                                             updated task-id audit])
                             line)))
                       (str/split-lines (slurp (str file))))]
        (write-file file (str (str/join "\n" ties) "\n")))
      (let [queued (filterv #(= "queued" (:lane %)) (rows root))
            active (first (filter #(= "spec" (:lane %)) (rows root)))
            completed (board root "complete-promote" "--name" (:name active))]
        (is (zero? (:exit completed)) (:err completed))
        (let [promoted (first (filter #(= "spec" (:lane %)) (rows root)))]
          (is (= (:task-id (first (sort-by (juxt :created :task-id) queued)))
                 (:task-id promoted)))))

      (let [active (first (filter #(= "spec" (:lane %)) (rows root)))
            empty (board root "complete-promote" "--name" (:name active))]
        (is (zero? (:exit empty)) (:err empty))
        (is (str/includes? (:out empty) "IDLE:"))
        (is (fs/regular-file? (fs/path root ".swarmforge/project-idle"))))
      (finally
        (fs/delete-tree root)))))

(deftest promotion-repairs-a-crash-and-reuses-a-request
  (let [root (tmp-project)]
    (try
      (route! root "active" "task-active")
      (route! root "next" "task-next")
      (let [request (fs/path root ".swarmforge/dashboard/requests/pending/req-promote-task-active.request")]
        (let [done (fs/path root ".swarmforge/dashboard/requests/done/req-promote-task-active.request")]
          (fs/create-dirs (fs/parent done))
          (fs/move request done)))
      ;; Simulate a crash after A moved to done and the promotion was journaled,
      ;; but before the next card moved or its request was created.
      (write-file (fs/path root ".swarmforge/board/tasks.tsv")
                  (str (str/replace (slurp (str (fs/path root ".swarmforge/board/tasks.tsv")))
                                    #"active\tspec" "active\tdone")
                       ""))
      (write-file (fs/path root ".swarmforge/card-promotions/task-active.edn")
                  (pr-str {:completed-name "active"
                           :completed-id "task-active"
                           :promoted-name "next"
                           :promoted-id "task-next"
                           :request-id "req-promote-task-next"
                           :status :promoting
                           :created-at "2026-11-07T00:00:00Z"}))
      (let [recovered (board root "complete-promote" "--name" "active")]
        (is (zero? (:exit recovered)) (:err recovered))
        (is (str/includes? (:out recovered) "PROMOTED:next:task-next:")))
      (is (= "spec" (:lane (first (filter #(= "next" (:name %)) (rows root))))))
      (is (= 1 (count (request-files root))))
      (let [request (first (request-files root))
            before (slurp (str request))
            again (board root "complete-promote" "--name" "active")]
        (is (zero? (:exit again)) (:err again))
        (is (= [request] (request-files root)))
        (is (= before (slurp (str request)))))
      (finally
        (fs/delete-tree root)))))

(deftest a-failed-promotion-leaves-the-next-card-queued-for-retry
  (let [root (tmp-project)]
    (try
      (route! root "active" "task-active")
      (route! root "next" "task-next")
      (let [pending (fs/path root ".swarmforge/dashboard/requests/pending")
            done (doto (fs/path root ".swarmforge/dashboard/requests/done/req-promote-task-active.request")
                   fs/create-dirs)]
        (fs/move (first (request-files root)) done)
        (fs/delete-tree pending)
        (write-file pending "not a directory\n")
        (let [failed (board root "complete-promote" "--name" "active")]
          (is (not (zero? (:exit failed)))))
        (fs/delete pending)
        (fs/create-dirs pending))
      (is (= {"active" "done" "next" "queued"}
             (into {} (map (juxt :name :lane)) (rows root))))
      (is (empty? (request-files root)))
      (let [promotion (edn/read-string
                       (slurp (str (fs/path root ".swarmforge/card-promotions/task-active.edn"))))]
        (is (= :promoting (:status promotion))))
      (let [recovered (board root "complete-promote" "--name" "active")]
        (is (zero? (:exit recovered)) (:err recovered)))
      (is (= "spec" (:lane (first (filter #(= "next" (:name %)) (rows root))))))
      (is (= ["req-promote-task-next.request"]
             (mapv fs/file-name (request-files root))))
      (finally
        (fs/delete-tree root)))))

(deftest stage-complete-is-the-promotion-boundary
  (let [root (tmp-project)]
    (try
      (route! root "active" "task-active")
      (route! root "next" "task-next")
      (let [done (fs/path root ".swarmforge/dashboard/requests/done/req-promote-task-active.request")]
        (fs/create-dirs (fs/parent done))
        (fs/move (first (request-files root)) done))
      (write-file (fs/path root ".gitignore") ".swarmforge/\n")
      (sh/sh "git" "init" "-q" :dir (str root))
      (sh/sh "git" "config" "user.email" "test@example.com" :dir (str root))
      (sh/sh "git" "config" "user.name" "Test" :dir (str root))
      (sh/sh "git" "add" "-A" :dir (str root))
      (sh/sh "git" "commit" "-q" "-m" "fixture" :dir (str root))
      (let [propose (sh/sh "bb" (str (fs/path scripts "stage.bb"))
                           "propose" "spec" "tasks/*.md" "--root" (str root))
            code (sh/sh "bb" (str (fs/path scripts "stage.bb"))
                        "check" "code" "--root" (str root))]
        (is (zero? (:exit propose)) (str (:err propose) (:out propose)))
        (is (zero? (:exit code)) (str (:err code) (:out code)))
        (let [complete (sh/sh "bb" (str (fs/path scripts "stage.bb"))
                              "complete" "--root" (str root))]
          (is (zero? (:exit complete)) (str (:err complete) (:out complete)))
          (is (str/includes? (:out complete) "PROMOTED:next:task-next:"))))
      (is (= "done" (:lane (first (filter #(= "active" (:name %)) (rows root))))))
      (is (= "spec" (:lane (first (filter #(= "next" (:name %)) (rows root))))))
      (is (= ["req-promote-task-next.request"]
             (mapv fs/file-name (request-files root))))
      (let [run (sh/sh "bb" (str (fs/path scripts "agent-runner.bb"))
                       "--root" (str root)
                       "--once" "--no-serve"
                       "--agent-command" fake-agent-command)]
        (is (zero? (:exit run)) (:err run)))
      (is (empty? (request-files root)))
      (is (fs/regular-file?
           (fs/path root ".swarmforge/dashboard/requests/done/req-promote-task-next.request")))
      (finally
        (sh/sh "tmux" "-S" (str (fs/path root ".swarmforge/agent-tmux.sock"))
               "kill-server")
        (fs/delete-tree root)))))

(deftest dashboard-exposes-queued-and-one-active-card
  (let [root (tmp-project)]
    (try
      (route! root "active" "task-active")
      (route! root "queued" "task-queued")
      (let [state (json/parse-string
                   (:out (sh/sh (str (fs/path scripts "pack_web.sh"))
                                "--test-state" (str root)))
                   true)]
        (is (= "queued" (first (:lanes state))))
        (is (= "active" (:name (:active_card state))))
        (is (false? (:idle state))))
      (finally
        (fs/delete-tree root)))))

(deftest stage-selects-the-active-card-and-ignores-queued-cards
  (let [root (tmp-project)]
    (try
      (route! root "active" "task-active")
      (route! root "queued" "task-queued")
      (sh/sh "git" "init" "-q" :dir (str root))
      (sh/sh "git" "config" "user.email" "test@example.com" :dir (str root))
      (sh/sh "git" "config" "user.name" "Test" :dir (str root))
      (sh/sh "git" "add" "-A" :dir (str root))
      (sh/sh "git" "commit" "-q" "-m" "fixture" :dir (str root))
      (let [listing (sh/sh "bb" (str (fs/path scripts "stage.bb")) "list"
                           "--root" (str root))]
        (is (zero? (:exit listing)) (:err listing))
        (is (str/includes? (:out listing) "card: active")))
      (finally
        (fs/delete-tree root)))))

(deftest approved-routing-queues-without-another-approval-on-promotion
  (let [root (tmp-project)
        forge (fs/create-temp-dir {:dir "/tmp" :prefix "sf-card-route-forge."})]
    (try
      (write-file (fs/path forge ".swarmforge/roles.tsv")
                  (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                          forge))
      (fs/create-dirs (fs/path forge "projects"))
      (let [route (fn [name]
                    (let [note (fs/path forge (str name ".md"))]
                      (write-file note (str "# " name "\n\n" name " note\n"))
                      (let [proposed
                            (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                                   "propose" (str root) name (str note)
                                   "--forge-root" (str forge))
                            id (second (re-find #"PROPOSED: (\S+)"
                                                (:out proposed)))]
                        (is (zero? (:exit proposed)) (:err proposed))
                        {:id id
                         :result
                         (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                                "commit" id "--operator-said" (str "approve " id)
                                "--forge-root" (str forge))})))]
        (let [first (route "first")]
          (is (zero? (:exit (:result first))) (:err (:result first)))
          (is (str/includes? (:out (:result first)) "in lane spec"))
          (let [proposal (edn/read-string
                          (slurp (str (fs/path forge ".swarmforge" "route-proposals"
                                              (str (:id first) ".edn")))))]
            (is (= "spec" (:lane proposal)))
            (is (= "spec" (:created-lane proposal)))))
        (let [second-card (route "second")]
          (is (zero? (:exit (:result second-card))) (:err (:result second-card)))
          (is (str/includes? (:out (:result second-card)) "in lane queued"))
          (is (str/includes? (:out (:result second-card)) "will be requested after"))
          (let [proposal (edn/read-string
                          (slurp (str (fs/path forge ".swarmforge" "route-proposals"
                                              (str (:id second-card) ".edn")))))]
            (is (= "queued" (:lane proposal)))
            (is (= "queued" (:created-lane proposal)))
            (is (= (:task-id (first (filter #(= "second" (:name %)) (rows root))))
                   (:task-id proposal)))))
        (is (= 1 (count (request-files root)))))
      (let [active (first (filter #(= "first" (:name %)) (rows root)))
            pending (first (request-files root))
            done (fs/path root ".swarmforge/dashboard/requests/done"
                          (fs/file-name pending))]
        (fs/create-dirs (fs/parent done))
        (fs/move pending done)
        (let [promoted (board root "complete-promote" "--name" (:name active))]
          (is (zero? (:exit promoted)) (:err promoted))
          (is (str/includes? (:out promoted) "PROMOTED:second:"))))
      (is (= [1 1] [(count (filter #(= "done" (:lane %)) (rows root)))
                    (count (filter #(= "spec" (:lane %)) (rows root)))]))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree forge)))))

(deftest route-report-refuses-a-missing-or-malformed-board-row
  (let [root (tmp-project)]
    (try
      (is (try
            (route-card/created-task-row root "missing")
            false
            (catch clojure.lang.ExceptionInfo e
              (str/includes? (ex-message e) "task id missing is missing"))))
      (write-file (fs/path root ".swarmforge/board/tasks.tsv")
                  (str "one\tspec\t2026-11-07T00:00:00Z\t2026-11-07T00:00:00Z\tsame\t0\n"
                       "two\tqueued\t2026-11-07T00:00:01Z\t2026-11-07T00:00:01Z\tsame\t0\n"))
      (is (try
            (route-card/created-task-row root "same")
            false
            (catch clojure.lang.ExceptionInfo e
              (str/includes? (ex-message e)
                             "more than one board row names task id same"))))
      (is (= 2 (count (rows root))))
      (finally
        (fs/delete-tree root)))))

(deftest pack-routing-keeps-its-master-lane-report
  (let [forge (fs/create-temp-dir {:dir "/tmp" :prefix "sf-pack-route-forge."})
        project (fs/path forge "projects" "pack")
        note (fs/path forge "pack-card.md")]
    (try
      (write-file (fs/path forge ".swarmforge/roles.tsv")
                  (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\n"
                          forge))
      (write-file (fs/path project ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"
                          project))
      (write-file (fs/path project "stages.edn")
                  (pr-str {:task "pack" :stages [{:name "spec"}]}))
      (write-file note "# pack-card\n\nPack card.\n")
      (let [proposal
            (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                   "propose" (str project) "pack-card" (str note)
                   "--forge-root" (str forge))
            id (second (re-find #"PROPOSED: (\S+)" (:out proposal)))
            committed
            (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                   "commit" id "--operator-said" (str "approve " id)
                   "--forge-root" (str forge))
            record (edn/read-string
                    (slurp (str (fs/path forge ".swarmforge" "route-proposals"
                                        (str id ".edn")))))]
        (is (zero? (:exit proposal)) (:err proposal))
        (is (zero? (:exit committed)) (:err committed))
        (is (str/includes? (:out committed) "in lane specifier"))
        (is (= "specifier" (:lane record)))
        (is (= "specifier" (:created-lane record)))
        (is (= "specifier" (:lane (first (rows project))))))
      (finally
        (fs/delete-tree forge)))))

(deftest machine-proposal-list-detail-filter-and-note-fidelity
  (let [forge (tmp-forge)
        alpha (init-project! (fs/path forge "projects" "alpha-app"))
        beta (init-project! (fs/path forge "projects" "beta-app"))
        note "# Quote the proposal\n\nA \"quoted\" line and a backslash: \\\\\n\nUnicode: λ\n"]
    (try
      (let [alpha-id (propose! forge alpha "alpha-card" note)
            beta-id (propose! forge beta "beta-card" "# Beta\n\nReview beta.\n")
            listed (route-json "list" "--forge-root" (str forge) "--json")
            alpha-listed (route-json "list" "--project" "alpha-app"
                                     "--forge-root" (str forge) "--json")
            detail (route-json "detail" alpha-id
                               "--forge-root" (str forge) "--json")
            inspected (route-json "inspect" beta-id
                                  "--forge-root" (str forge) "--json")]
        (is (= [alpha-id beta-id]
               (mapv :id (:proposals listed))))
        (is (= ["alpha-app" "beta-app"]
               (mapv :project (:proposals listed))))
        (is (= ["pending" "pending"]
               (mapv :status (:proposals listed))))
        (is (= ["spec" "spec"]
               (mapv :proposed_lane (:proposals listed))))
        (is (= [alpha-id] (mapv :id (:proposals alpha-listed))))
        (is (= (str/trimr note) (:note detail)))
        (is (= "alpha-card" (:name detail)))
        (is (= "spec" (:lane detail)))
        (is (= "beta-app" (:project inspected)))
        (is (not (str/includes? (:out (route-result
                                      "list" "--forge-root" (str forge) "--json"))
                                (str forge)))))
      (finally
        (fs/delete-tree forge)))))

(deftest machine-proposal-approval-is-single-use-and-reports-the-actual-lane
  (let [forge (tmp-forge)
        project (init-project! (fs/path forge "projects" "queue-app"))]
    (try
      (let [spec-id (propose! forge project "first-card" "# First\n\nFirst note.\n")
            queued-id (propose! forge project "second-card" "# Second\n\nSecond note.\n")
            spec-approved (route-json "approve" spec-id
                                      "--operator-said" (str "approve " spec-id)
                                      "--forge-root" (str forge) "--json")
            queued-approved (route-json "approve" queued-id
                                        "--operator-said" (str "approve " queued-id)
                                        "--forge-root" (str forge) "--json")
            pending (route-json "list" "--forge-root" (str forge) "--json")
            duplicate (route-result "approve" spec-id
                                    "--operator-said" (str "approve " spec-id)
                                    "--forge-root" (str forge) "--json")
            duplicate-json (json/parse-string (:out duplicate) true)]
        (is (= ["spec" "consumed"]
               [(:lane spec-approved) (:status spec-approved)]))
        (is (= "spec" (:created_lane spec-approved)))
        (is (not (str/blank? (:task_id spec-approved))))
        (is (= ["queued" "consumed"]
               [(:lane queued-approved) (:status queued-approved)]))
        (is (= "queued" (:created_lane queued-approved)))
        (is (not (str/blank? (:task_id queued-approved))))
        (is (= [] (:proposals pending)))
        (is (= 2 (count (rows project))))
        (is (not (zero? (:exit duplicate))))
        (is (= "proposal_consumed"
               (get-in duplicate-json [:error :code])))
        (is (= 2 (count (rows project)))))
      (finally
        (fs/delete-tree forge)))))

(deftest dependent-proposal-releases-only-after-verified-card-cleanup
  (let [forge (tmp-forge)
        alpha (init-project! (fs/path forge "projects" "alpha-app"))
        beta (init-project! (fs/path forge "projects" "beta-app"))]
    (try
      (let [prerequisite-id
            (propose! forge alpha "prerequisite-card"
                      "# Prerequisite\n\nFinish first.\n")
            dependent-id
            (propose-dependent! forge beta "dependent-card"
                                "# Dependent\n\nWait for verified cleanup.\n"
                                prerequisite-id)
            prerequisite-task-id (atom nil)
            pending-list (route-json "list" "--forge-root" (str forge) "--json")
            dependent (route-json "detail" dependent-id
                                  "--forge-root" (str forge) "--json")]
        (is (= [prerequisite-id] (mapv :id (:proposals pending-list)))
            "the blocked dependent is not part of the legacy actionable list")
        (is (= prerequisite-id (:depends_on dependent)))
        (is (true? (:blocked dependent)))
        (is (= "pending" (get-in dependent [:dependency :status])))
        (is (= "prerequisite-card"
               (get-in dependent [:dependency :card_name])))

        (let [approved (approve-proposal! forge prerequisite-id)
              in-progress (route-json "detail" dependent-id
                                      "--forge-root" (str forge) "--json")
              shell-blocked (route-result "commit" dependent-id
                                          "--operator-said" (str "approve " dependent-id)
                                          "--forge-root" (str forge))
              machine-blocked (route-result "approve" dependent-id
                                            "--operator-said" (str "approve " dependent-id)
                                            "--forge-root" (str forge)
                                            "--json")
              machine-error (json/parse-string (:out machine-blocked) true)]
          (reset! prerequisite-task-id (:task_id approved))
          (is (= "consumed" (:status approved)))
          (is (true? (:blocked in-progress)))
          (is (= "in-progress" (get-in in-progress [:dependency :status])))
          (is (false? (get-in in-progress [:dependency :completed])))
          (is (not (zero? (:exit shell-blocked))))
          (is (str/includes? (str (:err shell-blocked) (:out shell-blocked))
                             prerequisite-id))
          (is (str/includes? (str (:err shell-blocked) (:out shell-blocked))
                             "prerequisite-card"))
          (is (not (zero? (:exit machine-blocked))))
          (is (= "dependency_blocked"
                 (get-in machine-error [:error :code]))))

        (mark-card-done! alpha "prerequisite-card")
        (let [cleanup (verified-cleanup!
                       forge "alpha-app"
                       @prerequisite-task-id
                       "report-dependent-release")
              _ (is (zero? (:exit cleanup)) (:err cleanup))
              completed-prerequisite
              (route-json "detail" prerequisite-id
                          "--forge-root" (str forge) "--json")
              released (route-json "detail" dependent-id
                                   "--forge-root" (str forge) "--json")
              marker-before (edn/read-string
                             (slurp (str (fs/path forge ".swarmforge"
                                                 "route-proposals"
                                                 (str prerequisite-id ".edn")))))
              retry (completed-report "remove" "--root" (str forge)
                                      "report-dependent-release")
              marker-after (edn/read-string
                            (slurp (str (fs/path forge ".swarmforge"
                                                "route-proposals"
                                                (str prerequisite-id ".edn")))))]
          (is (= "consumed" (:status completed-prerequisite)))
          (is (true? (:completed completed-prerequisite)))
          (is (some? (java.time.Instant/parse
                      (:completed_at completed-prerequisite))))
          (is (= "report-dependent-release"
                 (:completion_report_id completed-prerequisite)))
          (is (true? (get-in released [:dependency :completed])))
          (is (false? (:blocked released)))
          (is (zero? (:exit retry)) (:err retry))
          (is (= (:completed-at marker-before) (:completed-at marker-after)))
          (is (= [dependent-id]
                 (mapv :id (:proposals
                            (route-json "list" "--forge-root" (str forge)
                                        "--json")))))
          (let [approved-dependent (approve-proposal! forge dependent-id)
                proposal (edn/read-string
                          (slurp (str (fs/path forge ".swarmforge"
                                              "route-proposals"
                                              (str dependent-id ".edn")))))]
            (is (= "consumed" (:status approved-dependent)))
            (is (= prerequisite-id (:depends-on proposal))))))
      (finally
        (fs/delete-tree forge)))))

(deftest dependent-proposals-fail-closed-without-verified-cleanup
  (let [forge (tmp-forge)
        alpha (init-project! (fs/path forge "projects" "alpha-app"))
        beta (init-project! (fs/path forge "projects" "beta-app"))]
    (try
      (testing "missing and superseded prerequisites are refused at proposal time"
        (let [note (fs/path forge "dependent.md")
              _ (write-file note "# Dependent\n")
              missing (route-result "propose" (str beta) "missing-dependent"
                                    (str note)
                                    "--depends-on" "proposal-20261111T000000Z"
                                    "--forge-root" (str forge))
              malformed-id "proposal-20261111T000001Z"
              malformed-file (fs/path forge ".swarmforge" "route-proposals"
                                     (str malformed-id ".edn"))
              _ (write-file malformed-file "not an edn map\n")
              malformed (route-result "propose" (str beta) "malformed-dependent"
                                      (str note)
                                      "--depends-on" malformed-id
                                      "--forge-root" (str forge))
              prerequisite (propose! forge alpha "superseded-card"
                                     "# Superseded\n")
              file (fs/path forge ".swarmforge" "route-proposals"
                              (str prerequisite ".edn"))
              record (edn/read-string (slurp (str file)))]
          (is (not (zero? (:exit missing))))
          (is (not (zero? (:exit malformed))))
          (is (str/includes? (str (:err malformed) (:out malformed))
                             "malformed"))
          (write-file file
                      (pr-str (assoc record
                                     :superseded-at "2026-11-11T00:00:00Z")))
          (let [superseded (route-result "propose" (str beta) "superseded-dependent"
                                         (str note)
                                         "--depends-on" prerequisite
                                         "--forge-root" (str forge))]
            (is (not (zero? (:exit superseded))))
            (is (str/includes? (str (:err superseded) (:out superseded))
                               "superseded")))))

      (testing "a cycle in the prerequisite chain is refused"
        (let [first-id (propose! forge alpha "cycle-first" "# First\n")
              second-id (propose-dependent! forge beta "cycle-second" "# Second\n" first-id)
              first-file (fs/path forge ".swarmforge" "route-proposals"
                                   (str first-id ".edn"))
              first-record (edn/read-string (slurp (str first-file)))
              note (fs/path forge "cycle.md")]
          (write-file first-file
                      (pr-str (assoc first-record :depends-on second-id)))
          (write-file note "# Cycle dependent\n")
          (let [cycle (route-result "propose" (str beta) "cycle-third"
                                    (str note)
                                    "--depends-on" first-id
                                    "--forge-root" (str forge))]
            (is (not (zero? (:exit cycle))))
            (is (str/includes? (str (:err cycle) (:out cycle))
                               "Dependency cycle")))))

      (testing "direct done-row deletion does not stamp completion"
        (let [prerequisite-id (propose! forge alpha "direct-delete"
                                        "# Direct delete\n")
              dependent-id (propose-dependent! forge beta "direct-dependent"
                                               "# Direct dependent\n"
                                               prerequisite-id)
              approved (approve-proposal! forge prerequisite-id)]
          (mark-card-done! alpha "direct-delete")
          (let [board-file (fs/path alpha ".swarmforge" "board" "tasks.tsv")
                remaining (->> (str/split-lines (slurp (str board-file)))
                               (remove #(str/starts-with? % "direct-delete\t"))
                               (str/join "\n"))]
            (write-file board-file (str remaining "\n")))
          (let [proposal-file (fs/path forge ".swarmforge" "route-proposals"
                                       (str prerequisite-id ".edn"))
                record (edn/read-string (slurp (str proposal-file)))]
            (write-file
             proposal-file
             (pr-str
              (assoc record
                     :completed-at "2026-11-11T00:00:01Z"
                     :completed-task-id (:task_id approved)
                     :completion-report-id "report-interrupted-cleanup"))))
          (let [detail (route-json "detail" dependent-id
                                   "--forge-root" (str forge) "--json")
                commit (route-result "commit" dependent-id
                                     "--operator-said" (str "approve " dependent-id)
                                     "--forge-root" (str forge))]
            (is (= "consumed" (:status approved)))
            (is (false? (get-in detail [:dependency :completion_recorded])))
            (is (true? (:blocked detail)))
            (is (= "verified card cleanup is still pending"
                   (get-in detail [:dependency :blocking_reason])))
            (is (not (zero? (:exit commit))))
            (is (str/includes? (str (:err commit) (:out commit))
                               "verified card cleanup is still pending")))))
      (finally
        (fs/delete-tree forge)))))

(deftest machine-proposal-operations-handle-superseded-invalid-and-sensitive-records
  (let [forge (tmp-forge)
        project (init-project! (fs/path forge "projects" "safe-app"))
        secret-path (str (fs/path project "private" "note.txt"))
        token "ghp_1234567890abcdefghijklmnopqrstuvwxyz"]
    (try
      (let [superseded-id
            (propose! forge project "old-card" "# Old\n\nReplaced proposal.\n")
            sensitive-id
            (propose! forge project "safe-card"
                      (str "# Safe\n\nPath " secret-path "\nToken " token "\n"))
            superseded-file
            (fs/path forge ".swarmforge" "route-proposals"
                     (str superseded-id ".edn"))]
        (write-file superseded-file
                    (pr-str (assoc (edn/read-string (slurp (str superseded-file)))
                                   :status :superseded)))
        (let [superseded (route-json "detail" superseded-id
                                     "--forge-root" (str forge) "--json")
              sensitive (route-json "detail" sensitive-id
                                    "--forge-root" (str forge) "--json")
              pending (route-json "list" "--forge-root" (str forge) "--json")]
          (is (= "superseded" (:status superseded)))
          (is (= [sensitive-id] (mapv :id (:proposals pending))))
          (is (str/includes? (:note sensitive) "[redacted-path]"))
          (is (str/includes? (:note sensitive) "[redacted-token]"))
          (is (not (str/includes? (:out (route-result
                                        "detail" sensitive-id
                                        "--forge-root" (str forge) "--json"))
                                  secret-path)))
          (is (not (str/includes? (:out (route-result
                                        "list" "--forge-root" (str forge) "--json"))
                                  token)))))
      (let [invalid-id (route-result "detail" "../escape"
                                     "--forge-root" (str forge) "--json")
            missing-filter (route-result "list" "--project" "missing-app"
                                         "--forge-root" (str forge) "--json")
            malformed (fs/path forge ".swarmforge" "route-proposals"
                               "proposal-malformed.edn")]
        (write-file malformed "not an edn map\n")
        (is (= "invalid_proposal_id"
               (get-in (json/parse-string (:out invalid-id) true)
                       [:error :code])))
        (is (= "invalid_project"
               (get-in (json/parse-string (:out missing-filter) true)
                       [:error :code])))
        (let [broken (route-result "list" "--forge-root" (str forge) "--json")]
          (is (not (zero? (:exit broken))))
          (is (= "proposal_invalid"
                 (get-in (json/parse-string (:out broken) true)
                         [:error :code])))))
      (fs/delete-tree project)
      (let [orphan (propose! forge (doto (fs/path forge "projects" "orphan-app")
                                     init-project!)
                             "orphan-card" "# Orphan\n")
            _ (fs/delete-tree (fs/path forge "projects" "orphan-app"))
            listed (route-result "detail" orphan
                                 "--forge-root" (str forge) "--json")]
        (is (= "invalid_project"
               (get-in (json/parse-string (:out listed) true)
                       [:error :code]))))
      (finally
        (fs/delete-tree forge)))))
