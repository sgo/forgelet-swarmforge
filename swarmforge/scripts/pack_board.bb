#!/usr/bin/env bb

(ns pack-board
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def usage-text
  (str "Usage:\n"
       "  pack_board.sh create --name <name> --lane <lane> [--root <dir>] [--text <text>]\n"
       "  pack_board.sh create <name> <lane>\n"
       "  pack_board.sh move --name <name> --lane <lane> [--root <dir>]\n"
       "  pack_board.sh move <name> <lane>\n"
       "  pack_board.sh done --name <name> [--root <dir>]\n"
       "  pack_board.sh done <name>\n"
       "  pack_board.sh list [--root <dir>]\n"
       "  pack_board.sh lanes [--root <dir>]\n"
       "  pack_board.sh master-lane [--root <dir>]\n"
       "  pack_board.sh archive --role <role> [--root <dir>]\n"
       "  pack_board.sh archive <role>\n"
       "  pack_board.sh archive-all [--root <dir>]\n"
       "  pack_board.sh increment-audit --task-id <task-id> [--root <dir>]\n"
       "  pack_board.sh route --name <name> --task-id <task-id> --text <text> [--root <dir>]\n"
       "  pack_board.sh complete-promote --name <name> [--root <dir>]\n"
       "  pack_board.sh remove --name <name> [--root <dir>]\n"
       "  pack_board.sh delete --name <name> [--root <dir>]\n"
       "  pack_board.sh delete <name>"))

(def flags {"--root" :root "--name" :name "--lane" :lane "--text" :text "--role" :role "--task-id" :task-id})
(def script-dir (fs/parent *file*))
(try
  (require 'handoff-lib)
  (catch Exception _
    (load-file (str (fs/path script-dir "handoff_lib.bb")))))

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn command [dir & args]
  (apply sh (concat args [:dir (str dir)])))

(defn git-root []
  (handoff-lib/git-toplevel))

(defn git-common-dir []
  (handoff-lib/git-common-dir))

(defn roles-at? [root]
  (handoff-lib/roles-at? root))

(defn project-root []
  (try
    (handoff-lib/project-root)
    (catch clojure.lang.ExceptionInfo e
      (exit! (or (:exit (ex-data e)) 1) (ex-message e)))))

(defn parse-args [args]
  (loop [args args opts {} positionals []]
    (if (empty? args)
      (assoc opts :positional positionals)
      (let [head (first args)
            flag (get flags head)]
        (cond
          (nil? flag)
          (recur (rest args) opts (conj positionals head))

          (nil? (second args))
          (exit! 1 (str "Missing value for " head))

          :else
          (recur (drop 2 args) (assoc opts flag (second args)) positionals))))))

(defn resolve-root [opts]
  (or (:root opts) (project-root)))

(defn board-dir [root]
  (fs/path root ".swarmforge" "board"))

(defn tasks-file [root]
  (fs/path (board-dir root) "tasks.tsv"))

(defn lanes-file [root]
  (fs/path (board-dir root) "lanes.tsv"))

(defn single-agent? [root]
  (fs/regular-file? (fs/path root ".swarmforge" "single-agent")))

(defn stage-lanes [root]
  (let [file (fs/path root "stages.edn")]
    (when (fs/regular-file? file)
      (let [lanes (mapv :name (:stages (edn/read-string (slurp (str file)))))]
        (if (single-agent? root)
          (into ["queued"] (conj lanes "done"))
          (conj lanes "done"))))))

