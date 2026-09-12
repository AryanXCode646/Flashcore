#!/usr/bin/env python3
"""
FlashCore Autonomous AI Pull Request Code Reviewer.

Inspects PR code diffs using Google GenAI SDK (gemini-2.5-flash with automatic
resilient fallback across models) and posts constructive, high-signal security,
concurrency, correctness, and architectural feedback directly onto the GitHub Pull Request.
"""

import os
import sys
import re
import json
import argparse
import datetime
from pathlib import Path

try:
    from google import genai
    from google.genai import types
    GENAI_AVAILABLE = True
except ImportError:
    GENAI_AVAILABLE = False

try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False

PR_REVIEW_TAG = "<!-- flashcore-autonomous-pr-review -->"

REVIEW_SYSTEM_INSTRUCTION = """
You are FlashCore's Autonomous AI Pull Request Reviewer and Senior Systems Architect.
FlashCore is a high-performance Android application for low-level USB Mass Storage flashing,
SCSI Command Block Wrapper (CBW/CSW) operations, FAT32 formatting, ISO9660/El Torito parsing,
and MBR/GPT partition layout manipulation.

Your goal is to provide constructive, highly actionable code reviews focused on:
1. SECURITY & MEMORY SAFETY:
   - Bounds validation on raw block device offsets and sector addresses.
   - Defensive path sanitization preventing path traversal in extracted files.
   - Protection of sensitive system partitions.
   - Memory management of native DirectByteBuffer allocations.
2. CONCURRENCY & THREAD SAFETY:
   - Proper use of Kotlin Coroutine dispatchers (avoiding IO blocking on Main thread).
   - Thread safety in circular ring buffers (SPSC invariants) and atomic progress reporting.
   - Synchronization when canceling USB transfers or handling device detachment.
3. PROTOCOL & CORRECTNESS INTEGRITY:
   - Big-Endian vs Little-Endian conventions (SCSI is Big-Endian; FAT32/MBR/GPT are Little-Endian).
   - 64-bit integer calculations for LBA addresses and byte counts (prevent 32-bit Int overflow).
   - Correct cluster chain termination and FAT table updates.
   - Comprehensive handling of SCSI CHECK CONDITION and REQUEST SENSE responses.
4. RESOURCE CLEANUP:
   - Deterministic release of UsbDeviceConnection, UsbInterface, FileDescriptors, and Streams.

REVIEW FORMAT:
Structure your response in GitHub-flavored Markdown:
## 🤖 FlashCore Autonomous AI PR Review

### 📋 Overview & Intent
A brief 2-3 sentence summary of the pull request changes and architectural intent.

### 🚦 Verdict
**[ APPROVED | CHANGES REQUESTED | COMMENT ]**
Provide a brief justification for the verdict.

### 🔍 Critical & High Priority Findings (if any)
If vulnerabilities, race conditions, or correctness bugs are found:
- **Location**: `file:line`
- **Issue**: Explanation of the hazard
- **Impact**: Security, reliability, or data corruption consequences
- **Suggested Fix**: Ready-to-use code block showing the corrected implementation.

### 💡 Optimizations & Idiomatic Kotlin Suggestions
Minor improvements, performance enhancements, or defensive hardening suggestions.

*(If the changes are completely clean and defect-free, explicitly commend the safe implementation and note that no blocking flaws were detected).*
"""


def get_pr_metadata(event_path: str) -> dict:
    """Extract PR metadata from GitHub Actions event payload."""
    if not event_path or not os.path.exists(event_path):
        return {}
    try:
        with open(event_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        pr = data.get("pull_request", {})
        return {
            "number": pr.get("number"),
            "title": pr.get("title"),
            "base_ref": pr.get("base", {}).get("ref"),
            "head_ref": pr.get("head", {}).get("ref"),
            "base_sha": pr.get("base", {}).get("sha"),
            "head_sha": pr.get("head", {}).get("sha"),
            "is_draft": pr.get("draft", False),
            "author": pr.get("user", {}).get("login"),
        }
    except Exception as e:
        print(f"[!] Error parsing event JSON: {e}")
        return {}


def fetch_pr_diff(repo: str, pr_number: int, token: str) -> str:
    """Fetch PR diff text from GitHub API."""
    if not token or not repo or not pr_number:
        return ""
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3.diff",
        "User-Agent": "FlashCore-AI-Reviewer"
    }
    url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}"
    res = requests.get(url, headers=headers, timeout=20)
    if res.status_code == 200:
        return res.text
    print(f"[!] Failed to fetch PR diff: {res.status_code} {res.text}")
    return ""


