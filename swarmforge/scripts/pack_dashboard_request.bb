#!/usr/bin/env bb

(ns pack-dashboard-request
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  pack_dashboard_request.sh answer <id> <answer-file>\n"
       "                                  [--approve-route-card <proposal-id>]\n"
       "  pack_dashboard_request.sh clarify <question-file>\n"
       "  pack_dashboard_request.sh create --root <dir> --body <text>\n"
       "  pack_dashboard_request.sh list --root <dir>\n"))

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
  (let [result (command "." "git" "rev-parse" "--show-toplevel")]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn git-common-dir []
  (let [result (command "." "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      (let [path (str/trim (:out result))]
        (if (fs/absolute? path)
          (str (fs/path path))
          (str (fs/absolutize path)))))))

(defn has-roles? [root]
  (fs/exists? (fs/path root ".swarmforge" "roles.tsv")))

(defn project-root []
  (or (let [parent (some-> (git-common-dir) fs/parent str)]
        (when (has-roles? parent) parent))
      (when (has-roles? (git-root)) (git-root))
      (when (has-roles? (str (fs/absolutize "."))) (str (fs/absolutize ".")))
      (exit! 1 "Cannot find SwarmForge project root")))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn infer-role []
  (let [here (or (git-root) (str (fs/absolutize ".")))
        file (fs/path (project-root) ".swarmforge" "roles.tsv")]
    (when (fs/exists? file)
      (some (fn [line]
              (let [cols (str/split line #"\t")
                    role (first cols)
                    wt (when (>= (count cols) 3) (nth cols 2))]
                (when (and (not-empty role) (not-empty wt) (same-path? wt here))
                  role)))
            (str/split-lines (slurp (str file)))))))

(defn sender-role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (infer-role)
      (exit! 1 "Set SWARMFORGE_ROLE.")))

