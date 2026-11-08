(ns swarmforge.forge-request-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def helper (fs/path repo-root "swarmforge" "scripts" "forge_request.bb"))
(def route-helper (fs/path repo-root "swarmforge" "scripts" "route_card.bb"))

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
           "--forge-root" (str forge) "--operator-said" "yes")
          proposal-id (second (re-find #"PROPOSED:\s+(\S+)" (:out proposed)))]
      (is (zero? (:exit proposed)) (str (:err proposed) (:out proposed)))
      (is proposal-id)
      (let [committed
            (process/shell
             env "bb" (str route-helper)
             "commit" proposal-id "--forge-root" (str forge))]
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