def filter_diff(diff_text: str, max_chars: int = 80000) -> tuple[str, bool]:
    """Filter out binary, svg, lockfiles, and truncate if overly large."""
    lines = diff_text.splitlines()
    filtered_lines = []
    skip_current_file = False

    for line in lines:
        if line.startswith("diff --git"):
            skip_current_file = any(line.endswith(ext) for ext in [
                ".png", ".jpg", ".webp", ".ico", ".svg", ".jar", ".jks", ".keystore",
                "gradle-wrapper.jar", "package-lock.json"
            ])
        if not skip_current_file:
            filtered_lines.append(line)

    result = "\n".join(filtered_lines)
    is_truncated = False
    if len(result) > max_chars:
        result = result[:max_chars] + f"\n\n... [DIFF TRUNCATED: {len(result) - max_chars} characters omitted] ...\n"
        is_truncated = True

    return result, is_truncated


def generate_review(client: "genai.Client", primary_model: str, pr_info: dict, diff_text: str) -> tuple[str, str]:
    """Generate constructive review using Gemini with automatic resilient fallback."""
    prompt = f"""
Pull Request #{pr_info.get('number', 'N/A')}: {pr_info.get('title', 'Unknown')}
Author: {pr_info.get('author', 'Unknown')}
Base: {pr_info.get('base_ref', 'main')} <- Head: {pr_info.get('head_ref', 'feature')}

Below is the unified git diff of this pull request:

```diff
{diff_text}
```

Please execute your autonomous review following the specified criteria and formatting.
"""
    candidate_models = [primary_model]
    for m in ["gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"]:
        if m not in candidate_models:
            candidate_models.append(m)

    last_error = None
    for model_name in candidate_models:
        try:
            print(f"[*] Calling Gemini model '{model_name}'...")
            response = client.models.generate_content(
                model=model_name,
                contents=prompt,
                config=types.GenerateContentConfig(
                    temperature=0.2,
                    top_p=0.95,
                    system_instruction=REVIEW_SYSTEM_INSTRUCTION
                )
            )
            if response and response.text:
                return response.text, model_name
        except Exception as e:
            err_str = str(e)
            last_error = e
            print(f"[!] Model '{model_name}' invocation failed: {err_str}")
            # Continuously attempt next candidate model on any API rejection or rate limit
            continue

    return f"Error executing Gemini review: {last_error}", primary_model


def post_or_update_pr_comment(repo: str, pr_number: int, token: str, review_body: str, model_used: str) -> None:
    """Post or update review comment on the PR with pagination support."""
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "FlashCore-AI-Reviewer"
    }
    url_comments = f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments"

    # Prepend tag to review body for idempotency
    tagged_body = f"{PR_REVIEW_TAG}\n{review_body}\n\n---\n*Reviewed autonomously by `{model_used}` via `google-genai` SDK.*"

    try:
        res = requests.get(url_comments, headers=headers, params={"per_page": 100}, timeout=15)
        existing_comment_id = None
        if res.status_code == 200:
            for comment in res.json():
                if PR_REVIEW_TAG in comment.get("body", ""):
                    existing_comment_id = comment["id"]
                    break

        if existing_comment_id:
            print(f"[✓] Updating existing PR review comment #{existing_comment_id}...")
            patch_res = requests.patch(
                f"https://api.github.com/repos/{repo}/issues/comments/{existing_comment_id}",
                headers=headers,
                json={"body": tagged_body},
                timeout=15
            )
            if patch_res.status_code == 200:
                print(f"[✓] Successfully updated comment #{existing_comment_id}")
            else:
                print(f"[!] Failed to update comment: {patch_res.status_code} {patch_res.text}")
        else:
            print(f"[✓] Posting new PR review comment on PR #{pr_number}...")
            post_res = requests.post(
                url_comments,
                headers=headers,
                json={"body": tagged_body},
                timeout=15
            )
            if post_res.status_code == 201:
                print(f"[✓] Successfully posted PR review comment #{post_res.json().get('id')}")
            else:
                print(f"[!] Failed to post comment: {post_res.status_code} {post_res.text}")
    except Exception as e:
        print(f"[!] Network or API exception posting comment: {e}")


