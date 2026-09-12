---
name: "commit"
description: "Generate a commit message from the provided diff."
mode: "EDIT"
context-action: "ADDITIVE"
---
Generate a brief, scoped Conventional Commit message for the following diff:

<git_diff>
{{vcs_diff}}
</git_diff>

Output ONLY the `<ide:fill_commit_message>` tool. No explanations.