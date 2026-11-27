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

## Models in the catalog (downloaded by the user on demand; not part of the app or this repository)

Licences are as stated in the model catalog (`android-companion/services/ai/src/main/java/com/rokid/context/ai/local/LocalModels.kt`); URLs and sha256 are pinned there. No model was modified by this project.

| Model | Licence (catalog) | Notes |
|---|---|---|
| bge-small-en-v1.5 (GGUF q8 by CompendiumLabs, model by BAAI) | MIT (upstream BAAI MIT) | Default text embedder. |
| Nomic Embed v1.5 (text GGUF and vision ONNX, nomic-ai) | Apache-2.0 | Opt-in; never auto-bound. |
| SigLIP 2 base patch16-224 (ONNX export by onnx-community, model by Google) | Apache-2.0 | Opt-in shadow embedder. The SentencePiece `tokenizer.model` comes from the google repo; its separate licence is verify (the catalog states one licence for the bundle). |
| SmolVLM2-500M-Video-Instruct (see above) | Apache-2.0 | Offline captioner, off by default. |

## Seeding data (Place guide)

The place seeder fetches facts on the user's network and stores them on the device. Seeding data is not shipped in this repository or in the APK.

- Wikipedia article text (intros): CC BY-SA (Creative Commons Attribution-ShareAlike). Version (4.0 vs 3.0 per article): verify. Attribution is shown in the seeding screen; derived text stays under the same licence.
- Wikidata: CC0 1.0 (public domain dedication).
- OpenStreetMap data: ODbL 1.0, (c) OpenStreetMap contributors (https://www.openstreetmap.org/copyright).
- Wikimedia Commons images (reference photos of seeded places): the app downloads one 330 px thumbnail per place from Wikimedia Commons, only for files under a free licence (CC BY, CC BY-SA, CC0 or public domain; files under any other or unknown licence are skipped). The author, licence name and link to the file page are stored with each photo and shown with it (Settings > Place cards, the detail of a card that cites the place: "Reference photo: author, licence, Wikimedia Commons"). The images stay on the device, in the app's private encrypted place database: they are used only for on-device matching, are never uploaded, shown to any model service or included in a backup, and are not redistributed.

## Map tiles (MapTiler, live only)

The optional street map (Settings > Privacy > Pause zones, off by default) requests raster tiles live from MapTiler as a selector under the zone boxes (`docs/offline-maps.md`). Tiles are not stored or redistributed by this project, and no key is in this file or the repository (it comes from untracked `local.properties`). The tiles show "(c) MapTiler (c) OpenStreetMap contributors". MapTiler's terms apply to whoever supplies the key: verify.
