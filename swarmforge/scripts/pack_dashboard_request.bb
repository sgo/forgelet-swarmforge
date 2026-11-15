#!/usr/bin/env bb

(ns pack-dashboard-request
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def usage-text
  (str "Usage:\n"
       "  pack_dashboard_request.sh answer <id> <answer-file>\n"
       "                                  [--approve-route-card <proposal-id>\n"
       "                                   | --reply-with <exact text>\n"
       "                                   | --without-route-card-action\n"
       "                                   | --without-quick-reply]\n"
       "  pack_dashboard_request.sh clarify <question-file>\n"
       "  pack_dashboard_request.sh clarify-chat <clarification-id> <question-file>\n"
       "                                           [--reference <clarification-id>]\n"
       "  pack_dashboard_request.sh withdraw-clarification <clarification-id>\n"
       "  pack_dashboard_request.sh create --root <dir> --body <text>\n"
       "  pack_dashboard_request.sh list --root <dir>\n"
       "\n"
       "An answer that names a pending local route-card proposal must choose\n"
       "exactly one structured action with --approve-route-card. Use\n"
       "--without-route-card-action only when the response merely discusses the\n"
       "proposal and must not offer approval. Prose never selects or attaches an\n"
       "action automatically. An apparent `reply with ...` instruction requires\n"
       "either --reply-with or --without-quick-reply. Only one structured action\n"
       "may be attached.\n"))

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

