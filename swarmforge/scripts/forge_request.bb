#!/usr/bin/env bb

(ns forge-request
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def usage-text
  (str "Gate a cross-forge GitHub issue through this receiving forge.\n\n"
       "Usage:\n"
       "  forge_request.sh intake <issue-url> --forge-root <root>\n"
       "  forge_request.sh list --forge-root <root>\n"
       "  forge_request.sh detail <request-id> --forge-root <root>\n"
       "  forge_request.sh approve <request-id> --forge-root <root>\n"
       "      (--approval <dashboard-request-id> | --operator-said <exact words>)\n"
       "  forge_request.sh bind <request-id> --forge-root <root>\n"
       "      --project-root <root> --proposal <route-proposal-id> [--card <name>]\n"
       "  forge_request.sh link-card <request-id> --forge-root <root> --card <name>\n"
       "  forge_request.sh guard <request-id> --forge-root <root>\n"
       "      --project-root <root> --card <name>\n\n"
       "intake writes only .swarmforge/forge-requests/<id>.edn. It creates no\n"
       "card and performs no composition, restart or deployment. Only local\n"
       "operator words can approve it. bind records the route-card proposal used\n"
       "for the local work, link-card records its consumed board card, and guard\n"
       "is the execution check. GitHub issue text is never execution authority.\n"))

(def required-headings
  ["Need" "Contract" "Boundaries" "Testing" "Out of scope" "Consumers waiting"])

