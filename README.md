# Whisper-Live-Subtitle
ANDROID APP that can RECOGNIZE ANY LIVE AUDIO/VIDEO STREAMING (using Tiny Whisper Model tiny-decoder.int8.onnx and tiny-encoder.int8.onnx from [Sherpa](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html) then TRANSLATE (using unofficial online Google Translate API) and display it as LIVE CAPTION / LIVE SUBTITLE

If you want to test another model, just replace them in app/src.main/assets/whisper and then modify the file app\src\main\java\whisper\k2fsa\sherpa\onnx\OfflineRecognizer.kt

if the model you want to test has already listed there you can just use it in function initOfflineRecognizer() in AudioCaptureService.java, and if not just change this part of OfflineRecognizer.kt:

        100 -> {
            val modelDir = "whisper"

            return OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/tiny-encoder.int8.onnx",
                    decoder = "$modelDir/tiny-decoder.int8.onnx",
                    language = language,
                    task = "transcribe",
                ),
                tokens = "$modelDir/tiny-tokens.txt",
                modelType = "whisper",
            )
        }

Check my other SPEECH RECOGNITIION + TRANSLATE PROJECTS https://github.com/botbahlul?tab=repositories

Buy me coffee : https://sociabuzz.com/botbahlul/tribe
