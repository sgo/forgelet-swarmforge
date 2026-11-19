(ns swarmforge.forge-request-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [forge-request :as request]))

(def repo-root (fs/cwd))
(def helper (fs/path repo-root "swarmforge" "scripts" "forge_request.bb"))
(def route-helper (fs/path repo-root "swarmforge" "scripts" "route_card.bb"))
(def required-headings
  ["Need" "Contract" "Boundaries" "Testing" "Out of scope" "Consumers waiting"])

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run! [dir env & args]
  (apply process/shell
         {:dir (str dir)
          :continue true
          :out :string
          :err :string
          :env (merge {"PATH" (System/getenv "PATH")
                       "HOME" (System/getenv "HOME")}
                      env)}
         (concat ["bb" (str helper)] args)))

(defn init-git! [root]
  (sh/sh "git" "init" "-q" :dir (str root))
  (sh/sh "git" "remote" "add" "origin"
         "https://github.com/sgo/forgelet-forge.git" :dir (str root)))

(defn forge! []
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-forge-request."})]
    (write-file (fs/path root ".swarmforge/roles.tsv")
                "lieutenant\tmaster\tforge\tswarmforge-lieutenant\tLieutenant\tcodex\ttask\n")
    (fs/create-dirs (fs/path root "projects"))
    (write-file (fs/path root "forge-requesters") "sgo\n")
    (init-git! root)
    root))

(defn project! [forge name]
  (let [root (fs/path forge "projects" name)]
    (write-file (fs/path root ".swarmforge/single-agent") "agent\n")
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "agent\tmaster\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                        root))
    root))

(def body
  (str "### Need\n\nConsumers need the thing.\n\n"
       "### Contract\n\nA stable public contract.\n\n"
       "### Boundaries\n\nOnly the owner changes it.\n\n"
       "### Testing\n\nUse an in-memory fake.\n\n"
       "### Out of scope\n\nnone\n\n"
       "### Consumers waiting\n\nConsumer app.\n"))

(defn issue [author issue-body]
  {:author {:login author}
   :body issue-body
   :number 7
   :repository {:nameWithOwner "sgo/forgelet-forge"}
   :title "contract-lib: provide the thing"
   :url "https://github.com/sgo/forgelet-forge/issues/7"})

(defn fake-gh! [root issue-data]
  (let [json-file (fs/path root "issue.json")
        gh (fs/path root "fake-gh")]
    (write-file json-file (json/generate-string issue-data))
    (write-file gh "#!/bin/sh\ncat \"$SWARMFORGE_TEST_ISSUE_JSON\"\n")
    (fs/set-posix-file-permissions gh "rwxr-xr-x")
    {:command (str gh) :json (str json-file)}))

(defn intake! [forge fake]
  (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
               "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
        "intake" "https://github.com/sgo/forgelet-forge/issues/7"
        "--forge-root" (str forge)))

(defn render! [root title sections output]
  (run! root {} "render" "--title" title
        "--sections" (str sections) "--output" (str output)))

(defn validate-body! [root file]
  (run! root {} "validate-body" (str file)))

