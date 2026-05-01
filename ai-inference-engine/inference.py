"""
inference.py — 4-bit Quantized Small Language Model Loader & Generator
======================================================================
Loads a HuggingFace causal-LM with NF4 4-bit quantization via bitsandbytes,
optimised for local CUDA execution.

Key design decisions:
  - BitsAndBytesConfig with NF4 dtype: halves VRAM vs FP16 with minimal
    quality loss (Dettmers et al., 2023).
  - bfloat16 compute dtype: stable numerics on Ampere+ GPUs.
  - Flash Attention 2: 4-6x faster attention on long contexts (optional).
  - Singleton model: loaded once per process, reused for all queries.
  - Deterministic low-temperature generation: factual RAG responses.
"""

from __future__ import annotations

import logging
from functools import lru_cache
from typing import Optional

import torch
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    GenerationConfig,
    TextStreamer,
)

from config import cfg

logger = logging.getLogger(__name__)


# ─── dtype helper ─────────────────────────────────────────────────────────

_DTYPE_MAP = {
    "bfloat16": torch.bfloat16,
    "float16":  torch.float16,
    "float32":  torch.float32,
}


def _resolve_dtype(dtype_str: str) -> torch.dtype:
    dtype = _DTYPE_MAP.get(dtype_str.lower())
    if dtype is None:
        raise ValueError(
            f"Unknown dtype '{dtype_str}'. Choose from: {list(_DTYPE_MAP)}"
        )
    return dtype


# ─── LLM Model Wrapper ────────────────────────────────────────────────────

class LanguageModel:
    """
    Wrapper around a HuggingFace CausalLM with 4-bit quantization.

    Parameters
    ----------
    model_id : str
        HuggingFace Hub model ID or local directory path.
    load_in_4bit : bool
        Enable NF4 4-bit quantization (requires CUDA + bitsandbytes).
    device_map : str
        Accelerate device mapping strategy ('auto', 'cuda', 'cpu').
    """

    def __init__(
        self,
        model_id: str = cfg.LLM_MODEL_ID,
        load_in_4bit: bool = cfg.LLM_LOAD_IN_4BIT,
        device_map: str = cfg.LLM_DEVICE_MAP,
    ) -> None:
        self.model_id = model_id
        cuda_available = torch.cuda.is_available()

        if load_in_4bit and not cuda_available:
            logger.warning(
                "4-bit quantization requires CUDA. "
                "Falling back to full-precision CPU inference."
            )
            load_in_4bit = False
            device_map = "cpu"

        # ── BitsAndBytes quantization config ──────────────────────────────
        bnb_config: Optional[BitsAndBytesConfig] = None
        if load_in_4bit:
            compute_dtype = _resolve_dtype(cfg.LLM_BNB_COMPUTE_DTYPE)
            bnb_config = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",           # Normal Float 4 (best quality)
                bnb_4bit_compute_dtype=compute_dtype, # bfloat16 for Ampere+
                bnb_4bit_use_double_quant=True,       # nested quantization: saves ~0.4 GB
            )
            logger.info(
                "4-bit NF4 quantization enabled (compute_dtype=%s, double_quant=True)",
                cfg.LLM_BNB_COMPUTE_DTYPE,
            )

        # ── Attention implementation ──────────────────────────────────────
        attn_impl = "flash_attention_2" if (
            cfg.LLM_USE_FLASH_ATTENTION and cuda_available
        ) else "eager"

        # ── Load tokeniser ────────────────────────────────────────────────
        logger.info("Loading tokeniser for '%s' …", model_id)
        self.tokeniser = AutoTokenizer.from_pretrained(
            model_id,
            cache_dir=str(cfg.LLM_CACHE_DIR),
            trust_remote_code=True,
        )
        # Many instruction-tuned models omit a pad token; use eos instead
        if self.tokeniser.pad_token is None:
            self.tokeniser.pad_token = self.tokeniser.eos_token

        # ── Load model ────────────────────────────────────────────────────
        logger.info(
            "Loading model '%s' — device_map=%s, 4bit=%s, attn=%s …",
            model_id, device_map, load_in_4bit, attn_impl,
        )
        self.model = AutoModelForCausalLM.from_pretrained(
            model_id,
            quantization_config=bnb_config,
            device_map=device_map,
            attn_implementation=attn_impl,
            cache_dir=str(cfg.LLM_CACHE_DIR),
            trust_remote_code=True,
        )
        self.model.eval()

        if cuda_available and not load_in_4bit and device_map not in ("auto", "cpu"):
            # Full-precision: move to GPU explicitly when not using device_map=auto
            self.model = self.model.to("cuda")

        logger.info("Language model ready.")

    # ── Generation ────────────────────────────────────────────────────────

    def generate(
        self,
        prompt: str,
        max_new_tokens: int = cfg.MAX_NEW_TOKENS,
        temperature: float = cfg.TEMPERATURE,
        top_p: float = cfg.TOP_P,
        repetition_penalty: float = cfg.REPETITION_PENALTY,
        stream: bool = False,
    ) -> str:
        """
        Generate a response for the given prompt string.

        Parameters
        ----------
        prompt : str
            The fully-formatted prompt (system + context + question).
        max_new_tokens : int
            Upper bound on generated token count.
        temperature : float
            Sampling temperature. Low values → deterministic / factual.
        top_p : float
            Nucleus sampling threshold.
        repetition_penalty : float
            Penalise repeated n-grams to reduce looping.
        stream : bool
            If True, stream tokens to stdout as they are generated.

        Returns
        -------
        str
            The model-generated text (excluding the prompt).
        """
        # Tokenise prompt
        inputs = self.tokeniser(
            prompt,
            return_tensors="pt",
            padding=True,
            truncation=True,
            max_length=4096,
        )

        # Move input tensors to the same device as the model
        device = next(self.model.parameters()).device
        inputs = {k: v.to(device) for k, v in inputs.items()}

        # Streamer: prints tokens live if stream=True
        streamer = TextStreamer(
            self.tokeniser, skip_prompt=True, skip_special_tokens=True
        ) if stream else None

        gen_config = GenerationConfig(
            max_new_tokens=max_new_tokens,
            do_sample=temperature > 0.0,
            temperature=temperature if temperature > 0.0 else None,
            top_p=top_p,
            repetition_penalty=repetition_penalty,
            pad_token_id=self.tokeniser.pad_token_id,
            eos_token_id=self.tokeniser.eos_token_id,
        )

        with torch.inference_mode():
            output_ids = self.model.generate(
                **inputs,
                generation_config=gen_config,
                streamer=streamer,
            )

        # Decode only the newly generated tokens (exclude the prompt)
        prompt_length = inputs["input_ids"].shape[1]
        new_tokens = output_ids[0][prompt_length:]
        return self.tokeniser.decode(new_tokens, skip_special_tokens=True).strip()


# ── Singleton accessor ────────────────────────────────────────────────────

@lru_cache(maxsize=1)
def get_language_model() -> LanguageModel:
    """
    Return the process-level singleton LanguageModel.
    The model is loaded on first call and cached for all subsequent calls.
    """
    return LanguageModel()
