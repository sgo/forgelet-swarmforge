#!/usr/bin/env zsh

terminal_backend_label() {
  echo "Kitty"
}

kitty_remote_control() {
  local address=""
  if [[ -n "${KITTY_LISTEN_ON:-}" ]]; then
    address="$KITTY_LISTEN_ON"
    # Kitty sometimes reports a listen_on template (e.g. unix:/tmp/kitty.sock)
    # while the live socket is PID-suffixed. Fall back to the suffixed path
    # if the literal one from the env var isn't actually there.
    if [[ "$address" == unix:* && -n "${KITTY_PID:-}" ]]; then
      local socket_path="${address#unix:}"
      if [[ ! -S "$socket_path" && -S "${socket_path}-${KITTY_PID}" ]]; then
        address="unix:${socket_path}-${KITTY_PID}"
      fi
    fi
  fi
  # Kitty only exports KITTY_LISTEN_ON and KITTY_PID to processes it spawned.
  # A swarm launched from Terminal.app, an ssh session, or a launcher inherits
  # SWARMFORGE_TERMINAL=kitty from the shell profile without those variables,
  # so find a live socket on disk instead. Newest first, since a socket file
  # can outlive the kitty that created it.
  if [[ -z "$address" ]]; then
    address="$(kitty_discover_address)"
  fi
  if [[ -n "$address" ]]; then
    kitten @ --to "$address" "$@"
  else
    kitten @ "$@"
  fi
}

kitty_discover_address() {
  local candidate
  for candidate in $(command ls -1dt /tmp/kitty.sock*(N) 2>/dev/null); do
    [[ -S "$candidate" ]] || continue
    if kitten @ --to "unix:${candidate}" ls >/dev/null 2>&1; then
      print -- "unix:${candidate}"
      return 0
    fi
  done
  return 1
}

terminal_backend_can_open_sessions() {
  if kitty_remote_control ls >/dev/null 2>&1; then
    return 0
  fi

  print -u2 "Kitty remote control is unavailable. Run 'kitten @ ls' in this Kitty shell to diagnose it, then set 'allow_remote_control yes' in kitty.conf."
  return 1
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_window_exists() {
  local window_id="$1"
  [[ -n "$window_id" ]] || return 1

  # Only an answer that the window is not there may count as missing - a
  # question that gets no answer may not, because `kitten @ ls --match` exits
  # non-zero both when nothing matches and when kitty cannot be asked at all,
  # and the window watchdog reads three misses in a row as "the cleanup
  # owner's surface is gone" and ends the project. On 2026-09-27 that
  # conflation ended live swarms three times - 07:39, 10:35, 12:18 - with
  # every window still there: kitty's process had not restarted since 19
  # September, its whole window list came back empty for a minute or two, and
  # then answered again with the same ids. So ask for the window list first;
  # if that question fails, or kitty answers with no windows at all, say the
  # window is still there and say why on stderr, which the watchdog's own log
  # keeps.
  # How long the question took is part of the answer: a kitty that is slow is a
  # loaded machine, and one that never answers is something else. Every failure
  # says which it was, so the next occurrence names itself instead of looking
  # like a window that went away.
  zmodload zsh/datetime 2>/dev/null
  local listing started
  local -F elapsed
  started=$EPOCHREALTIME
  if ! listing="$(kitty_remote_control ls 2>/dev/null)"; then
    elapsed=$(( EPOCHREALTIME - started ))
    print -u2 "kitty did not answer after $(printf '%.1f' "$elapsed")s; window ${window_id} cannot be called missing"
    return 0
  fi
  if [[ -z "$listing" ]]; then
    elapsed=$(( EPOCHREALTIME - started ))
    print -u2 "kitty answered with an empty window list after $(printf '%.1f' "$elapsed")s; window ${window_id} cannot be called missing"
    return 0
  fi

  kitty_remote_control ls --match "id:$window_id" >/dev/null 2>&1
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"
  local placement_args=()
  local window_id=""
  local theme_file=""

  if [[ -n "$sibling_id" ]]; then
    # Split next to an existing panel from this same batch; kitty resolves
    # --next-to by window id, so this lands in whichever OS window that
    # sibling opened in rather than wherever the caller's focus is.
    placement_args=(--type=window --location=split --next-to "id:$sibling_id")
  else
    # First panel of a batch: open a brand-new OS window instead of
    # splitting into whatever kitty window currently has focus.
    placement_args=(--type=os-window)
  fi

  # Per-project theme: if <project>/.swarmforge/kitty-theme.conf exists, apply
  # it to this window after launch. Kitty colors are per-window, so each panel
  # gets the theme without changing the rest of the kitty instance.
  if [[ -n "${WORKING_DIR:-}" && -f "$WORKING_DIR/.swarmforge/kitty-theme.conf" ]]; then
    theme_file="$WORKING_DIR/.swarmforge/kitty-theme.conf"
  fi

  window_id="$(kitty_remote_control launch \
    "${placement_args[@]}" \
    --cwd "$WORKING_DIR" \
    --title "$title" \
    tmux -S "$TMUX_SOCKET" attach-session -t "$session")"

  if [[ -n "$window_id" && -n "$theme_file" ]]; then
    kitty_remote_control set-colors --match "id:$window_id" "$theme_file" >/dev/null 2>&1 || true
  fi

  print -- "$window_id"
}

terminal_close_window() {
  local window_id="$1"
  [[ -n "$window_id" ]] || return 0

  kitty_remote_control close-window --match "id:$window_id" >/dev/null 2>&1 || true
}
