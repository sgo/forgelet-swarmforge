#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_commit="${SWARMFORGE_ACCEPTANCE_SOURCE_COMMIT:-$(git -C "$root" rev-parse HEAD)}"

[[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo "runtime identity acceptance: source commit must be 40 lowercase hex characters" >&2
  exit 2
}
git -C "$root" cat-file -e "$source_commit^{commit}" 2>/dev/null || {
  echo "runtime identity acceptance: source commit is not present in $root" >&2
  exit 2
}

mkdir -p "$root/tmp"
work="$(mktemp -d "$root/tmp/runtime-identity-acceptance.XXXXXX")"
cleanup() {
  local code=$?
  rm -rf "$work"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

source_repo="$work/source"
target="$work/target"
home="$work/home"
config="$work/config"
packs="$work/packs"
pack="$packs/lieutenant"
fake_bin="$work/bin"
curl_log="$work/curl.log"

mkdir -p "$target" "$home" "$config" "$fake_bin" \
  "$pack/swarmforge/roles" "$work/tmp"

# The source is a local clone. Both the layer and product branches point at the
# one explicit commit, so product selection cannot consult a mutable remote.
git clone -q --no-checkout "$root" "$source_repo"
git -C "$source_repo" checkout -q -B forgelet "$source_commit"
git -C "$source_repo" branch -f lieutenant "$source_commit"

cat >"$pack/swarm" <<'EOF'
#!/bin/sh
exit 0
EOF
cat >"$pack/swarmforge/swarmforge.conf" <<'EOF'
window coder codex master
EOF
printf 'Fixture coder.\n' >"$pack/swarmforge/roles/coder.prompt"
chmod +x "$pack/swarm"

# A failed product archive fetch proves that the local exact-commit fallback is
# used and records every URL the composer attempted.
cat >"$fake_bin/curl" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$SWARMFORGE_ACCEPTANCE_CURL_LOG"
exit 7
EOF
chmod +x "$fake_bin/curl"

if [[ -n "${SWARMFORGE_ACCEPTANCE_TEST_HOLD_SECONDS:-}" ]]; then
  : >"$work/held"
  remaining="$((SWARMFORGE_ACCEPTANCE_TEST_HOLD_SECONDS * 10))"
  while (( remaining > 0 )); do
    sleep 0.1
    remaining=$((remaining - 1))
  done
fi

(
  cd "$target"
  env -i \
    HOME="$home" \
    XDG_CONFIG_HOME="$config" \
    TMPDIR="$work/tmp" \
    PATH="$fake_bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin" \
    SWARMFORGE_ACCEPTANCE_CURL_LOG="$curl_log" \
    SWARMFORGE_GIT_DIR="$source_repo" \
    SWARMFORGE_SOURCE_COMMIT="$source_commit" \
    SWARMFORGE_REPO_URL="http://127.0.0.1:9" \
    SWARMFORGE_PACKS_DIR="$packs" \
    SWARMFORGE_SKIP_BRIDGE=1 \
    SWARMFORGE_NO_SELF_UPDATE=1 \
    SWARMFORGE_TERMINAL=none \
    FORGELET_HOST_TOKEN_FILE="$work/poison/host-token" \
    FORGELET_HOST_URL="http://127.0.0.1:9" \
    MATRIX_BRIDGE_CONFIG="$work/poison/matrix-bridge.json" \
    MATRIX_HOMESERVER_DIR="$work/poison/matrix-homeserver" \
    "$root/get-swarm-forge" lieutenant
)

manifest="$target/.swarmforge/runtime-manifest.edn"
[[ -f "$manifest" ]] || {
  echo "runtime identity acceptance: runtime manifest was not written" >&2
  exit 1
}
[[ -f "$curl_log" ]] || {
  echo "runtime identity acceptance: product source fetch was not observed" >&2
  exit 1
}
[[ "$(wc -l <"$curl_log" | tr -d ' ')" == "1" ]] || {
  echo "runtime identity acceptance: unexpected extra network fetch" >&2
  exit 1
}
[[ "$(cat "$curl_log")" == *"http://127.0.0.1:9/archive/refs/heads/lieutenant.tar.gz"* ]] || {
  echo "runtime identity acceptance: source selection left the fixture origin" >&2
  exit 1
}

bb -e '
(require (quote [babashka.fs :as fs])
         (quote [clojure.edn :as edn])
         (quote [clojure.string :as str]))
(import (quote java.security.MessageDigest))

(defn hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn sha [path]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (fs/read-all-bytes path))))

(let [[commit target source manifest-path] *command-line-args*
      manifest (edn/read-string (slurp manifest-path))
      files (:files manifest)
      digest-text (str/join "\n" (map (fn [[path hash]]
                                       (str path "\t" hash))
                                     files))
      digest (str "sha256:" (hex (.digest
                                  (MessageDigest/getInstance "SHA-256")
                                  (.getBytes digest-text
                                             java.nio.charset.StandardCharsets/UTF_8))))
      policy (:bridge_policy manifest)]
  (when-not (= commit (:source_commit manifest))
    (throw (ex-info "manifest source commit does not match" {})))
  (when-not (= digest (:digest manifest))
    (throw (ex-info "manifest digest does not match its file list" {})))
  (when-not (= "preserve-installed-bridge-v1" (:id policy))
    (throw (ex-info "bridge policy evidence is missing" {})))
  (when-not (:skip_build_and_install policy)
    (throw (ex-info "bridge installation was not skipped" {})))
  (doseq [[relative expected] files]
    (let [installed (fs/path target relative)
          sourced (fs/path source relative)]
      (when-not (fs/regular-file? installed)
        (throw (ex-info (str "installed runtime file is missing: " relative) {})))
      (when-not (fs/regular-file? sourced)
        (throw (ex-info (str "source runtime file is missing: " relative) {})))
      (when-not (= expected (sha installed))
        (throw (ex-info (str "installed hash differs: " relative) {})))
      (when-not (= (sha sourced) (sha installed))
        (throw (ex-info (str "installed bytes differ from source: " relative) {})))))
  (println (str "runtime identity digest: " (:digest manifest)))
  (println (str "runtime files verified: " (count files))))
' "$source_commit" "$target" "$source_repo" "$manifest"

[[ ! -e "$target/projects/forgelet-bridge" ]] || {
  echo "runtime identity acceptance: bridge installation was not skipped" >&2
  exit 1
}

echo "PASS runtime identity acceptance: exact source, digest, files and bridge policy verified"
echo "PASS isolation: local source only, no Host API and no live Matrix boundary"
