---
name: jmeter-load-test
description: Design and execute a JMeter load test against a service endpoint. Use when the user asks for a load test, performance test, or stress test, or to run JMeter. Asks for the target endpoint if not provided; compares results against SLOs and draws conclusions only when the user gives an objective, otherwise reports raw results only.
---

# JMeter Load Test — Usage Instructions

## Purpose

Design, execute, and report a JMeter load test for a service endpoint in one
flow: gather inputs → build the `.jmx` test plan → run in CLI mode → analyze
results → report (and compare against objectives when provided).

## When to use it

Use when the user asks for a "load test", "performance test", "stress test",
or to "run JMeter" against an endpoint. Do not use for functional testing
(use the project's normal test suite) and never run load against an
environment the user did not explicitly authorize.

## Inputs

- **Target endpoint (required)**: base URL, path, HTTP method, required headers.
- **Load objective (optional)**: TPS, max latency, max error rate.
- **Environment**: local or a named shared environment.

## Steps

1. **Gather the inputs above.**
2. **Locate the flow in the source.**
3. **Verify JMeter is available.**
4. **Build the test plan** as `<output-dir>/<plan-name>.jmx`.
5. **Execute in CLI mode**:
   ```bash
   jmeter -n -t load-test/<plan>.jmx -l load-test/results.jtl -e -o load-test/report/
   ```
6. **Analyze results.**
7. **Report.**
