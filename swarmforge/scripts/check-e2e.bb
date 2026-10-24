#!/usr/bin/env bb

(ns check-e2e
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn fail! [message]
  (binding [*out* *err*]
    (println (str "e2e: " message)))
  (System/exit 1))

(defn option [args name]
  (loop [remaining args]
    (cond
      (empty? remaining) nil
      (= (first remaining) name) (second remaining)
      :else (recur (nnext remaining)))))

(defn read-edn [file]
  (try
    (edn/read-string (slurp (str file)))
    (catch Exception cause
      (fail! (str "cannot read " file ": " (.getMessage cause))))))

(defn string-list [value]
  (cond
    (nil? value) []
    (string? value) [value]
    (and (sequential? value) (every? string? value)) (vec value)
    :else (fail! (str "expected a string or vector of strings, got " (pr-str value)))))

(defn latest-approved-proposal [state card]
  (->> (vals (:proposals state))
       (filter #(and (= :approved (:status %))
                     (= (:id card) (:id (:card %)))))
       (sort-by :at)
       last))

(defn waiver-reason [root files]
  (some (fn [file]
          (let [path (fs/path root file)
                text (when (fs/regular-file? path) (slurp (str path)))
                marker "## E2E waiver"
                index (when text (str/index-of text marker))]
            (when index
              (let [reason (str/trim (subs text (+ index (count marker))))]
                (when-not (str/blank? reason) reason)))))
        files))

(defn redact [text values]
  (reduce (fn [result [name value]]
            (if (str/blank? value)
              result
              (str/replace result value (str "<redacted:" name ">"))))
          text
          values))

(defn write-report! [root lines]
  (let [file (fs/path root "target" "e2e" "check.log")]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str (str/join "\n" lines) "\n"))))

(defn -main [& args]
  (let [root (fs/absolutize (or (option args "--root") "."))
        stages-file (fs/path root "stages.edn")
        config (when (fs/regular-file? stages-file) (read-edn stages-file))
        _ (when-not config (fail! (str "missing " stages-file)))
        state-file (fs/path root ".swarmforge" "stages"
                            (str (:task config) ".edn"))
        state (when (fs/regular-file? state-file) (read-edn state-file))
        card (:card state)
        _ (when-not card (fail! "the stage state has no active card"))
        proposal (latest-approved-proposal state card)
        _ (when-not proposal (fail! (str "no approved spec for card " (:name card))))
        contract-file (fs/path root "qa" "e2e.edn")
        contract (when (fs/regular-file? contract-file) (read-edn contract-file))
        _ (when-not contract (fail! "missing qa/e2e.edn"))
        entry (or (get-in contract [:cards (:name card)])
                  (get-in contract [:cards (:id card)]))
        task-files (filter #(str/starts-with? % "tasks/") (:files proposal))
        waiver (when-not entry (waiver-reason root task-files))]
    (if-not entry
      (if waiver
        (let [lines [(str "card: " (:name card))
                     (str "task-id: " (:id card))
                     "decision: waived"
                     (str "reason: " waiver)]]
          (write-report! root lines)
          (println (str/join "\n" lines)))
        (fail! (str "card " (:name card)
                    " has no qa/e2e.edn entry and an approved task note has no ## E2E waiver")))
      (let [procedure (:procedure entry)
            script (:script entry)
            required (string-list (:required-inputs entry))
            env (System/getenv)
            missing (->> required
                         (filter #(str/blank? (get env %)))
                         vec)
            values (map (fn [name] [name (get env name "")]) required)]
        (when (str/blank? procedure)
          (fail! (str "card " (:name card) " has no :procedure")))
        (when (str/blank? script)
          (fail! (str "card " (:name card) " has no :script")))
        (when-not (fs/regular-file? (fs/path root procedure))
          (fail! (str "missing procedure " procedure)))
        (when-not (fs/regular-file? (fs/path root script))
          (fail! (str "missing QA script " script)))
        (when (seq missing)
          (write-report! root
                         [(str "card: " (:name card))
                          (str "task-id: " (:id card))
                          "decision: failed"
                          (str "missing inputs: " (str/join ", " missing))])
          (fail! (str "missing required inputs: " (str/join ", " missing))))
        (let [result (process/sh {:dir (str root) :continue true}
                                 "bash" (str (fs/path root script)))
              raw (str (:out result) (:err result))
              safe (redact raw values)
              lines (concat
                     [(str "card: " (:name card))
                      (str "task-id: " (:id card))
                      (str "procedure: " procedure)
                      (str "script: " script)
                      (str "required inputs: " (str/join ", " required))
                      (str "exit: " (:exit result))
                      "---"]
                     (str/split-lines safe))]
          (write-report! root lines)
          (println safe)
          (when-not (zero? (:exit result))
            (fail! (str script " exited " (:exit result)))))))))

(apply -main *command-line-args*)
