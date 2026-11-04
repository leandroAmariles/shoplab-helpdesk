---
name: neo4j-architecture-graph
description: Discover classes, implementations, dependencies, annotations, Kafka topics (producers/consumers), databases, REST/Feign calls, and change impact with the local Neo4j architecture graph, then verify results in source. Use for architecture discovery and dependency tracing, including across multiple ingested microservices that only interact at runtime (no shared Maven dependency).
---

# Neo4j Architecture Graph — Usage Instructions

## Purpose

`graph-rag/` maintains a local Neo4j graph of this repository's classes, annotations, and
dependencies (and, if `graph-rag/.env`'s `PROJECT_PATHS` lists more than one project, of every
ingested sibling repository too). Use it to answer structural questions before searching source
files manually.

## When to use it

Use the graph first when the task requires any of the following:
- Locating a class or interface
- Discovering implementations
- Tracing dependencies
- Inspecting architecture relationships
- Inspecting annotations
- Assessing change impact

## How to query the graph

If `.mcp.json` (or `.agents/plugins/graph-rag/mcp_config.json` in Antigravity) has a `graph-rag` MCP server configured, prefer its tools for Cypher queries. Otherwise, run `graph-rag/queries/graph_rag_query.py "<question>"`.