def write_step_summary(summary_md: str) -> None:
    """Write markdown summary to GITHUB_STEP_SUMMARY environment file."""
    step_summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary_path:
        try:
            with open(step_summary_path, "a", encoding="utf-8") as f:
                f.write(summary_md + "\n")
        except Exception as e:
            print(f"[!] Warning: Failed writing to GITHUB_STEP_SUMMARY: {e}")


def main():
    parser = argparse.ArgumentParser(description="FlashCore Autonomous PR Reviewer (Gemini 2.5 Flash)")
    parser.add_argument("--pr-number", type=int, default=None, help="Pull Request number")
    parser.add_argument("--repo", default=None, help="GitHub repository (owner/name)")
    parser.add_argument("--model", default="gemini-2.5-flash", help="Gemini model name")
    parser.add_argument("--diff-file", default=None, help="Optional local diff file for testing")
    parser.add_argument("--dry-run", action="store_true", help="Perform mock review without API calls")

    args = parser.parse_args()

    repo = args.repo or os.environ.get("GITHUB_REPOSITORY")
    token = os.environ.get("GITHUB_TOKEN")
    api_key = os.environ.get("GEMINI_API_KEY")
    event_path = os.environ.get("GITHUB_EVENT_PATH")

    pr_metadata = get_pr_metadata(event_path)
    pr_number = args.pr_number or pr_metadata.get("number")

    print("==================================================")
    print("FLASHCORE AUTONOMOUS AI PR REVIEWER")
    print(f"Repository: {repo}")
    print(f"PR Number:  {pr_number}")
    print(f"Model:      {args.model}")
    print("==================================================")

    if not api_key and not args.dry_run:
        print("[!] Notice: GEMINI_API_KEY not found in environment.")
        print("[!] Skipping AI review step gracefully (standard for unprivileged fork PRs).")
        write_step_summary("### 🤖 Autonomous PR Review Skipped\n`GEMINI_API_KEY` was not provided.")
        sys.exit(0)

    if not pr_number and not args.diff_file:
        print("[!] Notice: No PR number or diff file detected. Exiting.")
        sys.exit(0)

    # Get diff
    diff_text = ""
    if args.diff_file:
        diff_text = Path(args.diff_file).read_text(encoding="utf-8", errors="replace")
    elif repo and pr_number and token and REQUESTS_AVAILABLE:
        print(f"[*] Fetching PR diff from GitHub API for PR #{pr_number}...")
        diff_text = fetch_pr_diff(repo, pr_number, token)
    else:
        print("[!] Unable to fetch diff: Missing token or network requirements.")
        sys.exit(0)

    if not diff_text or not diff_text.strip():
        print("[*] PR diff is empty. No review necessary.")
        write_step_summary("### 🤖 Autonomous PR Review\nNo code changes found in diff.")
        sys.exit(0)

    filtered_diff, is_truncated = filter_diff(diff_text)
    if not filtered_diff.strip():
        print("[*] No substantive code files modified. Skipping LLM review.")
        write_step_summary("### 🤖 Autonomous PR Review\nNo modified code files requiring review.")
        sys.exit(0)

    print(f"[✓] Diff prepared ({len(filtered_diff)} characters, truncated={is_truncated}).")

    model_used = args.model
    if args.dry_run:
        print("[*] Running in DRY-RUN mode.")
        review_output = "### 🤖 FlashCore AI PR Review (DRY RUN)\nMock verification completed successfully."
    else:
        if not GENAI_AVAILABLE:
            print("[!] google-genai library missing. Install via pip install google-genai.")
            sys.exit(1)
        client = genai.Client(api_key=api_key)
        print(f"[*] Analyzing diff with {args.model} (with resilient model fallback)...")
        review_output, model_used = generate_review(client, args.model, pr_metadata, filtered_diff)

    # Output to stdout
    print("\n--- GENERATED REVIEW ---\n")
    print(review_output)
    print("\n-------------------------\n")

    # Post comment to PR
    if token and repo and pr_number and not args.dry_run:
        post_or_update_pr_comment(repo, pr_number, token, review_body=review_output, model_used=model_used)

    write_step_summary(f"{review_output}\n\n*(Diff characters reviewed: {len(filtered_diff)}, Model: `{model_used}`)*")
    print("[✓] PR review workflow finished successfully.")


if __name__ == "__main__":
    main()