(def request-id-re #"forge-request-[A-Za-z0-9._~:-]+")

(def proposal-id-re #"proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")

(def repository-re #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

(def issue-url-re
  #"https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/issues/([0-9]+)")

(def affirmative-re #"(?i)\b(approve|approved|yes|ok|okay|go ahead|start|started)\b")

(def negative-re #"(?i)\b(no|not|dont|don't|won't|wont|wait|hold|reject|never)\b")

(declare link-card!)

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn fail! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(defn forge-root [args]
  (let [given (or (flag-value args "--forge-root")
                  (not-empty (System/getenv "SWARMFORGE_FORGE_ROOT"))
                  (str (fs/absolutize ".")))
        root (fs/absolutize given)]
    (when-not (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
      (fail! (str "Not a forge root (no .swarmforge/roles.tsv): " root)))
    (when-not (fs/directory? (fs/path root "projects"))
      (fail! (str "Not a forge root (no projects/): " root)))
    (fs/canonicalize root)))

(defn command [dir & args]
  (apply process/sh {:continue true :dir (str dir)} args))

(defn git-output [root & args]
  (let [result (apply command root "git" args)]
    (when-not (zero? (:exit result))
      (fail! (str "Git failed in " root ": "
                  (str/trim (str (:err result) (:out result))))))
    (str/trim (:out result))))

(defn logical-repository [forge]
  (let [remote (git-output forge "remote" "get-url" "origin")
        repository (or (second (re-find
                                #"(?:github\.com[/:])([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?$"
                                remote))
                       (second (re-find #"^([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)$"
                                        remote)))]
    (when-not (and repository (re-matches repository-re repository))
      (fail! (str "Forge origin is not a GitHub repository: " remote)))
    repository))

(defn requesters [forge]
  (let [file (fs/path forge "forge-requesters")]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (map str/trim)
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           (map str/lower-case)
           set)
      #{})))

(defn gh-command []
  (or (not-empty (System/getenv "SWARMFORGE_GH_COMMAND"))
      "gh"))

(defn fetch-issue [url]
  (let [result (process/sh {:continue true}
                           (gh-command)
                           "issue" "view" url
                           "--json" "author,body,number,repository,title,url")]
    (when-not (zero? (:exit result))
      (fail! (str "Could not fetch issue " url ": "
                  (str/trim (str (:err result) (:out result))))))
    (try
      (json/parse-string (:out result) true)
      (catch Exception _
        (fail! (str "GitHub returned malformed issue JSON for " url))))))

(defn heading-present? [body heading]
  (boolean (re-find (re-pattern (str "(?m)^### " (java.util.regex.Pattern/quote heading)
                                    "[ \\t]*$"))
                    (or body ""))))

(defn validate-issue! [forge url]
  (let [[_ owner repository-name number] (if (string? url)
                                           (re-matches issue-url-re url)
                                           (or (re-matches issue-url-re (:url url)) []))
        issue (if (string? url) (fetch-issue url) url)
        author (get-in issue [:author :login])
        repository (get-in issue [:repository :nameWithOwner])
        expected-repository (logical-repository forge)]
    (when-not (= (str owner "/" repository-name) repository)
      (fail! "Issue URL and GitHub response name different repositories"))
    (when-not (= expected-repository repository)
      (fail! (str "Issue targets " repository ", not receiving forge "
                  expected-repository)))
    (when-not (contains? (requesters forge) (str/lower-case (or author "")))
      (fail! (str "Requester is not listed in " (fs/path forge "forge-requesters")
                  ": " author)))
    (when-not (= (str number) (str (:number issue)))
      (fail! "Issue number changed while reading"))
    (doseq [heading required-headings]
      (when-not (heading-present? (:body issue) heading)
        (fail! (str "Issue is missing required heading: " heading))))
    (let [contract (or (second (re-find
                                #"(?ms)^### Contract[ \t]*\n(.*?)(?=^### |\z)"
                                (:body issue)))
                       "")]
      {:issue issue
       :author (str author)
       :repository repository
       :number (:number issue)
       :contract (str/trim contract)
       :url (:url issue)})))

(defn sha256 [text]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x"
            (BigInteger. 1 (.digest digest (.getBytes (str text) "UTF-8"))))))

(defn issue-digest [issue]
  (sha256 (str (str/trim (or (:title issue) ""))
               "\n"
               (str/trimr (or (:body issue) "")))))

(defn requests-dir [forge]
  (fs/path forge ".swarmforge" "forge-requests"))

(defn request-file [forge id]
  (fs/path (requests-dir forge) (str id ".edn")))

(defn request-id [repository number]
  (str "forge-request-"
       (str/replace (str repository "-" number) #"[^A-Za-z0-9._~:-]" "-")))

(defn atomic-write! [file value]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".request."})]
    (spit (str tmp) (str (pr-str value) "\n"))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn read-record! [forge id]
  (when-not (and id (re-matches request-id-re id))
    (fail! (str "Invalid forge request id: " id)))
  (let [file (request-file forge id)]
    (when-not (fs/regular-file? file)
      (fail! (str "No local forge request " id " in " (requests-dir forge))))
    (edn/read-string (slurp (str file)))))

(defn save-record! [forge record]
  (atomic-write! (request-file forge (:id record)) record)
  record)

(defn intake! [args]
  (let [url (first args)
        _ (when-not url (fail! "Missing GitHub issue URL"))
        forge (forge-root args)
        checked (validate-issue! forge url)
        issue (:issue checked)
        id (request-id (:repository checked) (:number checked))
        digest (issue-digest issue)
        existing (when (fs/regular-file? (request-file forge id))
                   (edn/read-string (slurp (str (request-file forge id)))))]
    (when (and existing (not= (:digest existing) digest)
               (not= "pending-approval" (:status existing)))
      (fail! (str "Request " id " changed after approval; intake refused")))
    (let [record (cond
                   (and existing (= (:digest existing) digest)) existing
                   :else
                   {:id id
                    :repository (:repository checked)
                    :issue-number (:number checked)
                    :url (:url checked)
                    :requester (:author checked)
                    :target-forge-root (str forge)
                    :title (:title issue)
                    :body (:body issue)
                    :contract (:contract checked)
                    :digest digest
                    :status "pending-approval"
                    :created-at (or (:created-at existing) (now))
                    :updated-at (now)})]
      (save-record! forge record)
      (println (str "PENDING-APPROVAL:" id))
      (println (str "digest: " digest))
      id)))

(defn dashboard-approval [forge id]
  (when (str/blank? (or id ""))
    (fail! "Missing approval request id"))
  (let [paths [(fs/path forge ".swarmforge/dashboard/requests/pending" (str id ".request"))
               (fs/path forge ".swarmforge/dashboard/requests/done" (str id ".request"))]
        pending (first paths)
        done (second paths)]
    (cond
      (fs/regular-file? pending)
      (fail! (str "Approval " id " is still unanswered"))
      (fs/regular-file? done)
      (let [[head _] (str/split (slurp (str done)) #"\n\n" 2)
            headers (into {} (for [line (str/split-lines (or head ""))
                                  :let [[k v] (str/split line #": " 2)]
                                  :when (and k v)]
                              [k v]))]
        (str/replace (get headers "response" "") #"\\n" "\n"))
      :else
      (fail! (str "No local approval request " id)))))

(defn affirmative? [words]
  (let [text (or words "")]
    (and (not (str/blank? text))
         (not (re-find negative-re text))
         (boolean (re-find affirmative-re text)))))

(defn approve! [args]
  (let [id (first args)
        forge (forge-root args)
        record (read-record! forge id)
        explicit-approval (flag-value args "--approval")
        operator-said (flag-value args "--operator-said")
        words (cond
                (not (str/blank? (or operator-said ""))) operator-said
                (not (str/blank? (or explicit-approval "")))
                (dashboard-approval forge explicit-approval)
                :else (fail! "Pass --approval or --operator-said for local approval"))]
    (when-not (= (:status record) "pending-approval")
      (fail! (str "Forge request " id " is already " (:status record)
                  " and its approval is single-use")))
    (when-not (= (str forge) (str (:target-forge-root record)))
      (fail! "Approval is for a different forge root"))
    (when-not (affirmative? words)
      (fail! (str "Local operator did not approve the request: " words)))
    (let [current (validate-issue! forge (:url record))
          current-digest (issue-digest (:issue current))]
      (when-not (= current-digest (:digest record))
        (fail! "Issue title or body changed after intake; propose intake again"))
      (save-record!
       forge
       (assoc record
              :status "approved"
              :approved-digest current-digest
              :approved-words (str/trim words)
              :approved-at (now)
              :approved-under-root (str forge)))
      (println (str "APPROVED:" id))
      id)))

(defn project-root! [forge given]
  (when (str/blank? (or given ""))
    (fail! "Missing project root"))
  (let [projects (fs/canonicalize (fs/path forge "projects"))
        root (fs/canonicalize (fs/absolutize given))]
    (when-not (and (= projects (fs/parent root))
                   (fs/directory? root)
                   (or (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
                       (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))))
      (fail! (str "Project is not a managed project in this forge: " root)))
    root))

(defn task-rows [project]
  (let [file (fs/path project ".swarmforge" "board" "tasks.tsv")]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map (fn [line]
                  (let [[name lane created updated task-id audit]
                        (str/split line #"\t" -1)]
                    {:name name :lane lane :created created :updated updated
                     :id (or (not-empty task-id) name) :audit audit})))
           vec)
      [])))

(defn card-row! [project card-name]
  (when (str/blank? (or card-name ""))
    (fail! "Missing local card name"))
  (let [matches (filterv #(= card-name (:name %)) (task-rows project))]
    (when-not (= 1 (count matches))
      (fail! (str "Expected one local card named " card-name
                  ", found " (count matches))))
    (first matches)))

(defn route-proposal! [forge project proposal-id]
  (when-not (and proposal-id (re-matches proposal-id-re proposal-id))
    (fail! (str "Invalid route proposal id: " proposal-id)))
  (let [file (fs/path forge ".swarmforge" "route-proposals" (str proposal-id ".edn"))]
    (when-not (fs/regular-file? file)
      (fail! (str "No local route proposal " proposal-id)))
    (let [proposal (edn/read-string (slurp (str file)))]
      (when-not (= (str project) (str (fs/canonicalize (:project proposal))))
        (fail! "Route proposal targets a different project"))
      proposal)))

(defn bind! [args]
  (let [id (first args)
        forge (forge-root args)
        record (read-record! forge id)
        project (project-root! forge (flag-value args "--project-root"))
        proposal-id (flag-value args "--proposal")
        proposal (route-proposal! forge project proposal-id)]
    (when-not (= (:status record) "approved")
      (fail! (str "Forge request " id " is " (:status record)
                  ", not locally approved")))
    (when-not (= (:digest record) (:approved-digest record))
      (fail! "Approved issue digest is missing or mismatched"))
    (let [bound (save-record!
                 forge
                 (assoc record
                        :status "bound"
                        :binding {:project-root (str project)
                                  :proposal-id proposal-id
                                  :proposal-name (:name proposal)
                                  :bound-at (now)}))]
      (println (str "BOUND:" id " proposal " proposal-id))
      (if-let [card (flag-value args "--card")]
        (do (link-card! (into [id "--forge-root" (str forge) "--card" card]
                              args))
            id)
        (do (println "Link the consumed card with forge_request.sh link-card")
            id)))))

(defn link-card! [args]
  (let [id (first args)
        forge (forge-root args)
        record (read-record! forge id)
        card-name (flag-value args "--card")
        binding (:binding record)]
    (when-not (contains? #{"approved" "bound"} (:status record))
      (fail! (str "Forge request " id " is " (:status record)
                  ", not locally approved")))
    (when-not binding
      (fail! "Bind the request to its local route proposal first"))
    (let [project (fs/canonicalize (:project-root binding))
          proposal (route-proposal! forge project (:proposal-id binding))
          row (card-row! project card-name)]
      (when-not (= card-name (:name proposal))
        (fail! "Local card name does not match the route proposal"))
      (when-not (and (:consumed-at proposal)
                     (= (:task-id proposal) (:id row)))
        (fail! "Route proposal has not created this local card"))
      (save-record!
       forge
       (assoc record
              :status "ready"
              :card {:name card-name
                     :id (:id row)
                     :linked-at (now)}))
      (println (str "READY:" id " card " card-name " (" (:id row) ")"))
      id)))

(defn guard! [args]
  (let [id (first args)
        forge (forge-root args)
        record (read-record! forge id)]
    (when-not (= (:status record) "ready")
      (fail! (str "Forge request " id " is " (:status record)
                  "; local approval and card linkage are required")))
    (when-not (= (str forge) (str (:target-forge-root record)))
      (fail! "Forge request belongs to a different target root"))
    (let [project (project-root! forge (flag-value args "--project-root"))
          card-name (flag-value args "--card")
          row (card-row! project card-name)]
      (when (contains? #{"done" "queued"} (:lane row))
        (fail! (str "Local card " card-name " is not active: " (:lane row))))
      (when-not (= (str project) (:project-root (:binding record)))
        (fail! "Forge request is bound to a different local project"))
      (when-not (= card-name (get-in record [:card :name]))
        (fail! "Forge request is linked to a different local card"))
      (when-not (= (:id row) (get-in record [:card :id]))
        (fail! "Local card identity changed since linkage"))
      (let [current (validate-issue! forge (:url record))]
        (when-not (= (issue-digest (:issue current)) (:approved-digest record))
          (fail! "GitHub issue changed after local approval; stop and re-intake")))
      (println (str "AUTHORIZED:" id " card " card-name))
      {:id id
       :repository (:repository record)
       :issue-number (:issue-number record)
       :project-root (str project)
       :card (get-in record [:card :name])
       :card-id (get-in record [:card :id])
       :digest (:approved-digest record)})))

(defn list! [args]
  (let [forge (forge-root args)
        dir (requests-dir forge)]
    (doseq [file (when (fs/directory? dir)
                   (sort (fs/list-dir dir)))
            :when (str/ends-with? (fs/file-name file) ".edn")]
      (let [record (edn/read-string (slurp (str file)))]
        (println (str (:id record) "\t" (:status record) "\t"
                      (:repository record) "#" (:issue-number record)))))))

(defn detail! [args]
  (let [forge (forge-root args)
        record (read-record! forge (first args))]
    (println (json/generate-string record))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (println usage-text)
    (System/exit 0))
  (case (first args)
    "intake" (intake! (rest args))
    "approve" (approve! (rest args))
    "bind" (bind! (rest args))
    "link-card" (link-card! (rest args))
    "guard" (guard! (rest args))
    "execute" (guard! (rest args))
    "list" (list! (rest args))
    "detail" (detail! (rest args))
    (do (binding [*out* *err*] (println usage-text))
        (System/exit 1)))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
