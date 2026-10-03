# Spec

<!-- The agent writes this from the approved intent. You validate it against the intent.
     If the spec and the intent disagree, the intent wins until you change the intent. -->

## Intent
`intent/classifier.md` (approved).

## Components
One entry per component. Two design decisions are required for each.

### classifier
- **What it does:** takes one support message as a CLI argument, sends it to a hosted LLM through OpenRouter, and prints a compact single-line JSON object with three fields — `category`, `urgency`, `reason`. The `Eval.java` harness is the black-box test suite for this component, not a separate deliverable.
- **Language:** Java (JDK 17+). **Why:** Evaluated against Python. While Python requires zero external dependencies due to standard library `json`, Java paired with Jackson provides strict type enforcement and robust schema validation for structured data. The trade-off is managing a single external `.jar` file on the classpath without a build tool like Maven or Gradle, which is preferred over maintaining a fragile hand-rolled JSON parser.
- **Model:** `minimax/minimax-m3`. **Why:** this is the course default cheap model from `CLAUDE.md` and the cheapest option in the OpenRouter account used for Week 3. The eval also runs `xiaomi/mimo-v2.6-flash` as a second cheap model so we can compare how two different cheap models follow a strict JSON contract when given only a prompt (no `response_format`, no tool calling). The differing axis is raw prompt-driven JSON adherence, not capability. A frontier model was not chosen because the eval question is specifically "do cheap models hold the schema?" — adding a frontier model would mask the answer.
- **Interfaces:**
  - **Input:** one command-line argument containing the support message. Missing argument → usage line on stderr, non-zero exit.
  - **Output (stdout):** compact, single-line valid JSON with exactly `category`, `urgency`, `reason`.
  - **Errors (stderr):** distinguishing messages for missing arg, missing key, network failure, malformed JSON, unlisted enum value. Stdout stays empty on errors; exit is non-zero.
  - **Environment:** `OPENROUTER_API_KEY` (required), `CHAT_BASE_URL` (defaults to OpenRouter), `CHAT_MODEL` (defaults to `minimax/minimax-m3`).
- **Dependencies:** Jackson (one jar), standard library for HTTP, I/O, and process control.

## Behavior
Requirements the tests will check. Number them.

1. `java Classifier "<message>"` with a non-empty argument exits 0 and prints exactly one line of compact JSON to stdout.
2. The JSON object has exactly three fields: `category` (string), `urgency` (string), `reason` (string), with no extra whitespace or trailing fields.
3. `category` is one of `billing`, `technical`, `sales`, `unknown`. `urgency` is one of `low`, `medium`, `high`. Any other value causes a non-zero exit and a distinguishing stderr message; stdout stays empty.
4. Invoking with zero arguments prints a usage line to stderr and exits non-zero; stdout stays empty.
5. With `OPENROUTER_API_KEY` unset, the program exits non-zero with a distinguishing stderr message and leaves stdout empty.
6. With `CHAT_MODEL` set to `minimax/minimax-m3`, all five `Eval.java` cases pass: three clear-category messages, one ambiguous message (accepted if either of two valid categories), one non-support message (must yield `category: unknown`). `Eval.java` prints PASS/FAIL per case and a final summary line with total passes/failures and token counts.
7. With `CHAT_MODEL` set to `xiaomi/mimo-v2.6-flash`, behavior 6 repeats: same five cases, same pass criteria, same PASS/FAIL plus summary output.
8. On a network failure, HTTP error, or model response that is not parseable as the three-field JSON object, the program exits non-zero with a distinguishing stderr message and leaves stdout empty. No retries.

## Failure handling
- **Missing argument:** usage line on stderr, non-zero exit. stdout empty.
- **Missing `OPENROUTER_API_KEY`:** "missing OPENROUTER_API_KEY" (or equivalent distinguishing text) on stderr, non-zero exit. stdout empty.
- **Network / HTTP failure (timeout, non-2xx, DNS):** distinguishing stderr message naming the failure mode, non-zero exit. stdout empty.
- **Malformed JSON from the model (not parseable, missing field, wrong type):** distinguishing stderr message, non-zero exit. stdout empty.
- **Unlisted enum value** (`category` not in the four-value set, `urgency` not in the three-value set): distinguishing stderr message naming the offending field and value, non-zero exit. stdout empty.
- **Empty reply:** distinguishing stderr message naming the failure, non-zero exit. stdout empty.
- **No retries, no backoff.** A failure is reported, the program exits, the caller decides whether to run it again.

## Cost estimate
Per call, the prompt is the inline system prompt + the JSON-schema literal example + the single user message (estimate ~400 input tokens) and the model returns ~50 output tokens. Five messages × two models = 10 calls per eval run. At both models' cheap-tier pricing (sub-cent per call), one full eval run is on the order of a few cents; a semester of ~50 eval runs is in the low single-digit dollars. The eval prints token counts per case so the actual spend can be verified.

## Out of scope
- The `Eval.java` harness itself — it is the black-box test suite for this component, not a deliverable.
- Authentication beyond `OPENROUTER_API_KEY` (no OAuth, no key files).
- Retries, backoff, batching multiple messages.
- Persistent storage, history, any UI beyond the CLI.
- `response_format` or function/tool calling — the eval depends on observing raw prompt-driven JSON.
- Branching workflow, `plan.md`, pull requests — Week 3 commits directly to `main`.
