import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch
from api import app

client = TestClient(app)

@pytest.fixture
def mock_rag_result():
    return {
        "query": "Test Query",
        "answer": "Maintenance required: replace bearing.",
        "retrieved_chunks": [],
        "prompt": "Test Prompt"
    }

def test_generate_insight_success(mock_rag_result):
    """
    Test the /generate-insight endpoint by mocking the query_rag function.
    This prevents loading the heavy LLM into memory during unit tests.
    """
    
    # Payload matching the Pydantic schema in api.py
    payload = {
        "sensor_id": "PUMP-001",
        "anomaly_type": "HIGH_VIBRATION",
        "raw_telemetry": {
            "vibration": 9.5,
            "temperature": 65.0,
            "timestamp": "2026-05-01T12:00:00Z"
        },
        "query": "What is the root cause?"
    }

    # Use patch to mock 'query_rag' in the 'api' module
    with patch("api.query_rag") as mock_query:
        # Mock the RAGResult-like object
        from rag_engine import RAGResult
        mock_query.return_value = RAGResult(
            query=mock_rag_result["query"],
            answer=mock_rag_result["answer"],
            retrieved_chunks=[],
            prompt=mock_rag_result["prompt"]
        )

        response = client.post("/generate-insight", json=payload)

        # Assertions
        assert response.status_code == 200
        data = response.json()
        assert "insight" in data
        assert data["insight"] == "Maintenance required: replace bearing."
        assert "confidence_score" in data
        assert "inference_time_ms" in data

def test_health_check():
    """Simple test for the liveness endpoint."""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"
