"""Shared Transformers chat inference for generation, RAG, Refine and Judge."""
import os
import json

from services.runtime_config import llm_memory_options, runtime_bool

DEFAULT_MODEL = 'meta-llama/Llama-3.1-8B-Instruct'


def model_id(role='generator'):
    base = os.getenv('TTOBAGI_LLM_MODEL', DEFAULT_MODEL)
    return os.getenv('TTOBAGI_JUDGE_MODEL', base) if role == 'judge' else base


def load_pipeline_models(generator_loader, judge_loader):
    """Share weights only when both model identity and revision match."""
    if (model_id('judge') == model_id()
            and os.getenv('TTOBAGI_JUDGE_REVISION') == os.getenv('TTOBAGI_LLM_REVISION')):
        tok, model = judge_loader()
        return tok, model, tok, model
    tok, model = generator_loader()
    judge_tok, judge_model = judge_loader()
    return tok, model, judge_tok, judge_model


def load_chat_model(name, role='generator'):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

    revision = os.getenv('TTOBAGI_JUDGE_REVISION' if role == 'judge'
                         else 'TTOBAGI_LLM_REVISION') or None
    tokenizer = AutoTokenizer.from_pretrained(name, revision=revision, trust_remote_code=False)
    if not tokenizer.chat_template:
        raise ValueError('The selected model must provide a chat template')
    dtype = (torch.bfloat16 if torch.cuda.is_available()
             and torch.cuda.is_bf16_supported(including_emulation=False)
             else torch.float16 if torch.cuda.is_available() else torch.float32)
    options = llm_memory_options()
    placement = os.getenv('TTOBAGI_LLM_DEVICE_MAP')
    device_map = json.loads(placement) if placement else 'auto'
    if placement and (not isinstance(device_map, dict) or not device_map):
        raise ValueError('TTOBAGI_LLM_DEVICE_MAP must be a nonempty JSON object')
    quant = os.getenv('TTOBAGI_LLM_QUANTIZATION', 'none')
    if quant not in {'none', '4bit'}:
        raise ValueError('TTOBAGI_LLM_QUANTIZATION must be none or 4bit')
    if quant == '4bit':
        if not torch.cuda.is_available():
            raise ValueError('4bit inference requires CUDA')
        options['quantization_config'] = BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type='nf4',
            bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=dtype,
            llm_int8_enable_fp32_cpu_offload=runtime_bool('TTOBAGI_LLM_CPU_OFFLOAD', False))
    model = AutoModelForCausalLM.from_pretrained(
        name, revision=revision, trust_remote_code=False,
        torch_dtype=dtype, device_map=device_map, **options).eval()
    return tokenizer, model


def stop_tokens(tokenizer, model):
    values = []
    for item in (tokenizer.eos_token_id, model.generation_config.eos_token_id):
        for token in item if isinstance(item, (list, tuple)) else [item]:
            if token is not None and token not in values:
                values.append(token)
    # Llama ends assistant turns with eot_id, distinct from end_of_text.
    if '<|eot_id|>' in tokenizer.get_vocab():
        token = tokenizer.convert_tokens_to_ids('<|eot_id|>')
        if token not in values:
            values.append(token)
    if not values:
        raise ValueError('The selected model has no end-of-sequence token')
    return values


def generate_chat(messages, tokenizer, model, *, max_new_tokens, temperature, top_p):
    import torch
    from services.analysis_progress import count

    inputs = tokenizer.apply_chat_template(
        messages, add_generation_prompt=True, tokenize=True,
        return_tensors='pt', return_dict=True)
    # Never silently truncate RAG evidence, JSON instructions or Refine feedback.
    limit = min(int(os.getenv('TTOBAGI_LLM_CONTEXT_TOKENS', '8192')),
                model.config.max_position_embeddings)
    length = inputs['input_ids'].shape[-1]
    if length + max_new_tokens > limit:
        raise ValueError(f'LLM context budget exceeded: {length}+{max_new_tokens}>{limit}')
    embedding = model.get_input_embeddings()
    device = getattr(getattr(embedding, '_hf_hook', None), 'execution_device', None)
    inputs = inputs.to(device if device is not None else embedding.weight.device)
    stops = stop_tokens(tokenizer, model)
    count('llmCalls')
    count('llmInputTokens', length)
    with torch.inference_mode():
        output = model.generate(
            **inputs, max_new_tokens=max_new_tokens, do_sample=temperature > 0,
            **({'temperature': temperature, 'top_p': top_p} if temperature > 0 else {}),
            eos_token_id=stops, pad_token_id=tokenizer.pad_token_id
            if tokenizer.pad_token_id is not None else stops[0])
    count('llmOutputTokens', len(output[0][length:]))
    return tokenizer.decode(output[0][length:], skip_special_tokens=True).strip()
