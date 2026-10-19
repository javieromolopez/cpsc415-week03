# Project conventions

<!-- The agent reads this at the start of every session. Keep it short and current.
     Graded: does it reflect how the team actually works? -->

## What this repository is
A support message JSON classifier and automated evaluation harness comparing two OpenRouter models.

## Commands
```
# build 
javac -cp ".;lib/*" *.java
# test 
java -cp ".;lib/*" Eval
```

## Conventions
- Language: Java (JDK 17+). Use Jackson for JSON parsing.
- Default Model: minimax/minimax-m3
- Tests live at the repository root.
- File naming: PascalCase fo classes files (e.g., Classifier.java)

## Working rules

For an introductory lab, follow its explicitly assigned stages; the full chain below applies to major projects. Week 1 uses its own minimal repository.

- Write or update `intent/` and `spec.md` before code. Get `plan.md` approved before implementing.
- One feature per branch and pull request. Never push to `main` directly.
- Never commit `.env` or `.claude/settings.local.json`.
- This is the Week 3 introductory lab. Stages assigned: intent and spec.
  No plan.md, no branches or pull requests. Commit to main.
- Standard library only, except that Java may add one JSON library jar.

## Common mistakes
Things the agent got wrong before and must not repeat. Add to this list as they happen.
