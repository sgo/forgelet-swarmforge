#!/usr/bin/env bb

(ns stage-jobs
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile)
           (java.time Duration Instant)
           (java.util UUID)))

(def default-heartbeat-stale-ms 30000)
(def default-launch-grace-ms 10000)
(def job-id-re #"stage-job-[A-Za-z0-9][A-Za-z0-9._-]{0,139}")
(def bounded-progress-chars 160)

(defn now []
  (str (Instant/now)))

(defn valid-job-id? [id]
  (boolean (and (string? id) (re-matches job-id-re id))))

(defn jobs-dir [root]
  (fs/path root ".swarmforge" "stage-jobs"))

(defn job-file [root id]
  (fs/path (jobs-dir root) (str id ".edn")))

(defn exit-file [root id]
  (fs/path (jobs-dir root) (str id ".exit.edn")))

(defn log-file [root id]
  (fs/path (jobs-dir root) (str id ".log")))

(defn control-log-file [root id]
  (fs/path (jobs-dir root) (str id ".supervisor.log")))

(defn cancel-file [root id]
  (fs/path (jobs-dir root) (str id ".cancel")))

(defn supervisor-socket [id]
  (fs/path (System/getProperty "java.io.tmpdir")
           (str "sf-stage-"
                (subs id (max 0 (- (count id) 16)))
                ".sock")))

(defn atomic-write! [path text]
  (let [path (fs/path path)
        parent (or (fs/parent path) (fs/path "."))
        tmp (fs/path parent
                     (str "." (fs/file-name path) ".tmp-" (UUID/randomUUID)))]
    (fs/create-dirs parent)
    (try
      (spit (str tmp) text)
      (try
        (fs/move tmp path {:replace-existing true :atomic-move true})
        (catch Exception _
          (fs/move tmp path {:replace-existing true})))
      (finally
        (when (fs/exists? tmp)
          (fs/delete-if-exists tmp))))))

(defn atomic-write-edn! [path value]
  (atomic-write! path (str (pr-str value) "\n")))

(defn read-edn-file [path]
  (when (and path (fs/regular-file? path))
    (try
      (edn/read-string (slurp (str path)))
      (catch Exception _
        nil))))

(defn load-job [root id]
  (when (valid-job-id? id)
    (read-edn-file (job-file root id))))

(defn load-exit [root id]
  (when (valid-job-id? id)
    (read-edn-file (exit-file root id))))

(defn update-job! [root id f]
  (when-let [job (load-job root id)]
    (let [updated (f job)]
      (atomic-write-edn! (job-file root id) updated)
      updated)))

(defn job-files [root]
  (let [dir (jobs-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".edn")
                         (not (str/ends-with? (fs/file-name %) ".exit.edn"))))
           sort
           vec)
      [])))

