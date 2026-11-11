#!/usr/bin/env bb

(ns pack-web
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def script-dir (fs/parent *file*))
(load-file (str (fs/path script-dir "forge.bb")))
(load-file (str (fs/path script-dir "completed_card_report.bb")))

(def usage-text
  (str "Usage:\n"
       "  pack_web.sh --serve <root> [port]\n"
       "  pack_web.sh --test-state <root>\n"
       "  pack_web.sh --test-state-query <root> <query>\n"
       "  pack_web.sh --test-html\n"
       "  pack_web.sh --test-post-task <root> <name> <text>\n"
       "  pack_web.sh --test-post-chat <root> <text>\n"
       "  pack_web.sh --test-post-chat-json <root> <json>\n"
       "  pack_web.sh --test-post-chat-file <root> <json-file>\n"
       "  pack_web.sh --test-inject-payload [name text]\n"
       "  pack_web.sh --test-inject-argv <root> <file> <text>\n"
       "  pack_web.sh --test-approve <root> <id>\n"
       "  pack_web.sh --test-reject <root> <id>\n"
       "  pack_web.sh --test-pane <root> <role>\n"
       "  pack_web.sh --test-agent-page [role]\n"
       "  pack_web.sh --test-heat <root>\n"
       "  pack_web.sh --test-heat-isolation <root-a> <root-b>\n"
       "  pack_web.sh --test-heat-codex <root>\n"
       "  pack_web.sh --test-heat-reorder <root>\n"
       "  pack_web.sh --test-heat-head <root>\n"
       "  pack_web.sh --test-heat-mail <root>\n"
       "  pack_web.sh --test-heat-grok <root>\n"
       "  pack_web.sh --test-heat-collapse <root>\n"
       "  pack_web.sh --test-status-pane <root> <text>\n"
       "  pack_web.sh --test-status-persist <root> <first> <second>\n"
       "  pack_web.sh --test-answer-clarification <root> <id> <text>\n"
       "  pack_web.sh --test-task <root> <name>\n"
       "  pack_web.sh --test-delete-task <root> <name>\n"
       "  pack_web.sh --test-delete-approval <root> <id>\n"
       "  pack_web.sh --test-retry-task <root> <id> <comments>\n"
       "  pack_web.sh --test-save-comments <root> <id> <path> <comments>\n"
       "  pack_web.sh --test-doc <root> <path>\n"
       "  pack_web.sh --test-teardown <root> [TEARDOWN]\n"
       "  pack_web.sh --test-new-project <root> <name> <pack> [mission]\n"
       "  pack_web.sh --test-open-project <root> <name>\n"
       "  pack_web.sh --test-close-project <root> <name>\n"
       "  pack_web.sh --test-inferred-name <input> [github]\n"
       "  pack_web.sh --test-mission <root> [project]"))

(def example-task-name "htw-console-app")
(def example-task-text
  "Integrate the stories in ~/junk/htw-stories into one console application.")

(def ^:dynamic *tmux-stub* nil)
(def ^:dynamic *pane-text* nil)
(def ^:dynamic *sync-teardown?* false)
(def ^:dynamic *state-cache* nil)
(def ^:dynamic *state-metrics* nil)
(def teardown-delay-ms 250)
(def pane-capture-lines 2000)
(def pane-heat (atom {}))
(def pane-status (atom {}))
(def pane-status-lines (atom {}))
(def lieutenant-activity-history (atom {}))
(def ^:dynamic *lieutenant-activity-context* nil)

(defn state-metric! [kind]
  (when *state-metrics*
    (swap! *state-metrics* update kind (fnil inc 0))))

