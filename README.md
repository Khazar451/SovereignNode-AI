<div align="center">
  <img src="https://img.shields.io/badge/Status-Production_Ready-emerald?style=for-the-badge" alt="Status" />
  <img src="https://img.shields.io/badge/Deployment-Air--Gapped_Edge-violet?style=for-the-badge" alt="Deployment" />
  <img src="https://img.shields.io/badge/Architecture-Microservices-cyan?style=for-the-badge" alt="Architecture" />
</div>

<br />

# ⬡ SovereignNode AI

**SovereignNode AI** is a production-ready, air-gapped Industrial IoT predictive maintenance edge node. It is designed to operate entirely on constrained local hardware, ingesting high-frequency factory telemetry, detecting critical anomalies, and generating Retrieval-Augmented Generation (RAG) diagnostic reports—**all without ever sending sensitive operational data to the cloud.**

---

## The "Why": Architecture & Tech Stack

Industrial environments require absolute data sovereignty, high reliability, and rapid insight generation on highly constrained edge servers. 

- **Java 21 & Spring Boot 3 (Telemetry Ingestion)**: Chosen for the ingestion layer due to its enterprise reliability, strict typing, and seamless AMQP integration for handling massive influxes of sensor data without dropping packets.
- **Python & FastAPI (AI Engine)**: Chosen because Python is the undisputed king of the ML ecosystem (HuggingFace, PyTorch). FastAPI provides the high-performance, asynchronous endpoints required to bridge the Java layer with the AI engine.
- **React, Vite, TypeScript, Tailwind (Dashboard)**: Chosen for the frontend to provide a lightning-fast, reactive operator dashboard with real-time polling hooks and a dynamic user interface.
- **MongoDB & RabbitMQ (Data & Messaging)**: Chosen to decouple the ingestion logic from database writes, ensuring zero data loss during high-load spikes while persisting telemetry securely.

---

## Repository Structure

The platform is divided into domain-specific microservices:

- **`/iot-telemetry-service`**: The Java-based ingestion engine that consumes AMQP streams and persists telemetry to MongoDB.
- **`/ai-inference-engine`**: The Python backend hosting the quantized `Qwen-0.5B` Small Language Model (SLM) and the ChromaDB vector store for RAG grounding.
- **`/sovnode-dashboard`**: The Vite-powered React operator UI that visualizes live sensor feeds and AI diagnostic insights.
- **Root Directory**: Contains the master `docker-compose.yml`, a mock Python `telemetry_simulator.py`, and the `setup_edge` bootstrap scripts.

---

## Quickstart Guide

The entire backend runs in a cross-platform Docker Compose stack. It relies on the NVIDIA Container Toolkit for GPU passthrough but is heavily optimized to run safely on standard CPUs (`~1.1 GB` RAM total footprint) if hardware resources are constrained.

### Linux (Ubuntu / Mint)

1. Ensure Docker and Docker Compose are installed.
2. Grant execution permissions and run the bootstrap script:

```bash
sudo bash ./setup_edge.sh
```
*(If the server dependencies are already installed, you can simply run `docker compose up -d`)*

### Windows (WSL 2)

> [!WARNING]  
> **Windows Prerequisites**  
> For this stack to run correctly on Windows, you **MUST** ensure:
> 1. Docker Desktop is installed and the **"WSL 2 based engine"** is enabled in Settings -> General.
> 2. You have the latest NVIDIA Windows drivers installed. WSL 2 natively supports GPU passthrough automatically if the host Windows drivers are up-to-date.

1. Open PowerShell as Administrator.
2. Run the deployment script:

```powershell
.\setup_edge.ps1
```

---

## Verifying the Deployment

Once the Docker Compose stack is running, you can interact with the platform:

1. **Start the Dashboard**: Open a new terminal in the `sovnode-dashboard` folder and run the UI natively:
   ```bash
   cd sovnode-dashboard
   npm run dev
   ```
   *Access the dashboard at `http://localhost:5173`.*

2. **Trigger the Simulator**: Start the Python telemetry simulator to stream mock sensor data into the platform:
   ```bash
   python telemetry_simulator.py
   ```

3. **Monitor the AI**: Watch the inference engine logs in real-time as it detects anomalies and generates insights:
   ```bash 
   docker compose logs -f inference-engine
   ```

---

### Engineering Deep Dive
Curious about how we optimized this stack to run on a 2GB VRAM edge server without crashing? Read the [Architecture & Engineering Report](./ENGINEERING_REPORT.md).
