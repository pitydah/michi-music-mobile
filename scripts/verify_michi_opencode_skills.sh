#!/usr/bin/env bash
# Chris Banes/OpenCode skill use inventory & receipt checks. Original session log review is separate.
set -Eeuo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
receipt="${MICHI_SKILL_RECEIPT:-$repo/build/verification/michi-audio/skills-used.tsv}"
usage() { cat <<'HELP'
Usage: bash scripts/verify_michi_opencode_skills.sh --inventory|--check
--inventory: list local SKILL.md paths and show whether OpenCode plugin is configured;
             does NOT claim a plugin was loaded or a skill was invoked.
--check: require exact Chris Banes skill IDs and LOADED rows with non-placeholder
         OpenCode session/log references. This is declaration validation ONLY.
         Reviewer MUST corroborate each row against actual OpenCode tool logs.
For this cumulative R15 audio + UI patch, nine skills are required; others are
conditional by the changed scope. Never fabricate receipts or tool calls.
HELP
}
required=(
  'bootstrap:using-chrisbanes-skills'
  'plan:to-plan'
  'implement:deliver-spec'
  'concurrency:kotlin-concurrency-and-flow'
  'api:kotlin-api-design'
  'ui-state:compose-state-and-effects'
  'ui-component:compose-component-design'
  'ui-test:compose-ui-testing-patterns'
  'gradle:gradle-run'
)
roots=(
  "$repo/.opencode/skills" "$repo/.claude/skills" "$repo/.agents/skills"
  "$HOME/.config/opencode/skills" "$HOME/.claude/skills" "$HOME/.agents/skills"
  # chrisbanes/skills installs here by default; without it the inventory reported
  # NATIVE_SKILL_TOOL_REQUIRED even when all nine skills were present.
  "$HOME/.local/share/chrisbanes-skills/skills"
)
find_skill() {
  local skill="$1" root
  for root in "${roots[@]}"; do
    if [[ -f "$root/$skill/SKILL.md" ]]; then
      printf '%s\n' "$root/$skill/SKILL.md"
      return 0
    fi
  done
  return 1
}
plugin_configured() {
  local file
  for file in "$repo/opencode.json" "$repo/opencode.jsonc" \
              "$HOME/.config/opencode/opencode.json" "$HOME/.config/opencode/opencode.jsonc"; do
    [[ -f "$file" ]] || continue
    if grep -Fq 'chrisbanes-skills@git+https://github.com/chrisbanes/skills.git' "$file"; then
      return 0
    fi
  done
  return 1
}
mode="${1:---inventory}"
case "$mode" in
  --help|-h) usage; exit 0 ;;
  --inventory)
    printf 'SKILL_ID\tLOCAL_SKILL_PATH\n'
    for root in "${roots[@]}"; do
      [[ -d "$root" ]] || continue
      for file in "$root"/*/SKILL.md; do
        [[ -f "$file" ]] || continue
        printf '%s\t%s\n' "$(basename "$(dirname "$file")")" "$file"
      done
    done
    if plugin_configured; then
      echo 'PLUGIN_CONFIGURED: chrisbanes/skills (NOT proof of runtime skill registration)'
    else
      echo 'PLUGIN_CONFIG_UNKNOWN: ask OpenCode native skill tool; plugin can be global or loaded elsewhere'
    fi
    printf 'REQUIRED_R15: %s\n' "${required[*]}"
    ;;
  --check)
    if [[ ! -f "$receipt" ]]; then
      printf 'BLOCKED_SKILL_EVIDENCE: missing receipt %s\n' "$receipt" >&2
      exit 5
    fi
    printf 'phase\tskill_id\tdiscovery\treceipt\n'
    for entry in "${required[@]}"; do
      phase="${entry%%:*}" skill="${entry#*:}"
      if loc="$(find_skill "$skill")"; then
        discovery="LOCAL:$loc"
      elif plugin_configured; then
        discovery='PLUGIN_CONFIGURED_UNVERIFIED'
      else
        discovery='NATIVE_SKILL_TOOL_REQUIRED'
      fi
      # Evidence must name the native skill invocation/session; a template is not evidence.
      if ! awk -F '\t' -v p="$phase" -v s="$skill" '
        NF==4 && $1==p && $2==s && $3=="LOADED" && length($4)>=12 &&
        $4 !~ /(placeholder|example|template|your-|opencode-session-id-or-tool-log-reference)/ &&
        $4 ~ /^(opencode-session:|opencode-log:|session:|log:)/ {ok=1}
        END {exit !ok}' "$receipt"; then
        printf 'BLOCKED_SKILL_EVIDENCE: missing or placeholder LOADED row for %s/%s\n' "$phase" "$skill" >&2
        exit 5
      fi
      printf '%s\t%s\t%s\tRECORDED_NOT_VERIFIED\n' "$phase" "$skill" "$discovery"
    done
    printf '%s\n' 'RECEIPT_FORMAT_PASS: nine Chris Banes skills declared with references.'
    printf '%s\n' 'NOT A GO: reviewer must independently inspect original OpenCode tool logs and gradle-run bounded results.'
    ;;
  *) usage >&2; exit 64 ;;
esac