(defn parse-flag [args key]
  (let [tail (drop-while #(not= key %) args)]
    (second tail)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn chat-id []
  (str "req-" (str/replace (timestamp) #"[^0-9A-Za-z]" "")))

(defn clar-id []
  (str "clar-" (str/replace (timestamp) #"[^0-9A-Za-z]" "")))

(defn pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "pending"))

(defn done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "done"))

(defn request-file [dir id]
  (fs/path dir (str id ".request")))

(defn parse-kv [text]
  (let [[header body] (str/split (or text "") #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    (assoc headers "body" (or (get headers "body") body ""))))

(def chat-thread-id-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def chat-proposal-id-re #"proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def chat-source-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
(def chat-token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(defn safe-thread-id [value]
  (let [value (some-> value str/trim not-empty)]
    (when (and value (re-matches chat-thread-id-re value))
      value)))

(defn safe-proposal-id [value]
  (let [value (some-> value str/trim not-empty)]
    (when (and value (re-matches chat-proposal-id-re value))
      value)))

(defn safe-source [value]
  (let [value (some-> value str/trim not-empty)]
    (when (and value
               (re-matches chat-source-re value)
               (not (re-find chat-token-re value)))
      value)))

(def route-card-action-type "approve-route-card")
(def route-proposal-id-re #"proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def family-name-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def project-name-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def absolute-path-re
  #"(?<![A-Za-z0-9+.-])/(?:Users|home|private|var|tmp|opt|Volumes|srv)/[^\s)\]},>]+")

(defn action-text [value maximum]
  (let [text (-> (str (or value ""))
                 (str/replace #"[\p{Cntrl}]" " ")
                 (str/replace #"\s+" " ")
                 (str/replace absolute-path-re "[redacted-path]")
                 (str/replace chat-token-re "[redacted-token]")
                 str/trim)]
    (when-not (str/blank? text)
      (subs text 0 (min maximum (count text))))))

(defn proposal-status [proposal]
  (let [status (some-> (:status proposal) name str/lower-case)]
    (cond
      (or (not (str/blank? (str (or (:consumed-at proposal) ""))))
          (= status "consumed"))
      "consumed"

      (or (not (str/blank? (str (or (:superseded-at proposal) ""))))
          (not (str/blank? (str (or (:superseded-by proposal) ""))))
          (= status "superseded"))
      "superseded"

      (or (nil? status) (= status "pending"))
      "pending"

      :else "malformed")))

(defn containing-route-root [root]
  (let [root (fs/canonicalize (fs/path root))]
    (or (when (fs/directory? (fs/path root ".swarmforge" "route-proposals"))
          root)
        (let [projects (fs/parent root)
              forge (when projects (fs/parent projects))]
          (when (and projects
                     forge
                     (= "projects" (str (fs/file-name projects)))
                     (fs/directory? (fs/path forge ".swarmforge" "route-proposals")))
            forge)))))

(defn safe-route-proposal! [root proposal-id]
  (let [proposal-id (str (or proposal-id ""))]
    (when-not (re-matches route-proposal-id-re proposal-id)
      (exit! 1 "Route-card proposal id is invalid."))
    (let [forge (or (containing-route-root root)
                    (exit! 1 "Route-card proposal ownership could not be resolved."))
          file (fs/path forge ".swarmforge" "route-proposals"
                        (str proposal-id ".edn"))]
      (when-not (fs/regular-file? file)
        (exit! 1 "Route-card proposal was not found."))
      (let [proposal (try
                       (edn/read-string (slurp (str file)))
                       (catch Exception _
                         (exit! 1 "Route-card proposal is malformed.")))]
        (when-not (and (map? proposal)
                       (= proposal-id (:id proposal))
                       (= "pending" (proposal-status proposal)))
          (exit! 1 (if (map? proposal)
                     (str "Route-card proposal is " (proposal-status proposal)
                          " and cannot be offered for approval.")
                     "Route-card proposal is malformed.")))
        (let [project (try
                        (fs/canonicalize
                         (fs/path (str (or (:project proposal) ""))))
                        (catch Exception _
                          (exit! 1 "Route-card proposal ownership is invalid.")))
              expected-parent (try
                                (fs/canonicalize (fs/path forge "projects"))
                                (catch Exception _ nil))
              family (action-text (fs/file-name forge) 128)
              project-name (action-text (fs/file-name project) 128)
              task-name (action-text (:name proposal) 256)
              label (action-text (str "Approve " (or task-name "")) 80)]
          (when-not (and expected-parent
                         (= expected-parent (fs/parent project))
                         (fs/directory? project)
                         family
                         (re-matches family-name-re family)
                         project-name
                         (re-matches project-name-re project-name)
                         task-name)
            (exit! 1 "Route-card proposal ownership is invalid."))
          {:type route-card-action-type
           :proposal_id proposal-id
           :family family
           :project project-name
           :task_name task-name
           :label label
           :status "pending"})))))

(defn render-request
  [{:strs [id status body response created_at updated_at role
           thread_id source proposal_id
           action_type action_proposal_id action_family action_project
           action_task_name action_label action_status]}]
  (str "id: " id "\n"
       "status: " status "\n"
       (when-not (str/blank? role) (str "role: " role "\n"))
       (when-not (str/blank? thread_id) (str "thread_id: " thread_id "\n"))
       (when-not (str/blank? source) (str "source: " source "\n"))
       (when-not (str/blank? proposal_id) (str "proposal_id: " proposal_id "\n"))
       (when-not (str/blank? action_type) (str "action: " action_type "\n"))
       (when-not (str/blank? action_proposal_id)
         (str "action_proposal_id: " action_proposal_id "\n"))
       (when-not (str/blank? action_family)
         (str "action_family: " action_family "\n"))
       (when-not (str/blank? action_project)
         (str "action_project: " action_project "\n"))
       (when-not (str/blank? action_task_name)
         (str "action_task_name: " action_task_name "\n"))
       (when-not (str/blank? action_label)
         (str "action_label: " action_label "\n"))
       (when-not (str/blank? action_status)
         (str "action_status: " action_status "\n"))
       "created_at: " created_at "\n"
       (when updated_at (str "updated_at: " updated_at "\n"))
       (when-not (str/blank? response)
         (str "response: " (str/replace response #"\n" (constantly "\\n")) "\n"))
       "\n"
       (or body "")
       (when-not (str/ends-with? (or body "") "\n") "\n")))

(defn list-files [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".request"))
         (sort-by str)
         vec)
    []))

(defn stored-route-action [headers]
  (let [status (some-> (get headers "action_status") str/trim str/lower-case)
        status (when (contains? #{"pending" "consumed" "superseded" "unavailable"}
                                status)
                 status)
        proposal-id (safe-proposal-id (get headers "action_proposal_id"))
        family (safe-thread-id (get headers "action_family"))
        project (safe-thread-id (get headers "action_project"))
        task-name (action-text (get headers "action_task_name") 256)
        label (action-text (get headers "action_label") 80)]
    (when (and (= route-card-action-type (get headers "action"))
               status proposal-id family project task-name label)
      {:type route-card-action-type
       :proposal_id proposal-id
       :family family
       :project project
       :task_name task-name
       :label label
       :status status})))

(defn request-entry [path]
  (let [m (parse-kv (slurp (str path)))]
    (let [action (stored-route-action m)]
      (cond-> {:id (get m "id")
               :status (get m "status")
               :role (get m "role")
               :body (or (get m "body") "")
               :response (or (not-empty (str/replace (get m "response" "")
                                                     #"\\n" "\n")) "")
               :created_at (get m "created_at")
               :updated_at (get m "updated_at")}
        (safe-thread-id (get m "thread_id"))
        (assoc :thread_id (safe-thread-id (get m "thread_id")))

        (safe-source (get m "source"))
        (assoc :source (safe-source (get m "source")))

        (safe-proposal-id (get m "proposal_id"))
        (assoc :proposal_id (safe-proposal-id (get m "proposal_id")))

        action
        (assoc :actions [action])))))

(defn clar-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "pending"))

(defn clar-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "done"))

(defn clar-file [dir id]
  (fs/path dir (str id ".request")))

(defn create-clarification! [root role body]
  (when (str/blank? body)
    (exit! 1 "Missing clarification question"))
  (let [id (clar-id)
        now (timestamp)
        file (clar-file (clar-pending-dir root) id)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (render-request {"id" id
                                      "status" "pending"
                                      "role" role
                                      "body" body
                                      "created_at" now}))
    id))

(defn clarify! [question-file]
  (when-not (fs/regular-file? question-file)
    (exit! 1 (str "Question file not found: " question-file)))
  (let [root (project-root)
        role (sender-role)
        body (slurp (str question-file))]
    (println (create-clarification! root role body))))

(defn list-requests [root]
  (vec (concat (map request-entry (list-files (pending-dir root)))
               (map request-entry (list-files (done-dir root))))))

(defn create-request! [root body]
  (when (str/blank? body)
    (exit! 1 "Missing request body"))
  (let [id (chat-id)
        now (timestamp)
        file (request-file (pending-dir root) id)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (render-request {"id" id
                                      "status" "pending"
                                      "body" body
                                      "created_at" now}))
    {:id id :status "pending" :body body :response "" :created_at now}))

(defn find-pending [root id]
  (let [file (request-file (pending-dir root) id)]
    (when (fs/regular-file? file)
      file)))

(defn find-done-request [root id]
  (let [file (request-file (done-dir root) id)]
    (when (fs/regular-file? file)
      file)))

(defn find-done-clarification [root id]
  (let [file (clar-file (clar-done-dir root) id)]
    (when (fs/regular-file? file)
      file)))

(defn complete-pending-request! [root id src answer-file action]
  (let [text (str/trim (slurp (str answer-file)))
        now (timestamp)
        dest (request-file (done-dir root) id)
        entry (request-entry src)]
    (fs/create-dirs (fs/parent dest))
    (let [tmp (fs/create-temp-file {:dir (fs/parent dest)
                                    :prefix ".answered-request."})
          headers {"id" (:id entry)
                   "status" "done"
                   "role" (:role entry)
                   "body" (:body entry)
                   "response" text
                   "created_at" (:created_at entry)
                   "updated_at" now
                   "thread_id" (:thread_id entry)
                   "source" (:source entry)
                   "proposal_id" (:proposal_id entry)
                   "action_type" (:type action)
                   "action_proposal_id" (:proposal_id action)
                   "action_family" (:family action)
                   "action_project" (:project action)
                   "action_task_name" (:task_name action)
                   "action_label" (:label action)
                   "action_status" (:status action)}]
      (spit (str tmp) (render-request headers))
      (fs/move tmp dest {:replace-existing true :atomic-move true})
      (fs/delete-if-exists src))
    (println "ANSWERED:" id)))

(defn answer-request! [root id answer-file approve-route-card]
  (when (str/blank? id)
    (exit! 1 "Missing request id"))
  (when-not (fs/regular-file? answer-file)
    (exit! 1 (str "Answer file not found: " answer-file)))
  (if-let [src (find-pending root id)]
    (let [action (when-not (str/blank? approve-route-card)
                   (safe-route-proposal! root approve-route-card))]
      (complete-pending-request! root id src answer-file action))
    (if-let [done (find-done-request root id)]
      (let [entry (request-entry done)
            text (str/trim (slurp (str answer-file)))
            stored-action (first (:actions entry))]
        (when-not (= text (:response entry))
          (exit! 1 (str "Request already answered with a different response: " id)))
        (when (and (not (str/blank? approve-route-card))
                   (not= approve-route-card (:proposal_id stored-action)))
          (exit! 1 (str "Request already answered with a different action: " id)))
        (println "ANSWERED:" id))
      (if (find-done-clarification root id)
        (println "ANSWERED:" id)
        (exit! 1 (str "Unknown pending request: " id))))))

(defn -main [& args]
  (case (first args)
    "answer" (answer-request! (project-root) (second args) (nth args 2 nil)
                              (parse-flag args "--approve-route-card"))
    "clarify" (clarify! (second args))
    "create" (let [root (or (parse-flag args "--root") (project-root))
                   body (or (parse-flag args "--body") "")]
               (println (:id (create-request! root body))))
    "list" (let [root (or (parse-flag args "--root") (project-root))]
             (doseq [item (list-requests root)]
               (println (str (:id item) "\t" (:status item) "\t" (:body item)))))
    (do (usage)
        (exit! 1 nil)))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