(defn validate-issue! [root fake url & [title]]
  (apply run! root
         {"SWARMFORGE_GH_COMMAND" (:command fake)
          "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
         "validate-issue" url
         (cond-> [] title (into ["--title" title]))))

(def six-sections
  {"Need" "Consumer app cannot request this today."
   "Contract" (str "A stable request surface.\n\n"
                   "```markdown\n### Need\nThis fenced example is not a heading.\n```\n\n"
                   "#### Details\n\nFixed: JSON output.")
   "Boundaries" "Only the receiver changes its project."
   "Testing" "Use an in-memory fake."
   "Out of scope" "none"
   "Consumers waiting" "consumer-app in the requesting forge."})

(defn request-id [result]
  (second (re-find #"PENDING-APPROVAL:(\S+)" (:out result))))

(defn record [forge id]
  (clojure.edn/read-string
   (slurp (str (fs/path forge ".swarmforge/forge-requests" (str id ".edn"))))))

(defn route-card! [forge project card-name]
  (let [note (fs/path project "card-note.md")
        env {:dir (str forge)
             :continue true
             :out :string
             :err :string
             :env (merge {"PATH" (System/getenv "PATH")
                          "HOME" (System/getenv "HOME")}
                         {})}]
    (write-file note (str "# " card-name "\n\nLocal routed work.\n"))
    (let [proposed
          (process/shell
           env "bb" (str route-helper)
           "propose" (str project) card-name (str note)
           "--forge-root" (str forge))
          proposal-id (second (re-find #"PROPOSED:\s+(\S+)" (:out proposed)))]
      (is (zero? (:exit proposed)) (str (:err proposed) (:out proposed)))
      (is proposal-id)
      (let [committed
            (process/shell
             env "bb" (str route-helper)
             "commit" proposal-id "--operator-said" (str "approve " proposal-id)
             "--forge-root" (str forge))]
        (is (zero? (:exit committed)) (str (:err committed) (:out committed)))
        proposal-id))))

(deftest intake-accepts-listed-requesters-and-refuses-unlisted-authors
  (let [forge (forge!)
        allowed (fake-gh! forge (issue "sgo" body))
        accepted (intake! forge allowed)]
    (try
      (is (zero? (:exit accepted)) (:err accepted))
      (is (str/starts-with? (request-id accepted) "forge-request-"))
      (let [unlisted (fake-gh! forge (issue "mallory" body))
            refused (intake! forge unlisted)]
        (is (not (zero? (:exit refused))))
        (is (str/includes? (str (:err refused) (:out refused))
                           "not listed")))
      (finally
        (fs/delete-tree forge)))))

(deftest approval-requires-the-local-current-digest-and-is-single-use
  (let [forge (forge!)
        fake (fake-gh! forge (issue "sgo" body))
        intake (intake! forge fake)
        id (request-id intake)]
    (try
      (is (zero? (:exit intake)) (:err intake))
      (let [changed (fake-gh! forge (issue "sgo" (str body "\nChanged.\n")))
            approval (run! forge {"SWARMFORGE_GH_COMMAND" (:command changed)
                                  "SWARMFORGE_TEST_ISSUE_JSON" (:json changed)}
                        "approve" id "--forge-root" (str forge)
                        "--operator-said" "approve this local request")]
        (is (not (zero? (:exit approval))))
        (is (str/includes? (str (:err approval) (:out approval)) "changed after intake"))
        (is (= "pending-approval" (:status (record forge id)))))
      (let [stable (fake-gh! forge (issue "sgo" body))
            approved (run! forge {"SWARMFORGE_GH_COMMAND" (:command stable)
                                  "SWARMFORGE_TEST_ISSUE_JSON" (:json stable)}
                          "approve" id "--forge-root" (str forge)
                          "--operator-said" "approve this local request")
            again (run! forge {"SWARMFORGE_GH_COMMAND" (:command stable)
                               "SWARMFORGE_TEST_ISSUE_JSON" (:json stable)}
                       "approve" id "--forge-root" (str forge)
                       "--operator-said" "approve again")]
        (is (zero? (:exit approved)) (:err approved))
        (is (= "approved" (:status (record forge id))))
        (is (= "approve this local request"
               (:approved-words (record forge id))))
        (is (= (:digest (record forge id))
               (:approved-digest (record forge id))))
        (is (not (zero? (:exit again))))
        (is (str/includes? (str (:err again) (:out again)) "single-use")))
      (finally
        (fs/delete-tree forge)))))

(deftest approval-belongs-to-one-forge-root
  (let [first-forge (forge!)
        second-forge (forge!)
        fake (fake-gh! first-forge (issue "sgo" body))
        intake (intake! first-forge fake)
        id (request-id intake)]
    (try
      (is (zero? (:exit intake)) (:err intake))
      (let [wrong-root
            (run! first-forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                               "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "approve" id "--forge-root" (str second-forge)
                  "--operator-said" "approve")]
        (is (not (zero? (:exit wrong-root))))
        (is (str/includes? (str (:err wrong-root) (:out wrong-root))
                           "No local forge request")))
      (finally
        (fs/delete-tree first-forge)
        (fs/delete-tree second-forge)))))

(deftest the-same-issue-is-independent-in-two-receiving-forges
  (let [first-forge (forge!)
        second-forge (forge!)
        first-fake (fake-gh! first-forge (issue "sgo" body))
        second-fake (fake-gh! second-forge (issue "sgo" body))]
    (try
      (let [first-intake (intake! first-forge first-fake)
            second-intake (intake! second-forge second-fake)
            first-id (request-id first-intake)
            second-id (request-id second-intake)]
        (is (zero? (:exit first-intake)) (:err first-intake))
        (is (zero? (:exit second-intake)) (:err second-intake))
        (is (= first-id second-id))
        (is (fs/regular-file? (fs/path first-forge ".swarmforge/forge-requests"
                                      (str first-id ".edn"))))
        (is (fs/regular-file? (fs/path second-forge ".swarmforge/forge-requests"
                                      (str second-id ".edn")))))
      (finally
        (fs/delete-tree first-forge)
        (fs/delete-tree second-forge)))))

(deftest approval-proposal-card-linkage-is-the-execution-gate
  (let [forge (forge!)
        project (project! forge "consumer")
        fake (fake-gh! forge (issue "sgo" body))
        intake (intake! forge fake)
        id (request-id intake)
        board (fs/path project ".swarmforge/board/tasks.tsv")]
    (try
      (is (zero? (:exit intake)) (:err intake))
      (is (not (fs/exists? board))
          "intake must not create a card")
      (is (not (fs/exists? (fs/path forge ".swarmforge/open-projects")))
          "intake must not open anything")
      (let [pending-guard
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "guard" id "--forge-root" (str forge)
                  "--project-root" (str project) "--card" "consumer-work")]
        (is (not (zero? (:exit pending-guard))))
        (is (str/includes? (str (:err pending-guard) (:out pending-guard))
                           "pending-approval")))
      (let [approved
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "approve" id "--forge-root" (str forge)
                  "--operator-said" "approve this local request")]
        (is (zero? (:exit approved)) (:err approved)))
      (is (not (fs/exists? board)) "approval must not create a card")
      (let [proposal-id (route-card! forge project "consumer-work")]
        (is (str/starts-with? proposal-id "proposal-"))
        (is (fs/regular-file? board))
        (let [bound
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "bind" id "--forge-root" (str forge)
                  "--project-root" (str project)
                  "--proposal" proposal-id
                  "--card" "consumer-work")
            guarded
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "guard" id "--forge-root" (str forge)
                  "--project-root" (str project)
                  "--card" "consumer-work")]
        (is (zero? (:exit bound)) (str (:err bound) (:out bound)))
        (is (str/includes? (:out bound) "READY:"))
        (is (zero? (:exit guarded)) (str (:err guarded) (:out guarded)))
        (is (str/includes? (:out guarded) "AUTHORIZED:"))))
      (is (= "ready" (:status (record forge id))))
      (let [edited (fake-gh! forge (issue "sgo" (str body "\nPost-approval edit.\n")))
            refused
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command edited)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json edited)}
                  "guard" id "--forge-root" (str forge)
                  "--project-root" (str project)
                  "--card" "consumer-work")]
        (is (not (zero? (:exit refused))))
        (is (str/includes? (str (:err refused) (:out refused))
                           "changed after local approval")))
      (finally
        (fs/delete-tree forge)))))