(defn jobs [root]
  (->> (job-files root)
       (keep (fn [file]
               (let [id (str/replace (fs/file-name file) #"\.edn$" "")]
                 (if-let [job (read-edn-file file)]
                   (assoc job :id id)
                   {:id id :status "malformed"}))))
       vec))

(defn process-start-identity [pid]
  (when (and pid (not (str/blank? (str pid))))
    (let [result (sh/sh "ps" "-o" "lstart=" "-p" (str pid))]
      (when (zero? (:exit result))
        (not-empty (str/trim (:out result)))))))

(defn process-live? [pid]
  (boolean
   (when (and pid (not (str/blank? (str pid))))
     (zero? (:exit (sh/sh "kill" "-0" (str pid)))))))

(defn process-matches?
  [pid start-identity]
  (let [current (process-start-identity pid)]
    (boolean (and (process-live? pid)
                  (not (str/blank? start-identity))
                  (= start-identity current)))))

(defn instant-age-ms [value at]
  (try
    (.toMillis (Duration/between (Instant/parse (str value))
                                 (Instant/parse (str at))))
    (catch Exception _ nil)))

(defn elapsed-seconds [value at]
  (when-let [age (instant-age-ms value at)]
    (max 0 (long (/ age 1000)))))

(defn configured-milliseconds [name default]
  (try
    (let [parsed (Long/parseLong
                  (or (System/getenv name) (str default)))]
      (if (pos? parsed) parsed default))
    (catch Exception _ default)))

(defn heartbeat-stale? [job at]
  (let [age (instant-age-ms (:heartbeat_at job) at)]
    (or (nil? age)
        (> age (configured-milliseconds
                "STAGE_JOB_HEARTBEAT_STALE_MS"
                default-heartbeat-stale-ms)))))

(defn launch-stale? [job at]
  (let [age (instant-age-ms (:created_at job) at)]
    (and (= "launching" (:status job))
         (or (nil? age)
             (> age (configured-milliseconds
                     "STAGE_JOB_LAUNCH_GRACE_MS"
                     default-launch-grace-ms))))))

(defn bounded-progress [value]
  (let [text (some-> value
                     str
                     (str/replace #"[\p{Cc}\p{Cf}]" " ")
                     (str/replace #"\s+" " ")
                     str/trim
                     not-empty)]
    (when text
      (subs text 0 (min bounded-progress-chars (count text))))))

(defn log-tail [path maximum]
  (when (and path (fs/regular-file? path))
    (with-open [file (RandomAccessFile. (str path) "r")]
      (let [length (.length file)
            start (max 0 (- length maximum))
            bytes (byte-array (- length start))]
        (.seek file start)
        (.readFully file bytes)
        (String. bytes "UTF-8")))))

(defn progress-from-log [path]
  (let [markers (->> (or (log-tail path 8192) "")
                     str/split-lines
                     (keep #(second (re-find
                                    #"\[stage-job\]\s+(command\s+\d+/\d+\s+(?:started|completed|failed))"
                                    %)))
                     vec)]
    (bounded-progress (last markers))))

(defn log-bytes [path]
  (when (and path (fs/regular-file? path))
    (.size (fs/file path))))

(defn exit-valid? [id job exit]
  (and (map? exit)
       (= id (:job_id exit))
       (= (:command_fingerprint job) (:command_fingerprint exit))
       (contains? #{"succeeded" "failed" "timed-out"
                    "cancelled" "interrupted"}
                  (:status exit))
       (integer? (:exit exit))
       (string? (:finished_at exit))
       (not (str/blank? (:finished_at exit)))))

(defn view
  ([root id] (view root id (now)))
  ([root id at]
   (let [job (load-job root id)
         exit (load-exit root id)
         live? (process-matches? (:pid job) (:process_start_identity job))
         stale? (heartbeat-stale? job at)
         missing-log? (and (:command_pid job)
                           (not (fs/regular-file?
                                 (log-file root id))))
         exit-status (:status exit)
         status
         (cond
           (nil? job) "unknown"
           (:consumed_at job) "completed"
           (:retired_at job) "retired"
           (:reconcile_error job) "failed"
           missing-log? "unknown"
           (= "succeeded" exit-status)
           (if (exit-valid? id job exit)
             "completed-unconsumed"
             "unknown")
           (contains? #{"failed" "timed-out" "cancelled" "interrupted"}
                      exit-status)
           (if (exit-valid? id job exit)
             exit-status
             "unknown")
           (= "launching" (:status job))
           (if (launch-stale? job at) "unknown" "waiting-for-job")
           (not live?) "unknown"
           stale? "unknown"
           :else "waiting-for-job")]
     {:id id
      :job job
      :exit exit
      :status status
      :live live?
      :heartbeat_stale stale?
      :elapsed_seconds (elapsed-seconds (:created_at job) at)})))

(defn blocking-view? [view]
  (contains? #{"waiting-for-job" "completed-unconsumed" "failed"
               "timed-out" "cancelled" "interrupted" "unknown"}
             (:status view)))

(defn public-view [view at]
  (let [job (:job view)]
    (when job
      {:id (:id view)
       :status (:status view)
       :stage (:stage job)
       :card (:card job)
       :card_name (:card_name job)
       :card_id (:card_id job)
       :requested_lane (:requested_lane job)
       :role (:role job)
       :created_at (:created_at job)
       :started_at (:started_at job)
       :finished_at (get-in view [:exit :finished_at])
       :heartbeat_at (:heartbeat_at job)
       :elapsed_seconds (elapsed-seconds (:created_at job) at)
       :progress (bounded-progress (:progress job))
       :timeout_seconds (:timeout_seconds job)
       :exit (get-in view [:exit :exit])
       :reason (bounded-progress (or (:reconcile_error job)
                                     (get-in view [:exit :reason])))})))

(defn blocking-views
  ([root] (blocking-views root (now)))
  ([root at]
   (->> (map (fn [job] (view root (:id job) at)) (jobs root))
        (filter blocking-view?)
        (sort-by #(str (get-in % [:job :created_at])))
        vec)))

(defn current-view [root state]
  (when-let [id (get-in state [:running :job-id])]
    (view root id)))

(defn root-blocking? [root]
  (boolean (seq (blocking-views root))))
