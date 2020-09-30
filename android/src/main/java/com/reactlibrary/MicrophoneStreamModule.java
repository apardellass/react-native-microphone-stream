package com.reactlibrary;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class MicrophoneStreamModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;
    private AudioRecord audioRecord;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;
    private boolean running;
    private int bufferSize;
    private Thread recordingThread;

    MicrophoneStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "MicrophoneStream";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        if (eventEmitter == null) {
            eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
        }

        if (running || (recordingThread != null && recordingThread.isAlive())) {
            return;
        }

        if (audioRecord != null && audioRecord.getState() != AudioRecord.STATE_UNINITIALIZED) {
            audioRecord.stop();
            audioRecord.release();
        }

        // for parameter description, see
        // https://developer.android.com/reference/android/media/AudioRecord.html

        int sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channelsPerFrame")) {
            int channelsPerFrame = options.getInt("channelsPerFrame");

            // every other case --> CHANNEL_IN_MONO
            if (channelsPerFrame == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        // we support only 8-bit and 16-bit PCM
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerChannel")) {
            int bitsPerChannel = options.getInt("bitsPerChannel");

            if (bitsPerChannel == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        if (options.hasKey("bufferSize")) {
            this.bufferSize = options.getInt("bufferSize");
        } else {
            this.bufferSize = 8192;
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                this.bufferSize * 2);

        recordingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    recording();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "RecordingThread");
    }

    @ReactMethod
    public void start() {
        if (!running && audioRecord != null && audioRecord.getState() != AudioRecord.STATE_UNINITIALIZED && recordingThread != null) {
            running = true;
            audioRecord.startRecording();
            recordingThread.start();
        }
    }

    @ReactMethod
    public void pause() {
        if (audioRecord != null && audioRecord.getState() == AudioRecord.RECORDSTATE_RECORDING) {
            running = false;
            audioRecord.stop();
        }
    }

    @ReactMethod
    public void stop() {
        if (audioRecord != null && audioRecord.getState() != AudioRecord.STATE_UNINITIALIZED) {
            running = false;
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void recording() throws IOException {
        FLACEncoder flacEncoder = new FLACEncoder();
        StreamConfiguration streamConfiguration = new StreamConfiguration();
        streamConfiguration.setChannelCount(1);
        streamConfiguration.setBitsPerSample(16);
        streamConfiguration.setSampleRate(16000);
        flacEncoder.setStreamConfiguration(streamConfiguration);

        final List<Byte> encodedData = new ArrayList<>();

        while (running && !reactContext.getCatalystInstance().isDestroyed()) {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            flacEncoder.setOutputStream(new FLACOutputStream() {
                long position = 0;
                long size = 0;

                @Override
                public long seek(long pos) {
                    return pos;
                }

                @Override
                public int write(byte[] data, int offset, int count) {
                    byteArrayOutputStream.write(data, offset, count);

                    if (position + count > size)
                        size = position + count;
                    position += count;

                    return count;
                }

                @Override
                public long size() {
                    return size;
                }

                @Override
                public void write(byte data) throws IOException {
                    byteArrayOutputStream.write(data);

                    if (position + 1 > size)
                        size = position + 1;
                    position += 1;
                }

                @Override
                public boolean canSeek() {
                    return true;
                }

                @Override
                public long getPos() {
                    return 0;
                }
            });

            flacEncoder.openFLACStream();

            short[] buffer = new short[bufferSize];
            int[] bufferAux = new int[bufferSize];
            audioRecord.read(buffer, 0, bufferSize);

            for (int i = 0; i < bufferSize; i++) {
                bufferAux[i] = buffer[i];
            }

            flacEncoder.addSamples(bufferAux, bufferSize);
            int encodedCount = flacEncoder.encodeSamples(bufferSize, true);

            if (encodedCount < bufferSize) {
                flacEncoder.encodeSamples(flacEncoder.samplesAvailableToEncode(), true);
            }

            String base64Data = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);
            eventEmitter.emit("audioData", base64Data);
            encodedData.clear();
        }
    }
}