(deftest missing-targets-and-card-less-binding-fail-closed
  (let [forge (forge!)
        project (project! forge "consumer")
        fake (fake-gh! forge (issue "sgo" body))
        intake (intake! forge fake)
        id (request-id intake)]
    (try
      (is (zero? (:exit intake)) (:err intake))
      (let [approved
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "approve" id "--forge-root" (str forge)
                  "--operator-said" "approve")]
        (is (zero? (:exit approved)) (:err approved)))
      (let [no-card
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "link-card" id "--forge-root" (str forge)
                  "--card" "consumer-work")]
        (is (not (zero? (:exit no-card))))
        (is (str/includes? (str (:err no-card) (:out no-card))
                           "Bind the request")))
      (fs/delete-tree project)
      (let [missing-target
            (run! forge {"SWARMFORGE_GH_COMMAND" (:command fake)
                         "SWARMFORGE_TEST_ISSUE_JSON" (:json fake)}
                  "bind" id "--forge-root" (str forge)
                  "--project-root" (str project)
                  "--proposal" "proposal-20261108T180000Z")]
        (is (not (zero? (:exit missing-target)))))
      (finally
        (fs/delete-tree forge)))))

(deftest request-template-and-renderer-use-one-exact-heading-contract
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-forge-render."})
        template (run! root {} "template")
        sections (fs/path root "sections.json")
        output (fs/path root "body.md")]
    (try
      (write-file sections (json/generate-string six-sections))
      (let [rendered (render! root "contract-lib: provide requests" sections output)
            body (slurp (str output))
            headings (->> (request/headings-in-body body)
                          (filter #(= 3 (:level %)))
                          (mapv #(str "### " (:title %))))]
        (is (zero? (:exit template)) (:err template))
        (is (= (mapv #(str "### " %) required-headings)
               (->> (str/split-lines (:out template))
                    (filter #(str/starts-with? % "### ")))))
        (is (zero? (:exit rendered)) (str (:err rendered) (:out rendered)))
        (is (= (mapv #(str "### " %) required-headings) headings))
        (is (= 6 (count headings)))
        (is (str/includes? body "### Need\nThis fenced example is not a heading."))
        (is (str/includes? body "#### Details\n\nFixed: JSON output.")))
      (finally
        (fs/delete-tree root)))))

(defn invalid-body-fixture! [root rendered]
  (let [variants
        {"level-two" (str/replace rendered #"(?m)^### Need$" "## Need")
         "missing" (str/replace rendered #"(?ms)^### Out of scope\n.*?(?=^### )" "")
         "duplicate" (str rendered "\n### Contract\n\nDuplicate.\n")
         "out-of-order" (str/replace rendered #"(?ms)^### Need\n\n(.*?)\n\n### Contract\n\n(.*?)\n\n### Boundaries"
                                    "### Contract\n\n$2\n\n### Need\n\n$1\n\n### Boundaries")
         "empty" (str/replace rendered #"(?ms)(^### Need\n\n).*?(?=^### Contract)"
                                "$1")}]
    (into {}
          (map (fn [[name body]]
                 (let [file (fs/path root (str name ".md"))]
                   (write-file file body)
                   [name file])))
          variants)))

(deftest malformed-request-bodies-are-refused-before-filing
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-forge-invalid."})
        sections (fs/path root "sections.json")
        output (fs/path root "body.md")]
    (try
      (write-file sections (json/generate-string six-sections))
      (is (zero? (:exit (render! root "contract-lib: provide requests"
                                sections output))))
      (let [rendered (slurp (str output))
            invalid (invalid-body-fixture! root rendered)]
        (doseq [[name file] invalid]
          (let [checked (validate-body! root file)
                diagnostic (str (:err checked) (:out checked))]
            (is (not (zero? (:exit checked))) name)
            (is (< (count diagnostic) 1000) name)
            (is (str/includes? diagnostic "Invalid cross-forge request body")
                name)))
        (let [checked (validate-body! root output)]
          (is (zero? (:exit checked)) (str (:err checked) (:out checked)))
          (is (= "valid" (:status (json/parse-string (:out checked) true))))))
      (finally
        (fs/delete-tree root)))))

(deftest receiving-intake-accepts-only-the-rendered-contract
  (let [forge (forge!)
        root (fs/path forge "draft")
        sections (fs/path root "sections.json")
        output (fs/path root "body.md")]
    (try
      (write-file sections (json/generate-string six-sections))
      (is (zero? (:exit (render! root "contract-lib: provide requests"
                                sections output))))
      (let [rendered (slurp (str output))
            accepted (fake-gh! forge (issue "sgo" rendered))
            accepted-intake (intake! forge accepted)]
        (is (zero? (:exit accepted-intake))
            (str (:err accepted-intake) (:out accepted-intake)))
        (let [invalid (str/replace rendered #"(?m)^### Contract$" "## Contract")
              refused (fake-gh! forge (issue "sgo" invalid))
              refused-intake (intake! forge refused)]
          (is (not (zero? (:exit refused-intake))))
          (is (str/includes? (str (:err refused-intake) (:out refused-intake))
                             "wrong heading depth for Contract"))))
      (finally
        (fs/delete-tree forge)))))

(deftest fetched-issue-validation-round-trips-and-detects-heading-drift
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-forge-fetch."})
        forge (forge!)
        sections (fs/path root "sections.json")
        output (fs/path root "body.md")
        url "https://github.com/sgo/forgelet-forge/issues/7"
        title "contract-lib: provide the thing"]
    (try
      (write-file sections (json/generate-string six-sections))
      (is (zero? (:exit (render! root title sections output))))
      (let [rendered (slurp (str output))
            live (fake-gh! root (issue "sgo" rendered))
            valid (validate-issue! root live url title)]
        (is (zero? (:exit valid)) (str (:err valid) (:out valid)))
        (is (= "valid" (:status (json/parse-string (:out valid) true))))
        (let [changed-body (str/replace rendered
                                        #"(?m)^### Consumers waiting$"
                                        "## Consumers waiting")
              changed (fake-gh! root (issue "sgo" changed-body))
              fetched (validate-issue! root changed url title)
              local (let [file (fs/path root "changed.md")]
                      (write-file file changed-body)
                      (validate-body! root file))]
          (is (not (zero? (:exit fetched))))
          (is (str/includes? (str (:err fetched) (:out fetched))
                             "wrong heading depth for Consumers waiting"))
          (is (not (zero? (:exit local))))
          (is (str/includes? (str (:err local) (:out local))
                             "wrong heading depth for Consumers waiting"))))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree forge)))))
