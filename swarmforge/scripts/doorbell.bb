#!/usr/bin/env bb

(ns doorbell
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; The gap and the fill of a request nobody has answered: how long a delivered
;; request waits before the doorbell rings it again - a session that is away is
;; not battered - and how many rings it takes before the pass reports it as
;; still unanswered rather than ringing it forever.
(def gap-minutes 10)
(def fill 3)

(def usage-text
  (str "Ring a chat request that was written down but never reached the role.\n"
       "\n"
       "Usage:\n"
       "  doorbell.sh [<forge-root>] [--idler <path>]\n"
       "\n"
       "The dashboard writes the operator's message down, types it into the master\n"
       "role's pane and answers 200 — and when the typing fails, only its own stderr\n"
       "says so. The bridge counts the work done, and the idler check examines only\n"
       "roles holding cards, so a request that never arrived is invisible everywhere.\n"
       "The doorbell is that repair, and it is visible rather than hoped for.\n"
       "\n"
       "For every pending request it says one of three things: already delivered and\n"
       "left alone (the pane shows the request's own id, or an earlier pass saw it\n"
       "delivered); never delivered and rung again (the pane does not show the id and\n"
       "the role is not mid-turn); or not rung because the role is busy — injecting\n"
       "into a role mid-turn is exactly the case that loses a request.\n"
       "\n"
       "The ring carries what the request is: the command that answers it, and the\n"
       "gate it holds, when it holds one — an approval the operator forwarded is\n"
       "the operator's decision and a clarification the operator's answer. Printing\n"
       "what a ring would carry, without a pane to ring:\n"
       "\n"
       "  doorbell.sh print-ring <body>\n"
       "\n"
       "The ring goes in as one paste and then one Enter, and the pane is asked what\n"
       "it took: an empty composer is a ring that landed, and a ring the pane is\n"
       "still holding is reported as not landed rather than counted as delivered.\n"
       "\n"
       "It rings a request it has already rung only once the gap has passed, and\n"
       "only up to its fill: what it has seen, what it has rung, and when, live in\n"
       "<forge-root>/.swarmforge/doorbell.edn.\n"
       "\n"
       "Delivered is not answered. A request the dashboard still holds as pending\n"
       "is one nobody answered, whatever the pane can prove, so a delivered request\n"
       "is rung again once the gap has passed — a session that is away is not\n"
       "battered — and up to its fill, after which the pass reports it as still\n"
       "unanswered rather than ringing it forever. Each ring says which one it is.\n"
       "  gap: " gap-minutes " minutes   fill: " fill " rings\n"))

(defn exit! [status message]
  (binding [*out* *err*]
    (println message))
  (System/exit status))

(defn flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(defn rows [path]
  (when (fs/regular-file? path)
    (->> (str/split-lines (slurp (str path)))
         (remove str/blank?)
         (map #(str/split % #"\t" -1))
         vec)))

(defn forge-root [args]
  (let [given (first (remove #(str/starts-with? % "--") args))
        root (fs/absolutize (or given (str (fs/absolutize "."))))]
    (when-not (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
      (exit! 2 (str "Not a forge root (no .swarmforge/roles.tsv): " root)))
    root))

;; The master row is the role the dashboard types chat requests into: the one
;; whose own row says it is the master. The doorbell rings the same pane the
;; dashboard was supposed to.
(defn master-row [root]
  (some #(when (= "master" (nth % 1 nil)) %) (rows (fs/path root ".swarmforge" "roles.tsv"))))

(defn tmux-socket [root]
  (let [file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? file) (not-empty (str/trim (slurp (str file)))))))

(defn tmux [socket & args]
  (try
    (let [{:keys [out exit]} (apply process/sh (concat ["tmux" "-S" socket] args))]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn request-files [root]
  (let [dir (fs/path root ".swarmforge" "dashboard" "requests" "pending")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (fs/file-name %) ".request"))
           (sort-by fs/file-name)
           vec)
      [])))

;; A request file is header lines, a blank line, then the body — the shape the
;; dashboard writes and every reader of it takes apart.
(defn parse-request [file]
  (let [[headers body] (str/split (slurp (str file)) #"\n\n" 2)
        field (fn [name]
                (some (fn [line]
                        (when (str/starts-with? line (str name ": "))
                          (subs line (+ 2 (count name)))))
                      (str/split-lines headers)))]
    {:id (field "id")
     :created-at (field "created_at")
     :body (str/trim (or body ""))
     :file (str file)}))

(defn pending-requests [root]
  (->> (request-files root)
       (map parse-request)
       (remove #(str/blank? (:id %)))
       vec))

(defn ledger-path [root]
  (fs/path root ".swarmforge" "doorbell.edn"))

;; The ledger keeps the three outcomes apart, because they mean different
;; things: a request the pane proved is delivered, one this pass rang has been
;; delivered by the doorbell, and one that was only looked at - seen while the
;; role was mid-turn - is still owed. Recording a skip as delivery would let a
;; message the operator sent and that never arrived be written off silently,
;; which is the failure this tool exists to repair.
;;
;; It also keeps the rings themselves - how many, and when the last one was -
;; because delivery is not an answer: a request the dashboard still holds is
;; rung again once the gap has passed, up to the fill.
(defn ledger [root]
  (let [path (ledger-path root)
        stored (if (fs/regular-file? path)
                 (try (edn/read-string (slurp (str path))) (catch Exception _ {}))
                 {})]
    {:delivered (set (:delivered stored))
     :rung (set (:rung stored))
     :owed (set (:owed stored))
     :rings (into {} (for [[id ring] (:rings stored)]
                       [id {:count (or (:count ring) 0) :at (:at ring)}]))}))

(defn save-ledger! [root ledger]
  (let [path (ledger-path root)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str (into (sorted-map)
                                   {:delivered (vec (sort (:delivered ledger)))
                                    :rung (vec (sort (:rung ledger)))
                                    :owed (vec (sort (:owed ledger)))
                                    :rings (:rings ledger)})))))

(defn now [] (java.time.Instant/now))

;; When a request was last heard of: the doorbell's own last ring, or the moment
;; the dashboard wrote the request down, which is when it typed it. A time the
;; record does not carry is no reason to hold back - ringing is the repair
;; working - so anything unreadable counts as long past.
(defn past-gap? [at]
  (let [then (try (java.time.Instant/parse (str/trim (str at)))
                  (catch Exception _ nil))]
    (or (nil? then)
        (>= (.toMinutes (java.time.Duration/between then (now))) gap-minutes))))

;; The check that knows what "working" means for each tool is the idler check;
;; the doorbell asks it rather than repeating its rules, and treats anything but
;; a role mid-turn as a window to ring into.
(defn idler-check [root idler]
  (let [command (or idler (str (fs/path (fs/parent *file*) "role_health.sh")))]
    (try
      (let [{:keys [out]} (process/sh command root "--forge-root" root)]
        out)
      (catch Exception _ ""))))

(defn verdict-of [report role]
  (->> (str/split-lines (str report))
       (map #(str/split % #"\s+"))
       (filter #(and (seq %) (= role (first %))))
       first
       second))

(defn busy? [root role idler]
  (= "working" (verdict-of (idler-check root idler) role)))

;; The dashboard types the id in as [<id>], which is the only delivery evidence
;; there is. What counts is what the pane can still prove, not only what its
;; visible screen shows: a request delivered before the doorbell existed, or long
;; enough ago to have scrolled away, is still in the scrollback, and reading the
;; screen alone would ring it a second time. The bound stays where the evidence
;; does - a request the pane can no longer prove is not remembered forever, and
;; ringing it is the repair working.
(def scrollback-lines 2000)

(defn pane-screen [socket pane]
  (tmux socket "capture-pane" "-p" "-t" pane))

(defn pane-scrollback [socket pane]
  (tmux socket "capture-pane" "-p" "-S" (str "-" scrollback-lines) "-t" pane))

(defn holds-id? [text id]
  (boolean (and text (str/includes? text (str "[" id "]")))))

;; The evidence, named the way the pass reports it: what the pane still shows
;; says the most, then what its scrollback remembers, and the ledger last - it is
;; the fallback for a request the pane can no longer prove at all.
(defn delivered-evidence [id screen scrollback ledger]
  (cond
    (holds-id? screen id) "the screen"
    (holds-id? scrollback id) "the scrollback"
    (or (contains? (:rung ledger) id) (contains? (:delivered ledger) id)) "the ledger"
    :else nil))

;; The ring carries the answering command with it for the same reason the
;; dashboard's wake does: a request a pane holds without that command is a
;; request whose answer never reaches the operator's phone.
;;
;; It carries the gate for the same reason, and in the same words the
;; dashboard's wake uses: an approval notification the operator forwarded looks
;; like any other chat request, but it is a gate, and the gate is the operator's,
;; not the lieutenant's to take. The bridge writes the notification's first
;; line, so the shape is knowable rather than guessed at - and the ring is where
;; a session that never read its prompt, or read a stale copy in a pane that has
;; been up for days, meets that gate.
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
              " state, then reply with what you would answer and why. Do not answer"
              " the clarification request unless the operator says to."))))

(defn ordinal [n]
  (case (int n)
    1 "first"
    2 "second"
    3 "third"
    (str n "th")))

;; A request that has been rung before says so, so a pane that has seen the same
;; alert three times knows it is not new.
(defn ring-note [ring]
  (when (> ring 1)
    (str " This is the doorbell's " (ordinal ring) " ring of this request:"
         " the earlier one reached this pane and nothing acted on it.")))

;; What a pane has not submitted: the composer, the line its own cursor sits on.
;; A terminal draws the input it is holding where its cursor is, so the words at
;; the end of that line are the end of what it has not taken yet - and a pane
;; whose composer still holds the doorbell's own ring is a pane no session has
;; read, whatever the rest of its screen shows.
(def composer-window 16)

(defn squashed [text]
  (str/replace (or text "") #"\s+" ""))

(defn pane-cursor [socket pane]
  (some-> (tmux socket "display-message" "-p" "-t" pane "#{cursor_y}") str/trim parse-long))

(defn pane-composer [socket pane]
  (let [row (pane-cursor socket pane)
        lines (str/split-lines (or (pane-screen socket pane) ""))]
    (when (and row (<= 0 row) (< row (count lines)))
      (nth lines row))))

;; A composer is drawn with the pane's own mark beside the words, and a pane
;; wraps a long text where its width falls, so the end of what it holds is read
;; as the end of the typed words: a window of them wide enough to be the words
;; rather than the mark, and present whatever the pane wrapped around.
(defn composer-holds? [composer text]
  (let [line (squashed composer)
        text (squashed text)
        end (if (>= (count line) composer-window)
              (subs line (- (count line) composer-window))
              line)]
    (boolean (and (seq line) (seq text) (str/includes? text end)))))

(defn wake-text
  ([id body] (wake-text id body 1))
  ([id body ring]
   (str (if (str/includes? (or body "") "\n")
          (str "[" id "]\n" body)
          (str "[" id "] " body))
        "\n"
        (answer-reminder id body)
        (ring-note ring))))

;; Everything a pane could be holding for one request: what the dashboard types,
;; and every ring the doorbell has typed for it. A copy of any of them sitting in
;; the composer is input the pane has not taken, so it proves nothing arrived.
(defn typed-into-the-pane [id body rings]
  (into [(str "[" id "] " body)]
        (map #(wake-text id body %) (range 1 (inc rings)))))

;; The ring goes in as one paste and then one Enter. A request runs to lines -
;; the body's own, the answering command, the gate it holds - and a terminal that
;; is still taking that text swallows the Enter that follows it, which leaves the
;; whole ring in the composer with nothing having read it. One paste is what
;; keeps a body's own newlines from submitting the ring piecemeal, and one Enter
;; is what the pane takes as the one turn.
(def ring-buffer "swarmforge-doorbell")

(defn ring! [socket pane id body ring]
  (let [text (wake-text id body ring)]
    (tmux socket "set-buffer" "-b" ring-buffer text)
    (tmux socket "paste-buffer" "-d" "-p" "-b" ring-buffer "-t" pane)
    (tmux socket "send-keys" "-t" pane "C-m")
    text))

;; How long a pane is given to draw what it took before the doorbell judges it:
;; the paste and the Enter arrive a moment before the pane has drawn the turn.
(def ring-settle-ms 150)

;; Whether the ring landed: the composer it went into was read, and it is empty
;; again. A composer still holding the ring's own words is the Enter the pane
;; lost, and one that cannot be read at all is not proof that the pane took the
;; ring - either way the pass reports it rather than writing the request down as
;; delivered, because counting a ring no session has read is the failure this
;; tool exists to prevent.
(defn ring-landed? [socket pane text]
  (Thread/sleep ring-settle-ms)
  (let [composer (pane-composer socket pane)]
    (and (some? composer) (not (composer-holds? composer text)))))

;; The ring one request would be typed with, for a reader with no pane to ring:
;; the installer's self-check asks for this rather than standing up a session,
;; and reads back the words the ring carries. The id is the pass's own name for
;; the request it has not written down.
(defn print-ring [body]
  (println (wake-text "self-check" (or body ""))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (println usage-text)
    (System/exit 0))
  (when (= "print-ring" (first args))
    (print-ring (second args))
    (System/exit 0))
  (let [root (forge-root args)
        idler (flag-value args "--idler")
        row (master-row root)
        role (first row)
        pane (nth row 3 nil)
        socket (tmux-socket root)
        screen (when (and socket pane) (pane-screen socket pane))
        scrollback (when (and socket pane) (pane-scrollback socket pane))
        composer (when (and socket pane) (pane-composer socket pane))
        kept (atom (ledger root))
        requests (pending-requests root)]
    (println (str "doorbell: read the pane " (or pane "-") " of the role " (or role "-")
                  " for " (str root)))
    (if (empty? requests)
      (println "nothing pending: no chat request is waiting to be delivered")
      (doseq [{:keys [id body created-at]} requests]
        (let [quoted (str "\"" body "\"")
              rung (get (:rings @kept) id)
              rings (or (:count rung) 0)
              ;; What the pane could still be holding of this request: the
              ;; dashboard's own typing, and every ring the doorbell has typed.
              ;; A copy in the composer is input the pane has not taken, so it is
              ;; not evidence that anything was delivered - and a request the
              ;; pane is still holding a ring of was not rung at all.
              still-held (some #(when (composer-holds? composer %) %)
                               (typed-into-the-pane id body rings))
              proved (when-not still-held (delivered-evidence id screen scrollback @kept))
              ;; A request is due again once the gap has passed since it was last
              ;; heard of: the doorbell's own last ring, or the moment the
              ;; dashboard wrote it down.
              due (past-gap? (or (:at rung) created-at))]
          (cond
            ;; The pane is still holding a ring the doorbell typed: no session
            ;; has taken it, so the request is owed and the pass says so rather
            ;; than ringing the same text into the same composer again.
            (and (pos? rings) still-held)
            (do (swap! kept update :owed conj id)
                (swap! kept update :rung disj id)
                (println (str "the chat request " quoted " is still owed: the ring the doorbell"
                              " typed is still in the pane's composer, and no session has taken it")))

            ;; The dashboard still holds it, so nobody answered it, and the
            ;; pane's words are not an answer.
            (and proved (>= rings fill))
            (do (swap! kept update :owed conj id)
                (println (str "the chat request " quoted " is still unanswered: the doorbell has rung it its"
                              " fill of " fill " rings and no session has acted on it")))

            (and proved (not due))
            (do (when-not (contains? (:rung @kept) id) (swap! kept update :delivered conj id))
                (swap! kept update :owed (fnil disj #{}) id)
                (println (str "the chat request " quoted " was already delivered from " proved
                              " and left alone: nobody has answered it, and the doorbell rings it"
                              " again once the gap has passed")))

            (nil? pane)
            (do (swap! kept update :owed conj id)
                (println (str "the chat request " quoted " was not rung: the role " (or role "-")
                              " has no pane to ring")))

            (busy? root role idler)
            ;; Looked at, not delivered: the request stays owed, and a later
            ;; pass rings it once the role is free.
            (do (swap! kept update :owed conj id)
                (println (str "the chat request " quoted " was not rung because the role "
                              (or role "-") " was busy")))

            :else
            (let [text (ring! socket pane id body (inc rings))
                  landed (ring-landed? socket pane text)]
                (swap! kept update :rings assoc id {:count (inc rings) :at (str (now))})
                (if landed
                  (do (swap! kept update :rung conj id)
                      (swap! kept update :owed (fnil disj #{}) id)
                      (println (cond
                                 (pos? rings)
                                 (str "the chat request " quoted " was never answered and was rung again")
                                 proved
                                 (str "the chat request " quoted " was never answered and rung into " pane)
                                 :else
                                 (str "the chat request " quoted " was never delivered and rung into " pane))))
                  ;; The Enter was lost and the ring is still in the composer:
                  ;; the request is owed, not delivered, and the next reader is
                  ;; told so rather than left to read the pane.
                  (do (swap! kept update :owed conj id)
                      (swap! kept update :rung disj id)
                      (println (str "the chat request " quoted " was rung and the ring did not land:"
                                    " the pane is still holding it, so no session has seen it")))))))))
    (save-ledger! root @kept)
    (System/exit 0)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
