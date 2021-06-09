package zhjinrui.com.OnvifPlayer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import be.teletask.onvif.OnvifManager;
import be.teletask.onvif.listeners.OnvifResponseListener;
import be.teletask.onvif.models.OnvifDevice;
import be.teletask.onvif.models.OnvifType;
import be.teletask.onvif.requests.OnvifRequest;
import be.teletask.onvif.responses.OnvifResponse;

public class MainActivity extends Activity implements OnvifResponseListener, Thread.UncaughtExceptionHandler, SurfaceHolder.Callback {
    final String TAG = "RtspClient";
    final static String S_URL = "url";
    final static String S_RTSP_OVER_TCP = "rtsp_over_tcp";
    final static String S_PTZ_SPEED = "ptz_speed";
    RtspClient rtspClient;
    MediaCodec codec;
    SurfaceView svPreview;
    EditText etUrl;
    CheckBox cbTCP;
    TextView tvHelp, tvAbout;
    ImageButton btnPlay, btnPause, btnFast, btnSlow, btnUp, btnDown, btnLeft, btnRight, btnZoomin, btnZoomout;
    ImageButton btnAddPreset, btnDelPreset;
    Spinner spnPresets;
    ImageView ivDonate;
    SeekBar sbSpeed;
    RTPH264 rtph264;
    float speed = 1.0f;
    boolean pause = false;
    SharedPreferences preferences;
    OnvifManager onvifManager;
    OnvifDevice device;
    GestureDetector mGesDetect;
    boolean fill = true;
    private String profileToken = "Profile_1";
    // 初始大小随意，码流中有SPS和PPS，解码器会自动调整大小

