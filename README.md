# Support Message Classifier & Eval Harness

## How to Run

To run the evaluation harness, you must first set you environmental variables in PowerShell if windows:

$env:OPENROUTER_API_KEY="your_api_key_here"
$env:CHAT_BASE_URL="https://openrouter.ai/api/v1"
$env:CHAT_MODEL="minimax/minimax-m3"

You then need to compile the java file and run the evaluation harnes using the Jackson dependenies in the lib folder:

javac -cp ".;lib/*" Classifier.java Eval.java

java -cp ".;lib/*" Eval

## The 5 Test Cases

In the eval-cases.json file there are 5 different like user prompt questions that I invented to test the models, adhering to the JSON schema and its logical routing.

1. Billing (duplacate Charge): Catches a standard, unambiguous billing issue.
2. Technical (Login Error): Catches a standard technical support issue.
3. Sales (Enterprise Pricing): Catches a standard sales and upgrade inquiry.
4. Ambiguous (Payment Lockout): Catches a tricky boundary condition("My payment went through ... but the system hasn't unlocked my account") that tests if the model can safely route a dual-intent message to either billing or technical without breaking schema.
5. Unknown (Recipe Request): Catches off-topic, non-support inputs to ensure they are properly filtered out.

## Model Comparison

| Model | Pass Rate | Prompt Tokens | Completion Tokens | Total Tokens |
| :--- | :--- | :--- | :--- | :--- |
|minimax/minimax-m3 | 5/5 | 2003 | 543 | 2546 |
|xiaomi/mimo-v2.6-flash | 5/5 | 1213 | 253 | 1466 |

**Observations**: Both models perfectly adhered to the strict JSON constraints and successfully navigated the ambiguous edge case. However, Xiaomi did the same result while being more efficient by using roughly 40% less prompt tokens and less than half of the completion tokens than the MiniMax model.

## Correction in spec

I made two corrections inside the spec.md file.

1. The agent wanted to explain that the reason behind using java as the language for this lab was that it was instructed for. Instead I prompt it this: "- Language: Java (JDK 17+). Why: Evaluated against Python. While Python requires zero external dependencies due to standard library json, Java paired with Jackson provides strict type enforcement and robust schema validation for structured data. The trade-off is managing a single external .jar file on the classpath without a build tool like Maven or Gradle, which is preferred over maintaining a fragile hand-rolled JSON parser."
2. I instruct the agent to add an extra bullet point at the end of the Failure Handling Section to clarify how my program handles missing inputs: "- Empty reply: distinguishing stderr message naming the failure, non-zero exit. stdout empty".

## Code Explanation

"if (!expected.contains(cat.asText())) {
            return "got category=" + cat.asText() + ", expected one of " + expected;
        }"
- This line of code judges a case where the text from the inquiry is not an expected type. Which in that case the code inside the if statement is executed and it returns an error message: "got category= 'cat.asText()', expected one of 'expected'". Therefore, the line of code helps determining if the text is of an expected type, or judging a case.
