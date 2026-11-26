# Third-party notices

## llama.cpp and ggml (MIT)

The offline photo captioner (`android-companion/services/vision-local`, off by default) and the local embedding endpoint (`android-companion/services/ai`) are built against llama.cpp
(including `libmtmd`, the multimodal library) and ggml. Source: the git submodule `android-companion/services/ai/src/main/cpp/llama.cpp`
(https://github.com/ggml-org/llama.cpp, pinned by its gitlink, commit 4dd6dd4b96f32eb53a91d659bbff8b37bbd282b9, build b11618). It is not copied into this repository.

    MIT License

    Copyright (c) 2023-2026 The ggml authors

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
    PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The full text ships with the submodule (`llama.cpp/LICENSE`).

## SmolVLM2-500M-Video-Instruct (Apache-2.0)

Model weights and vision projector, GGUF conversions by ggml-org (https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF) of the model by Hugging Face
(https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct), licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
The files are NOT part of the app or this repository: the user downloads them on demand from the model catalog (Settings > AI > Local models; pinned URLs and sha256 in `LocalModels.kt`),
and the feature that uses them is off by default. No change was made to the model by this project.
