# Intent: classifier

<!-- Single-message support-message classifier. The Eval.java harness is the
     black-box test suite for this component, not a separate deliverable. -->

## Goal
A small Java command-line program that takes one support message, asks a
hosted LLM to classify it, and prints a single-line JSON object with a
category, an urgency, and a one-sentence reason.

## Who it is for
Course evaluators comparing how different hosted models follow a structured
JSON contract when asked purely through prompt instructions. Without it we
have no repeatable way to score models on the same five messages.

## Constraints
- Java; one external library allowed — Jackson, used to parse the model's
  text response. File name `Classifier.java` (PascalCase).
- Hosted model via the OpenRouter API. Default model
  `minimax/minimax-m3`. Model and endpoint must be swappable through
  environment variables `CHAT_BASE_URL`, `CHAT_MODEL`, and
  `OPENROUTER_API_KEY`.
- Input is a single command-line argument containing the message. If the
  argument is missing, print a usage line to stderr and exit non-zero.
- Output to stdout must be compact, single-line valid JSON with exactly
  three fields: `category`, `urgency`, `reason`. Stdout stays clean on
  errors; all error reporting goes to stderr with a non-zero exit.
- The system prompt and the user-message template live inline as Java
  string constants inside `Classifier.java`. The prompt embeds the JSON
  schema as a literal example so the model has an exact shape to mimic.
- No `response_format`, no function/tool calling. The eval depends on
  observing how each model handles raw JSON-by-prompt alone.
- Category must be one of `billing`, `technical`, `sales`, `unknown`;
  urgency must be one of `low`, `medium`, `high`. Anything outside these
  sets is an invalid response and exits non-zero with a distinguishing
  stderr message. No retries.
- Week 3 introductory lab: commit directly to `main`; no branches, no
  pull requests, no `plan.md`.

## Not in scope
- The evaluation harness itself (`Eval.java`) — it is the black-box test
  suite for this component, not a deliverable artifact with its own
  intent.
- Authentication beyond `OPENROUTER_API_KEY` (no OAuth, no key files).
- Retries, backoff, or batching of multiple messages.
- Persistent storage, history, or any UI beyond the CLI.

## Success looks like
- On a valid input, `java Classifier "..."` prints one compact
  single-line JSON object whose `category` is in the four-value set and
  whose `urgency` is in the three-value set, and exits 0.
- A missing argument, a network failure, malformed JSON, or an unlisted
  enum value each produces a non-zero exit and a clear, distinguishing
  stderr message, leaving stdout empty.
- The five-case Eval harness (three clear categories, one ambiguous case
  that accepts either of two valid categories, one non-support message
  that must yield `unknown`) passes when run with `CHAT_MODEL=minimax/minimax-m3`
  and again with `CHAT_MODEL=xiaomi/mimo-v2.6-flash`, printing PASS/FAIL
  per case and a final summary line with total passes/failures and
  token counts.

## Open questions
None — all decisions captured.

**Approved by:** <Javier Romo>, <September 28, 2026>