(defn state-cached [kind key f]
  (if *state-cache*
    (let [entry (delay (do (state-metric! kind) (f)))
          cached (get (swap! *state-cache*
                             #(if (contains? % key) % (assoc % key entry)))
                      key)]
      @cached)
    (do
      (state-metric! kind)
      (f))))

(def dashboard-project-workers 4)
(def dashboard-chat-workers 8)

(defn bounded-pmap [limit f values]
  (let [values (vec values)]
    (loop [offset 0
           result []]
      (if (>= offset (count values))
        result
        (let [end (min (count values) (+ offset limit))
              jobs (mapv #(future (f %)) (subvec values offset end))
              next-result (try
                            (into result (mapv deref jobs))
                            (catch Throwable throwable
                              (doseq [job jobs] (future-cancel job))
                              (throw throwable)))]
          (recur end next-result))))))

(declare session-name pane-target live-pane-text role-row pane-sample backend-name
         in-process-for-row in-process-task-names approvals
         handoff-files batch-dirs in-process-dir allowed-doc?
         delete-approval! retry-approval! parse-message pane-status-for role-rows
         recorded-pane state-pane-text state-session-alive? single-agent-project?)

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn display-name-for-role [role]
  (->> (str/split (str/replace role #"[-_]" " ") #"\s+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn task-payload
  ([] (task-payload example-task-name example-task-text))
  ([name text] (str "Task: " name "\n\n" (or text ""))))

(defn reject-message [task]
  (str "Rejected: " task))

(defn tmux-stub []
  (or *tmux-stub* (System/getenv "SWARMFORGE_TMUX_STUB")))

(defn record-argv! [file argv]
  (when-let [dir (fs/parent file)]
    (fs/create-dirs dir))
  (spit (str file) (str (pr-str (vec argv)) "\n") :append true))

(defn record-buffer! [file text]
  (when-let [dir (fs/parent file)]
    (fs/create-dirs dir))
  (spit (str file ".stdin") text :append true))

(defn send-keys! [socket session & keys]
  (let [argv (into ["tmux" "-S" socket "send-keys" "-t" session] keys)]
    (if-let [stub (tmux-stub)]
      (record-argv! stub argv)
      (let [result (apply sh argv)]
        (when-not (zero? (:exit result))
          (throw (ex-info "tmux send-keys failed" result)))))))

(defn tmux! [socket & args]
  (let [argv (into ["tmux" "-S" socket] args)]
    (if-let [stub (tmux-stub)]
      (record-argv! stub argv)
      (let [result (apply sh argv)]
        (when-not (zero? (:exit result))
          (throw (ex-info "tmux failed" result)))))))

(defn tmux-load-buffer! [socket buffer text]
  (let [argv ["tmux" "-S" socket "load-buffer" "-b" buffer "-"]]
    (if-let [stub (tmux-stub)]
      (do
        (record-argv! stub argv)
        (record-buffer! stub text))
      (let [result (apply sh (concat argv [:in text]))]
        (when-not (zero? (:exit result))
          (throw (ex-info "tmux load-buffer failed" result)))))))

(defn role-rows [root]
  (state-cached
   :role-row-reads [::role-rows (str root)]
   (fn []
     (let [file (fs/path root ".swarmforge" "roles.tsv")]
       (if (fs/exists? file)
         (->> (str/split-lines (slurp (str file)))
              (remove str/blank?)
              (mapv #(str/split % #"\t" -1)))
         [])))))

(defn master-row [root]
  (if (single-agent-project? root)
    (first (role-rows root))
    (some #(when (= "master" (nth % 1 nil)) %) (role-rows root))))

(defn master-session [root]
  (when-let [row (master-row root)]
    (session-name row)))

(defn tmux-socket [root]
  (let [file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/exists? file)
      (not-empty (str/trim (slurp (str file)))))))

;; The wake goes in as one paste and then one Enter. A chat request runs to lines -
;; the operator's own, the answering command, the gate when it holds one - and a
;; terminal still taking that text swallows the Enter that follows it. The whole
;; wake then sits in the composer reading as nothing having read it, and the
;; operator deletes words they did not type. One paste is what keeps the body's own
;; newlines from submitting the request piecemeal, and one Enter is the one turn
;; the pane takes.
;;
;; The doorbell is what proves a request was read: it rings whatever the dashboard
;; still holds, and it now judges whether its own ring landed. So the wake does not
;; have to prove itself - it has to leave nothing behind that nobody sent.
(def wake-buffer "swarmforge-wake")

(defn inject-target! [socket target text]
  (when (and socket target (not (str/blank? text)))
    (tmux-load-buffer! socket wake-buffer text)
    (tmux! socket "paste-buffer" "-d" "-p" "-b" wake-buffer "-t" target)
    (tmux! socket "send-keys" "-t" target "C-m")))

(defn inject-role! [root role text]
  (try
    (let [socket (tmux-socket root)
          target (when-let [row (role-row root role)]
                   (pane-target row))]
      (when-not (and socket target)
        (throw (ex-info "missing tmux target" {:role role :socket socket})))
      (inject-target! socket target text))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "inject failed role=" role
                      " socket=" (tmux-socket root)
                      " error=" (.getMessage e)))
        (flush)))))

(defn inject-master! [root text]
  (when-let [row (master-row root)]
    (inject-role! root (first row) text)))

(defn inject-master-strict! [root text]
  (let [row (master-row root)]
    (when-not row
      (throw (ex-info "Master wake failed." {:http-status 502})))
    (let [socket (tmux-socket root)
          target (pane-target row)]
      (when-not (and socket target)
        (throw (ex-info "Master wake failed." {:http-status 502})))
      (try
        (inject-target! socket target text)
        (catch Exception _
          (throw (ex-info "Master wake failed." {:http-status 502})))))))

(defn pack-board-result [root & args]
  (let [script (str (fs/path script-dir "pack_board.sh"))]
    (apply sh (concat [script] args ["--root" (str root)]))))

(defn pack-board [root & args]
  (state-metric! :board-subprocesses)
  (let [result (apply pack-board-result root args)]
    (when-not (zero? (:exit result))
      (let [msg (str/trim (str (:err result) "\n" (:out result)))]
        (throw (ex-info msg {:exit (:exit result)
                             :http-status (if (str/includes? msg "Duplicate") 409 400)}))))
    (:out result)))

(defn lines [text]
  (->> (str/split-lines (or text ""))
       (remove str/blank?)
       vec))

(defn lanes [root]
  (state-cached
   :lane-builds [::lanes (str root)]
   (fn []
     (if (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))
       (let [file (fs/path root "stages.edn")
             stages (if (fs/regular-file? file)
                      (:stages (edn/read-string (slurp (str file))))
                      [])]
         (into ["queued"] (conj (mapv :name stages) "done")))
       (let [file (fs/path root ".swarmforge" "board" "lanes.tsv")]
         (if (fs/regular-file? file)
           (mapv #(first (str/split % #"\t")) (lines (slurp (str file))))
           (mapv first (role-rows root))))))))

(defn master-role [root]
  (state-cached
   :master-role-builds [::master-role (str root)]
   (fn []
     (let [rows (vec (role-rows root))
           single-agent? (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))
           masters (if single-agent?
                     rows
                     (filterv #(= "master" (nth % 1 nil)) rows))]
       (when-not (= 1 (count masters))
         (throw
          (ex-info
           (if single-agent?
             "A single-agent project must name exactly one role"
             "Config must name exactly one master worktree")
           {:http-status 500})))
       (ffirst masters)))))

(defn task-entry [line]
  (let [[name lane _created updated task-id audit-count] (str/split line #"\t" -1)]
    {:name name
     :id (or (not-empty task-id) name)
     :lane lane
     :updated_at updated
     :audit_count (if (and audit-count (re-matches #"[0-9]+" audit-count))
                    (Long/parseLong audit-count)
                    0)}))

(declare tasks)

(defn active-single-agent-card [root]
  (state-cached
   :active-card-builds [::active-card (str root)]
   (fn []
     (when (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))
       (let [stages-file (fs/path root "stages.edn")
             project-name (when (fs/regular-file? stages-file)
                            (:task (edn/read-string (slurp (str stages-file)))))]
         (first
          (remove (fn [task]
                    (or (= (:name task) project-name)
                        (#{"done" "queued"} (:lane task))))
                  (tasks root))))))))

(defn project-idle? [root]
  (and (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))
       (nil? (active-single-agent-card root))))

(defn last-n-lines [text n]
  (vec (take-last n (str/split-lines (or text "")))))

(defn pane-sentences [text]
  (->> (str/split-lines (or text ""))
       (map str/trim)
       (remove str/blank?)
       (str/join " ")
       (#(str/split % #"(?<=[.!?…])\s+"))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn fold-apostrophe [s]
  (str/replace (or s "") "\u2019" "'"))

(defn i-status? [sentence]
  (boolean (re-find #"\bI(?:'(?:ll|m|ve))?\b" (fold-apostrophe sentence))))

(defn other-status? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"\blet me\b" n)
                 (re-find #"hand off" n)
                 (re-find #"handing off" n)
                 (re-find #"handoff" n)
                 (re-find #"continue" n)
                 (re-find #"\breceived\b" n)
                 (re-find #"\bsettled\b" n)
                 (re-find #"\bresolved\b" n)
                 (re-find #"\bcompleted\b" n)
                 (re-find #"\bfound\b" n)))))

(defn tool-trace? [sentence]
  (boolean (re-find #"(?i)^(?:•\s*)?(?:Ran|Edited|Added)\b"
                    (fold-apostrophe sentence))))

(defn mail-banner? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"you have new handoff mail" n)
                 (re-find #"if idle, run ready_for_next" n)
                 (re-find #"rejected:" n)))))

(defn pane-chrome? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"to view transcript" n)
                 (re-find #"running the handoff command again" n)))))

(defn status-sentence? [sentence]
  (and (not (mail-banner? sentence))
       (not (tool-trace? sentence))
       (not (pane-chrome? sentence))
       (or (i-status? sentence) (other-status? sentence))))

(defn strip-bullet [sentence]
  (str/replace (or sentence "") #"^[•*›]\s*" ""))

(defn codex-throwaway-bullet? [sentence]
  (let [n (str/lower-case (fold-apostrophe (strip-bullet sentence)))]
    (boolean (or (re-find #"^(?:working|ran|edited|added|searching|searched)\b" n)
                 (re-find #"you have \d+ usage limit reset available" n)
                 (mail-banner? sentence)
                 (pane-chrome? sentence)))))

(defn codex-bullets [text]
  (loop [lines (mapv str/trim (str/split-lines (or text "")))
         current nil
         out []]
    (if-let [line (first lines)]
      (cond
        (str/blank? line)
        (recur (next lines) current out)

        (re-find #"^[•*]\s*" line)
        (recur (next lines) line (cond-> out current (conj current)))

        current
        (recur (next lines) (str current " " line) out)

        :else
        (recur (next lines) current out))
      (cond-> out current (conj current)))))

(defn pane-cache-key [root role]
  [(str root) (str role)])

(defn matching-status-sentences [text backend]
  (let [tail (last-n-lines (pane-sample text backend) 20)
        joined (str/join "\n" tail)
        from-sentences (filterv status-sentence? (pane-sentences joined))]
    (if (= "codex" backend)
      (let [bullets (vec (remove codex-throwaway-bullet? (codex-bullets joined)))]
        (if (seq bullets) bullets from-sentences))
      from-sentences)))

(defn im-status-lines [role text backend]
  (let [found (vec (take-last 2 (matching-status-sentences text backend)))]
    (if (seq found)
      (do (swap! pane-status-lines assoc role found)
          (swap! pane-status assoc role (last found))
          found)
      (or (not-empty (get @pane-status-lines role))
          (let [one (get @pane-status role "")]
            (if (str/blank? one) [] [one]))))))

(defn im-status [role text backend]
  (or (last (im-status-lines role text backend)) ""))

(defn board-tasks [root]
  (state-cached
   :board-task-reads [::board-tasks (str root)]
   (fn []
     (let [file (fs/path root ".swarmforge" "board" "tasks.tsv")]
       (if (fs/regular-file? file)
         (mapv task-entry (lines (slurp (str file))))
         [])))))

(defn pane-status-lines-for [root role]
  (state-cached
   :pane-status-builds [::pane-status (str root) (str role)]
   (fn []
     (let [row (role-row root role)
           text (when row (state-pane-text root role row))
           backend (when row (backend-name row))]
       (if row
         (im-status-lines (pane-cache-key root role) text backend)
         [])))))

(defn pane-status-for [root role]
  (state-cached
   :pane-status-for-builds [::pane-status-for (str root) (str role)]
   #(or (last (pane-status-lines-for root role)) "")))

(defn active-card-names
  ([root role] (active-card-names root role (board-tasks root)))
  ([root role cards]
   (let [row (role-row root role)
         names (when row (in-process-task-names (in-process-for-row row)))
         lane-cards (filter #(= role (:lane %)) cards)]
     (if (seq names)
       (set names)
       (if (= 1 (count lane-cards))
         #{(:name (first lane-cards))}
         #{})))))

(defn rejected-task? [root name]
  (fs/exists? (fs/path root ".swarmforge" "notify" (str "reject-" name))))

(defn task-with-status-from-view
  [root task pending-ids pending-names active-names pane-statuses]
  (let [role (:lane task)
        name (:name task)
        task-id (:id task)]
    (assoc task :status
           (cond
             (= "done" role) ""
             (rejected-task? root name) "REJECTED"
             (or (contains? pending-ids task-id)
                 (contains? pending-names name)) "Waiting for approval"
             (contains? (get active-names role #{}) name)
             (get pane-statuses role "")
             :else "waiting in queue"))))

(defn task-with-status [root task]
  (let [board (board-tasks root)
        approval-items (approvals root)
        pending-ids (set (keep #(not-empty (:task_id %)) approval-items))
        pending-names (set (keep #(not-empty (:task %)) approval-items))
        active-by-role (into {}
                             (map (fn [row]
                                    [(first row)
                                     (active-card-names root (first row) board)])
                                  (role-rows root)))
        pane-statuses (into {}
                            (keep (fn [[role names]]
                                    (when (seq names)
                                      [role (pane-status-for root role)])))
                            active-by-role)]
    (task-with-status-from-view root task pending-ids pending-names
                                active-by-role pane-statuses)))

(defn batch-task-names [dir]
  (in-process-task-names (handoff-files dir)))

(defn multi-batches [dir]
  (for [b (batch-dirs dir)
        :let [names (batch-task-names b)]
        :when (next names)]
    [(fs/file-name b) names]))

(defn index-batches [idx pairs]
  (reduce (fn [m [id names]]
            (reduce #(assoc %1 %2 id) m names))
          idx
          pairs))

(defn batch-index [root]
  (reduce (fn [idx row]
            (let [wt (nth row 2)]
              (if (str/blank? wt)
                idx
                (index-batches idx
                               (concat (multi-batches (fs/path wt ".swarmforge" "handoffs" "inbox" "completed"))
                                       (multi-batches (in-process-dir wt)))))))
          {}
          (role-rows root)))

(defn reverse-handoff? [path]
  (let [h (:headers (parse-message path))]
    (and (= "git_handoff" (get h "type"))
         (= "true" (get h "non-forwarding")))))

(defn merging-card [root row]
  (when-let [file (first (filter reverse-handoff? (in-process-for-row row)))]
    (let [h (:headers (parse-message file))
          name (or (get h "task") (get h "task_id"))
          role (first row)
          sender (str/trim (or (get h "from") ""))]
      (when-not (str/blank? name)
        {:name name
         :id (str "merging-" (or (get h "task_id") name))
         :lane role
         :updated_at (or (not-empty (get h "dequeued_at")) "")
         :audit_count 0
         :merging true
         :status (str "Merging " sender)}))))

(defn merging-cards [root]
  (vec (keep #(merging-card root %) (role-rows root))))

(defn tasks [root]
  (state-cached
   :task-view-builds [::tasks (str root)]
   (fn []
     (let [raw-board (board-tasks root)
           idx (batch-index root)
           approval-items (approvals root)
           pending-ids (set (keep #(not-empty (:task_id %)) approval-items))
           pending-names (set (keep #(not-empty (:task %)) approval-items))
           active-by-role (into {}
                                (map (fn [row]
                                       [(first row)
                                        (active-card-names root (first row)
                                                           raw-board)])
                                     (role-rows root)))
           pane-statuses (into {}
                               (keep (fn [[role names]]
                                       (when (seq names)
                                         [role (pane-status-for root role)])))
                               active-by-role)
           board (mapv (fn [task]
                         (let [task (task-with-status-from-view
                                     root task pending-ids pending-names
                                     active-by-role pane-statuses)]
                           (if-let [batch (get idx (:name task))]
                             (assoc task :batch batch)
                             task)))
                       raw-board)]
       (into (merging-cards root) board)))))

(defn parse-message [path]
  (state-cached
   :message-parses [::message (str path)]
   (fn []
     (let [content (slurp (str path))
           [header body] (str/split content #"\n\n" 2)
           headers (into {}
                         (for [line (str/split-lines header)
                               :let [[k v] (str/split line #": " 2)]
                               :when (and k v)]
                           [k v]))]
       {:headers headers :body (or body "")}))))

(defn comma-list [text]
  (->> (str/split (or text "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn pending-dir [root]
  (fs/path root ".swarmforge" "handoffs" "pending_approval"))

(defn pending-files [root]
  (let [dir (pending-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %)))
      [])))

(defn approval-id [path]
  (str/replace (fs/file-name path) #"\.handoff$" ""))

(defn reviews-file [root id]
  (fs/path (pending-dir root) (str id ".reviews.json")))

(defn read-reviews [root id]
  (let [file (reviews-file root id)]
    (if (fs/regular-file? file)
      (try
        (let [parsed (json/parse-string (slurp (str file)))]
          (if (map? parsed) parsed {}))
        (catch Exception _ {}))
      {})))

(defn write-reviews! [root id reviews]
  (let [file (reviews-file root id)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (json/generate-string reviews))))

(defn drop-reviews! [root id]
  (fs/delete-if-exists (reviews-file root id)))

(defn task-reviews-file [root task-id]
  (fs/path root ".swarmforge" "rejected-tasks" task-id "reviews.json"))

(defn read-task-reviews [root task-id]
  (let [file (task-reviews-file root task-id)]
    (if (and (not (str/blank? task-id)) (fs/regular-file? file))
      (try
        (let [parsed (json/parse-string (slurp (str file)))]
          (if (map? parsed) parsed {}))
        (catch Exception _ {}))
      {})))

(defn write-task-reviews! [root task-id store]
  (when-not (str/blank? task-id)
    (let [file (task-reviews-file root task-id)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) (json/generate-string store)))))

(defn drop-task-reviews! [root task-id]
  (when-not (str/blank? task-id)
    (fs/delete-if-exists (task-reviews-file root task-id))))

(defn iso-now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn append-task-review! [root task-id path comments]
  (let [text (str/trim (or comments ""))]
    (when (and (not (str/blank? task-id)) (not (str/blank? path)) (not (str/blank? text)))
      (let [store (read-task-reviews root task-id)
            entry {"at" (iso-now) "text" text}
            history (conj (vec (get store path [])) entry)]
        (write-task-reviews! root task-id (assoc store path history))))))

(defn path-review-history [root task-id path]
  (vec (get (read-task-reviews root task-id) path [])))

(defn approval-entry [root path]
  (let [headers (:headers (parse-message path))
        to (first (comma-list (get headers "to")))
        id (approval-id path)
        kind (not-empty (get headers "kind"))]
    (cond-> {:id id
             :gate (str "spec → " to)
             :task_id (or (not-empty (get headers "task_id")) (get headers "task"))
             :task (get headers "task")
             :artifacts (filterv #(allowed-doc? root %)
                                  (comma-list (get headers "artifacts")))
             :reviews (read-reviews root id)}
      kind (assoc :kind kind))))

(defn approvals [root]
  (state-cached
   :approval-scans [::approvals (str root)]
   #(mapv (fn [path] (approval-entry root path)) (pending-files root))))

;; An approval the dashboard resolved, kept so a reader that did not resolve it can be
;; told how it ended rather than only that it is gone. The bridge's room is that reader:
;; an approval approved away from the room - the dashboard's Accept, or an approve the
;; lieutenant runs on the operator's word - left the bridge nothing to report but
;; "Resolved on the desktop", because a resolution it did not carry out leaves no
;; resolution in its own state. Both endings write one record, keyed by the approval's
;; own id and worded as the bridge words them, and only the newest are kept: a reader
;; that missed one has missed it, and this is not the bridge's memory.
(def resolved-approvals-kept 100)

(defn resolved-approvals-dir [root]
  (fs/path root ".swarmforge" "approvals" "resolved"))

(defn resolved-approval-files [dir]
  (->> (fs/list-dir dir)
       (filter #(str/ends-with? (fs/file-name %) ".edn"))
       (sort-by #(fs/last-modified-time %) #(compare %2 %1))))

(defn resolved-approval [path]
  (try
    (let [record (edn/read-string (slurp (str path)))]
      (when (:id record)
        {:id (:id record)
         :task (:task record)
         :resolution (:resolution record)
         :at (:at record)}))
    (catch Exception _ nil)))

(defn resolved-approvals [root]
  (state-cached
   :resolved-approval-scans [::resolved-approvals (str root)]
   (fn []
     (let [dir (resolved-approvals-dir root)]
       (if (fs/directory? dir)
         (->> (resolved-approval-files dir) (keep resolved-approval) vec)
         [])))))

(defn record-resolution!
  "Write down how an approval ended, before its pending file is gone: the id, the card,
  and the resolution worded the way the bridge words it (approved, sent_back)."
  [root id task resolution]
  (let [dir (resolved-approvals-dir root)
        _ (fs/create-dirs dir)
        file (fs/path dir (str id ".edn"))]
    (spit (str file)
          (pr-str {:id id
                   :task task
                   :resolution resolution
                   :at (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                                (java.time.Instant/now))}))
    (doseq [old (drop resolved-approvals-kept (resolved-approval-files dir))]
      (fs/delete-if-exists old))))

(defn listed [dir pred]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter pred)
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn handoff-files [dir]
  (listed dir #(and (fs/regular-file? %)
                    (str/ends-with? (fs/file-name %) ".handoff"))))

(defn batch-dirs [dir]
  (listed dir #(and (fs/directory? %)
                    (str/starts-with? (fs/file-name %) "batch_"))))

(defn in-process-files [dir]
  (into (handoff-files dir)
        (mapcat handoff-files (batch-dirs dir))))

(defn iso-mtime [path]
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (.toInstant (fs/last-modified-time path))))

(defn work-entry [role path]
  (let [headers (:headers (parse-message path))]
    {:task (get headers "task")
     :role role
     :updated_at (or (not-empty (get headers "dequeued_at"))
                     (iso-mtime path))}))

(defn in-process-dir [worktree]
  (fs/path worktree ".swarmforge" "handoffs" "inbox" "in_process"))

(defn session-alive? [socket session]
  (state-metric! :tmux-session-checks)
  (boolean
   (when (and socket session)
     (zero? (:exit (sh "tmux" "-S" socket "has-session" "-t" session))))))

(defn state-session-alive? [root role row]
  (state-cached
   :tmux-session-builds [::session-alive (str root) (str role)]
   #(session-alive? (tmux-socket root) (session-name row))))

(defn role-queue-state [alive? busy?]
  (cond
    (not alive?) "no_session"
    busy? "live"
    :else "idle"))

(defn in-process-for-row [row]
  (let [worktree (nth row 2 nil)]
    (if (str/blank? worktree)
      []
      (state-cached
       :in-process-scans [::in-process (str worktree)]
       #(in-process-files (in-process-dir worktree))))))

(defn session-name [row]
  (let [role (first row)
        session (nth row 3 nil)]
    (if (str/blank? session)
      (str "swarmforge-" role)
      session)))

(defn pane-target [row]
  (let [session (session-name row)
        window (nth row 4 nil)]
    (if (str/blank? window)
      session
      (str session ":" window ".0"))))

(defn backend-name [row]
  (str/lower-case (or (nth row 5 nil) "")))

(defn drop-mail-lines [text]
  (->> (str/split-lines (or text ""))
       (remove mail-banner?)
       (str/join "\n")))

(defn pane-sample [text _backend]
  (drop-mail-lines text))

(defn bag-diff [a b]
  (let [ks (set (concat (keys a) (keys b)))]
    (reduce (fn [n k]
              (+ n (Math/abs (- (long (get a k 0)) (long (get b k 0))))))
            0
            ks)))

(defn heat-from-count [n]
  (min 6 (long n)))

(defn record-heat! [key text backend]
  (let [tail (last-n-lines (pane-sample text backend) 20)
        bag (frequencies tail)
        prev (get @pane-heat key)
        n (if (:bag prev) (bag-diff (:bag prev) bag) 0)
        heat (heat-from-count n)]
    (swap! pane-heat assoc key {:bag bag :heat heat})
    heat))

(defn role-heat [root role alive? text backend]
  (if alive?
    (record-heat! (pane-cache-key root role) text backend)
    0))

(def master-shells
  #{"zsh" "bash" "sh" "dash" "ksh" "fish" "tcsh" "csh" "login" "-zsh" "-bash"})

(def activity-working-pattern
  #"(?im)^\s*(?:[•*]\s*working(?:\s*\([^)\r\n]*\))?|[✻✽✶✳]\s*.*\(esc\s+to\s+interrupt[^)\r\n]*\)|esc\s+to\s+interrupt(?:\s*[·.)-].*)?|waiting\s+for\s+response(?:\s+\d+(?:\.\d+)?s)?)\s*$")

(def activity-idle-pattern
  #"(?im)^\s*(?:[›❯]\s+ask (?:codex|claude|copilot) to do anything|\? for shortcuts|enter:send\s+esc:cancel|ready(?: for (?:the )?next (?:task|card))?[.!]?|idle[.!]?|(?:done|completed|finished)(?: successfully)?[.!]?)\s*$")

(def activity-token-pattern
  #"(?i)\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(def activity-path-pattern
  #"(?:(?:~|/)[^\s]+|[A-Za-z]:\\[^\s]+)")

(def activity-socket-pattern
  #"\bswarmforge-[A-Za-z0-9._-]+\b")

(def activity-status-max-chars 120)
(def activity-tail-lines 8)

(defn fallback-shell? [command]
  (let [normalized (some-> command
                           str/trim
                           str/lower-case
                           (str/replace #"^.*[\\/]" ""))]
    (contains? master-shells normalized)))

(defn safe-activity-line [line]
  (some-> line
          (str/replace #"[\p{Cc}\p{Cf}]" " ")
          (str/replace #"\s+" " ")
          str/trim
          not-empty
          (str/replace activity-token-pattern "[redacted]")
          (str/replace activity-path-pattern "[path]")
          (str/replace activity-socket-pattern "[socket]")
          (as-> text
                (if (> (count text) activity-status-max-chars)
                  (str (subs text 0 (dec activity-status-max-chars)) "…")
                  text))
          str/trim
          not-empty))

(defn last-pattern-match [pattern text]
  (let [matcher (re-matcher pattern text)]
    (loop [found nil]
      (if (.find matcher)
        (recur {:start (.start matcher)
                :line (str/trim (.group matcher))})
        found))))

(defn activity-tail-lines-for [text]
  (last-n-lines (pane-sample text nil) activity-tail-lines))

(defn activity-marker [text]
  (let [lines (activity-tail-lines-for text)
        tail (str/join "\n" lines)
        working (last-pattern-match activity-working-pattern tail)
        idle (last-pattern-match activity-idle-pattern tail)
        marker (cond
                 (nil? working) idle
                 (nil? idle) working
                 (>= (:start idle) (:start working)) idle
                 :else working)
        line-number (when marker
                      (count (re-seq #"\n"
                                     (subs tail 0 (:start marker)))))]
    (when marker
      (assoc marker
             :state (if (= marker idle) "idle" "working")
             :line-index (or line-number 0)
             :kind (if (and (= marker idle)
                            (re-find #"(?i)ask codex to do anything"
                                     (:line marker)))
                     :codex-idle-prompt
                     :generic)))))

(defn activity-output-state [text]
  (or (:state (activity-marker text)) "unknown"))

(defn activity-status-line [text marker]
  (let [lines (activity-tail-lines-for text)
        current (str/join "\n" (drop (or (:line-index marker) 0) lines))
        recognized (->> (pane-sentences current)
                        (filter status-sentence?)
                        last)
        candidate (or recognized
                      (some-> (:line marker) strip-bullet))]
    (safe-activity-line candidate)))

(defn pane-current-command [socket target]
  (state-metric! :tmux-command-reads)
  (try
    (let [result (sh "tmux" "-S" socket "display-message" "-p" "-t" target
                     "#{pane_current_command}")]
      (when (zero? (:exit result))
        (not-empty (str/trim (:out result)))))
    (catch Exception _ nil)))

(defn state-pane-command [root role row]
  (state-cached
   :pane-command-builds [::pane-command (str root) (str role)]
   #(pane-current-command (tmux-socket root) (pane-target row))))

(defn lieutenant-runtime-facts [root]
  (if *lieutenant-activity-context*
    *lieutenant-activity-context*
    (if-let [row (master-row root)]
      (let [alive? (try
                     (state-session-alive? root "lieutenant" row)
                     (catch Exception _ false))]
        {:alive? alive?
         :command (when alive?
                    (state-pane-command root "lieutenant" row))})
      {:alive? false})))

(defn activity-fingerprint [activity]
  (select-keys activity [:alive :state :status_line]))

(defn lieutenant-activity-snapshot [root]
  (let [row (master-row root)
        {:keys [alive? command]} (lieutenant-runtime-facts root)
        available? (and alive? (not (fallback-shell? command)))
        backend (when row (backend-name row))
        text (when available? (state-pane-text root "lieutenant" row))
        marker (when available? (activity-marker text))
        state (if available? (or (:state marker) "unknown") "unavailable")
        heat (if available?
               (role-heat root "lieutenant" true text backend)
               0)
        activity {:alive (boolean alive?)
                  :state state
                  :heat heat
                  :status_line (when (and available? marker)
                                 (activity-status-line text marker))}
        fingerprint (activity-fingerprint activity)
        now (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                     (java.time.Instant/now))
        history (swap! lieutenant-activity-history
                        update root
                        (fn [previous]
                          (if (= fingerprint (:fingerprint previous))
                            previous
                            {:fingerprint fingerprint
                             :last_changed_at now})))]
    (assoc activity :last_changed_at
           (get-in history [root :last_changed_at]))))

(defn cards-in-lane [all-tasks lane]
  (filterv #(= lane (:lane %)) all-tasks))

(defn queue-row [role names batch-names busy? alive? activity updated]
  {:task (or (first names) "")
   :tasks (vec names)
   :batch_tasks (vec batch-names)
   :role role
   :state (role-queue-state alive? busy?)
   :updated_at (or updated "")
   :activity activity})

(defn in-process-task-names [files]
  (->> files
       (map #(get-in (parse-message %) [:headers "task"]))
       (remove str/blank?)
       distinct
       vec))

(defn work-task-names [files cards]
  (let [from-files (in-process-task-names files)
        from-cards (mapv :name cards)]
    (vec (if (seq from-files) from-files from-cards))))

(defn in-process-batch-task-names [row]
  (let [worktree (nth row 2 nil)
        dir (when-not (str/blank? worktree) (in-process-dir worktree))
        batches (when dir (batch-dirs dir))]
    (if (= 1 (count batches))
      (in-process-task-names (handoff-files (first batches)))
      [])))

(defn work-row-for-role [root socket row all-tasks]
  (let [role (first row)
        files (in-process-for-row row)
        path (first files)
        from-file (when path (work-entry role path))
        cards (cards-in-lane all-tasks role)
        card (first cards)
        busy? (boolean (or path card))
        alive? (state-session-alive? root role row)
        text (when (or alive? (some? *pane-text*))
               (state-pane-text root role row))
        names (work-task-names files cards)
        batch-names (in-process-batch-task-names row)]
    (queue-row role names batch-names busy? alive?
               (role-heat root role (or alive? (some? *pane-text*)) text (backend-name row))
               (or (:updated_at from-file) (:updated_at card) ""))))

(defn work-in-flight [root]
  (state-cached
   :work-view-builds [::work-in-flight (str root)]
   (fn []
     (let [socket (tmux-socket root)
           all-tasks (tasks root)]
       (mapv #(work-row-for-role root socket % all-tasks) (role-rows root))))))

(defn chat-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "pending"))

(defn chat-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "done"))

(defn chat-files [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".request"))
         (sort-by str)
         vec)
    []))

(def chat-thread-id-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def chat-proposal-id-re #"proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def chat-request-id-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def chat-source-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
(def chat-token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(defn chat-thread-id [headers]
  (let [value (some-> (get headers "thread_id") str/trim not-empty)]
    (when (and value (re-matches chat-thread-id-re value))
      value)))

(defn chat-source [headers]
  (let [value (some-> (get headers "source") str/trim not-empty)]
    (when (and value
               (re-matches chat-source-re value)
               (not (re-find chat-token-re value)))
      value)))

(defn chat-proposal-id [headers]
  (let [value (some-> (get headers "proposal_id") str/trim not-empty)]
    (when (and value (re-matches chat-proposal-id-re value))
      value)))

(def route-card-action-type "approve-route-card")
(def route-action-family-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def route-absolute-path-re
  #"(?<![A-Za-z0-9+.-])/(?:Users|home|private|var|tmp|opt|Volumes|srv)/[^\s)\]},>]+")

(defn route-action-text [value maximum]
  (let [text (-> (str (or value ""))
                 (str/replace #"[\p{Cntrl}]" " ")
                 (str/replace #"\s+" " ")
                 (str/replace route-absolute-path-re "[redacted-path]")
                 (str/replace chat-token-re "[redacted-token]")
                 str/trim)]
    (when-not (str/blank? text)
      (subs text 0 (min maximum (count text))))))

(defn route-proposal-status [proposal]
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

(defn chat-route-root [root]
  (when root
    (let [root (try
                 (fs/canonicalize (fs/path root))
                 (catch Exception _ nil))]
      (when root
        (or (when (fs/directory? (fs/path root ".swarmforge" "route-proposals"))
              root)
            (let [projects (fs/parent root)
                  forge (when projects (fs/parent projects))]
              (when (and projects
                         forge
                         (= "projects" (str (fs/file-name projects)))
                         (fs/directory? (fs/path forge ".swarmforge"
                                                "route-proposals")))
                forge)))))))

(defn resolve-chat-action [root action]
  (let [forge (chat-route-root root)
        proposal-id (:proposal_id action)
        file (when (and forge proposal-id)
               (fs/path forge ".swarmforge" "route-proposals"
                        (str proposal-id ".edn")))
        proposal (when (and file (fs/regular-file? file))
                   (try
                     (edn/read-string (slurp (str file)))
                     (catch Exception _ nil)))
        project (when (and forge proposal (string? (:project proposal)))
                  (try
                    (fs/canonicalize (fs/path (:project proposal)))
                    (catch Exception _ nil)))
        expected-parent (when forge
                          (try
                            (fs/canonicalize (fs/path forge "projects"))
                            (catch Exception _ nil)))
        family (when forge (route-action-text (fs/file-name forge) 128))
        project-name (when project (route-action-text (fs/file-name project) 128))
        status (when (and (map? proposal)
                          (= proposal-id (:id proposal))
                          expected-parent
                          (= expected-parent (fs/parent project))
                          (= family (:family action))
                          (= project-name (:project action)))
                 (route-proposal-status proposal))
        available? (= "pending" status)]
    (cond-> action
      (and available? (route-action-text (:name proposal) 256))
      (assoc :task_name (route-action-text (:name proposal) 256)
             :label (route-action-text (str "Approve " (:name proposal)) 80))

      true
      (assoc :status (or status "unavailable")
             :available available?))))

(defn chat-action [root headers]
  (let [stored-status (some-> (get headers "action_status")
                              str/trim str/lower-case)
        stored-status (when (contains? #{"pending" "consumed" "superseded"
                                          "unavailable"}
                                       stored-status)
                        stored-status)
        proposal-id (chat-proposal-id {"proposal_id" (get headers "action_proposal_id")})
        family (let [value (some-> (get headers "action_family") str/trim not-empty)]
                 (when (and value (re-matches route-action-family-re value)) value))
        project (let [value (some-> (get headers "action_project") str/trim not-empty)]
                  (when (and value (re-matches route-action-family-re value)) value))
        task-name (route-action-text (get headers "action_task_name") 256)
        label (route-action-text (get headers "action_label") 80)
        action (when (and (= route-card-action-type (get headers "action"))
                          stored-status proposal-id family project task-name label)
                 {:type route-card-action-type
                  :proposal_id proposal-id
                  :family family
                  :project project
                  :task_name task-name
                  :label label
                  :status stored-status})]
    (when action
      (resolve-chat-action root action))))

(defn parse-chat
  ([path] (parse-chat nil path))
  ([root path]
  (state-metric! :chat-file-reads)
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))
        proposal-id (chat-proposal-id headers)
        thread-id (chat-thread-id headers)
        source (chat-source headers)
        action (chat-action root headers)]
    (cond-> {:id (get headers "id")
             :status (get headers "status")
             :body (or body "")
             :response (str/replace (get headers "response" "") #"\\n" "\n")
             :created_at (get headers "created_at")}
      proposal-id (assoc :proposal_id proposal-id)
      thread-id (assoc :thread_id thread-id)
      source (assoc :source source)
      action (assoc :actions [action])))))

(defn list-chat [root]
  (state-cached
   :chat-scans [::chat (str root)]
   #(vec (bounded-pmap dashboard-chat-workers
                       (fn [path] (parse-chat root path))
                       (concat (chat-files (chat-pending-dir root))
                               (chat-files (chat-done-dir root)))))))

(defn clar-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "pending"))

(defn clar-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "done"))

(defn parse-clarification [path]
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:id (get headers "id")
     :status (get headers "status")
     :role (get headers "role")
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")
     :created_at (get headers "created_at")}))

(defn list-clarifications [root]
  (state-cached
   :clarification-scans [::clarifications (str root)]
   #(vec (bounded-pmap dashboard-chat-workers
                       parse-clarification
                       (concat (chat-files (clar-pending-dir root))
                               (chat-files (clar-done-dir root)))))))

(defn chat-id []
  (str "req-" (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")))

(defn chat-request-file [root state id]
  (fs/path root ".swarmforge" "dashboard" "requests" state
           (str id ".request")))

(defn existing-chat-request [root id]
  (some (fn [path]
          (when (fs/regular-file? path)
            {:path path :entry (parse-chat path)}))
        [(chat-request-file root "pending" id)
         (chat-request-file root "done" id)]))

(defn fresh-chat-id [root]
  (loop []
    (let [id (chat-id)]
      (if (existing-chat-request root id)
        (recur)
        id))))

(defn with-chat-request-lock [root f]
  (let [path (fs/path root ".swarmforge" "dashboard" "requests" "requests.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs (fs/parent path))
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn chat-request-invalid! [message]
  (throw (ex-info message {:http-status 400})))

(defn supplied-chat-id [value kind pattern]
  (when-not (and (string? value)
                 (re-matches pattern value)
                 (not (re-find chat-token-re value)))
    (chat-request-invalid! (str kind " is invalid.")))
  value)

;; A request typed into the pane without the command that answers it leaves the
;; operator with nothing: the request is written down, and the reply lands only
;; where they cannot see it. The reminder rides with the request itself, in the
;; same words roles/lieutenant.prompt already carries, so a session that never
;; read its prompt - or read a stale copy - still answers through the tool.
;; An approval notification the operator forwards arrives looking like any other
;; chat request, but it carries a gate, and the gate is the operator's: the
;; lieutenant reads the pending handoff and says what it thinks. The bridge
;; writes the notification's first line, so the shape is knowable rather than
;; guessed at.
(defn approval-request? [text]
  (boolean (re-find #"(?m)^Approval for .+ in .+" (or text ""))))

(defn clarification-request? [text]
  (boolean (re-find #"(?m)^Clarification for .+ from .+" (or text ""))))

(defn answer-reminder [id text]
  (str "Answer with: pack_dashboard_request.sh answer " id " ./tmp/answer.txt"
       " (a reply only in this pane reaches nobody)."
       (when (approval-request? text)
         (str " This one is an approval gate, and the gate is the operator's: read the"
              " pending handoff, then reply with your assessment and a recommendation."
              " Do not approve unless the operator says to."))
       (when (clarification-request? text)
         (str " This one is a clarification an agent is blocked on, and the answer is"
              " the operator's to give: read the question, ground it in the project's"
              " state, then reply with what you would answer and why. Do not answer it"
              " unless the operator says to."))))

(defn chat-wake [id text]
  (str (if (str/includes? (or text "") "\n")
         (str "[" id "]\n" text)
         (str "[" id "] " text))
       "\n"
       (answer-reminder id text)))

(defn clar-wake [id role question answer]
  (str "[" id "]\n"
       "Clarification requested from: " role "\n"
       "Question:\n" (str/trimr (or question "")) "\n"
       "Answer:\n" (str/trimr (or answer ""))))

(defn write-chat-request!
  ([root text]
   (write-chat-request! root text {}))
  ([root text {:keys [request_id thread_id source proposal_id]}]
   (let [id (or request_id (fresh-chat-id root))
         text (str/trimr (or text ""))
         file (fs/path (chat-pending-dir root) (str id ".request"))]
     (fs/create-dirs (fs/parent file))
     (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                     :prefix ".chat-request."})]
       (spit (str tmp)
             (str "id: " id "\n"
                  "status: pending\n"
                  "created_at: " (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                                          (java.time.Instant/now)) "\n"
                  (when thread_id (str "thread_id: " thread_id "\n"))
                  (when source (str "source: " source "\n"))
                  (when proposal_id (str "proposal_id: " proposal_id "\n"))
                  "\n"
                  text "\n"))
       (fs/move tmp file {:replace-existing true :atomic-move true}))
     id)))

(defn dashboard-state [root]
  (let [master (master-role root)]
    {:master_role master
     :master_display (display-name-for-role master)
     :lanes (lanes root)
     :tasks (tasks root)
     :active_card (active-single-agent-card root)
     :idle (project-idle? root)
     :approvals (approvals root)
     :resolved_approvals (resolved-approvals root)
     :work_in_flight (work-in-flight root)
     :chat (list-chat root)
     :clarifications (list-clarifications root)}))

(defn tagged [project items]
  (mapv #(assoc % :project project) items))

(defn open-project-root [forge name]
  (str (forge/project-dir forge name)))

(defn single-agent-project? [root]
  (fs/regular-file? (fs/path root ".swarmforge" "single-agent")))

(defn project-dashboard-url [root]
  (let [file (fs/path root ".swarmforge" "dashboard-url")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn activation-file [root task-id]
  (fs/path root ".swarmforge" "card-activations"
           (str (str/replace (str task-id) #"[^0-9A-Za-z._-]" "_") ".edn")))

(defn activation-record [root task-id]
  (let [file (activation-file root task-id)]
    (when (fs/regular-file? file)
      (try
        (let [record (edn/read-string (slurp (str file)))]
          {:present true
           :activated-at (or (:activated_at record)
                             (:activated-at record)
                             (get record "activated_at")
                             (get record "activated-at"))})
        (catch Exception _
          {:present true :activated-at nil})))))

(defn parse-instant [value]
  (when (string? value)
    (try
      (java.time.Instant/parse value)
      (catch Exception _ nil))))

(defn duration-seconds [start end]
  (let [start (parse-instant start)
        end (parse-instant end)]
    (when (and start end (not (.isBefore end start)))
      (.getSeconds (java.time.Duration/between start end)))))

(defn project-task-name [root]
  (let [file (fs/path root "stages.edn")]
    (when (fs/regular-file? file)
      (try
        (:task (edn/read-string (slurp (str file))))
        (catch Exception _ nil)))))

(defn completed-card [root project-name line]
  (let [[name lane created updated task-id] (str/split line #"\t" -1)
        task-id (or (not-empty task-id) name)]
    (when (and (not (str/blank? name))
               (not= name project-name)
               (= lane "done"))
      (let [activation (activation-record root task-id)
            start (if (:present activation)
                    (:activated-at activation)
                    created)
            duration (duration-seconds start updated)]
        {:project project-name
         :task_id task-id
         :active_seconds duration}))))

(defn completion-cards [root]
  (let [file (fs/path root ".swarmforge" "board" "tasks.tsv")
        project-name (project-task-name root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (keep #(completed-card root project-name %))
           vec)
      [])))

(defn completion-durations [root]
  (->> (completion-cards root)
       (keep :active_seconds)
       vec))

(defn rounded-mean [values]
  (Math/round (double (/ (reduce + 0 values) (count values)))))

(defn median-seconds [values]
  (let [sorted (vec (sort values))
        count (count sorted)
        middle (quot count 2)]
    (if (odd? count)
      (nth sorted middle)
      (Math/round (double (/ (+ (nth sorted (dec middle))
                               (nth sorted middle))
                            2.0))))))

(defn completion-stats
  ([root] (completion-stats root nil))
  ([root reports]
   (state-cached
    :completion-stat-builds [::completion-stats (str root)]
    (fn []
      (let [project-name (project-task-name root)
            reported-identities (completed-card-report/reported-identities reports)
            current (->> (completion-cards root)
                         (remove #(contains? reported-identities
                                             [(:project %) (:task_id %)]))
                         (keep :active_seconds))
            historical (if (and (seq reports) project-name)
                         (completed-card-report/verified-durations reports project-name)
                         [])
            durations (try
                        (vec (concat historical current))
                        (catch Exception _ []))]
        (if (seq durations)
          {:completed_cards (count durations)
           :median_seconds (long (median-seconds durations))
           :mean_seconds (long (rounded-mean durations))
           :min_seconds (long (reduce min durations))
           :max_seconds (long (reduce max durations))}
          {:completed_cards 0
           :median_seconds nil
           :mean_seconds nil
           :min_seconds nil
           :max_seconds nil}))))))

(defn project-slice
  ([forge name] (project-slice forge name nil))
  ([forge name reports]
   (let [root (open-project-root forge name)
         stats (completion-stats root reports)]
     {:name name
      :open true
      :single_agent (single-agent-project? root)
      :active_card (active-single-agent-card root)
      :idle (project-idle? root)
      :dashboard_url (project-dashboard-url root)
      :lanes (lanes root)
      :tasks (tagged name (tasks root))
      :work_in_flight (tagged name (work-in-flight root))
      :completion_stats stats})))

(defn forge-dashboard-state [root]
  (let [open (forge/read-open-projects root)
        reports (try
                  (completed-card-report/history-records root)
                  (catch Exception _ []))
        projects (bounded-pmap dashboard-project-workers
                               #(project-slice root % reports)
                               open)]
    {:forge true
     :master_role "lieutenant"
     :master_display "Lieutenant"
     :packs (mapv (fn [p] {:name p :conf (or (forge/pack-conf root p) "")})
                  (forge/list-pack-names root))
     :all_projects (forge/list-project-names root)
     :open_projects open
     :projects projects
     :approvals (vec (mapcat (fn [name]
                               (try
                                 (tagged name (approvals (open-project-root root name)))
                                 (catch Exception _ [])))
                             open))
     :resolved_approvals (vec (mapcat (fn [name]
                                        (try
                                          (tagged name (resolved-approvals (open-project-root root name)))
                                          (catch Exception _ [])))
                                      open))
     :clarifications (vec (mapcat (fn [name]
                                    (try
                                      (tagged name (list-clarifications (open-project-root root name)))
                                      (catch Exception _ [])))
                                  open))
     :chat (list-chat root)
     :lieutenant_status (pane-status-lines-for root "lieutenant")
     :lieutenant_activity (lieutenant-activity-snapshot root)
     :lanes []
     :tasks []
     :work_in_flight (vec (mapcat :work_in_flight projects))}))

(defn api-state [root]
  (if *state-cache*
    (if (forge/forge? root)
      (forge-dashboard-state root)
      (dashboard-state root))
    (binding [*state-cache* (atom {})]
      (if (forge/forge? root)
        (forge-dashboard-state root)
        (dashboard-state root)))))

(defn health-response []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\":true,\"live\":true}"})

(def chat-page-default-limit 40)
(def chat-page-max-limit 100)
(def chat-page-source "app.lieutenant-chat")

(defn chat-page-error! [message]
  (throw (ex-info message {:http-status 400})))

(defn query-parameters [uri]
  (try
    (let [query (second (str/split (or uri "") #"\?" 2))
          pairs (if (str/blank? query)
                  []
                  (map (fn [pair]
                         (let [[key value] (str/split pair #"=" 2)]
                           [(java.net.URLDecoder/decode (or key "") "UTF-8")
                            (java.net.URLDecoder/decode (or value "") "UTF-8")]))
                       (str/split query #"&")))]
      (when (some #(str/blank? (first %)) pairs)
        (chat-page-error! "Chat pagination query is malformed."))
      (when-not (= (count pairs) (count (distinct (map first pairs))))
        (chat-page-error! "Chat pagination query repeats a parameter."))
      (into {} pairs))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception _
      (chat-page-error! "Chat pagination query is malformed."))))

(defn chat-page-parameters [uri]
  (let [params (query-parameters uri)
        pagination-keys (filter #(str/starts-with? % "chat_") (keys params))
        supported #{"chat_thread_id" "chat_limit" "chat_cursor"}
        unsupported (remove supported pagination-keys)]
    (when (seq unsupported)
      (chat-page-error! "Unsupported chat pagination parameter."))
    (when (seq pagination-keys)
      (let [thread-id (some-> (get params "chat_thread_id") str/trim not-empty)
            limit-text (get params "chat_limit")
            limit (if (str/blank? limit-text)
                    chat-page-default-limit
                    (if (re-matches #"[0-9]{1,3}" limit-text)
                      (Long/parseLong limit-text)
                      -1))
            cursor (some-> (get params "chat_cursor") str/trim not-empty)]
        (when-not (and thread-id (re-matches chat-thread-id-re thread-id))
          (chat-page-error! "Chat conversation id is invalid."))
        (when-not (<= 1 limit chat-page-max-limit)
          (chat-page-error! "Chat page limit must be between 1 and 100."))
        {:thread_id thread-id :limit limit :cursor cursor}))))

(defn sha256-hex [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str
           (map #(format "%02x" %)
                (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn chat-cursor-payload [thread-id turn]
  {:version 1
   :thread_id thread-id
   :created_at (:created_at turn)
   :id (:id turn)})

(defn encode-chat-cursor [thread-id turn]
  (let [payload (chat-cursor-payload thread-id turn)
        checked (assoc payload :checksum (sha256-hex (pr-str payload)))]
    (-> (java.util.Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString (.getBytes (pr-str checked) "UTF-8")))))

(defn decode-chat-cursor [thread-id value]
  (try
    (let [decoded (String. (.decode (java.util.Base64/getUrlDecoder)
                                    (str value))
                           "UTF-8")
          cursor (edn/read-string decoded)
          payload (dissoc cursor :checksum)]
      (when-not (and (map? cursor)
                     (= #{:version :thread_id :created_at :id :checksum}
                        (set (keys cursor)))
                     (= 1 (:version cursor))
                     (= thread-id (:thread_id cursor))
                     (= (:checksum cursor)
                        (sha256-hex (pr-str payload)))
                     (string? (:created_at cursor))
                     (not (str/blank? (:created_at cursor)))
                     (string? (:id cursor))
                     (re-matches chat-request-id-re (:id cursor)))
        (chat-page-error! "Chat page cursor is invalid."))
      {:created_at (:created_at cursor) :id (:id cursor)})
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception _
      (chat-page-error! "Chat page cursor is malformed."))))

(defn general-chat-turn? [turn]
  (and (= chat-page-source (:source turn))
       (nil? (:proposal_id turn))
       (some? (:thread_id turn))))

(defn chat-order [turn]
  [(str (:created_at turn)) (str (:id turn))])

(defn ordered-general-chat [root]
  (let [turns (filter general-chat-turn? (list-chat root))
        invalid (some #(when (or (str/blank? (str (:id %)))
                                 (str/blank? (str (:created_at %))))
                         %)
                      turns)]
    (when invalid
      (throw (ex-info "Chat history contains a malformed general turn."
                      {:http-status 502})))
    (sort-by chat-order turns)))

(defn chat-conversation-summary [thread-id turns]
  (let [ordered (sort-by chat-order turns)
        latest (last ordered)]
    {:thread_id thread-id
     :turn_count (count ordered)
     :pending_count (count (filter #(= "pending" (:status %)) ordered))
     :latest (when latest
               (select-keys latest [:id :status :created_at :updated_at]))}))

(defn chat-conversation-summaries [turns]
  (->> (group-by :thread_id turns)
       (map (fn [[thread-id thread-turns]]
              (chat-conversation-summary thread-id thread-turns)))
       (sort-by :thread_id)
       vec))

(defn paged-chat-state [root pagination]
  (let [thread-id (:thread_id pagination)
        limit (:limit pagination)
        boundary (when-let [cursor (:cursor pagination)]
                   (decode-chat-cursor thread-id cursor))
        all-general (vec (ordered-general-chat root))
        thread-turns (filter #(= thread-id (:thread_id %)) all-general)
        eligible (if boundary
                   (filter #(neg? (compare (chat-order %)
                                           (chat-order boundary)))
                           thread-turns)
                   thread-turns)
        page (vec (take-last limit eligible))
        has-more (> (count eligible) limit)
        next-cursor (when has-more
                      (encode-chat-cursor thread-id (first page)))
        page-ids (set (map :id page))
        pending (->> thread-turns
                     (filter #(= "pending" (:status %)))
                     (remove #(contains? page-ids (:id %)))
                     (sort-by chat-order)
                     vec)
        summary (chat-conversation-summary thread-id thread-turns)
        base (api-state root)]
    (assoc base
           :chat page
           :chat_conversations (chat-conversation-summaries all-general)
           :chat_page {:thread_id thread-id
                       :limit limit
                       :has_more has-more
                       :next_cursor next-cursor
                       :conversation summary
                       :pending_turns pending})))

(defn api-state-with-chat-page [root uri]
  (if-let [pagination (chat-page-parameters uri)]
    (paged-chat-state root pagination)
    (api-state root)))

(defn require-root! [root]
  (when (str/blank? root)
    (exit! 1 "Missing project root"))
  root)

(defn dashboard-page []
  (slurp (str (fs/path script-dir "pack" "dashboard.html"))))

(defn slug [s]
  (str/replace (or s "") #"[^A-Za-z0-9]+" "_"))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSSSSS'Z'")
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn id-slug [s]
  (let [slugged (-> (or s "")
                    str/lower-case
                    (str/replace #"[^a-z0-9]+" "-")
                    (str/replace #"(^-+|-+$)" ""))]
    (if (str/blank? slugged) "task" slugged)))

(defn new-task-id [name]
  (str (id-timestamp) "-" (id-slug name)))

(defn queue-new-task-note! [root task-id name text]
  (let [to (master-role root)
        now (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                     (java.time.Instant/now))
        stamp (str/replace now #"[^0-9A-Za-z]" "")
        body (or text "")
        filename (str "50_" stamp "_from_New_Task_to_" (slug to) ".handoff")
        outbox (fs/path root ".swarmforge" "handoffs" "outbox")
        file (fs/path outbox filename)]
    (fs/create-dirs outbox)
    (spit (str file)
          (str "id: " stamp "_from_New_Task\n"
               "from: (New Task)\n"
               "to: " to "\n"
               "priority: 50\n"
               "type: note\n"
               "task_id: " task-id "\n"
               "task: " name "\n"
               "created_at: " now "\n"
               "\n"
               body
               (when-not (str/ends-with? body "\n") "\n")))))

(defn json-ok []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:ok true})})

(defn http-error [status message]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error message})})

(defn handoff-dirs [root]
  (->> (role-rows root)
       (map #(nth % 2 nil))
       (remove str/blank?)
       (cons (str root))
       distinct
       (mapv #(fs/path % ".swarmforge" "handoffs"))))

(defn glob-handoffs [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff")
                 (fs/glob dir "**/*.handoff"))
         (filter fs/regular-file?)
         distinct
         vec)
    []))

(defn handoff-task-id [path]
  (let [headers (:headers (parse-message path))]
    (or (not-empty (get headers "task_id"))
        (get headers "task"))))

(defn task-handoffs [root task-id & aliases]
  (->> (handoff-dirs root)
       (mapcat glob-handoffs)
       (filter #(contains? (set (remove str/blank? (cons task-id aliases)))
                           (handoff-task-id %)))
       vec))

(defn copy-into [dir path]
  (when (fs/regular-file? path)
    (fs/copy path (fs/path dir (fs/file-name path)) {:replace-existing true})))

(defn archive-rejected! [root task-id name]
  (let [dir (fs/path root ".swarmforge" "rejected-tasks" task-id)]
    (fs/create-dirs dir)
    (copy-into dir (fs/path root ".swarmforge" "board" (str name ".txt")))
    (copy-into dir (fs/path root ".swarmforge" "notify" (str "reject-" name)))
    (doseq [path (task-handoffs root task-id name)]
      (copy-into dir path))))

(defn drop-task-handoffs! [root task-id & aliases]
  (doseq [path (apply task-handoffs root task-id aliases)]
    (fs/delete-if-exists path)))

(defn audit-task-id [path]
  (try
    (get-in (edn/read-string (slurp (str path))) [:candidate :task-id])
    (catch Exception _ nil)))

(defn task-audits [root task-id & aliases]
  (let [wanted (set (remove str/blank? (cons task-id aliases)))
        dir (fs/path root ".swarmforge" "handoffs" "audit_pending")]
    (if (fs/directory? dir)
      (->> (fs/glob dir "**/*.edn")
           (filter #(contains? wanted (audit-task-id %)))
           vec)
      [])))

(defn drop-task-audits! [root task-id & aliases]
  (doseq [path (apply task-audits root task-id aliases)]
    (fs/delete-if-exists path)))

(defn reject-notify [root name]
  (fs/path root ".swarmforge" "notify" (str "reject-" name)))

(defn task-by-name [root name]
  (some #(when (= name (:name %)) %) (board-tasks root)))

(defn task-id-for-name [root name]
  (or (:id (task-by-name root name)) name))

(defn delete-task! [root name]
  (when (str/blank? name)
    (throw (ex-info "Missing task name" {:http-status 400})))
  (when-not (rejected-task? root name)
    (throw (ex-info (str "Not rejected: " name) {:http-status 400})))
  (let [task-id (task-id-for-name root name)]
    (archive-rejected! root task-id name)
    (drop-task-handoffs! root task-id name)
    (drop-task-audits! root task-id name)
    (drop-task-reviews! root task-id))
  (pack-board root "delete" "--name" name)
  (fs/delete-if-exists (reject-notify root name)))

(defn retry-task! [root name text]
  (throw (ex-info "Retry requires a pending approval id" {:http-status 400})))

(defn post-delete-task [root body]
  (let [{:keys [name id]} (json/parse-string (or body "{}") true)]
    (try
      (if (not-empty id)
        (delete-approval! root id)
        (delete-task! root name))
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn post-retry-task [root body]
  (let [{:keys [id comments]} (json/parse-string (or body "{}") true)]
    (try
      (when (str/blank? id)
        (throw (ex-info "Missing approval id" {:http-status 400})))
      (retry-approval! root id comments)
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn create-task! [root name text]
  (when (str/blank? name)
    (throw (ex-info "Missing task name" {:http-status 400})))
  (let [single-agent? (single-agent-project? root)
        task-id (new-task-id name)
        created
        (if single-agent?
          (pack-board root "route"
                      "--name" name
                      "--task-id" task-id
                      "--text" (or text ""))
          (pack-board root "create"
                      "--name" name
                      "--lane" (master-role root)
                      "--task-id" task-id
                      "--text" (or text "")))]
    (if single-agent?
      created
      (do
        created
        (queue-new-task-note! root task-id name (or text ""))
        :handoff))))

(defn post-tasks [root body]
  (let [{:keys [name text project]} (json/parse-string (or body "{}") true)
        dest (if (and (forge/forge? root) (not (str/blank? project)))
               (str (forge/project-dir root project))
               root)]
    (try
      (when (and (forge/forge? root) (str/blank? project))
        (throw (ex-info "Missing project" {:http-status 400})))
      (create-task! dest name text)
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn parse-chat-request-body [body]
  (try
    (let [value (json/parse-string (or body "{}") true)]
      (when-not (map? value)
        (chat-request-invalid! "Chat request body must be an object."))
      value)
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception _
      (chat-request-invalid! "Chat request body is invalid."))))

(defn same-chat-content? [entry text thread-id source proposal-id]
  (and (= (str/trimr (or (:body entry) ""))
          (str/trimr (or text "")))
       (= thread-id (:thread_id entry))
       (= source (:source entry))
       (= proposal-id (:proposal_id entry))))

(declare json-ok-data)

(defn post-chat [root body]
  (let [request (parse-chat-request-body body)
        text (or (:text request) "")
        metadata? (some #(contains? request %)
                        [:request_id :thread_id :source :proposal_id])
        request-id (when (contains? request :request_id)
                     (supplied-chat-id (:request_id request)
                                       "Request id"
                                       chat-request-id-re))
        thread-id (when (contains? request :thread_id)
                    (supplied-chat-id (:thread_id request)
                                      "Thread id"
                                      chat-thread-id-re))
        source (when (contains? request :source)
                 (supplied-chat-id (:source request)
                                   "Request source"
                                   chat-source-re))
        proposal-id (when (contains? request :proposal_id)
                      (supplied-chat-id (:proposal_id request)
                                        "Proposal id"
                                        chat-proposal-id-re))]
    (when (and metadata? (str/blank? text))
      (chat-request-invalid! "Chat text is required when request metadata is supplied."))
    (if (str/blank? text)
      (json-ok)
      (let [result
            (with-chat-request-lock
              root
              (fn []
                (let [id (or request-id (fresh-chat-id root))
                      existing (existing-chat-request root id)]
                  (if existing
                    (if (same-chat-content? (:entry existing) text thread-id source
                                            proposal-id)
                      {:id id}
                      (throw (ex-info "Request id was reused with different content."
                                      {:http-status 409})))
                    (let [options {:request_id id
                                   :thread_id thread-id
                                   :source source
                                   :proposal_id proposal-id}
                          id (write-chat-request! root text options)]
                      (try
                        (when-not (single-agent-project? root)
                          (if metadata?
                            (inject-master-strict! root (chat-wake id text))
                            (inject-master! root (chat-wake id text))))
                        {:id id}
                        (catch Exception error
                          (fs/delete-if-exists
                           (chat-request-file root "pending" id))
                          (throw error))))))))]
        (json-ok-data {:request_id (:id result)})))))

(defn clar-pending-file [root id]
  (fs/path (clar-pending-dir root) (str id ".request")))

(defn render-clarification [{:keys [id status role body response created_at]}]
  (str "id: " id "\n"
       "status: " status "\n"
       (when-not (str/blank? role) (str "role: " role "\n"))
       "created_at: " created_at "\n"
       (when-not (str/blank? response)
         (str "response: " (str/replace response #"\n" (constantly "\\n")) "\n"))
       "\n"
       (or body "")
       (when-not (str/ends-with? (or body "") "\n") "\n")))

(defn answer-clarification! [root id text]
  (let [src (clar-pending-file root id)]
    (when-not (fs/regular-file? src)
      (throw (ex-info (str "Unknown clarification: " id) {:http-status 404})))
    (let [entry (parse-clarification src)
          dest (fs/path (clar-done-dir root) (str id ".request"))
          role (:role entry)]
      (fs/create-dirs (fs/parent dest))
      (spit (str dest) (render-clarification (assoc entry
                                                   :status "done"
                                                   :response text)))
      (fs/delete-if-exists src)
      (inject-role! root role (clar-wake id role (:body entry) text)))))

(defn clarification-route [uri]
  (let [path (first (str/split (or uri "") #"\?"))]
    (when-let [[_ id] (re-matches #"/api/clarifications/([^/]+)/answer" path)]
      (java.net.URLDecoder/decode id "UTF-8"))))

(defn post-clarification [root uri body]
  (if-let [id (clarification-route uri)]
    (let [text (or (:text (json/parse-string (or body "{}") true)) "")]
      (answer-clarification! root id text)
      (json-ok))
    {:status 404 :body "Not found"}))

(defn pending-file [root id]
  (fs/path (pending-dir root) (str id ".handoff")))

(defn require-pending! [root id]
  (let [path (pending-file root id)]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "Unknown approval: " id) {:http-status 404})))
    path))

(defn with-approved [content]
  (if (re-find #"(?m)^approved: " content)
    content
    (str/replace-first content #"\n\n" "\napproved: true\n\n")))

(defn stage-command! [root & args]
  (let [result (apply sh
                      (concat ["bb" (str (fs/path script-dir "stage.bb"))]
                              args
                              ["--root" (str root)]))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str (:err result) "\n" (:out result)))
                      {:http-status 500})))
    (:out result)))

(defn approve! [root id]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task (get headers "task")
        task-id (or (not-empty (get headers "task_id")) task)]
    (if (= "stage-spec" (get headers "kind"))
      (do
        (stage-command! root "approve" id "--words" "approve")
        (record-resolution! root id task "approved")
        (drop-reviews! root id)
        (drop-task-reviews! root task-id)
        (write-chat-request!
         root
         (str "Spec proposal " id " was approved. Continue with the slice.\n\n"
              "Run stage list to see the next gate; do not skip clean, architect, "
              "harden, clean-final or verify.")))
      (let [dest (fs/path root ".swarmforge" "handoffs" "outbox" (fs/file-name src))]
        (fs/create-dirs (fs/parent dest))
        (spit (str dest) (with-approved (slurp (str src))))
        (fs/delete-if-exists src)
        (record-resolution! root id task "approved")
        (drop-reviews! root id)
        (drop-task-reviews! root task-id)))))

(defn save-review! [root id path comments]
  (when (str/blank? path)
    (throw (ex-info "Missing path" {:http-status 400})))
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        text (str/trim (or comments ""))]
    (write-reviews! root id (assoc (read-reviews root id) path text))
    (append-task-review! root task-id path text)))

(defn write-reject-notify! [root task]
  (when-not (str/blank? task)
    (let [path (fs/path root ".swarmforge" "notify" (str "reject-" task))]
      (fs/create-dirs (fs/parent path))
      (spit (str path) "rejected\n"))))

(defn git! [root & args]
  (let [result (apply sh (concat ["git" "-C" (str root)] args))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str (:err result) "\n" (:out result)))
                      {:http-status 500})))
    (str/trim (:out result))))

(defn git-ok? [root & args]
  (zero? (:exit (apply sh (concat ["git" "-C" (str root)] args)))))

(defn git-repo? [root]
  (git-ok? root "rev-parse" "--is-inside-work-tree"))

(defn worktree-for [root role]
  (if-let [row (role-row root role)]
    (or (not-empty (nth row 2 nil)) (str root))
    (str root)))

(defn rejected-ref [task-id n]
  (str "rejected/" task-id "/" n))

(defn rejected-latest [task-id]
  (str "rejected/" task-id "/latest"))

(defn git-ref-exists? [root ref]
  (git-ok? root "show-ref" "--verify" "--quiet" (str "refs/heads/" ref)))

(defn next-rejected-n [root task-id]
  (loop [n 1]
    (if (git-ref-exists? root (rejected-ref task-id n))
      (recur (inc n))
      n)))

(defn snapshot-rejected! [root task-id commit n]
  (when (and (git-repo? root) (not (str/blank? task-id)) (not (str/blank? commit)))
    (git! root "branch" "-f" (rejected-ref task-id n) commit)
    (git! root "branch" "-f" (rejected-latest task-id) commit)))

(defn restore-commit! [worktree commit]
  (when (and (git-repo? worktree) (not (str/blank? commit))
             (git-ok? worktree "rev-parse" "--verify" commit))
    (let [head (git! worktree "rev-parse" "HEAD")
          want (git! worktree "rev-parse" commit)]
      (when (not= head want)
        (git! worktree "reset" "--hard" commit)))))

(defn commit-parent [root commit]
  (when (and (git-repo? root) (not (str/blank? commit)))
    (not-empty (git! root "rev-parse" "--short=10" (str commit "^")))))

(defn rollback-target [root headers]
  (or (not-empty (get headers "task_base_commit"))
      (when-let [commit (not-empty (get headers "commit"))]
        (commit-parent root commit))))

(defn rollback-to-base! [root headers]
  (when-let [target (rollback-target root headers)]
    (when (git-repo? root)
      (git! root "reset" "--hard" target))))

(defn increment-audit-count! [root task-id]
  (when-not (str/blank? task-id)
    (pack-board root "increment-audit" "--task-id" task-id)))

(defn review-findings [reviews]
  (->> reviews
       (filter (fn [[_ text]] (not (str/blank? (str/trim (str text))))))
       (map (fn [[path text]] (str path ":\n" (str/trim (str text)))))
       (str/join "\n\n")))

(defn retry-message [task comments reviews]
  (let [extra (str/trim (or comments ""))
        findings (review-findings reviews)]
    (str "Retry audit for " task
         ". Re-read tasks/" task ".md as operator intent."
         " Read the remedial comments as audit findings."
         (when-not (str/blank? extra) (str "\n\n" extra))
         (when-not (str/blank? findings) (str "\n\n" findings)))))

(defn task-inbox-files [worktree state task-id task]
  (let [wanted (set (remove str/blank? [task-id task]))]
    (->> (glob-handoffs (fs/path worktree ".swarmforge" "handoffs" "inbox" state))
         (filter #(contains? wanted (handoff-task-id %)))
         vec)))

(defn write-retry-in-process! [worktree headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (or (get headers "task") task-id)
        base (not-empty (get headers "task_base_commit"))
        from (or (get headers "from") "")
        dir (fs/path worktree ".swarmforge" "handoffs" "inbox" "in_process")
        file (fs/path dir (str "50_retry_" (str/replace (or task-id "task") #"[^A-Za-z0-9]+" "_") ".handoff"))]
    (when-not (str/blank? task-id)
      (fs/create-dirs dir)
      (spit (str file)
            (str "from: (Retry)\n"
                 "to: " from "\n"
                 "priority: 50\n"
                 "type: note\n"
                 "task_id: " task-id "\n"
                 "task: " task "\n"
                 (when base (str "task_base_commit: " base "\n"))
                 "\n"
                 "Retry audit.\n")))))

(defn restore-task-base! [root headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (get headers "task")
        wt (worktree-for root (get headers "from"))
        in-proc (task-inbox-files wt "in_process" task-id task)
        done (task-inbox-files wt "completed" task-id task)]
    (cond
      (seq in-proc) nil
      (seq done)
      (let [src (first done)
            dest-dir (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")]
        (fs/create-dirs dest-dir)
        (fs/move src (fs/path dest-dir (fs/file-name src)) {:replace-existing true}))
      :else (write-retry-in-process! wt headers))))

(defn approval-doc-paths [headers reviews]
  (vec (distinct (concat (comma-list (get headers "artifacts"))
                         (keys reviews)))))

(defn retry-approval! [root id comments]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task (get headers "task")
        task-id (or (not-empty (get headers "task_id")) task)
        stage-spec? (= "stage-spec" (get headers "kind"))
        commit (not-empty (get headers "commit"))
        n (if (git-repo? root) (next-rejected-n root task-id) 1)
        wt (worktree-for root (get headers "from"))
        reviews (read-reviews root id)]
    (if stage-spec?
      (do
        (stage-command! root "reject" id "--words"
                        (or (not-empty comments) "sent back from the dashboard"))
        (record-resolution! root id task "sent_back")
        (drop-reviews! root id)
        (write-chat-request!
         root
         (str "Spec proposal " id " was sent back."
              (when-not (str/blank? comments) (str "\n\n" comments))
              "\n\nRevise the task note or Gherkin, then propose the bundle again.")))
      (do
        (doseq [path (approval-doc-paths headers reviews)]
          (append-task-review! root task-id path comments))
        (when commit
          (snapshot-rejected! root task-id commit n)
          (restore-commit! wt commit))
        (fs/delete-if-exists src)
        (record-resolution! root id task "sent_back")
        (drop-reviews! root id)
        (drop-task-audits! root task-id task)
        (restore-task-base! root headers)
        (increment-audit-count! root task-id)
        (when-not (str/blank? task)
          (inject-master! root (retry-message task comments reviews)))))))

(defn delete-approval! [root id]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task (get headers "task")
        task-id (or (not-empty (get headers "task_id")) task)
        commit (not-empty (get headers "commit"))
        n (if (git-repo? root) (next-rejected-n root task-id) 1)
        wt (worktree-for root (get headers "from"))]
    (if (= "stage-spec" (get headers "kind"))
      (do
        (stage-command! root "reject" id "--words" "withdrawn from the dashboard")
        (record-resolution! root id task "sent_back")
        (drop-reviews! root id)
        (drop-task-reviews! root task-id))
      (do
        (when commit
          (snapshot-rejected! root task-id commit n))
        (rollback-to-base! wt headers)
        (archive-rejected! root task-id task)
        (drop-task-handoffs! root task-id task)
        (drop-task-audits! root task-id task)
        (pack-board root "delete" "--name" task)
        (fs/delete-if-exists (reject-notify root task))
        (drop-reviews! root id)
        (drop-task-reviews! root task-id)))))

(defn approval-route [uri]
  (let [path (first (str/split (or uri "") #"\?"))]
    (when-let [[_ id action] (re-matches #"/api/approvals/([^/]+)/(approve|reject|comments)" path)]
      {:id (java.net.URLDecoder/decode id "UTF-8")
       :action action})))

(defn post-approval [root uri body]
  (if-let [{:keys [id action]} (approval-route uri)]
    (case action
      "approve" (do (approve! root id)
                    (json-ok))
      "comments" (let [{:keys [path comments]} (json/parse-string (or body "{}") true)]
                   (save-review! root id path comments)
                   (json-ok))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Reject opens the dialog; use Retry, Delete, or Accept."})})
    {:status 404 :body "Not found"}))

(defn query-value [uri key]
  (when-let [q (second (str/split (or uri "") #"\?" 2))]
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= k key)
                (java.net.URLDecoder/decode (or v "") "UTF-8"))))
          (str/split q #"&"))))

(defn existing-path [root rel]
  (let [path (fs/path root rel)]
    (when (fs/exists? path)
      (fs/canonicalize path))))

(defn under-dir? [file dir]
  (and file dir (fs/starts-with? file dir)))

(defn allowed-doc? [root rel]
  (when-not (str/blank? rel)
    (let [file (existing-path root rel)]
      (and (some? file)
           (fs/regular-file? file)
           (or (under-dir? file (existing-path root "features"))
               (under-dir? file (existing-path root "qa"))
               (under-dir? file (existing-path root "tasks")))))))

(defn get-mission [root]
  (let [file (fs/path root "mission.md")]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (if (fs/regular-file? file) (slurp (str file)) "")}))

(defn get-doc [root uri]
  (let [rel (query-value uri "path")]
    (if (allowed-doc? root rel)
      {:status 200
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (slurp (str (existing-path root rel)))}
      {:status 404 :body "Not found"})))

(defn parse-unified-diff [text]
  (->> (str/split-lines (or text ""))
       (drop-while #(not (str/starts-with? % "@@")))
       rest
       (keep (fn [line]
               (cond
                 (str/starts-with? line "+") {:type "add" :text (subs line 1)}
                 (str/starts-with? line "-") {:type "del" :text (subs line 1)}
                 (str/starts-with? line "\\") nil
                 (str/starts-with? line " ") {:type "same" :text (subs line 1)}
                 (str/blank? line) {:type "same" :text ""}
                 :else {:type "same" :text line})))
       vec))

(defn file-diff-lines [root prior commit rel]
  (let [result (apply sh ["git" "-C" (str root) "diff" "--no-color" "-U999999"
                          prior commit "--" rel])]
    (cond
      (not (zero? (:exit result))) nil
      (str/blank? (:out result))
      (mapv (fn [line] {:type "same" :text line})
            (str/split-lines (slurp (str (existing-path root rel)))))
      :else (parse-unified-diff (:out result)))))

(defn pending-headers [root id]
  (let [path (when-not (str/blank? id) (pending-file root id))]
    (when (and path (fs/regular-file? path))
      (:headers (parse-message path)))))

(defn get-api-doc [root uri]
  (let [rel (query-value uri "path")
        id (query-value uri "id")]
    (if-not (allowed-doc? root rel)
      {:status 404 :body "Not found"}
      (let [text (slurp (str (existing-path root rel)))
            headers (pending-headers root id)
            task-id (or (not-empty (get headers "task_id")) (get headers "task"))
            commit (not-empty (get headers "commit"))
            prior (when (and task-id (git-ref-exists? root (rejected-latest task-id)))
                    (rejected-latest task-id))
            lines (when (and prior commit)
                    (file-diff-lines root prior commit rel))
            has-diff (some? lines)
            history (mapv (fn [item]
                            {:at (or (get item "at") (:at item))
                             :text (or (get item "text") (:text item))})
                          (path-review-history root task-id rel))]
        {:status 200
         :headers {"Content-Type" "application/json; charset=utf-8"}
         :body (json/generate-string {:path rel
                                      :text text
                                      :has_diff has-diff
                                      :lines (or lines [])
                                      :history history})}))))

(defn task-query-name [uri]
  (when (str/starts-with? (or uri "") "/task")
    (query-value uri "name")))

(defn get-task [root uri]
  (let [name (task-query-name uri)
        file (when (and (not (str/blank? name))
                        (not (str/includes? name "/"))
                        (not (str/includes? name "..")))
               (fs/path root ".swarmforge" "board" (str name ".txt")))]
    (if (and file (fs/regular-file? file))
      {:status 200
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str name "\n\n" (slurp (str file)))}
      {:status 404 :body "Not found"})))

(defn html-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn role-row [root role]
  (some #(when (= role (first %)) %) (role-rows root)))

(defn session-for-role [root role]
  (when-let [row (role-row root role)]
    (session-name row)))

(defn worktree-for-role [root role]
  (when-let [row (role-row root role)]
    (nth row 2 nil)))

(defn tmux-capture [socket target]
  (state-metric! :tmux-pane-reads)
  (try
    (let [result (sh "tmux" "-S" socket "capture-pane" "-p" "-t" target
                     "-S" (str "-" pane-capture-lines))]
      (when (zero? (:exit result))
        (:out result)))
    (catch Exception _)))

(defn capture-pane [root role]
  (when-let [row (role-row root role)]
    (let [socket (tmux-socket root)]
      (when socket
        (or (tmux-capture socket (pane-target row))
            (tmux-capture socket (session-name row)))))))

(defn live-pane-text [root role]
  (or *pane-text*
      (capture-pane root role)
      (recorded-pane root role)))

(defn state-pane-text [root role row]
  (state-cached
   :pane-text-builds [::pane-text (str root) (str role)]
   (fn []
     (or *pane-text*
         (if (state-session-alive? root role row)
           (or (capture-pane root role)
               (recorded-pane root role))
           (recorded-pane root role))))))

(defn pane-files [root role]
  (let [dir (fs/path root ".swarmforge" "sessions" role)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (map #(fs/path % "pane.txt"))
           (filter fs/regular-file?)
           vec)
      [])))

(defn in-process-task [root role]
  (when-let [worktree (worktree-for-role root role)]
    (some #(get-in (parse-message %) [:headers "task"])
          (in-process-files (in-process-dir worktree)))))

(defn pane-for-task [files task]
  (when task
    (some #(when (= task (fs/file-name (fs/parent %))) %) files)))

(defn recorded-pane [root role]
  (let [direct (fs/path root ".swarmforge" "sessions" role "pane.txt")]
    (if (fs/regular-file? direct)
      (slurp (str direct))
      (let [files (pane-files root role)
            chosen (or (pane-for-task files (in-process-task root role))
                       (last (sort-by str files)))]
        (when chosen
          (slurp (str chosen)))))))

(defn pane-content [root role]
  (or (not-empty (capture-pane root role))
      (not-empty (recorded-pane root role))
      (str "(no pane capture for " role ")\n")))

(defn project-query [project]
  (when (not-empty project)
    (str "?project=" (java.net.URLEncoder/encode (str project) "UTF-8"))))

(defn pane-page [role snapshot & [project]]
  (let [role-html (html-escape role)
        pane-url (str "/api/agents/" role-html "/pane" (or (project-query project) ""))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<title>Agent " role-html "</title>"
         "<style>html,body{height:100%;margin:0;overflow:hidden;background:#111;color:#f4f4f4;"
         "font-family:ui-monospace,Menlo,monospace}"
         "body{display:flex;flex-direction:column}"
         "header{height:42px;box-sizing:border-box;padding:10px 12px;border-bottom:1px solid #333;flex:0 0 auto}"
         "h1{font:inherit;margin:0;font-size:14px}"
         "#pane{flex:1 1 auto;margin:0;padding:12px;white-space:pre-wrap;overflow:auto;"
         "min-height:0;height:calc(100vh - 42px);max-height:calc(100vh - 42px)}</style></head>"
         "<body><header><h1>" role-html "</h1></header>"
         "<pre id=\"pane\">" (html-escape snapshot) "</pre>"
         "<script>(function(){"
         "const pane=document.getElementById('pane');"
         "let stickBottom=true;"
         "let firstPaint=true;"
         "function nearBottom(){"
         "return (pane.scrollHeight-pane.scrollTop-pane.clientHeight)<=64;}"
         "function toEnd(){pane.scrollTop=pane.scrollHeight;stickBottom=true;}"
         "function toEndSoon(){"
         "toEnd();requestAnimationFrame(toEnd);"
         "setTimeout(toEnd,0);setTimeout(toEnd,50);setTimeout(toEnd,200);}"
         "pane.addEventListener('scroll',function(){stickBottom=nearBottom();},{passive:true});"
         "async function refresh(){"
         "const r=await fetch('" pane-url "',{cache:'no-store'});"
         "const text=await r.text();"
         "const changed=text!==pane.textContent;"
         "if(changed){pane.textContent=text;}"
         "if(firstPaint||stickBottom){toEndSoon();firstPaint=false;}"
         "}"
         "refresh();setInterval(refresh,1000);"
         "window.addEventListener('load',toEndSoon);"
         "window.addEventListener('pageshow',toEndSoon);"
         "})();</script></body></html>")))

(defn agent-role [uri]
  (when-let [[_ role] (re-matches #"/agent/([^/]+)"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn agent-pane-role [uri]
  (when-let [[_ role] (re-matches #"/api/agents/([^/]+)/pane"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn get-agent [root uri]
  (if-let [role (agent-role uri)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (pane-page role (pane-content root role) (query-value uri "project"))}
    {:status 404 :body "Not found"}))

(defn get-agent-pane [root uri]
  (if-let [role (agent-pane-role uri)]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (pane-content root role)}
    {:status 404 :body "Not found"}))

(defn request-project-root [forge uri]
  (if-not (forge/forge? forge)
    forge
    (if-let [name (not-empty (query-value uri "project"))]
      (str (forge/project-dir forge name))
      forge)))

(defn body-map [body]
  (try
    (json/parse-string (or body "{}") true)
    (catch Exception _ {})))

(defn json-ok-data [m]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string (merge {:ok true} m))})

(defn find-approval-root [forge id]
  (or (some (fn [name]
              (let [proot (str (forge/project-dir forge name))]
                (when (fs/regular-file? (pending-file proot id))
                  proot)))
            (forge/read-open-projects forge))
      (throw (ex-info (str "Unknown approval: " id) {:http-status 404}))))

(defn find-clar-root [forge id]
  (or (some (fn [name]
              (let [proot (str (forge/project-dir forge name))]
                (when (fs/regular-file? (clar-pending-file proot id))
                  proot)))
            (forge/read-open-projects forge))
      (throw (ex-info (str "Unknown clarification: " id) {:http-status 404}))))

(defn handle-get [root uri]
  (cond
    (= "/" uri)
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (dashboard-page)}

    (= "/api/health" (first (str/split (or uri "") #"\?")))
    (health-response)

    (= "/api/state" (first (str/split (or uri "") #"\?")))
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string (api-state-with-chat-page root uri))}

    (agent-pane-role uri)
    (get-agent-pane (request-project-root root uri) uri)

    (agent-role uri)
    (get-agent (request-project-root root uri) uri)

    (task-query-name uri)
    (get-task (request-project-root root uri) uri)

    (str/starts-with? (first (str/split (or uri "") #"\?")) "/api/doc")
    (get-api-doc (request-project-root root uri) uri)

    (str/starts-with? (or uri "") "/doc")
    (get-doc (request-project-root root uri) uri)

    (= "/api/mission" (first (str/split (or uri "") #"\?")))
    (get-mission (request-project-root root uri))

    :else {:status 404 :body "Not found"}))

(defn confirm-teardown? [body]
  (let [text (str/trim (or body ""))]
    (or (= "TEARDOWN" text)
        (try
          (= "TEARDOWN" (:confirm (json/parse-string text true)))
          (catch Exception _ false)))))

(defn current-pid []
  (str (.pid (java.lang.ProcessHandle/current))))

(defn pack-web-pid-file [root]
  (fs/path root ".swarmforge" "pack_web.pid"))

(defn write-pack-web-pid! [root]
  (let [file (pack-web-pid-file root)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str (current-pid) "\n"))))

(defn stop-pack-web! [root]
  (let [file (pack-web-pid-file root)
        pid (when (fs/exists? file)
              (not-empty (str/trim (slurp (str file)))))]
    (when (and pid (not= pid (current-pid)))
      (sh "kill" "-TERM" pid))
    (fs/delete-if-exists file)))

(defn list-tmux-sessions [socket]
  (if (str/blank? socket)
    []
    (let [result (sh "tmux" "-S" socket "list-sessions" "-F" "#{session_name}")]
      (if (zero? (:exit result))
        (->> (str/split-lines (:out result))
             (remove str/blank?)
             vec)
        []))))

(defn kill-session! [socket session]
  (sh "tmux" "-S" socket "kill-session" "-t" (str "=" session))
  (sh "tmux" "-S" socket "kill-session" "-t" session))

(defn kill-all-sessions-on-socket! [socket]
  (when-not (str/blank? socket)
    (doseq [session (list-tmux-sessions socket)]
      (kill-session! socket session))
    (sh "tmux" "-S" socket "kill-server")))

(defn stop-handoffd! [root]
  (sh "bb" (str (fs/path script-dir "stop_handoff_daemon.bb")) (str root)))

(defn swarm-cleanup! [root socket]
  (let [script (str (fs/path script-dir "swarm-cleanup.sh"))
        ids (str (fs/path root ".swarmforge" "window-ids"))]
    (apply sh (into [script (or socket "none") ids]
                    (list-tmux-sessions socket)))))

(defn close-swarm-bin []
  (let [path (fs/path (fs/parent (fs/parent script-dir)) "close-swarm")]
    (when (fs/exists? path)
      (str path))))

(defn close-swarm! [root]
  (if-let [bin (close-swarm-bin)]
    (sh bin (str root))
    (swarm-cleanup! root (tmux-socket root))))

(defn run-teardown! [root]
  (when (forge/forge? root)
    (forge/close-all-projects! root))
  (close-swarm! root)
  (stop-handoffd! root)
  (kill-all-sessions-on-socket! (tmux-socket root))
  (stop-pack-web! root)
  true)

(defn log-teardown-failure! [root e]
  (binding [*out* *err*]
    (println (str "teardown failed root=" root
                  " error=" (.getMessage e)))
    (flush)))

(defn schedule-teardown! [root]
  (if *sync-teardown?*
    (try
      (run-teardown! root)
      (catch Exception e
        (log-teardown-failure! root e)
        (exit! 1 nil)))
    (future
      (Thread/sleep teardown-delay-ms)
      (try
        (run-teardown! root)
        (System/exit 0)
        (catch Exception e
          (log-teardown-failure! root e)
          (System/exit 1)))))
  true)

(defn teardown-response [root body]
  (if (confirm-teardown? body)
    (do
      (schedule-teardown! root)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:ok true :status "teardown_started"})})
    {:status 400
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Teardown requires confirm=TEARDOWN (JSON {\"confirm\":\"TEARDOWN\"}).\n"}))

(defn post-new-project [root body]
  (let [parsed (body-map body)
        created (forge/instantiate! root parsed)]
    (forge/open-project! root (:name created))
    (json-ok-data created)))

(defn post-open-project [root body]
  (json-ok-data (forge/open-project! root (:name (body-map body)))))

(defn post-close-project [root body]
  (json-ok-data (forge/close-project! root (:name (body-map body)))))

(defn scoped-approval-root [root uri body]
  (if-not (forge/forge? root)
    root
    (let [m (body-map body)
          id (or (:id (approval-route uri)) (:id m))
          project (:project m)]
      (cond
        (not (str/blank? project)) (str (forge/project-dir root project))
        (not (str/blank? id)) (find-approval-root root id)
        :else (throw (ex-info "Missing project" {:http-status 400}))))))

(defn scoped-clar-root [root uri]
  (if-not (forge/forge? root)
    root
    (find-clar-root root (clarification-route uri))))

(defn handle-post [root uri body]
  (cond
    (= "/api/projects" uri) (post-new-project root body)
    (= "/api/projects/open" uri) (post-open-project root body)
    (= "/api/projects/close" uri) (post-close-project root body)
    (= "/api/tasks" uri) (post-tasks root body)
    (= "/api/tasks/delete" uri)
    (post-delete-task (scoped-approval-root root uri body) body)
    (= "/api/tasks/retry" uri)
    (post-retry-task (scoped-approval-root root uri body) body)
    (= "/api/chat" uri) (post-chat root body)
    (= "/api/teardown" uri) (teardown-response root body)
    (str/starts-with? (or uri "") "/api/approvals/")
    (post-approval (scoped-approval-root root uri body) uri body)
    (str/starts-with? (or uri "") "/api/clarifications/")
    (post-clarification (scoped-clar-root root uri) uri body)
    :else {:status 404 :body "Not found"}))

(defn handle-request [root {:keys [method uri body]}]
  (try
    (case method
      "GET" (handle-get root uri)
      "POST" (handle-post root uri body)
      {:status 404 :body "Not found"})
    (catch Exception e
      (http-error (or (:http-status (ex-data e)) 500) (.getMessage e)))))

(defn test-state! [root]
  (println (:body (handle-request (require-root! root) {:method "GET" :uri "/api/state"}))))

(declare test-http!)

(defn test-state-query! [root query]
  (test-http! (handle-request
               (require-root! root)
               {:method "GET"
                :uri (str "/api/state?" (or query ""))})))

(defn test-html! []
  (print (:body (handle-request nil {:method "GET" :uri "/"})))
  (flush))

(defn test-post-task! [root name text]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks"
                              :body (json/generate-string {:name name :text (or text "")})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-delete-task! [root name]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/delete"
                              :body (json/generate-string {:name name})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-delete-approval! [root id]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/delete"
                              :body (json/generate-string {:id id})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-retry-task! [root id comments]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/retry"
                              :body (json/generate-string {:id id :comments (or comments "")})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(declare test-http!)

(defn test-post-chat! [root text]
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri "/api/chat"
                               :body (json/generate-string {:text (or text "")})})))

(defn test-inject-payload! [name text]
  (println (if (and name text)
             (task-payload name text)
             (task-payload))))

(defn test-inject-argv! [root file text]
  (when (str/blank? file)
    (exit! 1 "Missing argv file"))
  (binding [*tmux-stub* file]
    (inject-master! (require-root! root) text)))

(defn test-http! [resp]
  (print (:body resp))
  (flush)
  (when-not (= 200 (:status resp))
    (binding [*out* *err*]
      (println (:body resp)))
    (System/exit 1)))

(defn test-post-chat-json! [root body-json]
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri "/api/chat"
                               :body body-json})))

(defn test-post-chat-file! [root body-file]
  (when-not (fs/regular-file? body-file)
    (exit! 1 "Missing chat request body file"))
  (test-post-chat-json! root (slurp (str body-file))))

(defn test-approval! [root id action]
  (when (str/blank? id)
    (exit! 1 "Missing approval id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/approvals/" id "/" action)})))

(defn test-save-comments! [root id path comments]
  (when (str/blank? id)
    (exit! 1 "Missing approval id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/approvals/" id "/comments")
                               :body (json/generate-string {:path path :comments (or comments "")})})))

(defn test-pane! [root role & [project]]
  (when (str/blank? role)
    (exit! 1 "Missing role"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/agents/" role "/pane"
                                          (or (project-query project) ""))})))
  (flush))

(defn test-agent-page! [role & [project]]
  (print (pane-page (or role "specifier") "" project))
  (flush))

(defn print-heat-pair! [root before-text after-text]
  (require-root! root)
  (reset! pane-heat {})
  (binding [*pane-text* before-text]
    (let [before (:activity (first (work-in-flight root)))]
      (binding [*pane-text* after-text]
        (let [after (:activity (first (work-in-flight root)))]
          (println (json/generate-string {:before before :after after})))))))

(defn test-heat! [root]
  (print-heat-pair! root "alpha\nline two\n" "beta\nline two\nchanged output\n"))

(defn print-heat-isolation! [root-a root-b]
  (reset! pane-heat {})
  (binding [*pane-text* "stable-a\n"]
    (work-in-flight root-a))
  (binding [*pane-text* "stable-b\n"]
    (work-in-flight root-b))
  (let [changed (binding [*pane-text* "changed-a\nmore\n"]
                  (:activity (first (work-in-flight root-a))))
        stable (binding [*pane-text* "stable-b\n"]
                 (:activity (first (work-in-flight root-b))))]
    (println (json/generate-string {:changed changed :stable stable}))))

(defn test-heat-isolation! [root-a root-b]
  (require-root! root-a)
  (require-root! root-b)
  (print-heat-isolation! root-a root-b))

(defn test-heat-codex! [root]
  (print-heat-pair! root
                    "I'll load the SwarmForge instructions.\n\nesc to interrupt · 3s\n"
                    "I'll load the SwarmForge instructions.\n\nesc to interrupt · 4s\n"))

(defn test-heat-reorder! [root]
  (let [lines (mapv #(str "line-" %) (range 20))]
    (print-heat-pair! root
                      (str (str/join "\n" lines) "\n")
                      (str (str/join "\n" (reverse lines)) "\n"))))

(defn test-heat-head! [root]
  (let [tail (mapv #(str "tail-" %) (range 20))
        before (str (str/join "\n" (concat ["a" "b" "c" "d" "e"] tail)) "\n")
        after (str (str/join "\n" (concat ["v" "w" "x" "y" "z"] tail)) "\n")]
    (print-heat-pair! root before after)))

(defn test-heat-mail! [root]
  (print-heat-pair! root
                    (str "stable\n"
                         "› You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "work line A\n"
                         "• Working (1s • esc to interrupt)\n"
                         "›\n")
                    (str "stable\n"
                         "› You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "work line B\n"
                         "• Working (2s • esc to interrupt)\n"
                         "›\n")))

(defn test-heat-grok! [root]
  (print-heat-pair! root
                    (str "I'll write the cave stories.\n"
                         "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "always-approve  shift+tab\n"
                         "Waiting for response 1s\n"
                         "enter:send  Esc:cancel\n")
                    (str "I'll write the cave stories.\n"
                         "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "always-approve  shift+tab\n"
                         "Waiting for response 2s\n"
                         "enter:send  Esc:cancel\n")))

(defn test-heat-collapse! [root]
  (print-heat-pair! root
                    (str "… +28 lines (ctrl + t to view transcript)\n"
                         "• Working (1s • esc to interrupt)\n")
                    (str "… +29 lines (ctrl + t to view transcript)\n"
                         "• Working (2s • esc to interrupt)\n")))

(defn test-status-pane! [root text]
  (require-root! root)
  (binding [*pane-text* (or text "")]
    (println (:body (handle-request root {:method "GET" :uri "/api/state"})))))

(defn test-status-persist! [root first-text second-text]
  (require-root! root)
  (reset! pane-status {})
  (reset! pane-status-lines {})
  (binding [*pane-text* (or first-text "")]
    (let [first-status (:status (first (:tasks (json/parse-string
                                                (:body (handle-request root {:method "GET" :uri "/api/state"}))
                                                true))))]
      (binding [*pane-text* (or second-text "")]
        (let [second-status (:status (first (:tasks (json/parse-string
                                                     (:body (handle-request root {:method "GET" :uri "/api/state"}))
                                                     true))))]
          (println (json/generate-string {:first first-status :second second-status})))))))

(defn activity-for-test [root context text]
  (binding [*lieutenant-activity-context* context
            *pane-text* (or text "")]
    (:lieutenant_activity (forge-dashboard-state root))))

(defn test-lieutenant-activity! [root alive command text]
  (require-root! root)
  (reset! pane-heat {})
  (reset! lieutenant-activity-history {})
  (let [context {:alive? (= "alive" alive)
                 :command (or command "")}
        activity (activity-for-test root context text)]
    (println (json/generate-string
              {:activity activity
               :fingerprint (activity-fingerprint activity)}))))

(defn test-lieutenant-activity-timeline! [root]
  (require-root! root)
  (reset! pane-heat {})
  (reset! lieutenant-activity-history {})
  (let [first (activity-for-test
               root
               {:alive? true :command "claude"}
               "I'm reviewing the board.\nesc to interrupt · 2s\n")
        same (activity-for-test
              root
              {:alive? true :command "claude"}
              "I'm reviewing the board.\nesc to interrupt · 2s\n")
        changed (activity-for-test
                 root
                 {:alive? true :command "claude"}
                 "I'm reviewing two cards.\nesc to interrupt · 3s\n")
        idle (activity-for-test
              root
              {:alive? true :command "claude"}
              "I'm ready for your next instruction.\n? for shortcuts\n")
        stale (do
                (swap! pane-status-lines
                       assoc [(str root) "lieutenant"] ["I'm reviewing old work."])
                (activity-for-test root {:alive? false} ""))]
    (println (json/generate-string
              {:first first :same same :changed changed :idle idle :stale stale}))))

(defn elapsed-millis [started]
  (long (/ (- (System/nanoTime) started) 1000000.0)))

(defn percentile [values fraction]
  (let [values (vec (sort values))
        index (min (dec (count values))
                   (long (Math/ceil (* fraction (count values)))))]
    (nth values index)))

(defn test-health! []
  (test-http! (handle-request nil {:method "GET" :uri "/api/health"})))

(defn test-health-metrics! []
  (let [metrics (atom {})
        started (System/nanoTime)]
    (binding [*state-metrics* metrics]
      (let [response (handle-request nil {:method "GET" :uri "/api/health"})]
        (println
         (json/generate-string
          {:milliseconds (elapsed-millis started)
           :bytes (count (:body response))
           :body (json/parse-string (:body response) true)
           :metrics @metrics}))))))

(defn test-health-during-slow-state! [root]
  (require-root! root)
  (let [metrics (atom {})]
    (with-redefs [api-state (fn [_]
                              (Thread/sleep 650)
                              {:slow_state true})]
      (binding [*state-metrics* metrics]
        (let [state-job (future
                          (handle-request root {:method "GET" :uri "/api/state"}))
              _ (Thread/sleep 50)
              started (System/nanoTime)
              health (handle-request nil {:method "GET" :uri "/api/health"})
              health-ms (elapsed-millis started)
              state-pending? (not (future-done? state-job))
              state-response @state-job]
          (println
           (json/generate-string
            {:health_ms health-ms
             :health_body (json/parse-string (:body health) true)
             :state_pending_at_health state-pending?
             :state_body (json/parse-string (:body state-response) true)
             :metrics @metrics})))))))

(defn test-dashboard-benchmark! [root iterations]
  (require-root! root)
  (let [iterations (max 1 (or (parse-long (str iterations)) 7))
        metrics (atom {})
        state-ms (atom [])
        state-bytes (atom [])
        health-ms (atom [])]
    (binding [*state-metrics* metrics]
      (dotimes [_ iterations]
        (reset! metrics {})
        (let [started (System/nanoTime)
              response (handle-request root {:method "GET" :uri "/api/state"})]
          (json/parse-string (:body response) true)
          (swap! state-ms conj (elapsed-millis started))
          (swap! state-bytes conj (count (:body response))))
        (let [started (System/nanoTime)
              response (handle-request nil {:method "GET" :uri "/api/health"})]
          (json/parse-string (:body response) true)
          (swap! health-ms conj (elapsed-millis started)))))
    (println
     (json/generate-string
      {:iterations iterations
       :state_ms @state-ms
       :state_p95_ms (percentile @state-ms 0.95)
       :state_max_ms (apply max @state-ms)
       :state_bytes @state-bytes
       :health_ms @health-ms
       :health_p95_ms (percentile @health-ms 0.95)
       :metrics @metrics}))))

(defn test-answer-clarification! [root id text]
  (when (str/blank? id)
    (exit! 1 "Missing clarification id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/clarifications/" id "/answer")
                               :body (json/generate-string {:text (or text "")})})))

(defn test-task! [root name]
  (when (str/blank? name)
    (exit! 1 "Missing task name"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/task?name=" name)})))
  (flush))

(defn test-mission! [root & [project]]
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/mission"
                                           (when-not (str/blank? project)
                                             (str "?project=" project)))})))
  (flush))

(defn test-doc! [root path]
  (when (str/blank? path)
    (exit! 1 "Missing path"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/doc?path=" path)})))
  (flush))

(defn test-api-doc! [root path id]
  (when (str/blank? path)
    (exit! 1 "Missing path"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/doc?path=" path
                                           (when-not (str/blank? id)
                                             (str "&id=" id)))})))
  (flush))

(defn test-project-http! [resp]
  (print (:body resp))
  (flush)
  (when-not (= 200 (:status resp))
    (binding [*out* *err*]
      (println (:body resp)))
    (System/exit 1)))

(defn test-new-project! [root name pack mission]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects"
                    :body (json/generate-string {:name name
                                                 :pack pack
                                                 :mission (or mission "")})})))

(defn test-open-project! [root name]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects/open"
                    :body (json/generate-string {:name name})})))

(defn test-close-project! [root name]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects/close"
                    :body (json/generate-string {:name name})})))

(defn test-inferred-name! [input github]
  (println (forge/inferred-name input (= "github" github))))

(defn test-teardown! [root confirm]
  (binding [*sync-teardown?* true]
    (let [resp (handle-request (require-root! root)
                               {:method "POST"
                                :uri "/api/teardown"
                                :body (when confirm
                                        (json/generate-string {:confirm confirm}))})]
      (when-not (= 200 (:status resp))
        (exit! 2 (:body resp)))
      (print (:body resp))
      (flush))))

(defn request-body [req]
  (when-let [body (:body req)]
    (if (string? body) body (slurp body))))

(defn request-uri [req]
  (let [uri (:uri req)
        qs (:query-string req)]
    (if (str/blank? qs) uri (str uri "?" qs))))

(defn http-handler [root]
  (fn [req]
    (handle-request root {:method (str/upper-case (name (:request-method req)))
                          :uri (request-uri req)
                          :body (request-body req)})))

(defn write-dashboard-url! [root url]
  (let [file (fs/path root ".swarmforge" "dashboard-url")]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str url "\n"))))

(defn parse-port [port-str]
  (if (str/blank? port-str) 0 (Long/parseLong port-str)))

(defn serve! [root port-str]
  (let [root (require-root! root)
        server (http/run-server (http-handler root)
                                {:ip "127.0.0.1"
                                 :port (parse-port port-str)
                                 :worker-count 8
                                 :legacy-return-value? false})
        url (str "http://127.0.0.1:" (http/server-port server))]
    (write-pack-web-pid! root)
    (write-dashboard-url! root url)
    (println url)
    (flush)
    @(promise)))

(defn -main [& args]
  (case (first args)
    "--serve" (serve! (second args) (nth args 2 nil))
    (do (usage)
        (exit! 1 nil)))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
