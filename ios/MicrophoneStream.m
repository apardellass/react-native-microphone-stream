#import "MicrophoneStream.h"
#import <FLACiOS/all.h>

#define BIAS (0x84)
#define CLIP 8159

@implementation MicrophoneStream {
    AudioQueueRef _queue;
    AudioQueueBufferRef _buffer;
    AVAudioSessionCategory _category;
    AVAudioSessionMode _mode;
}

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(init:(NSDictionary *) options) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    _category = [session category];
    _mode = [session mode];

    UInt32 bufferSize = options[@"bufferSize"] == nil ? 8192 : [options[@"bufferSize"] unsignedIntegerValue];

    AudioStreamBasicDescription description;
    description.mReserved = 0;
    description.mSampleRate = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    description.mBitsPerChannel = options[@"bitsPerChannel"] == nil ? 16 : [options[@"bitsPerChannel"] unsignedIntegerValue];
    description.mChannelsPerFrame = options[@"channelsPerFrame"] == nil ? 1 : [options[@"channelsPerFrame"] unsignedIntegerValue];
    description.mFramesPerPacket = options[@"framesPerPacket"] == nil ? 1 : [options[@"framesPerPacket"] unsignedIntegerValue];
    description.mBytesPerFrame = options[@"bytesPerFrame"] == nil ? 2 : [options[@"bytesPerFrame"] unsignedIntegerValue];
    description.mBytesPerPacket = options[@"bytesPerPacket"] == nil ? 2 : [options[@"bytesPerPacket"] unsignedIntegerValue];
    description.mFormatID = kAudioFormatLinearPCM;
    description.mFormatFlags = kAudioFormatFlagIsSignedInteger;

    AudioQueueNewInput(&description, inputCallback, (__bridge void *) self, NULL, NULL, 0, &_queue);
    AudioQueueAllocateBuffer(_queue, (UInt32) (bufferSize * 2), &_buffer);
    AudioQueueEnqueueBuffer(_queue, _buffer, 0, NULL);
}

RCT_EXPORT_METHOD(start) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setCategory:AVAudioSessionCategoryPlayAndRecord
                   error:nil];
    [session overrideOutputAudioPort:AVAudioSessionPortOverrideSpeaker
                               error:nil];
    AudioQueueStart(_queue, NULL);
}

RCT_EXPORT_METHOD(pause) {
    AudioQueuePause(_queue);
    AudioQueueFlush(_queue);
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setCategory:_category
                   error:nil];
    [session setMode:_mode
               error:nil];
}

RCT_EXPORT_METHOD(stop) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setCategory:_category
                   error:nil];
    [session setMode:_mode
               error:nil];
    AudioQueueStop(_queue, YES);
}

FLAC__StreamEncoder *encoder;
NSString *base64Encoded;
FILE *flacFile;
NSString *docDir;
NSString *dirName;

void inputCallback(
        void *inUserData,
        AudioQueueRef inAQ,
        AudioQueueBufferRef inBuffer,
        const AudioTimeStamp *inStartTime,
        UInt32 inNumberPacketDescriptions,
        const AudioStreamPacketDescription *inPacketDescs) {

    docDir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES)[0];
    dirName = [docDir stringByAppendingPathComponent:@"audio.flac"];
    NSFileManager *fileManager;
    fileManager = [NSFileManager defaultManager];
    NSData *emptyData = [[NSData alloc] init];
    [fileManager createFileAtPath: dirName contents:emptyData attributes:nil];

    encoder = FLAC__stream_encoder_new();
    FLAC__stream_encoder_set_verify(encoder,true);
    FLAC__stream_encoder_set_channels(encoder, 1);
    FLAC__stream_encoder_set_bits_per_sample(encoder, 16);
    FLAC__stream_encoder_set_sample_rate(encoder, 16000);
    FLAC__stream_encoder_init_file(encoder, [dirName UTF8String], NULL, NULL);

    unsigned char *buf = inBuffer->mAudioData;
    FLAC__int32 pcm[inNumberPacketDescriptions];

    for (size_t i = 0; i < inNumberPacketDescriptions; i++) {
        uint16_t msb = *(uint8_t *)(buf + i * 2 + 1);
        uint16_t usample = (msb << 8) | msb;

        union {
            uint16_t usample;
            int16_t  ssample;
        } u;

        u.usample = usample;
        pcm[i] = u.ssample;
    }

    FLAC__stream_encoder_process_interleaved(encoder, pcm, inNumberPacketDescriptions);

    [(__bridge MicrophoneStream *) inUserData processInputBuffer:inBuffer queue:inAQ];
}

- (void)processInputBuffer:(AudioQueueBufferRef)inBuffer queue:(AudioQueueRef)queue {
    FLAC__stream_encoder_finish(encoder);

    NSData *data = [NSData dataWithContentsOfFile: dirName];
    base64Encoded = [data base64EncodedStringWithOptions:0];

    [self sendEventWithName:@"audioData" body:base64Encoded];

    FLAC__stream_encoder_delete(encoder);

    AudioQueueEnqueueBuffer(queue, inBuffer, 0, NULL);
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"audioData"];
}

- (void)dealloc {
    AudioQueueStop(_queue, YES);
}

@end
