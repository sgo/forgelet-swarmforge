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
                               "--forge-root" (str forge)
                               "--operator-said" "yes")]
      (is (zero? (:exit result)) (:err result))
      (second (re-find #"PROPOSED: (\S+)" (:out result))))))

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
                                   "--forge-root" (str forge)
                                   "--operator-said" "yes")
                            id (second (re-find #"PROPOSED: (\S+)"
                                                (:out proposed)))]
                        (is (zero? (:exit proposed)) (:err proposed))
                        {:id id
                         :result
                         (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                                "commit" id "--forge-root" (str forge))})))]
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
                   "--forge-root" (str forge) "--operator-said" "yes")
            id (second (re-find #"PROPOSED: (\S+)" (:out proposal)))
            committed
            (sh/sh "bb" (str (fs/path scripts "route_card.bb"))
                   "commit" id "--forge-root" (str forge))
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
                                      "--operator-said" "approve"
                                      "--forge-root" (str forge) "--json")
            queued-approved (route-json "approve" queued-id
                                        "--operator-said" "approve"
                                        "--forge-root" (str forge) "--json")
            pending (route-json "list" "--forge-root" (str forge) "--json")
            duplicate (route-result "approve" spec-id
                                    "--operator-said" "approve"
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