(defn flag? [args key]
  (boolean (some #{key} args)))

(defn ensure-single-flag! [args key]
  (when (> (count (filter #{key} args)) 1)
    (exit! 1 (str "Option may appear only once: " key))))

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
(def clarification-id-re #"clar-[A-Za-z0-9][A-Za-z0-9._~:-]{0,139}")
(def clarification-max-chars 16384)
(def chat-token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")
(def clarification-path-re
  #"(?<![\p{L}\p{N}_+.-])(?:~|/(?:Users|home|private|var|tmp|opt|Volumes|srv|Applications|Library|usr|etc|bin))/[^\s)\]},>]+|(?<![\p{L}\p{N}_])[A-Za-z]:\\[^\s)\]},>]+")
(def clarification-control-re
  #"[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]")

(defn valid-clarification-id? [value]
  (boolean (and (string? value)
                (re-matches clarification-id-re value)
                (not (re-find chat-token-re value)))))

(defn clarification-invalid! [message]
  (exit! 1 message))

(defn safe-clarification-body [text]
  (let [body (str/trimr (or text ""))]
    (when (str/blank? body)
      (clarification-invalid! "Clarification text is required."))
    (when (re-find clarification-control-re body)
      (clarification-invalid! "Clarification text contains a control character."))
    (when (> (count body) clarification-max-chars)
      (clarification-invalid!
       (str "Clarification text must be at most "
            clarification-max-chars " characters.")))
    (-> body
        (str/replace clarification-path-re "[redacted-path]")
        (str/replace chat-token-re "[redacted-token]"))))

(defn safe-clarification-reference [value]
  (when (some? value)
    (when-not (valid-clarification-id? value)
      (clarification-invalid! "Clarification reference is invalid."))
    value))

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

(def reply-action-type "reply")
(def answer-action-flags
  ["--approve-route-card" "--without-route-card-action"
   "--reply-with" "--without-quick-reply"])
(def reply-text-max-chars 200)
(def reply-unsafe-framing-re #"[`$<>|;&\\]")
(def reply-control-re #"[\p{Cc}\p{Cf}]")
(def reply-command-re
  #"(?i)^(?:sudo\s+)?(?:rm|git|bb|sh|bash|zsh|curl|wget|gh|npm|mvn|java|node|python|ruby|perl|kill|pkill|chmod|chown|mkdir|mv|cp)\b")
(def apparent-reply-instruction-re
  #"(?i)\b(?:reply\s+with|reply|respond\s+with)\s+`([^`\r\n]{1,200})`")

(defn quick-reply-invalid! [message]
  (exit! 1 message))

(defn safe-quick-reply-text [value]
  (let [text (str/trim (str (or value "")))]
    (when (str/blank? text)
      (quick-reply-invalid! "Quick-reply text is required."))
    (when (> (count text) reply-text-max-chars)
      (quick-reply-invalid!
       (str "Quick-reply text must be at most "
            reply-text-max-chars " characters.")))
    (when (re-find reply-control-re text)
      (quick-reply-invalid!
       "Quick-reply text must be one line without control characters."))
    (when (re-find reply-unsafe-framing-re text)
      (quick-reply-invalid! "Quick-reply text contains unsafe framing."))
    (when (re-find reply-command-re text)
      (quick-reply-invalid! "Quick-reply text must not be a command."))
    (when (or (re-find absolute-path-re text)
              (re-find chat-token-re text))
      (quick-reply-invalid!
       "Quick-reply text must not contain paths or credentials."))
    text))

(defn stored-quick-reply-text [value]
  (let [text (str/trim (str (or value "")))]
    (when (and (not (str/blank? text))
               (<= (count text) reply-text-max-chars)
               (not (re-find reply-control-re text))
               (not (re-find reply-unsafe-framing-re text))
               (not (re-find reply-command-re text))
               (not (re-find absolute-path-re text))
               (not (re-find chat-token-re text)))
      text)))

(defn quick-reply-label [text]
  (action-text (str "Reply: " text) 80))

(defn reply-action [text]
  (let [text (safe-quick-reply-text text)]
    {:type reply-action-type
     :reply_text text
     :label (quick-reply-label text)
     :status "pending"}))

(defn apparent-reply-instruction [text]
  (some-> (re-find apparent-reply-instruction-re (or text ""))
          second
          str/trim
          not-empty
          (action-text 120)))

(defn quick-reply-challenge! [id instruction]
  (exit!
   1
   (str "Quick-reply action required before answering " id ".\n"
        "The response appears to request the reply `" instruction "`.\n"
        "Record it explicitly with --reply-with <exact text>.\n"
        "If the wording is literal prose and must not offer an action, rerun with "
        "--without-quick-reply.")))

(defn proposal-status [proposal]
  (let [raw (:status proposal)
        status (cond
                 (nil? raw) nil
                 (keyword? raw) (name raw)
                 (string? raw) raw
                 (symbol? raw) (name raw)
                 :else "invalid")
        status (some-> status str/lower-case)]
    (cond
      (or (not (str/blank? (str (or (:invalidated-at proposal) ""))))
          (not (str/blank? (str (or (:invalidated-reason proposal) "")))))
      "invalidated"

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

(def standalone-route-proposal-id-re
  #"(?<![A-Za-z0-9._~:/-])proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}(?![A-Za-z0-9._~:/-])")

(def max-route-candidates 20)

(defn proposal-token-value [value]
  (some-> value
          str/trim
          (str/replace #"[.,;!?]+$" "")
          not-empty))

(defn response-route-proposal-ids [text]
  (->> (re-seq standalone-route-proposal-id-re (or text ""))
       (keep proposal-token-value)
       distinct
       vec))

(defn route-proposal-descriptor [forge proposal-id proposal]
  (try
    (let [project (fs/canonicalize
                   (fs/path (str (or (:project proposal) ""))))
          expected-parent (fs/canonicalize (fs/path forge "projects"))
          family (action-text (fs/file-name forge) 128)
          project-name (action-text (fs/file-name project) 128)
          task-name (action-text (:name proposal) 256)
          label (action-text (str "Approve " (or task-name "")) 80)]
      (when (and (= expected-parent (fs/parent project))
                 (fs/directory? project)
                 family
                 (re-matches family-name-re family)
                 project-name
                 (re-matches project-name-re project-name)
                 task-name)
        {:type route-card-action-type
         :proposal_id proposal-id
         :family family
         :project project-name
         :task_name task-name
         :label label
         :status "pending"}))
    (catch Exception _ nil)))

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
        (or (route-proposal-descriptor forge proposal-id proposal)
            (exit! 1 "Route-card proposal ownership is invalid."))))))

(defn pending-route-proposal-candidates [root text]
  (if-let [forge (containing-route-root root)]
    (->> (response-route-proposal-ids text)
         (keep (fn [proposal-id]
                 (let [file (fs/path forge ".swarmforge" "route-proposals"
                                     (str proposal-id ".edn"))]
                   (when (fs/regular-file? file)
                     (let [proposal (try
                                      (edn/read-string (slurp (str file)))
                                      (catch Exception _ nil))]
                       (when (and (map? proposal)
                                  (= proposal-id (:id proposal))
                                  (= "pending" (proposal-status proposal)))
                         (when-let [action
                                    (route-proposal-descriptor
                                     forge proposal-id proposal)]
                           {:proposal_id proposal-id
                            :task_name (action-text (:task_name action) 120)})))))))
         vec)
    []))

(def explicit-send-approve-invitation-re
  #"(?i)(?<!not )(?<!never )(?<!don't )(?<!don’t )(?<!do not )\b(?:send|reply)\s+[\"“”']?\s*approve\s+(proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150})")

(def explicit-approve-if-want-invitation-re
  #"(?i)\bapprove\s+(proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150})\s*[,.!]?\s+if\s+you\s+want\b")

(def reply-approve-invitation-re
  #"(?i)(?<!not )(?<!never )(?<!don't )(?<!don’t )(?<!do not )\breply\s+approve\b")

(defn explicit-approval-invitation-ids [text]
  (let [direct (mapcat
                (fn [pattern]
                  (keep (comp proposal-token-value second)
                        (re-seq pattern (or text ""))))
                [explicit-send-approve-invitation-re
                 explicit-approve-if-want-invitation-re])
        paragraph (mapcat
                   (fn [block]
                     (when (re-find reply-approve-invitation-re block)
                       (keep proposal-token-value
                             (response-route-proposal-ids block))))
                   (str/split (or text "") #"(?:\r?\n[ \t]*){2,}"))]
    (->> (concat direct paragraph)
         distinct
         sort
         vec)))

(defn pending-approval-invitation-candidates [root text]
  (let [all (pending-route-proposal-candidates root text)
        invited (set (explicit-approval-invitation-ids text))]
    (->> all
         (filter #(contains? invited (:proposal_id %)))
         vec)))

(defn approval-invitation-challenge! [id candidates]
  (let [shown (take max-route-candidates candidates)
        choices (str/join
                 "\n"
                 (map (fn [{:keys [proposal_id task_name]}]
                        (str "  --approve-route-card " proposal_id
                             "  ; " task_name))
                      shown))
        omitted (- (count candidates) (count shown))]
    (exit!
     1
     (str "Approval action required before answering " id ".\n"
          "The response explicitly invites approval for "
          (count candidates) " pending local proposal(s):\n"
          choices
          (when (pos? omitted)
            (str "\n  ... and " omitted
                 " more; reduce the response to one invited proposal."))
          "\nAttach exactly one matching action with --approve-route-card "
          "<proposal-id>.\n"
          "--without-route-card-action is not allowed for an approval "
          "invitation; rewrite genuinely discussion-only text instead."))))

(defn render-request
  [{:strs [id status body response created_at updated_at role
           thread_id source proposal_id author direction kind clarification_id
           reference_id attention_state answered_at answered_by withdrawn_at seen_at
           action_type action_proposal_id action_family action_project
           action_task_name action_reply_text action_label action_status]}]
  (str "id: " id "\n"
       "status: " status "\n"
       (when-not (str/blank? role) (str "role: " role "\n"))
       (when-not (str/blank? thread_id) (str "thread_id: " thread_id "\n"))
       (when-not (str/blank? source) (str "source: " source "\n"))
       (when-not (str/blank? proposal_id) (str "proposal_id: " proposal_id "\n"))
       (when-not (str/blank? author) (str "author: " author "\n"))
       (when-not (str/blank? direction) (str "direction: " direction "\n"))
       (when-not (str/blank? kind) (str "kind: " kind "\n"))
       (when-not (str/blank? clarification_id)
         (str "clarification_id: " clarification_id "\n"))
       (when-not (str/blank? reference_id)
         (str "reference_id: " reference_id "\n"))
       (when-not (str/blank? attention_state)
         (str "attention_state: " attention_state "\n"))
       (when-not (str/blank? action_type) (str "action: " action_type "\n"))
       (when-not (str/blank? action_proposal_id)
         (str "action_proposal_id: " action_proposal_id "\n"))
       (when-not (str/blank? action_family)
         (str "action_family: " action_family "\n"))
       (when-not (str/blank? action_project)
         (str "action_project: " action_project "\n"))
       (when-not (str/blank? action_task_name)
         (str "action_task_name: " action_task_name "\n"))
       (when-not (str/blank? action_reply_text)
         (str "action_reply_text: " action_reply_text "\n"))
       (when-not (str/blank? action_label)
         (str "action_label: " action_label "\n"))
       (when-not (str/blank? action_status)
         (str "action_status: " action_status "\n"))
       "created_at: " created_at "\n"
       (when updated_at (str "updated_at: " updated_at "\n"))
       (when-not (str/blank? answered_at) (str "answered_at: " answered_at "\n"))
       (when-not (str/blank? answered_by) (str "answered_by: " answered_by "\n"))
       (when-not (str/blank? withdrawn_at) (str "withdrawn_at: " withdrawn_at "\n"))
       (when-not (str/blank? seen_at) (str "seen_at: " seen_at "\n"))
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

(defn stored-reply-action [headers]
  (let [status (some-> (get headers "action_status") str/trim str/lower-case)
        status (when (contains? #{"pending" "consumed" "unavailable"} status)
                 status)
        reply-text (stored-quick-reply-text (get headers "action_reply_text"))
        label (action-text (get headers "action_label") 80)]
    (when (and (= reply-action-type (get headers "action"))
               status
               reply-text
               (= label (quick-reply-label reply-text)))
      {:type reply-action-type
       :reply_text reply-text
       :label label
       :status status})))

(defn stored-action [headers]
  (let [route-fields? (some #(not (str/blank? (str (or (get headers %) ""))))
                            ["action_proposal_id" "action_family"
                             "action_project" "action_task_name"])
        reply-fields? (not (str/blank? (str (or (get headers
                                                       "action_reply_text")
                                                ""))))
        mixed? (and route-fields? reply-fields?)
        route (stored-route-action headers)
        reply (stored-reply-action headers)]
    (cond
      mixed? nil
      (and route reply) nil
      route route
      reply reply
      :else nil)))

(defn request-entry [path]
  (let [m (parse-kv (slurp (str path)))]
    (let [action (stored-action m)
          clarification-id (let [value (some-> (get m "clarification_id")
                                               str/trim not-empty)]
                             (when (valid-clarification-id? value) value))
          reference-id (let [value (some-> (get m "reference_id")
                                           str/trim not-empty)]
                         (when (valid-clarification-id? value) value))
          clarification? (and (= "project-agent" (get m "author"))
                              (= "project-agent" (get m "direction"))
                              (= "clarification" (get m "kind"))
                              clarification-id
                              (= clarification-id (get m "id"))
                              (contains? #{"open" "answered" "withdrawn"}
                                         (get m "status")))]
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

        clarification?
        (assoc :kind "clarification"
               :author "project-agent"
               :direction "project-agent"
               :clarification_id clarification-id
               :lifecycle (get m "status")
               :attention (get m "attention_state"))

        (and clarification? reference-id)
        (assoc :reference_id reference-id)

        (and clarification? (not (str/blank? (get m "answered_at"))))
        (assoc :answered_at (get m "answered_at"))

        (and clarification? (not (str/blank? (get m "answered_by"))))
        (assoc :answered_by (get m "answered_by"))

        (and clarification? (not (str/blank? (get m "withdrawn_at"))))
        (assoc :withdrawn_at (get m "withdrawn_at"))

        (and clarification? (not (str/blank? (get m "seen_at"))))
        (assoc :seen_at (get m "seen_at"))

        (and (not clarification?) clarification-id)
        (assoc :clarification_id clarification-id)

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

(defn with-request-lock [root f]
  (let [path (fs/path root ".swarmforge" "dashboard" "requests" "requests.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs (fs/parent path))
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn atomic-write-request! [file headers]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix ".chat-record."})]
    (spit (str tmp) (render-request headers))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn find-typed-clarification [root id]
  (when (valid-clarification-id? id)
    (some (fn [path]
            (when (fs/regular-file? path)
              (let [headers (parse-kv (slurp (str path)))]
                (when (and (= "project-agent" (get headers "author"))
                           (= "project-agent" (get headers "direction"))
                           (= "clarification" (get headers "kind"))
                           (= id (get headers "clarification_id")))
                  {:path path :headers headers}))))
          [(request-file (pending-dir root) id)
           (request-file (done-dir root) id)])))

(defn same-clarification-content?
  [headers body reference-id]
  (and (= "clarification" (get headers "kind"))
       (= (str/trimr (or body ""))
          (str/trimr (or (get headers "body") "")))
       (= reference-id (get headers "reference_id"))))

(defn create-chat-clarification! [root id text reference-id]
  (when-not (valid-clarification-id? id)
    (clarification-invalid! "Clarification id is invalid."))
  (let [body (safe-clarification-body text)
        reference-id (safe-clarification-reference reference-id)]
    (with-request-lock
      root
      (fn []
        (let [existing-path (some #(when (fs/regular-file? %) %)
                                  [(request-file (pending-dir root) id)
                                   (request-file (done-dir root) id)])
              existing (when existing-path
                         (parse-kv (slurp (str existing-path))))]
          (when existing-path
            (if (same-clarification-content? existing body reference-id)
              (do
                (println id)
                (flush)
                (exit! 0 nil))
              (exit! 1 "Clarification id was reused with different content.")))
          (when (and reference-id
                     (nil? (find-typed-clarification root reference-id)))
            (clarification-invalid! "Clarification reference is unknown."))
          (let [now (timestamp)
                file (request-file (pending-dir root) id)]
            (atomic-write-request!
             file
             {"id" id
              "status" "open"
              "author" "project-agent"
              "direction" "project-agent"
              "kind" "clarification"
              "clarification_id" id
              "reference_id" reference-id
              "attention_state" "unseen"
              "created_at" now
              "updated_at" now
              "body" body})
            id))))))

(defn withdraw-chat-clarification! [root id]
  (when-not (valid-clarification-id? id)
    (clarification-invalid! "Clarification id is invalid."))
  (with-request-lock
    root
    (fn []
      (let [{:keys [path headers] :as found} (find-typed-clarification root id)]
        (when-not found
          (exit! 1 (str "Unknown clarification: " id)))
        (when-not (= "open" (get headers "status"))
          (exit! 1 (str "Clarification is " (get headers "status")
                        " and cannot be withdrawn: " id)))
        (let [now (timestamp)]
          (atomic-write-request!
           path
           (assoc headers
                  "status" "withdrawn"
                  "withdrawn_at" now
                  "updated_at" now)))
        (println id)))))

(defn response-proposal-action!
  [text proposal-id]
  (when-not (some #{proposal-id} (response-route-proposal-ids text))
    (exit! 1 (str "Route-card action " proposal-id
                  " does not appear in the exact response.")))
  true)

(defn route-action-challenge!
  [id candidates]
  (let [shown (take max-route-candidates candidates)
        choices (str/join
                 "\n"
                 (map (fn [{:keys [proposal_id task_name]}]
                        (str "  pack_dashboard_request.sh answer " id
                             " <answer-file> --approve-route-card "
                             proposal_id "  ; " task_name))
                      shown))
        omitted (- (count candidates) (count shown))]
    (exit!
     1
     (str "Route-card action required before answering " id ".\n"
          "The response names pending local route-card proposal(s):\n"
          choices
          (when (pos? omitted)
            (str "\n  ... and " omitted " more; reduce the response to the "
                 "proposal you mean to offer."))
          "\nChoose exactly one proposal with --approve-route-card.\n"
          "If the response only discusses a proposal and must not offer "
          "approval, rerun with:\n"
          "  pack_dashboard_request.sh answer " id
          " <answer-file> --without-route-card-action"))))

(defn request-headers [entry text updated-at action]
  {"id" (:id entry)
   "status" "done"
   "role" (:role entry)
   "body" (:body entry)
   "response" text
   "created_at" (:created_at entry)
   "updated_at" updated-at
   "thread_id" (:thread_id entry)
   "source" (:source entry)
   "proposal_id" (:proposal_id entry)
   "clarification_id" (:clarification_id entry)
   "action_type" (:type action)
   "action_proposal_id" (:proposal_id action)
   "action_family" (:family action)
   "action_project" (:project action)
   "action_task_name" (:task_name action)
   "action_reply_text" (:reply_text action)
   "action_label" (:label action)
   "action_status" (:status action)})

(defn complete-pending-request! [root id src answer-file action]
  (let [text (str/trim (slurp (str answer-file)))
        now (timestamp)
        dest (request-file (done-dir root) id)
        entry (request-entry src)]
    (fs/create-dirs (fs/parent dest))
    (let [tmp (fs/create-temp-file {:dir (fs/parent dest)
                                    :prefix ".answered-request."})
          headers (request-headers entry text now action)]
      (spit (str tmp) (render-request headers))
      (fs/move tmp dest {:replace-existing true :atomic-move true})
      (fs/delete-if-exists src))
    (println "ANSWERED:" id)))

(defn backfill-action! [done entry text action]
  (let [tmp (fs/create-temp-file {:dir (fs/parent done)
                                  :prefix ".action-backfill."})
        headers (request-headers entry text (:updated_at entry) action)]
    (spit (str tmp) (render-request headers))
    (fs/move tmp done {:replace-existing true :atomic-move true})
    (println "ANSWERED:" (:id entry))))

(defn answer-pending!
  [root id answer-file approve-route-card without-route-card-action
   reply-with without-quick-reply]
  (let [src (find-pending root id)
        text (str/trim (slurp (str answer-file)))
        route-candidates (pending-route-proposal-candidates root text)
        invited-candidates (pending-approval-invitation-candidates root text)
        route-action (when-not (str/blank? approve-route-card)
                       (safe-route-proposal! root approve-route-card))
        reply-descriptor (when reply-with (reply-action reply-with))
        action (or route-action reply-descriptor)
        _ (when (seq invited-candidates)
            (when-not (and (= 1 (count invited-candidates))
                           route-action
                           (= (:proposal_id (first invited-candidates))
                              (:proposal_id route-action)))
              (approval-invitation-challenge! id invited-candidates)))
        _ (when route-action
            (response-proposal-action! text (:proposal_id route-action)))
        _ (when (and (nil? action) (not without-quick-reply))
            (when-let [instruction (apparent-reply-instruction text)]
              (quick-reply-challenge! id instruction)))
        candidates (when (and (nil? action)
                              (not without-route-card-action)
                              (nil? reply-descriptor)
                              (empty? invited-candidates))
                     route-candidates)]
    (when (seq candidates)
      (route-action-challenge! id candidates))
    (complete-pending-request! root id src answer-file action)))

(defn answer-done! [root id done answer-file approve-route-card
                    without-route-card-action reply-with without-quick-reply]
  (let [entry (request-entry done)
        stored-response (:response entry)
        text (str/trim (slurp (str answer-file)))
        stored-action (first (:actions entry))]
    (when-not (= text stored-response)
      (exit! 1 (str "Request already answered with a different response: " id)))
    (cond
      (and (not (str/blank? approve-route-card)) stored-action)
      (if (and (= route-card-action-type (:type stored-action))
               (= approve-route-card (:proposal_id stored-action)))
        (println "ANSWERED:" id)
        (exit! 1 (str "Request already answered with a different action: " id)))

      (not (str/blank? approve-route-card))
      (let [action (safe-route-proposal! root approve-route-card)]
        (response-proposal-action! stored-response (:proposal_id action))
        (backfill-action! done entry stored-response action))

      (and reply-with stored-action)
      (if (and (= reply-action-type (:type stored-action))
               (= (safe-quick-reply-text reply-with)
                  (:reply_text stored-action)))
        (println "ANSWERED:" id)
        (exit! 1 (str "Request already answered with a different action: " id)))

      reply-with
      (backfill-action! done entry stored-response (reply-action reply-with))

      (and without-route-card-action
           (= route-card-action-type (:type stored-action)))
      (exit! 1 (str "Request already answered with a route-card action: " id))

      (and without-quick-reply
           (= reply-action-type (:type stored-action)))
      (exit! 1 (str "Request already answered with a quick-reply action: " id))

      :else
      (println "ANSWERED:" id))))

(defn answer-request!
  [root id answer-file approve-route-card without-route-card-action
   reply-with without-quick-reply]
  (when (str/blank? id)
    (exit! 1 "Missing request id"))
  (when-not (fs/regular-file? answer-file)
    (exit! 1 (str "Answer file not found: " answer-file)))
  (when (and (not (str/blank? approve-route-card))
             reply-with)
    (exit! 1 "Choose either --approve-route-card or --reply-with."))
  (when (and (not (str/blank? approve-route-card))
             without-route-card-action)
    (exit! 1 "Choose either --approve-route-card or --without-route-card-action."))
  (when (and reply-with without-quick-reply)
    (exit! 1 "Choose either --reply-with or --without-quick-reply."))
  (with-request-lock
    root
    (fn []
      (let [candidate (or (find-pending root id) (find-done-request root id))
            entry (when candidate (request-entry candidate))]
        (when (= "clarification" (:kind entry))
          (exit! 1 "Answer this clarification through project chat.")))
      (cond
        (find-pending root id)
        (answer-pending! root id answer-file approve-route-card
                         without-route-card-action reply-with without-quick-reply)

        (find-done-request root id)
        (answer-done! root id (find-done-request root id) answer-file
                      approve-route-card without-route-card-action
                      reply-with without-quick-reply)

        (find-done-clarification root id)
        (println "ANSWERED:" id)

        :else
        (exit! 1 (str "Unknown pending request: " id))))))

(defn -main [& args]
  (case (first args)
    "answer" (let [approve-flag? (flag? args "--approve-route-card")
                   approve-id (parse-flag args "--approve-route-card")
                   reply-flag? (flag? args "--reply-with")
                   reply-with (parse-flag args "--reply-with")
                   without-quick-reply? (flag? args "--without-quick-reply")]
               (doseq [flag answer-action-flags]
                 (ensure-single-flag! args flag))
               (when (and approve-flag? (str/blank? (or approve-id "")))
                 (exit! 1 "Missing route-card proposal id."))
               (when (and reply-flag?
                          (or (nil? reply-with)
                              (some #{reply-with} answer-action-flags)))
                 (exit! 1 "Missing quick-reply text."))
               (answer-request! (project-root) (second args) (nth args 2 nil)
                                approve-id
                                (flag? args "--without-route-card-action")
                                reply-with
                                without-quick-reply?))
    "clarify" (clarify! (second args))
    "clarify-chat"
    (let [id (second args)
          question-file (nth args 2 nil)
          reference-id (parse-flag args "--reference")
          root (or (parse-flag args "--root") (project-root))]
      (when-not (fs/regular-file? (or question-file ""))
        (exit! 1 "Clarification question file is missing."))
      (println
       (create-chat-clarification!
        root id (slurp (str question-file)) reference-id)))
    "withdraw-clarification"
    (let [root (or (parse-flag args "--root") (project-root))]
      (withdraw-chat-clarification! root (second args)))
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
