#!/usr/bin/env bb

(ns nudge-role
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Queue a note for an existing board card into a role's handoff inbox.\n"
       "The project handoff daemon delivers the note; the board is not modified.\n"
       "\n"
       "Usage:\n"
       "  nudge_role.sh <project-root> <role> <task-name-or-id> <message-file> [<base-commit>]\n"
       "\n"
       "Example:\n"
       "  nudge_role.sh /path/to/project specifier project-bootstrap ./tmp/message.txt HEAD^\n"
       "\n"
       "When the card's deliverable is already committed before the note is\n"
       "dequeued, pass <base-commit> (usually the deliverable commit's parent)\n"
       "so ready_for_next snapshots that commit as task_base_commit instead of\n"
       "current HEAD. This avoids the empty-diff git_handoff error.\n"))

(defn usage []
  (println usage-text))

(defn exit! [status message]
  (binding [*out* *err*]
    (println message))
  (System/exit status))

(defn help-arg? [args]
  (boolean (some #{"--help" "-h"} args)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn slug [s]
  (-> (str/lower-case (or s ""))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn lines-of [path]
  (if (fs/regular-file? path)
    (->> (str/split-lines (slurp (str path)))
         (remove str/blank?))
    []))

(defn role-rows [root]
  (->> (lines-of (fs/path root ".swarmforge" "roles.tsv"))
       (mapv #(str/split % #"\t" -1))))

(defn board-rows [root]
  (->> (lines-of (fs/path root ".swarmforge" "board" "tasks.tsv"))
       (mapv #(str/split % #"\t" -1))))

(defn find-role [rows role]
  (some #(when (= role (first %)) %) rows))

(defn find-card [rows task]
  (let [want (str/lower-case (or task ""))]
    (some (fn [cols]
            (let [[name lane _created _updated task-id] cols]
              (when (or (= want (str/lower-case (or name "")))
                        (= want (str/lower-case (or task-id ""))))
                {:name name
                 :lane lane
                 :task-id task-id})))
          rows)))

(defn -main [& args]
  (when (help-arg? args)
    (usage)
    (System/exit 0))
  (when-not (or (= 4 (count args)) (= 5 (count args)))
    (usage)
    (System/exit 1))
  (let [[root role task message-file base-ref] args]
    (when-not (fs/directory? root)
      (exit! 1 (str "Project root not found: " root)))
    (let [base-commit (when (not (str/blank? (or base-ref "")))
                        (let [result (process/sh {:continue true}
                                                 "git" "-C" root "rev-parse" "--verify"
                                                 (str base-ref "^{commit}"))]
                          (when-not (zero? (:exit result))
                            (exit! 1 (str "Base commit does not resolve: " base-ref)))
                          (str/trim (:out result))))]
    (let [roles (role-rows root)]
      (when-not (seq roles)
        (exit! 1 (str "No roles file at " (fs/path root ".swarmforge" "roles.tsv"))))
      (when-not (find-role roles role)
        (exit! 1 (str "Unknown role '" role "'. Known roles: "
                      (str/join ", " (map first roles)))))
      (let [card (find-card (board-rows root) task)]
        (when-not card
          (exit! 1 (str "No board card matching '" task "' in "
                        (fs/path root ".swarmforge" "board" "tasks.tsv"))))
        (when-not (= role (:lane card))
          (exit! 1 (str "Card '" (:name card) "' is in lane '" (:lane card)
                        "', not '" role "'.")))
        (when-not (fs/regular-file? message-file)
          (exit! 1 (str "Message file not found: " message-file)))
        (let [now (timestamp)
              stamp (str/replace now #"[^0-9A-Za-z]" "")
              outbox (fs/path root ".swarmforge" "handoffs" "outbox")
              filename (str "50_" stamp "_from_Nudge_to_" (slug role) ".handoff")
              file (fs/path outbox filename)
              body (str/trimr (slurp (str message-file)))]
          (fs/create-dirs outbox)
          (spit (str file)
                (str "id: " stamp "_from_Nudge_to_" (slug role) "\n"
                     "from: (Operator)\n"
                     "to: " role "\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " (:task-id card) "\n"
                     "task: " (:name card) "\n"
                     "created_at: " now "\n"
                     (when base-commit (str "task_base_commit: " base-commit "\n"))
                     "\n"
                     body
                     (when-not (str/blank? body) "\n")))
          (println "NUDGE QUEUED:" (str file))
          (println "Daemon will deliver to role '" role "' inbox (task: " (:name card) ").")
          (when base-commit
            (println "task_base_commit set to " base-commit "."))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
