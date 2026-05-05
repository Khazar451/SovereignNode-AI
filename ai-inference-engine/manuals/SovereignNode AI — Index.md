---
title: SovereignNode AI — Engineering Index
tags: [sovereignnode, index, overview]
created: 2026-05-04
updated: 2026-05-04
type: index
---

# ⬡ SovereignNode AI — Engineering Index

> [!NOTE] About This Vault
> This knowledge base documents the internal engineering of the **SovereignNode AI** platform — an air-gapped, edge-native predictive maintenance system for industrial IoT. All AI inference runs fully offline on local hardware. Zero data egress.

---

## Platform Overview

SovereignNode AI is composed of three independently deployable services that form a complete edge-AI pipeline:

| Service | Language | Role |
|---|---|---|
| [[AI Inference Engine]] | Python 3.11 + FastAPI | Local RAG engine — reads manuals, answers diagnostic questions via a quantized LLM |
| [[IoT Telemetry Service]] | Java 21 + Spring Boot 3.3 | Ingests sensor readings, persists to MongoDB, publishes to RabbitMQ, triggers AI on anomalies |
| [[SovNode Dashboard]] | React 18 + TypeScript + Vite | Real-time monitoring UI — live sensor feed and AI diagnostic insights panel |

---

## Data Flow

```
[ Industrial Sensors ]
        |
        | POST /api/v1/telemetry
        v
[ IoT Telemetry Service :8080 ]
        |
        +──> MongoDB :27017          (persist SensorReading)
        +──> RabbitMQ :5672          (fan-out to downstream consumers)
        +──> AI Inference Engine     (on vibration anomaly > 7.1 mm/s)
                    |
                    | POST /generate-insight
                    v
        [ AI Inference Engine :8000 ]
                    |
                    +──> ChromaDB    (vector search over enterprise manuals)
                    +──> Local LLM   (Phi-3 mini, 4-bit NF4 quantized)
                    |
                    | AiInsightResponse (JSON)
                    v
        [ IoT Telemetry Service ]   (logs insight to structured output)
                    |
                    | GET /api/v1/telemetry/*
                    v
        [ SovNode Dashboard :5173 ] (polls Java API every 2s, calls Python on anomalies)
```

---

## Key Design Principles

> [!IMPORTANT] Air-Gapped by Design
> No model API keys. No cloud calls. Every LLM inference, every embedding, every vector lookup happens on the edge device. The system is designed to operate with zero internet connectivity.

> [!TIP] Java 21 Virtual Threads
> The Java service uses Project Loom virtual threads throughout — every HTTP request, every MongoDB write, every RabbitMQ publish, and every AI call runs on a lightweight virtual thread (~1 KB stack). This enables tens of thousands of concurrent sensor sessions without platform thread exhaustion.

> [!TIP] Strict RAG Grounding
> The LLM is explicitly instructed to answer ONLY from retrieved context passages. If the manual doesn't contain the answer, the model must say so. No hallucination is tolerated in a safety-critical industrial environment.

---

## Infrastructure Stack

| Component | Technology | Port |
|---|---|---|
| Vector Store | ChromaDB (persistent on-disk) | — |
| Document Storage | MongoDB | 27017 |
| Message Broker | RabbitMQ | 5672 / 15672 |
| Embedding Model | `all-MiniLM-L6-v2` (HuggingFace) | — |
| Language Model | `microsoft/Phi-3-mini-4k-instruct` (4-bit NF4) | — |
| Container Runtime | Docker + NVIDIA CUDA 12.1 | — |

---

## Component Notes

- [[AI Inference Engine]] — Python RAG pipeline: document ingestion, embedding, ChromaDB retrieval, quantized LLM generation, FastAPI REST bridge
- [[IoT Telemetry Service]] — Java Spring Boot: sensor ingestion API, MongoDB persistence, RabbitMQ messaging, anomaly detection, AI client
- [[SovNode Dashboard]] — React/TypeScript: polling hook, live sensor feed, AI diagnostics panel, design system

---

#sovereignnode #edge-ai #industrial-iot #predictive-maintenance
