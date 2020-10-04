# RtspPullClient
A pure Java RTSP streaming pull client with hard decode of H264 library and demo, simple but powerful, callback support

# Usage

    public void Play(View view) {
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            // 初始大小随意，码流中有SPS和PPS，解码器会自动调整大小
            MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            codec.configure(mediaFormat, surfaceView.getHolder().getSurface(), null, 0);
            codec.start();
        } catch (Exception e) {
            codec = null;
            showMessage("无法创建硬件解码器，可以取流但无法看到视频画面");
        }

        final boolean skip = cbSkip.isChecked();
        try {
            rtspClient = new RtspClient(url.getText().toString(), new RtspClientCallback() {
                @Override
                public void onPacket(int channel, byte[] packet, int len) {
                    if (codec == null) return;

                    byte[] frame;
                    if (skip) {
                        byte[] data = new byte[len -4];
                        System.arraycopy(packet, 4, data, 0, len - 4);
                        frame = rtph264.decode(data, len - 4);
                    } else
                        frame = rtph264.decode(packet, len);
                    if (frame == null) return;

                    int inputBufferIndex = codec.dequeueInputBuffer(0);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inputBufferIndex);
                        buffer.put(frame);
                        codec.queueInputBuffer(inputBufferIndex, 0, frame.length, rtph264.timestamp, 0);
                        int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);//拿到输出缓冲区的索引
                        if (outputBufferIndex >= 0)
                            codec.releaseOutputBuffer(outputBufferIndex, true);
                    }
                }

                @Override
                public void onResponse(List<String> headers, byte[] body) {

                }
            });
        } catch (URISyntaxException e) {
            showMessage("无效URL格式");
            return;
        }
        rtspClient.udpMode = !rtspOverTCP.isChecked();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!rtspClient.start()) showMessage("播放失败");
                pause = false;
            }
        }).start();
    }