    private AdapterView.OnItemSelectedListener onItemSelectedListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            if (i == 0) return;  // 默认当前位置，不动！
            String s = adapterView.getItemAtPosition(i).toString();
            String[] ss = s.split(":");
            goPreset(ss[0]);
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {

        }
    };

    private RtspClientCallback rtspClientCallback = new RtspClientCallback() {

        private byte[] trimFrame(byte[] save) {
            int index = 0;
            int len = 0;
            byte[] buffer = new byte[save.length];
            while (index < save.length - 2) {
                if (save[index] == 0 && save[index + 1] == 0 && (save[index + 2] == 1 || save[index + 2] == 3))
                    index += 3;
                else
                    buffer[len++] = save[index++];
            }
            while (index < save.length)  // 补齐尾部数据
                buffer[len++] = save[index++];

            byte[] ret = new byte[len];
            System.arraycopy(buffer, 0, ret, 0, len);
            return ret;
        }

        private void initMuxer() {
            try {
                muxer = new MediaMuxer("/sdcard/zhjinrui/demo.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException e) {
                muxer = null;
                return;
            }
            mediaFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(pps));
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(sps));
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080);
            mediaFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, 25);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            trackVideo = muxer.addTrack(mediaFormat);

            muxer.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            bufferInfo.offset = 0;
            bufferInfo.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            bufferInfo.presentationTimeUs = System.nanoTime() / 1000;

            bufferInfo.size = sps_frame.length;
            muxer.writeSampleData(trackVideo, ByteBuffer.wrap(sps_frame), bufferInfo);

            bufferInfo.size = pps_frame.length;
            muxer.writeSampleData(trackVideo, ByteBuffer.wrap(pps_frame), bufferInfo);

            videoReady = true;
        }

        @Override
        public void onPacket(int channel, byte[] packet, int len) {
            if (codec == null) return;

            byte[] frame = rtph264.decode(packet, len);
            if (frame == null) return;

            int flag = rtph264.getType(packet);
//            Log.e(TAG, "时间序列: " + rtph264.timestamp + " 帧类型: " + flag + " 帧长度: " + frame.length);

            // 海康Flag顺序： 7，8，6
            if (flag == 8) {  // pps
                pps = trimFrame(frame);
                formatSetted = true;
                pps_frame = frame;
            } else if (flag == 7) {
                sps = trimFrame(frame);
                sps_frame = frame;
            }

            if (formatSetted && !videoReady) {
                if (muxer == null) initMuxer();
            }

            if (videoReady) {
                try {
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    bufferInfo.offset = 0;
                    bufferInfo.size = frame.length;
                    if (flag == 8 || flag == 6 || flag == 7) {
                        bufferInfo.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                    } else if (flag == 28 || flag == 1) {
                        bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }

                    bufferInfo.presentationTimeUs = System.nanoTime() / 1000;
                    muxer.writeSampleData(trackVideo, ByteBuffer.wrap(frame), bufferInfo);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 开始解码显示在界面
            int inputBufferIndex = codec.dequeueInputBuffer(0);
            if (inputBufferIndex >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(inputBufferIndex);
                buffer.put(frame);
                codec.queueInputBuffer(inputBufferIndex, 0, frame.length, System.nanoTime() / 1000, 0);
//                codec.queueInputBuffer(inputBufferIndex, 0, frame.length, rtph264.timestamp, 0);
            } else if (inputBufferIndex == -1 && rtph264.timestamp % 5000 == 0)  // 减少缓冲区不足日志输出
                Log.e(TAG, "解码输入缓冲区不足");

            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);//拿到输出缓冲区的索引
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mediaFormat = codec.getOutputFormat();
            } else while (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, inputBufferIndex != -1);
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);//拿到输出缓冲区的索引
            }
        }

        @Override
        public void onResponse(List<String> headers, byte[] body) {

        }
    };

    private MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
    private MediaMuxer muxer;
    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private int trackVideo = 0;
    private boolean formatSetted = false;
    private boolean videoReady = false;
    private byte[] pps, pps_frame, sps, sps_frame;

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String msg = getString(R.string.crash) + throwable.getLocalizedMessage();
        showMessage(msg);
        Log.e(TAG, msg + "\n" + throwable.getStackTrace());
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        if (codec == null) initCodec();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Stop(null);
    }

    class DoubleTapGestureDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            fill = !fill;
            if (fill) {
                ConstraintLayout.LayoutParams layout = (ConstraintLayout.LayoutParams) svPreview.getLayoutParams();
                layout.height = 0;
                layout.bottomToTop = R.id.etURL;
                svPreview.requestLayout();
            } else {
                ViewGroup.LayoutParams lp = svPreview.getLayoutParams();
                int w = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                int h = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                lp.height = (int) (svPreview.getWidth() * (1.0f * h / w));
                svPreview.setLayoutParams(lp);
            }
            return true;
        }
    }

    private void request(String xml) {
        if (device == null) return;

        Log.d(TAG, xml);
        new Thread(() -> {
            onvifManager.sendOnvifRequest(device, new OnvifRequest() {
                @Override
                public String getXml() {
                    return xml;
                }

                @Override
                public OnvifType getType() {
                    return OnvifType.CUSTOM;
                }
            });
        }).start();
    }

    private void goPreset(String token) {
        request(String.format("<GotoPreset xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                "      <ProfileToken>" + profileToken + "</ProfileToken>\n" +
                "      <PresetToken>%s</PresetToken>\n" +
                "    </GotoPreset>", token));
    }

    private void initView() {
        svPreview = findViewById(R.id.surfaceView);
        svPreview.getHolder().addCallback(this);
        cbTCP = findViewById(R.id.cbTcp);
        etUrl = findViewById(R.id.etURL);
        btnFast = findViewById(R.id.Fast);
        btnSlow = findViewById(R.id.Slow);
        btnPlay = findViewById(R.id.Play);
        btnPause = findViewById(R.id.Pause);
        btnDown = findViewById(R.id.btnDown);
        btnUp = findViewById(R.id.btnUp);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnZoomin = findViewById(R.id.btnZoomin);
        btnZoomout = findViewById(R.id.btnZoomout);
        btnAddPreset = findViewById(R.id.btnAddPreset);
        btnDelPreset = findViewById(R.id.btnDelPreset);
        tvHelp = findViewById(R.id.tvHelp);
        tvAbout = findViewById(R.id.tvAbout);
        sbSpeed = findViewById(R.id.sbSpeed);
        spnPresets = findViewById(R.id.spnPresets);
        ivDonate = findViewById(R.id.ivDonate);

        spnPresets.setOnItemSelectedListener(onItemSelectedListener);
        btnDown.setOnTouchListener(onTouchListener);
        btnUp.setOnTouchListener(onTouchListener);
        btnLeft.setOnTouchListener(onTouchListener);
        btnRight.setOnTouchListener(onTouchListener);
        btnZoomin.setOnTouchListener(onTouchListener);
        btnZoomout.setOnTouchListener(onTouchListener);
        svPreview.setOnTouchListener((view, motionEvent) -> {
            mGesDetect.onTouchEvent(motionEvent);
            return true;
        });

        View.OnClickListener donateClick = view -> {
            if (ivDonate.getVisibility() == View.VISIBLE) {
                ivDonate.setVisibility(View.GONE);
            } else {
                ivDonate.setVisibility(View.VISIBLE);
            }
        };
        ivDonate.setOnClickListener(donateClick);
        tvAbout.setOnClickListener(donateClick);
        String text = tvAbout.getText().toString().replace("1.0", BuildConfig.VERSION_NAME);
        tvAbout.setText(text);
        updatePresetButtonUI(false);
    }

    private void updatePresetButtonUI(boolean enable) {
        runOnUiThread(() -> {
            int alpha = enable ? 255 : 100;
            btnAddPreset.setImageAlpha(alpha);
            btnDelPreset.setImageAlpha(alpha);
            btnZoomin.setImageAlpha(alpha);
            btnZoomout.setImageAlpha(alpha);
            btnUp.setImageAlpha(alpha);
            btnDown.setImageAlpha(alpha);
            btnLeft.setImageAlpha(alpha);
            btnRight.setImageAlpha(alpha);

            btnAddPreset.setEnabled(enable);
            btnDelPreset.setEnabled(enable);
            btnZoomin.setEnabled(enable);
            btnZoomout.setEnabled(enable);
            btnUp.setEnabled(enable);
            btnDown.setEnabled(enable);
            btnLeft.setEnabled(enable);
            btnRight.setEnabled(enable);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Thread.setDefaultUncaughtExceptionHandler(this);

        mGesDetect = new GestureDetector(this, new DoubleTapGestureDetector());
        initView();
        preferences = getSharedPreferences("RtspPlayer", MODE_PRIVATE);
        etUrl.setText(preferences.getString(S_URL, etUrl.getText().toString()));
        cbTCP.setChecked(preferences.getBoolean(S_RTSP_OVER_TCP, true));

        sbSpeed.setProgress(preferences.getInt(S_PTZ_SPEED, 50));
        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                tvHelp.setText(getString(R.string.ptz_speed) + i + getString(R.string.ptz_speed_range));
                preferences.edit().putInt(S_PTZ_SPEED, sbSpeed.getProgress()).commit();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        rtph264 = new RTPH264(0);

        updateUI(false);
    }

    private View.OnTouchListener onTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            tvHelp.setText(view.getContentDescription());
            if (device != null) {
                int action = motionEvent.getAction();
                String xml = "";
                float x = 0, y = 0, z = 0;
                float speed = 1.0f * sbSpeed.getProgress() / sbSpeed.getMax();
                int id = view.getId();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (id == R.id.btnUp)
                        y = speed;
                    else if (id == R.id.btnDown)
                        y = -speed;
                    else if (id == R.id.btnLeft)
                        x = -speed;
                    else if (id == R.id.btnRight)
                        x = speed;
                    else if (id == R.id.btnZoomin)
                        z = speed;
                    else if (id == R.id.btnZoomout)
                        z = -speed;
                    xml = String.format("<ContinuousMove xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                            "<ProfileToken>" + profileToken + "</ProfileToken>\n" +
                            "<Velocity>\n" +
                            "    <PanTilt x=\"%3.2f\" y=\"%3.2f\"/>\n" +
                            "    <Zoom x=\"%3.2f\" />\n" +
                            "</Velocity>\n" +
                            "<Timeout>PT60S</Timeout>\n" +
                            "<Speed>\n" +
                            "  <Zoom x=\"%3.2f\"/>\n" +
                            "  <PanTilt x=\"%3.2f\" y=\"%3.2f\" />\n" +
                            "</Speed></ContinuousMove>", x, y, z, speed, speed, speed);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    xml = "<Stop xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                            "  <ProfileToken>" + profileToken + "</ProfileToken>\n" +
                            "  <PanTilt>false</PanTilt><Zoom>false</Zoom></Stop>";
                }

                request(xml);
            }
            return false;
        }
    };

    private void initOnvifDevice() {
        device = new OnvifDevice(rtspClient.server, rtspClient.user, rtspClient.password);
        onvifManager = new OnvifManager();
        onvifManager.setOnvifResponseListener(MainActivity.this);
        request("<GetServices xmlns=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                "      <IncludeCapability>false</IncludeCapability>\n" +
                "    </GetServices>");
    }

    public void Play(View view) {
        speed = 1.f;
        if (codec == null) initCodec();

        if (ActivityCompat.checkSelfPermission(this, "android.permission.INTERNET") != PackageManager.PERMISSION_GRANTED) {
            // 没有网络权限，去申请权限
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.INTERNET"}, 1);
        }

        try {
            rtspClient = new RtspClient(etUrl.getText().toString(), rtspClientCallback);
        } catch (URISyntaxException e) {
            showMessage(getString(R.string.invalid_rtsp_url));
            return;
        }

        preferences.edit().putString(S_URL, etUrl.getText().toString()).commit();
        preferences.edit().putBoolean(S_RTSP_OVER_TCP, cbTCP.isChecked()).commit();
        rtspClient.udpMode = !cbTCP.isChecked();
        spnPresets.setSelection(0);
        new Thread(() -> {
            if (!rtspClient.start()) {
                showMessage(getString(R.string.play_failed));
                return;
            }
            updateUI(true);
            pause = false;
            initOnvifDevice();
        }).start();
    }

    private void initCodec() {
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            mediaFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 103, 100, 0, 31, -84, -76, 2, -128, 45, -56}));  // SPS
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 104, -18, 60, 97, 15, -1, -16, -121, -1, -8, 67, -1, -4, 33, -1, -2, 16, -1, -1, 8, 127, -1, -64})); // PPS
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            codec.configure(mediaFormat, svPreview.getHolder().getSurface(), null, 0);
            codec.start();
        } catch (Exception e) {
            try {
                if (codec != null) codec.stop();
            } catch (Exception e1) {
            }
            codec = null;
            showMessage(getString(R.string.error_create_codec));
        }
    }

    private void getPresets() {
        request("<GetPresets xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                "<ProfileToken>" + profileToken + "</ProfileToken></GetPresets>");
    }

    private void updateUI(final boolean enable) {
        runOnUiThread(() -> {
            if (enable) {
                btnPlay.setOnClickListener(this::Stop);
                btnPlay.setImageResource(R.drawable.stop);
            } else {
                btnPlay.setOnClickListener(this::Play);
                btnPlay.setImageResource(R.drawable.play);
            }
            btnPause.setEnabled(enable);
            btnPause.setImageAlpha(enable ? 255 : 100);
            btnFast.setEnabled(enable);
            btnFast.setImageAlpha(enable ? 255 : 100);
            btnSlow.setEnabled(enable);
            btnSlow.setImageAlpha(enable ? 255 : 100);
        });
    }

    private void showMessage(final String msg) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
        });
    }

    public void Stop(View v) {
        new Thread(() -> {
            if (rtspClient != null) rtspClient.stop();
            if (codec != null) try {
                codec.stop();
            } catch (Exception e) {
            }

            if (muxer != null) {
                muxer.stop();
                muxer.release();
                muxer = null;
            }
            rtspClient = null;
            codec = null;
            videoReady = false;
            formatSetted = false;
            updateUI(false);
        }).start();
    }

    public void Pause(View v) {
        updateHelp(btnPause.getContentDescription().toString());
        new Thread(() -> {
            if (rtspClient != null) {
                if (pause)
                    rtspClient.play(null, speed);
                else
                    rtspClient.pause();
                pause = !pause;
            }
        }).start();
    }

    private void updateHelp(String text) {
        runOnUiThread(() -> {
            tvHelp.setText(text);
        });
    }

    public void Slow(View v) {
        new Thread(() -> {
            speed /= 2;
            updateHelp(String.format(getString(R.string.play_speed), speed) + "\n" + btnSlow.getContentDescription());
            if (rtspClient != null) rtspClient.play(null, speed);
        }).start();
    }

    public void Fast(View v) {
        new Thread(() -> {
            speed *= 2;
            updateHelp(String.format(getString(R.string.play_speed), speed) + "\n" + btnFast.getContentDescription());
            if (rtspClient != null) rtspClient.play(null, speed);
        }).start();
    }

    public void setPreset(View v) {
        if (device == null) return;

        String token = spnPresets.getSelectedItemPosition() == 0 ? ""
                : "<PresetToken>" + spnPresets.getSelectedItem().toString().split(":")[0] + "</PresetToken>";
        request("<SetPreset xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                "<ProfileToken>" + profileToken + "</ProfileToken>\n" + token + "</SetPreset>");
    }

    public void unsetPreset(View v) {
        if (device == null || spnPresets.getSelectedItemPosition() == 0) return;

        String s = spnPresets.getSelectedItem().toString();
        spnPresets.setSelection(0);
        String[] ss = s.split(":");
        request("<RemovePreset xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">\n" +
                "<ProfileToken>" + profileToken + "</ProfileToken>\n" +
                "<PresetToken>" + ss[0] + "</PresetToken></RemovePreset>");
    }

    @Override
    public void onResponse(OnvifDevice onvifDevice, OnvifResponse response) {
        String xml = response.getXml();
        if (response.getErrorMessage() != null) showMessage(response.getErrorMessage());
        Log.i(TAG, onvifDevice.toString() + "=>" + xml);

        try {
            parseXML(xml);
        } catch (Exception e) {
            Log.e(TAG, getString(R.string.xml_error) + e.getMessage());
        }
    }

    /**
     * 根据DOM节点的名字，返回Node的子节点
     *
     * @param node 需要查找DOM节点
     * @param name 子节点的名字
     * @return
     */
    private Node getChildNodeByName(Node node, String name) {
        Node child = node.getFirstChild();
        while (child != null) {
            if (child.getNodeName().equals(name)) return child;
            child = child.getNextSibling();
        }
        return null;
    }

    private void parseGetService(Document doc) {
        String ptzService = "/onvif/PTZ";
        String mediaService = "/onvif/media";
        NodeList nodes = doc.getElementsByTagName("tds:Service");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = getChildNodeByName(nodes.item(i), "tds:Namespace");
            if (node == null) continue;

            String text = node.getTextContent();
            if (text.indexOf("/ptz/wsdl") > 0) {
                node = getChildNodeByName(nodes.item(i), "tds:XAddr");
                if (node == null) return;
                ptzService = node.getTextContent();
                updatePresetButtonUI(true);
                continue;
            }

            if (text.indexOf("/media/wsdl") > 0) {
                node = getChildNodeByName(nodes.item(i), "tds:XAddr");
                if (node == null) return;
                mediaService = node.getTextContent();
                continue;
            }
        }

        final String shortPTZService = URI.create(ptzService).getRawPath();
        this.device.getPath().setProfilesPath(URI.create(mediaService).getRawPath());
        onvifManager.getMediaProfiles(device, (device, mediaProfiles) -> {
            if (mediaProfiles.size() == 0) return;

            profileToken = mediaProfiles.get(0).getToken();
            Log.i(TAG, mediaProfiles.get(0).getToken() + ": " + mediaProfiles.get(0).getName());
            this.device.getPath().setServicesPath(shortPTZService);
            getPresets();
        });
    }

    private void parseGetPresets(Document doc) {
        NodeList nodes = doc.getElementsByTagName("tptz:Preset");
        if (nodes != null && nodes.getLength() > 0) {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_item);
            adapter.add(getString(R.string.current_position));
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                NamedNodeMap attributes = node.getAttributes();
                String token = attributes.getNamedItem("token").getNodeValue();
                NodeList subNodes = node.getChildNodes();
                String presetName = "";
                for (int j = 0; j < subNodes.getLength(); j++) {
                    if (subNodes.item(j).getNodeName().equals("tt:Name")) {
                        presetName = subNodes.item(j).getTextContent();
                        break;
                    }
                }
                adapter.add(String.format("%s: %s", token, presetName));
            }
            runOnUiThread(() -> {
                spnPresets.setOnItemSelectedListener(null);
                int idx = spnPresets.getSelectedItemPosition();
                spnPresets.setAdapter(adapter);
                spnPresets.setSelection(idx);
                spnPresets.setOnItemSelectedListener(onItemSelectedListener);
            });
            return;
        }
    }

    private void parseXML(String xml) throws ParserConfigurationException, IOException, SAXException {
        org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes()));
        // 不同服务器返回的SOAP标记不一样，有的是env，有的是soap，有的是s等
        if (doc.getFirstChild() == null) return;
        String s = doc.getFirstChild().getNodeName();
        int idx = s.indexOf(":");
        if (idx < 0)
            s = "soap:Body";
        else
            s = s.substring(0, idx);
        NodeList nodes = doc.getElementsByTagName(s + ":Body");
        if (nodes == null || nodes.getLength() == 0) return;

        String cmdResponse = nodes.item(0).getFirstChild().getNodeName();
        if ("tptz:SetPresetResponse".equals(cmdResponse)) {
            showMessage(String.format(getString(R.string.set_success), nodes.item(0).getFirstChild().getFirstChild().getTextContent()));
            getPresets();
        } else if ("tptz:GetPresetsResponse".equals(cmdResponse)) {
            parseGetPresets(doc);
        } else if ("tds:GetServicesResponse".equals(cmdResponse)) {
            parseGetService(doc);
        } else if ("tptz:RemovePresetResponse".equals(cmdResponse)) {
            showMessage(getString(R.string.del_preset_ok));
            getPresets();
        }
    }


    @Override
    public void onError(OnvifDevice onvifDevice, int errorCode, String errorMessage) {
        Log.i(TAG, onvifDevice.toString() + "=>" + errorMessage);
        if (errorMessage.indexOf("d:MatchingRuleNotSupported") > 0) return;

        try {
            org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(errorMessage.getBytes()));
            NodeList nodes = doc.getElementsByTagName("env:Text");
            if (nodes == null || nodes.getLength() == 0) return;

            showMessage(getString(R.string.str_error) + nodes.item(0).getTextContent());
        } catch (Exception e) {
            showMessage("ONVIF error: " + e.getMessage());
        }
    }

}
