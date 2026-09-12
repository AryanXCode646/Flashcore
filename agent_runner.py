import os
import google.generativeai as genai

# Configure free Gemini API key
genai.configure(api_key=os.environ["GEMINI_API_KEY"])

def audit_codebase_snippet(file_path):
    if not os.path.exists(file_path):
        return
        
    with open(file_path, "r") as f:
        code_content = f.read()

    prompt = f"""
    You are an autonomous senior software engineer. Perform a brutally honest audit of this file for security flaws, race conditions, and architectural bugs.
    
    File: {file_path}
    Content:
    {code_content}
    
    Provide specific P0/P1/P2 issues found and concrete remediation steps.
    """
    
    model = genai.GenerativeModel("gemini-1.5-pro")
    response = model.generate_content(prompt)
    print(f"--- Audit Report for {file_path} ---\n")
    print(response.text)

if __name__ == "__main__":
    # Target core file for Magicloder audit
    audit_codebase_snippet("main.go")
