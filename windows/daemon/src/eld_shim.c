// Minimal AAC-ELD decoder shim over FFmpeg libavcodec + libswresample (LGPL).
// Exposes a small, version-stable C ABI so the Rust side never touches
// AVCodecContext internals. Decodes AAC-ELD access units and resamples whatever
// rate the decoder produces to a fixed 48 kHz mono s16 (matching the virtual
// mic's capture format), so the pitch is always correct.
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define OUT_RATE 48000
// The AirPods hi-res mic AAC-ELD actually decodes at 64 kHz (PR #655's
// ELD_SAMPLE_RATE), but its 4-byte ASC advertises index 3 (48000), so FFmpeg
// labels the frames 48000. Feeding that 64 kHz content as 48 kHz played ~0.75x
// slow (deep) and overfed the ring. Resample from the true 64 kHz instead.
#define IN_RATE 64000
// The AirPods mic runs a few dB quiet with lots of headroom; a make-up gain
// brings it up to a comfortable level (applied on the float before resampling,
// so swr clamps cleanly if a loud transient would exceed full scale).
#define GAIN 3.0f

typedef struct {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
    SwrContext *swr;
    int in_rate;  // decoder output rate, learned from the first frame
} EldDec;

void *eld_open(const uint8_t *asc, int asc_len, int sample_rate) {
    const AVCodec *c = avcodec_find_decoder_by_name("aac");
    if (!c) return NULL;
    AVCodecContext *ctx = avcodec_alloc_context3(c);
    if (!ctx) return NULL;
    ctx->extradata = (uint8_t *)av_malloc(asc_len + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!ctx->extradata) { avcodec_free_context(&ctx); return NULL; }
    memset(ctx->extradata, 0, asc_len + AV_INPUT_BUFFER_PADDING_SIZE);
    memcpy(ctx->extradata, asc, asc_len);
    ctx->extradata_size = asc_len;
    ctx->sample_rate = sample_rate;  // channels/rate ultimately come from the ASC
    if (avcodec_open2(ctx, c, NULL) < 0) { avcodec_free_context(&ctx); return NULL; }
    EldDec *d = (EldDec *)calloc(1, sizeof(EldDec));
    if (!d) { avcodec_free_context(&ctx); return NULL; }
    d->ctx = ctx;
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    d->swr = NULL;
    d->in_rate = 0;
    return d;
}

// Decode one access unit into interleaved mono s16 at 48 kHz. `out_cap` is the
// sample capacity. Returns the number of samples written, or -1 on error.
int eld_decode(void *handle, const uint8_t *au, int au_len, int16_t *out, int out_cap) {
    EldDec *d = (EldDec *)handle;
    if (!d) return -1;
    d->pkt->data = (uint8_t *)au;
    d->pkt->size = au_len;
    if (avcodec_send_packet(d->ctx, d->pkt) < 0) return -1;

    int total = 0;
    while (avcodec_receive_frame(d->ctx, d->frame) == 0) {
        // (Re)build the resampler when the decoder's output rate is first known
        // or changes: decoder rate -> 48 kHz mono s16.
        if (!d->swr || d->in_rate != d->frame->sample_rate) {
            if (d->swr) swr_free(&d->swr);
            AVChannelLayout out_ch;
            av_channel_layout_default(&out_ch, 1);
            swr_alloc_set_opts2(&d->swr, &out_ch, AV_SAMPLE_FMT_S16, OUT_RATE,
                                &d->frame->ch_layout, (enum AVSampleFormat)d->frame->format,
                                IN_RATE, 0, NULL);
            swr_init(d->swr);
            d->in_rate = d->frame->sample_rate;
        }
        // Make-up gain with a soft limiter (tanh): normal speech keeps the ~x3
        // gain (nearly linear at low levels), while loud transients saturate
        // smoothly instead of hard-clipping (which sounded blown out).
        if (d->frame->format == AV_SAMPLE_FMT_FLTP || d->frame->format == AV_SAMPLE_FMT_FLT) {
            float *pf = (float *)d->frame->data[0];
            for (int i = 0; i < d->frame->nb_samples; i++) {
                pf[i] = tanhf(pf[i] * GAIN);
            }
        }
        uint8_t *outp = (uint8_t *)(out + total);
        int room = out_cap - total;
        if (room <= 0) { av_frame_unref(d->frame); break; }
        int got = swr_convert(d->swr, &outp, room,
                              (const uint8_t **)d->frame->data, d->frame->nb_samples);
        if (got > 0) total += got;
        av_frame_unref(d->frame);
    }
    return total;
}

// The decoder's output sample rate (learned after the first decoded frame), or 0.
int eld_in_rate(void *handle) {
    EldDec *d = (EldDec *)handle;
    return d ? d->in_rate : 0;
}

void eld_close(void *handle) {
    EldDec *d = (EldDec *)handle;
    if (!d) return;
    if (d->swr) swr_free(&d->swr);
    av_frame_free(&d->frame);
    av_packet_free(&d->pkt);
    avcodec_free_context(&d->ctx);
    free(d);
}
