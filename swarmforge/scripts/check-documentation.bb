#!/usr/bin/env bb

(ns check-documentation
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn fail! [message]
  (binding [*out* *err*]
    (println (str "documentation: " message)))
  (System/exit 1))

(defn argument [args name default]
  (loop [remaining args]
    (cond
      (empty? remaining) default
      (= (first remaining) name) (or (second remaining)
                                    (fail! (str name " needs a value")))
      :else (recur (nnext remaining)))))

(defn string-list [value]
  (cond
    (nil? value) []
    (string? value) [value]
    (and (sequential? value) (every? string? value)) (vec value)
    :else nil))

(defn heading-level [line]
  (count (take-while #(= % \#) line)))

(defn section-text [document heading]
  (let [lines (str/split-lines document)
        level (heading-level heading)
        start (first
               (keep-indexed
                (fn [index line]
                  (when (= (str/trim line) (str/trim heading)) index))
                lines))]
    (when-not (and start (pos? level))
      (fail! (str "missing section heading " (pr-str heading))))
    (->> (drop (inc start) lines)
         (take-while (fn [line]
                       (let [next-level (heading-level line)]
                         (or (zero? next-level)
                             (> next-level level)))))
         (str/join "\n"))))

(defn local-link-targets [text]
  (->> (re-seq #"(?<!!)\[[^\]]+\]\(([^)]+)\)" text)
       (map second)
       (map #(str/replace % #"\s+\".*\"$" ""))
       (remove #(or (str/blank? %)
                    (str/starts-with? % "#")
                    (str/starts-with? % "http://")
                    (str/starts-with? % "https://")
                    (str/starts-with? % "mailto:")))))

(defn check-links! [root file]
  (let [text (slurp (str file))]
    (doseq [target (local-link-targets text)]
      (let [relative (first (str/split target #"#" 2))
            resolved (fs/path (fs/parent file) relative)]
        (when-not (fs/exists? resolved)
          (fail! (str "broken link in " (fs/relativize root file)
                      ": " target)))))))

(defn check-example! [root example sections]
  (let [id (:id example)
        command (string-list (:command example))
        expected (string-list (:expect example))
        section (:section example)]
    (when (str/blank? id)
      (fail! "every documentation example needs an :id"))
    (when (str/blank? section)
      (fail! (str "example " id " needs a :section")))
    (when-not (some #(= section (:id %)) sections)
      (fail! (str "example " id " names an unknown section " section)))
    (when (empty? command)
      (fail! (str "example " id " needs a non-empty :command vector")))
    (when (empty? expected)
      (fail! (str "example " id " needs at least one :expect string")))
    (let [result (apply process/sh
                        (concat command
                                [{:dir (str root) :continue true}]))
          output (str (:out result) (:err result))]
      (when-not (zero? (:exit result))
        (fail! (str "example " id " exited " (:exit result) ":\n" output)))
      (doseq [text expected]
        (when-not (str/includes? output text)
          (fail! (str "example " id " did not print " (pr-str text)
                      ":\n" output))))
      (println (str "documentation example " id ": passed")))))

(defn -main [& args]
  (let [root (fs/absolutize (argument args "--root" "."))
        contract-file (fs/path root "docs" "manual-contract.edn")]
    (when-not (fs/regular-file? contract-file)
      (fail! (str "missing " (fs/relativize root contract-file))))
    (let [contract (try
                     (edn/read-string (slurp (str contract-file)))
                     (catch Exception cause
                       (fail! (str "cannot read docs/manual-contract.edn: "
                                   (.getMessage cause)))))
          documents (merge {:readme "README.md"
                            :manual "docs/manual.md"
                            :architecture "ARCHITECTURE.md"}
                           (:documents contract))
          document-paths (set (vals documents))
          sections (:sections contract)
          examples (:examples contract)]
      (doseq [[kind relative] documents]
        (when-not (fs/regular-file? (fs/path root relative))
          (fail! (str "missing " kind " document " relative))))
      (when (empty? sections)
        (fail! "manual-contract.edn has no sections; describe the maintained documents before completing the card"))
      (let [ids (map :id sections)]
        (when-not (= (count ids) (count (distinct ids)))
          (fail! "documentation section ids must be unique")))
      (doseq [[kind relative] documents]
        (when-not (some #(= relative (:document %)) sections)
          (fail! (str "no documentation section owns " kind " document " relative))))

      (let [readme (str (fs/path root (:readme documents)))]
        (doseq [[label path] [["manual" (:manual documents)]
                              ["architecture" (:architecture documents)]]]
          (when-not (str/includes? (slurp readme) path)
            (fail! (str "README.md does not link the " label " " path)))))

      (doseq [[kind relative] documents]
        (check-links! root (fs/path root relative)))

      (doseq [section sections]
        (let [id (:id section)
              document (:document section)
              heading (:heading section)
              apis (string-list (:apis section))
              section-examples (filter #(= id (:section %)) examples)]
          (when (str/blank? id)
            (fail! "every documentation section needs an :id"))
          (when (str/blank? document)
            (fail! (str "documentation section " id " needs a :document")))
          (when-not (contains? document-paths document)
            (fail! (str "documentation section " id " names unknown document "
                        document)))
          (when (str/blank? heading)
            (fail! (str "documentation section " id " needs a :heading")))
          (when (nil? apis)
            (fail! (str "documentation section " id " has a non-list :apis")))
          (when (and (empty? section-examples)
                     (not (:conceptual section)))
            (fail! (str "documentation section " id
                        " needs an executable example or :conceptual true")))
          (when (and (:conceptual section)
                     (str/blank? (:reason section)))
            (fail! (str "conceptual documentation section " id
                        " needs a :reason")))
          (let [file (fs/path root document)
                body (section-text (slurp (str file)) heading)]
            (doseq [api apis]
              (when-not (str/includes? body api)
                (fail! (str "documentation section " id
                            " does not name public API " api)))))))

      (let [ids (map :id examples)]
        (when-not (= (count ids) (count (distinct ids)))
          (fail! "documentation example ids must be unique")))
      (doseq [example examples]
        (check-example! root example sections))
      (println (str "documentation: " (count documents) " documents, "
                    (count sections) " sections and "
                    (count examples) " executable examples passed")))))

(apply -main *command-line-args*)
