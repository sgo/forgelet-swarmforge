#!/usr/bin/env bb

(ns pane-tree
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(def agent-shells
  #{"zsh" "bash" "sh" "dash" "fish" "ksh" "csh" "tcsh" "login" "-zsh" "-bash"})

(def pane-evidence-format
  "#{pane_id}|#{pane_pid}|#{pane_current_command}|#{pane_dead}|#{session_id}|#{window_id}")

(defn shell-command? [command]
  (let [normalized (some-> command
                           str/trim
                           str/lower-case
                           (str/replace #"^.*[\\/]" ""))]
    (contains? agent-shells normalized)))

(defn process-table []
  (let [result (process/sh {:continue true}
                           "ps" "-axo" "pid=,ppid=,stat=,command=")]
    (when (zero? (:exit result))
      (->> (str/split-lines (:out result))
           (keep (fn [line]
                   (let [[pid ppid stat command]
                         (str/split (str/trim line) #"\s+" 4)]
                     (when (and pid ppid stat command)
                       {:pid (Long/parseLong pid)
                        :ppid (Long/parseLong ppid)
                        :stat stat
                        :command command}))))
           vec))))

(defn live-process? [process]
  (not (str/starts-with? (:stat process) "Z")))

(defn process-descendants [processes pane-pid]
  (loop [frontier [{:pid pane-pid :depth 0}]
         seen #{pane-pid}
         result []]
    (if-let [{pid :pid depth :depth} (first frontier)]
      (let [children (->> processes
                          (filter #(= pid (:ppid %)))
                          (remove #(contains? seen (:pid %)))
                          (mapv #(assoc % :depth (inc depth))))]
        (recur (into (subvec frontier 1) children)
               (into seen (map :pid children))
               (into result children)))
      result)))

(defn process-matches-agent? [agent process]
  (let [agent (str/lower-case (str agent))
        command (str/lower-case (str (:command process)))]
    (and (not (str/blank? agent))
         (boolean
          (re-find (re-pattern
                    (str "(^|[/\\s])"
                         (java.util.regex.Pattern/quote agent)
                         "(?=\\s|$)"))
                   command)))))

(defn expected-agent-live? [agent pane live-descendants]
  (or (= (str/lower-case (str agent))
         (str/lower-case (str (:current_command pane))))
      (boolean (some #(process-matches-agent? agent %) live-descendants))))

(defn classify-pane
  "Classify one exact pane from its process tree. A live descendant always makes
  the role live; expected-agent-live distinguishes an expected TUI from an
  unrelated live child. Shell fallback is used only after the descendant check."
  ([pane] (classify-pane pane nil))
  ([pane expected-agent] (classify-pane pane expected-agent (process-table)))
  ([pane expected-agent processes]
   (let [descendants (when processes
                       (process-descendants processes (:pane_pid pane)))
         live-descendants (->> descendants
                               (filter live-process?)
                               vec)
         kind (cond
                (:pane_dead pane) :dead-pane
                (nil? processes) :unknown
                (seq live-descendants) :live
                (shell-command? (:current_command pane)) :dead-shell
                (str/blank? (:current_command pane)) :unknown
                :else :live-direct)]
     (assoc pane
            :kind kind
            :processes processes
            :descendants descendants
            :live_descendants live-descendants
            :live_pids (mapv :pid live-descendants)
            :expected_agent_live?
            (and (contains? #{:live :live-direct} kind)
                 (expected-agent-live?
                  expected-agent pane live-descendants))))))

(defn tmux-pane-evidence [socket session]
  (let [result (process/sh {:continue true}
                           "tmux" "-S" (str socket)
                           "list-panes" "-s" "-t" (str "=" session)
                           "-F" pane-evidence-format)
        lines (when (zero? (:exit result))
                (->> (str/split-lines (:out result))
                     (remove str/blank?)
                     vec))]
    (cond
      (not (zero? (:exit result))) {:kind :missing}
      (empty? lines) {:kind :missing}
      (> (count lines) 1)
      {:kind :ambiguous
       :message (str "more than one pane exists in session " session)}
      :else
      (let [[pane-id pane-pid command pane-dead session-id window-id]
            (str/split (first lines) #"\|" -1)]
        (if (and pane-id pane-pid command session-id window-id)
          {:kind :present
           :pane_id pane-id
           :pane_pid (Long/parseLong pane-pid)
           :current_command command
           :pane_dead (= pane-dead "1")
           :session_id session-id
           :window_id window-id}
          {:kind :unknown
           :message "tmux returned malformed pane identity"})))))

(defn observe-pane [socket session expected-agent]
  (let [pane (tmux-pane-evidence socket session)]
    (if (= :present (:kind pane))
      (classify-pane pane expected-agent)
      pane)))

(defn alive-kind? [kind]
  (contains? #{:live :live-direct} kind))
