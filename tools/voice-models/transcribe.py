from onnx_transcribe import StandaloneASRCTC
import os

try:
    asr = StandaloneASRCTC(model_dir=os.getcwd()+"/models")
    transcripts = asr.transcribe_file("telugu_test_audio.wav")
    print(transcripts)
except Exception as e:
    print(f"Error during transcription: {str(e)}")
