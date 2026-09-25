#!/usr/bin/env bb

(ns approve-task
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def usage-text
  (str "Approve the pending handoff for one board card and wait for delivery.\n"
       "\n"
       "Usage:\n"
       "  approve_task.sh <project-root> <task-name-or-id>\n"
       "\n"
       "Example:\n"
       "  approve_task.sh /path/to/project project-bootstrap\n"))

(defn usage []
  (println usage-text))

(defn exit! [status message]
  (binding [*out* *err*]
    (println message))
  (System/exit status))

(defn help-arg? [args]
  (boolean (some #{"--help" "-h"} args)))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))))

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

(defn card-keys [root task]
  (let [want (str/lower-case (or task ""))
        card (some (fn [cols]
                     (let [[name _lane _created _updated task-id] cols]
                       (when (or (= want (str/lower-case (or name "")))
                                 (= want (str/lower-case (or task-id ""))))
                         [name task-id])))
                   (board-rows root))]
    (if card
      (set card)
      #{task})))

(defn pending-files [root]
  (let [dir (fs/path root ".swarmforge" "handoffs" "pending_approval")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (str (fs/file-name %)) ".handoff")))
           (sort-by #(str (fs/file-name %)))
           vec)
      [])))

(defn matching-pending [root keys]
  (filterv (fn [file]
             (let [task (header-field file "task")
                   task-id (header-field file "task_id")]
               (boolean (some #(or (= task %) (= task-id %)) keys))))
           (pending-files root)))

(defn role-worktree [root role]
  (some (fn [cols]
          (when (= role (first cols))
            (nth cols 2 nil)))
        (role-rows root)))

(defn -main [& args]
  (when (help-arg? args)
    (usage)
    (System/exit 0))
  (when (not= 2 (count args))
    (usage)
    (System/exit 1))
  (let [[root task] args]
    (when-not (fs/directory? root)
      (exit! 1 (str "Project root not found: " root)))
    (let [keys (card-keys root task)
          matches (matching-pending root keys)]
      (when (empty? matches)
        (println (str "NO_PENDING_APPROVAL for task '" task "'"))
        (System/exit 0))
      (when (> (count matches) 1)
        (exit! 1 (str "Multiple pending approvals match '" task "': "
                      (str/join ", " (map #(str (fs/file-name %)) matches)))))
      (let [file (first matches)
            id (str/replace (str (fs/file-name file)) #"\.handoff$" "")
            recipient (header-field file "to")
            base (fs/file-name file)]
        (println "APPROVING:" id)
        (println "  task: " (or (header-field file "task") "-"))
        (println "  to:   " (or recipient "-"))
        (load-file (str (fs/path (fs/parent *file*) "pack_web.bb")))
        ((requiring-resolve 'pack-web/approve!) root id)
        (println "APPROVED -> outbox")
        (when recipient
          (let [worktree (role-worktree root recipient)
                dest-dir (when worktree
                           (fs/path worktree ".swarmforge" "handoffs" "inbox" "new"))
                deadline (+ (System/currentTimeMillis) 20000)]
            (loop []
              (when (and dest-dir
                         (not (fs/exists? (fs/path dest-dir base)))
                         (< (System/currentTimeMillis) deadline))
                (Thread/sleep 1000)
                (recur)))
            (if (and dest-dir (fs/exists? (fs/path dest-dir base)))
              (println "DELIVERED:" (str (fs/path dest-dir base)))
              (do
                (println "DELIVERY_PENDING: file left outbox but not yet in recipient inbox.")
                (System/exit 2)))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
