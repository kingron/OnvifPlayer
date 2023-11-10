# RtspPullClient
A pure Java RTSP streaming pull client & player with hard decode of H264 library and demo, simple but powerful, callback support. Onvif PTZ control support as well.

[![Lines Of Code](https://tokei.rs/b1/github/kingron/OnvifPlayer?category=code)](https://github.com/kingron/OnvifPlayer)

 ![image](https://github.com/kingron/OnvifPlayer/raw/main/Images/screen1.png)

安卓下RTSP取流及硬解码代码及播放，700行代码量搞定，包含示例代码。史上最简单、高性能RTSP流播放器了。特点：
* Support Basic & Digest authorization: RTSP取流，支持Basic和Digest鉴权
* UDP & RTSP Over TCP both: 支持UDP和RTSP Over TCP两种模式
* RTSP Play Control: 支持回放控制速度及位置拖动调整
* RTP packet callback & RTSP response callback: 支持RTSP命令响应回调和RTP包回调
* For streaming forward: 尤其适合取流转发不做解码的情况
* RTP Decode: RTP解码完整H264帧
* RTP Encode: 支持把完整H264帧编码为多个RTP包，可以直接用UDP或者RTP Over TCP发送走
* RTP extension support: RTP包支持扩展模式
* 安卓硬件解码视频流
* 代码超级简单易用，不包含任何非标准库，代码耦合度极低，纯Java实现，单文件类
* 资源和CPU消耗极低
* 完整Javadoc文档

代码特别有意思，例如异步转同步，RTSP Over TCP嵌入取流处理后还能继续支持其他RTSP指令及响应。 网络I/O读取不足处理，一行正则表达式提取复杂多个数据等。

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

        try {
            rtspClient = new RtspClient(url.getText().toString(), new RtspClientCallback() {
                @Override
                public void onPacket(int channel, byte[] packet, int len) {
                    if (codec == null) return;

                    byte[] frame = rtph264.decode(packet, len);
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
