# ANEMLL core (vendored)

The Neural Engine LLM engine (`../AneLlm.swift`) runs on five files of ANEMLL's Swift runtime, AnemllCore
(github.com/Anemll/Anemll, `anemll-swift-cli/Sources/AnemllCore`, commit 8d2791c): `InferenceManager.swift`,
`ModelLoader.swift`, `FFNChunk.swift`, `MetalArgmax.swift`, `SamplingConfig.swift`.

Changes: `@available(iOS 18.0, macOS 15.0, *)` on `InferenceManager` and `ModelLoader` (the app runs on iOS 16; Core ML's
model state is iOS 18). `AnemllShims.swift` replaces ANEMLL's `YAMLConfig` (Yams) and `Tokenizer` (swift-transformers)
with the few members the core uses, so neither package comes into the app.

License: MIT ("ANEMLL is licensed under the MIT License", ANEMLL's README; `license = "MIT"` in its pyproject.toml and
setup.py).

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
> rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
> persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and
> this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
