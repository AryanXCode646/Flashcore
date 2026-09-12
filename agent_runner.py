#!/usr/bin/env python3
"""
FlashCore Quick Agent Runner (Gemini 2.5 Flash).
Demonstrates localized autonomous file-level audit using the google-genai SDK.
"""

import os
import sys
from pathlib import Path

try:
    from google import genai
    from google.genai import types
except ImportError:
    pass


def audit_codebase_snippet(file_path: str):
    path = Path(file_path)
    if not path.exists():
        print(f"Error: Target file does not exist: {file_path}")
        return

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("Error: GEMINI_API_KEY environment variable is required.")
        return

    try:
        from google import genai
    except ImportError:
        print("Please install google-genai: pip install google-genai")
        return

    client = genai.Client(api_key=api_key)
    code_content = path.read_text(encoding="utf-8", errors="replace")

    prompt = f"""
    You are an autonomous senior systems engineer and security architect.
    Perform a rigorous audit of this file for security flaws, race conditions, resource leaks, and architectural bugs.

    File: {file_path}
    Content:
    {code_content}

    Provide specific P0/P1/P2 issues found and concrete remediation steps.
    """

    for model_name in ["gemini-2.5-flash", "gemini-3.6-flash", "gemini-2.0-flash", "gemini-1.5-flash"]:
        try:
            response = client.models.generate_content(
                model=model_name,
                contents=prompt
            )
            print(f"--- Audit Report for {file_path} ({model_name}) ---\n")
            print(response.text)
            return
        except Exception as e:
            err = str(e)
            if "NOT_FOUND" in err or "no longer available" in err or "404" in err:
                continue
            print(f"Error calling {model_name}: {e}")
            return


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java/com/ashishsinghbora/flashcore/flasher/fsm/FlasherStateMachine.kt"
    audit_codebase_snippet(target)