(defn with-board-lock [root f]
  (let [dir (board-dir root)
        path (fs/path dir "tasks.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs dir)
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn task-body-file [root name]
  (fs/path (board-dir root) (str name ".txt")))

(defn task-doc-file [root name]
  (fs/path root "tasks" (str name ".md")))

(defn write-body! [root name text]
  (when (some? text)
    (let [file (task-body-file root name)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) text))))

(defn write-task-doc! [root name text]
  (when (some? name)
    (let [file (task-doc-file root name)
          body (or text "")]
      (fs/create-dirs (fs/parent file))
      (spit (str file)
            (str "# " name "\n\n"
                 body
                 (when-not (str/ends-with? body "\n") "\n"))))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSSSSS'Z'")
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn slug [s]
  (let [slugged (-> (or s "")
                    str/lower-case
                    (str/replace #"[^a-z0-9]+" "-")
                    (str/replace #"(^-+|-+$)" ""))]
    (if (str/blank? slugged) "task" slugged)))

(defn new-task-id [name]
  (str (id-timestamp) "-" (slug name)))

(defn read-rows [file]
  (if (fs/exists? file)
    (->> (str/split-lines (slurp (str file)))
         (remove str/blank?)
         vec)
    []))

(defn write-rows [file rows]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".tasks."})]
    (spit (str tmp)
          (if (seq rows)
            (str (str/join "\n" rows) "\n")
            ""))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(declare atomic-spit! record-activation!)

(defn row-name [line]
  (first (str/split line #"\t")))

(defn find-task [rows name]
  (let [want (str/lower-case (or name ""))]
    (some #(when (= want (str/lower-case (or (row-name %) ""))) %) rows)))

(defn task-row
  ([name lane now]
   (task-row name lane now (new-task-id name)))
  ([name lane now task-id]
   (str/join "\t" [name lane now now task-id "0"])))

(defn task-name [opts]
  (or (:name opts) (second (:positional opts))))

(defn task-lane [opts]
  (or (:lane opts) (nth (:positional opts) 2 nil)))

(defn require-value! [value label]
  (when (str/blank? value)
    (exit! 1 (str "Missing " label))))

(defn create! [opts]
  (let [name (task-name opts)
        lane (task-lane opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (require-value! lane "lane")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)]
          (when (find-task rows name)
            (exit! 1 (str "Duplicate task name: " name)))
          (let [activated-at (timestamp)
                task-id (or (:task-id opts) (new-task-id name))]
            (when (= lane "spec")
              (record-activation! root task-id activated-at))
            (write-rows file (conj rows (task-row name lane activated-at task-id))))
          (write-body! root name (:text opts))
          (write-task-doc! root name (:text opts)))))))

(defn rewrite-lane
  ([line name lane]
   (rewrite-lane line name lane (timestamp)))
  ([line name lane updated]
   (let [[row-name _ created _updated task-id audit-count]
         (str/split line #"\t" -1)]
     (if (= (str/lower-case (or name "")) (str/lower-case (or row-name "")))
       (str/join "\t" [row-name lane created updated task-id
                       (or (not-empty audit-count) "0")])
       line))))

(defn set-lane! [opts lane]
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (require-value! lane "lane")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)
              task (find-task rows name)]
          (when-not task
            (exit! 1 (str "Unknown task name: " name)))
          (let [activated-at (timestamp)
                [_ _ _ _ task-id] (str/split task #"\t" -1)]
            (when (= lane "spec")
              (record-activation! root task-id activated-at))
            (write-rows file (mapv #(rewrite-lane % name lane activated-at)
                                   rows))))))))

(defn move! [opts]
  (set-lane! opts (task-lane opts)))

(defn done! [opts]
  (set-lane! opts "done"))

(defn list! [opts]
  (let [file (tasks-file (resolve-root opts))]
    (when (fs/exists? file)
      (print (slurp (str file)))
      (flush))))

(defn roles-file [root]
  (fs/path root ".swarmforge" "roles.tsv"))

(defn role-rows [root]
  (map #(str/split % #"\t" -1) (read-rows (roles-file root))))

(defn lanes! [opts]
  (let [root (resolve-root opts)
        file (lanes-file root)]
    (cond
      (single-agent? root)
      (doseq [lane (or (stage-lanes root) [])]
        (println lane))

      (fs/regular-file? file)
      (doseq [line (read-rows file)]
        (println (first (str/split line #"\t"))))

      :else
      (doseq [cols (role-rows root)]
        (println (first cols))))))

(defn master-lane! [opts]
  (let [root (resolve-root opts)
        rows (vec (role-rows root))
        masters (if (single-agent? root)
                  rows
                  (filterv #(= "master" (second %)) rows))]
    (when-not (= 1 (count masters))
      (exit! 1 (if (single-agent? root)
                 "A single-agent project must name exactly one role"
                 "Config must name exactly one master worktree")))
    (println (ffirst masters))))

(defn tmux-socket [root]
  (let [file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/exists? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn session-for-role [root role]
  (when-let [row (some #(when (= role (first %)) %) (role-rows root))]
    (let [session (nth row 3 nil)]
      (if (str/blank? session)
        (str "swarmforge-" role)
        session))))

(defn tmux-pane [root role]
  (let [socket (tmux-socket root)
        session (session-for-role root role)]
    (when (and socket session)
      (let [result (sh "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-")]
        (when (zero? (:exit result))
          (:out result))))))

(defn pane-text [root role]
  (or (System/getenv "SWARMFORGE_PANE_STUB")
      (tmux-pane root role)))

(defn archive-session! [root role]
  (when-not (str/blank? role)
    (when-let [text (pane-text root role)]
      (let [file (fs/path root ".swarmforge" "sessions" role "pane.txt")]
        (fs/create-dirs (fs/parent file))
        (spit (str file) text)))))

(defn archive-role [opts]
  (or (:role opts) (second (:positional opts))))

(defn archive! [opts]
  (let [role (archive-role opts)]
    (require-value! role "role")
    (archive-session! (resolve-root opts) role)))

(defn live-card [line]
  (let [[name lane] (str/split line #"\t")]
    (when (and (not (str/blank? name))
               (not (str/blank? lane))
               (not= "done" lane))
      [name lane])))

(defn archive-all! [opts]
  (let [root (resolve-root opts)
        roles (->> (read-rows (tasks-file root))
                   (keep live-card)
                   (map second)
                   distinct)]
    (doseq [role roles]
      (archive-session! root role))))

(defn parse-count [value]
  (if (and value (re-matches #"[0-9]+" value))
    (Long/parseLong value)
    0))

(defn rewrite-audit-count [line task-id]
  (let [[name lane created updated row-task-id audit-count] (str/split line #"\t" -1)
        row-key (or (not-empty row-task-id) name)]
    (if (= task-id row-key)
      (str/join "\t" [name lane created updated row-task-id (str (inc (parse-count audit-count)))])
      line)))

(defn increment-audit! [opts]
  (let [task-id (:task-id opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! task-id "task ID")
    (with-board-lock
      root
      (fn []
        (when (fs/exists? file)
          (let [rows (read-rows file)
                present? (some #(let [[name _lane _created _updated row-task-id]
                                      (str/split % #"\t" -1)]
                                  (= task-id (or (not-empty row-task-id) name)))
                               rows)]
            (when-not present?
              (exit! 1 (str "Unknown task ID: " task-id)))
            (write-rows file (mapv #(rewrite-audit-count % task-id) rows))))))))

(defn delete! [opts]
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)]
          (when-not (find-task rows name)
            (exit! 1 (str "Unknown task name: " name)))
          (write-rows file (filterv #(not= (str/lower-case name)
                                           (str/lower-case (or (row-name %) "")))
                                    rows))
          (fs/delete-if-exists (task-body-file root name)))))))

(defn remove! [opts]
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)]
          (when-not (find-task rows name)
            (exit! 1 (str "Unknown task name: " name)))
          (write-rows file (filterv #(not= (str/lower-case name)
                                           (str/lower-case (or (row-name %) "")))
                                    rows)))))))

(defn project-task-name [root]
  (let [file (fs/path root "stages.edn")]
    (when (fs/regular-file? file)
      (:task (edn/read-string (slurp (str file)))))))

(defn live-task? [project-name row]
  (let [[name lane] (str/split row #"\t" -1)]
    (and (not= name project-name)
         (not (str/blank? lane))
         (not (#{"done" "queued"} lane)))))

(defn project-active-cards [root rows]
  (let [project-name (project-task-name root)]
    (filterv #(live-task? project-name %) rows)))

(defn queued-cards [root rows]
  (let [project-name (project-task-name root)]
    (->> rows
         (filter (fn [row]
                   (let [[name lane] (str/split row #"\t" -1)]
                     (and (not= name project-name)
                          (= lane "queued")))))
         (sort-by (fn [row]
                    (let [[_ _ created _ task-id] (str/split row #"\t" -1)]
                      [created (or task-id "")])))
         vec)))

(defn request-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "pending"))

(defn done-request-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "done"))

(defn request-file [root request-id]
  (fs/path (request-dir root) (str request-id ".request")))

(defn request-present? [root request-id]
  (or (fs/regular-file? (request-file root request-id))
      (fs/regular-file? (fs/path (done-request-dir root)
                                 (str request-id ".request")))))

(defn atomic-spit! [path text]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/create-temp-file {:dir (fs/parent path) :prefix ".write."})]
    (spit (str tmp) text)
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn activation-file [root task-id]
  (fs/path root ".swarmforge" "card-activations"
           (str (str/replace (str task-id) #"[^0-9A-Za-z._-]" "_") ".edn")))

(defn record-activation! [root task-id activated-at]
  (when-not (str/blank? (or task-id ""))
    (atomic-spit! (activation-file root task-id)
                  (str (pr-str {:task_id (str task-id)
                                :activated_at activated-at})
                       "\n"))))

(defn request-id [task-id]
  (str "req-promote-" (str/replace (str task-id) #"[^0-9A-Za-z._-]" "_")))

(defn ensure-promotion-request! [root request-id name task-id text]
  (when-not (request-present? root request-id)
    (atomic-spit!
     (request-file root request-id)
     (str "id: " request-id "\n"
          "status: pending\n"
          "created_at: " (timestamp) "\n"
          "\n"
          "New routed card: " name "\n"
          "Task ID: " task-id "\n\n"
          (or text "")
          (when-not (str/ends-with? (or text "") "\n") "\n")))))

(defn route! [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        task-id (:task-id opts)
        text (or (:text opts) "")
        file (tasks-file root)]
    (require-value! name "task name")
    (require-value! task-id "task ID")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)]
          (when (find-task rows name)
            (exit! 1 (str "Duplicate task name: " name)))
          (let [active (project-active-cards root rows)]
            (when (> (count active) 1)
              (exit! 1 (str "More than one active card: "
                            (str/join ", " (map row-name active)))))
            (let [queued (when (empty? active) (queued-cards root rows))
                  recover (first queued)
                  recovery-name (when recover (row-name recover))
                  recovery-id (when recover
                                (nth (str/split recover #"\t" -1) 4 nil))
                  lane (if (or (seq active) recover) "queued" "spec")
                  activated-at (timestamp)
                  row (task-row name lane activated-at task-id)
                  recovered-rows (if recover
                                   (mapv #(if (= (row-name %) recovery-name)
                                            (rewrite-lane %
                                                          recovery-name
                                                          "spec"
                                                          activated-at)
                                            %)
                                         rows)
                                   rows)]
              (when recover
                (record-activation! root recovery-id activated-at)
                (let [body-file (task-body-file root recovery-name)]
                  (ensure-promotion-request!
                   root (request-id recovery-id) recovery-name recovery-id
                   (if (fs/regular-file? body-file) (slurp (str body-file)) ""))))
              (when (= lane "spec")
                (record-activation! root task-id activated-at))
              (write-rows file (conj recovered-rows row))
              (write-body! root name text)
              (write-task-doc! root name text)
              (cond
                recover
                (do
                  (fs/delete-if-exists (fs/path root ".swarmforge" "project-idle"))
                  (println (str "RECOVERED:" recovery-name ":" recovery-id))
                  (println (str "QUEUED:" name ":" task-id)))

                (= lane "spec")
                (let [rid (request-id task-id)]
                  (fs/delete-if-exists (fs/path root ".swarmforge" "project-idle"))
                  (ensure-promotion-request! root rid name task-id text)
                  (println (str "ACTIVE:" name ":" task-id ":" rid)))

                :else
                (println (str "QUEUED:" name ":" task-id))))))))))

(defn promotion-file [root completed-id]
  (fs/path root ".swarmforge" "card-promotions"
           (str (str/replace (str completed-id) #"[^0-9A-Za-z._-]" "_") ".edn")))

(defn read-promotion [root completed-id]
  (let [file (promotion-file root completed-id)]
    (when (fs/regular-file? file)
      (edn/read-string (slurp (str file))))))

(defn write-promotion! [root completed-id promotion]
  (atomic-spit! (promotion-file root completed-id)
                (str (pr-str promotion) "\n")))

(defn row-for [rows name]
  (some #(when (= (str/lower-case name)
                  (str/lower-case (or (row-name %) "")))
           %)
        rows))

(defn complete-row [line name]
  (if (= (str/lower-case name)
         (str/lower-case (or (row-name line) "")))
    (rewrite-lane line name "done")
    line))

(defn row-body [root row]
  (let [name (row-name row)]
    (if (fs/regular-file? (task-body-file root name))
      (slurp (str (task-body-file root name)))
      "")))

(defn promote-recorded! [root file rows promotion]
  (let [target (row-for rows (:promoted-name promotion))]
    (when target
      (let [[target-name target-lane _ _ target-id] (str/split target #"\t" -1)]
        (let [activated-at (timestamp)]
          (when (= target-lane "queued")
            (record-activation! root
                                (or (:promoted-id promotion) target-id)
                                activated-at))
          (ensure-promotion-request! root (:request-id promotion)
                                     target-name
                                     (or (:promoted-id promotion) target-id)
                                     (row-body root target))
          (when (= target-lane "queued")
            (atomic-spit!
             file
             (str (str/join
                   "\n"
                   (mapv #(if (= (row-name %) target-name)
                            (rewrite-lane % target-name "spec" activated-at)
                            %)
                         rows))
                  "\n")))
          (fs/delete-if-exists (fs/path root ".swarmforge" "project-idle")))))
    (write-promotion! root (:completed-id promotion)
                      (assoc promotion :status :complete
                                       :completed-at (timestamp)))
    (println (str "PROMOTED:" (:promoted-name promotion) ":"
                  (:promoted-id promotion) ":" (:request-id promotion)))
    (:request-id promotion)))

(defn complete-promote! [opts]
  (let [root (resolve-root opts)
        completed-name (task-name opts)
        file (tasks-file root)]
    (require-value! completed-name "task name")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)
              completed (row-for rows completed-name)]
          (when-not completed
            (exit! 1 (str "Unknown completed task name: " completed-name)))
          (let [[_ _ _ _ completed-id] (str/split completed #"\t" -1)
                existing (read-promotion root completed-id)
                after-done (mapv #(complete-row % completed-name) rows)]
            (if existing
              (case (:status existing)
                :idle (do (atomic-spit! file
                                        (str (str/join "\n" after-done) "\n"))
                          (println (str "IDLE:" completed-name))
                          nil)
                :waiting-active (do (atomic-spit! file
                                                  (str (str/join "\n" after-done) "\n"))
                                    (println (str "WAITING_ACTIVE:" completed-name))
                                    nil)
                (do
                  (atomic-spit! file (str (str/join "\n" after-done) "\n"))
                  (promote-recorded! root file after-done existing)))
              (let [active-after (project-active-cards root after-done)
                    target (when (empty? active-after)
                             (first (queued-cards root after-done)))
                    base {:completed-name completed-name
                          :completed-id completed-id
                          :created-at (timestamp)
                          :status :intent}
                    promotion (cond
                                (seq active-after)
                                (assoc base :status :waiting-active)

                                target
                                (let [[name _ _ _ task-id]
                                      (str/split target #"\t" -1)]
                                  (assoc base
                                         :status :promoting
                                         :promoted-name name
                                         :promoted-id task-id
                                         :request-id (request-id task-id)))

                                :else
                                (assoc base :status :idle))]
                (write-promotion! root completed-id promotion)
                (atomic-spit! file (str (str/join "\n" after-done) "\n"))
                (case (:status promotion)
                  :idle
                  (do
                    (atomic-spit! (fs/path root ".swarmforge" "project-idle")
                                  (str (:created-at promotion) "\n"))
                    (println (str "IDLE:" completed-name))
                    nil)

                  :waiting-active
                  (do (println (str "WAITING_ACTIVE:" completed-name))
                      nil)

                  :promoting
                  (promote-recorded! root file after-done promotion))))))))))

(def commands
  {"create" create!
   "route" route!
   "complete-promote" complete-promote!
   "move" move!
   "done" done!
   "list" list!
   "lanes" lanes!
   "master-lane" master-lane!
   "archive" archive!
   "archive-all" archive-all!
   "increment-audit" increment-audit!
   "remove" remove!
   "delete" delete!})

(defn -main [& args]
  (let [opts (parse-args args)
        command (get commands (first (:positional opts)))]
    (if command
      (command opts)
      (do (usage)
          (exit! 1 nil))))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
