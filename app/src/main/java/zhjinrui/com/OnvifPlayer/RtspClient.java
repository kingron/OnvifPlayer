
/**
 * @(#) RtspClient.java 1.0 2020-10-01
 * <p>
 * JRTSP Library - Java RTSP Library
 * Copyright (c) 2020. Kingron<Kingron@163.om>
 * You must get a license for commercial purpose.
 * 商业使用，必须获取授权
 */

package zhjinrui.com.OnvifPlayer;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RTSP拉流客户端回调接口
 */
interface RtspClientCallback {
    /**
     * 当收到RTP数据包时回调
     *
     * @param channel The channel of the packet, 0 = RTP, 1 = RTCP, other value setup by user
     * @param packet  The raw RTP packet，RTP裸数据包，包括 RTP Header + Data
     * @param len     The packet length，数据包长度
     */
    void onPacket(int channel, byte[] packet, int len);

    /**
     * 当收到 RTSP 响应包时回调
     *
     * @param headers 命令响应包头
     * @param body    命令响应数据体
     */
    void onResponse(List<String> headers, byte[] body);
}

/**
 * RTSP 拉流客户端: <a href="https://datatracker.ietf.org/doc/rfc2326/">RFC 2326</a><br/>
 * <p>Pull streaming from RTSP server, Callback when received RTP packet, Support RTP/UDP & RTP Over TCP both<br/>
 * 连接RTSP服务器取流并在收到视频UDP包数据后回调，支持UDP和RTSP Over TCP两种模式</p>
 * <pre>使用方法：
 * RtspClient rtspClient = new RtspClient("192.168.1.100", 554, "user name", "password", "h264/main/ch1/av_stream",
 *     new RtspClient.RtspClientCallback() {
 *    {@literal @}Override
 *     public void onPacket(byte[] packet, int len) {
 *         // now we get a raw RTP packet
 *         Log.i("RTSPClient", "Get RTP packet: " + len + " bytes");
 *     }
 * &nbsp;
 *    {@literal @}Override
 *     public void onResponse(List<String> headers, byte[] body) {
 *         // now we get a RTSP response
 *         Log.i("RTSPClient", "RTSP Response: " + String.join("\r\n", headers));
 *     }
 * });
 * &nbsp;
 * rtspClient.udpMode = true;
 * rtspClient.start();
 * rtspClient.pause();
 * rtspClient.play("180.000-", 1);    // Play start from 180 seconds, and normal speed
 * rtspClient.stop();
 * </pre>
 */
public class RtspClient {
    /**
     * 设置取流模式 true: UDP, false: RTP Over TCP
     */
    public boolean udpMode = false;
    /**
     * Socket 连接超时时间
     */
    public int TIMEOUT = 10000;
    /**
     * RTSP端口
     */
    public int port = 554;
    /**
     * RTSP服务器地址或者IP
     */
    public String server = "192.168.200.11";
    /**
     * RTSP用户名
     */
    public String user = "";
    /**
     * RTSP密码
     */
    public String password = "";
    /**
     * RTSP的URI地址，不含用户名和密码等
     */
    public String uri = "";

    /**
     * RTSP 回调接口
     */
    public RtspClientCallback callback;

    private static final Pattern REGEX_STATUS = Pattern.compile("RTSP/\\d.\\d (\\d+) (\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REXEG_HEADER = Pattern.compile("(\\S+):(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REXEG_TRANSPORT = Pattern.compile("server_port=(\\d+)-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REG_URI = Pattern.compile("rtsp://(\\S+)/(.*)", Pattern.CASE_INSENSITIVE);
    //    private static final Pattern REG_URI = Pattern.compile("rtsp://(\\S+):(.+)@(.+):(\\d+)/(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final String TAG = "RtspClient";
    private long seq = 0;
    private Socket socket;
    private DataInputStream input;
    private BufferedOutputStream output;
    private String authBasic = "";
    private DatagramSocket rtpSocket;
    private Timer heart = new Timer();
    private String session = "";
    private Thread thread = null;
    private Thread udpThread = null;
    private Object lock = new Object();
    private List<String> headers = new ArrayList<>();
    private HashMap<String, String> keyValues = new HashMap<>();
    private byte[] body = new byte[0];
    private String control = "trackID=0";
    private boolean authDigest = false;
    private String nonce;
    private String realm;
    private String hash1;

    private Runnable runnable = new Runnable() {
        byte[] buf = new byte[2000];

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted() && socket != null && socket.isConnected())
                try {
                    byte magic = input.readByte();
                    if (magic == '$') {   // RTP Over TCP的包开头为 $ 字符，需要读取RTP包
                        // RTP包格式: $ + 1 字节通道号 + 2 字节数据长度 + RTP裸数据包
                        int channel = input.readUnsignedByte();  // 0 = RTP packet, 1 = RTCP packet, etc
                        int len = input.readUnsignedShort();     // (b1 & 0xFF) << 8 | (b2 & 0xFF);
                        int count = 0;
                        while (count < len) {
                            int r = input.read(buf, count, len - count);
                            if (r <= 0) break;
                            count += r;
                        }

                        if (callback == null) continue;
                        try {
                            callback.onPacket(channel, buf, len);
                        } catch (Exception e) {
//                            Log.e(TAG, "RTP 回调错误: " + e.getMessage());
                        }
                    } else {  // 如果是RTSP响应数据包
                        headers.clear();
                        keyValues.clear();
                        StringBuilder sb = new StringBuilder(200);
                        String line = "R" + input.readLine();
                        headers.add(line);
                        sb.append(line + "\r\n");

                        while ((line = input.readLine()) != null) {
                            if (line == null) throw new SocketException("Connection lost");
                            if (line.equals("")) break;

                            headers.add(line);
                            sb.append(line + "\r\n");
                            Matcher matcher = REXEG_HEADER.matcher(line);
                            if (matcher.find())
                                keyValues.put(matcher.group(1), matcher.group(2).trim());
                        }

                        int len = (keyValues.containsKey("Content-Length")) ? Integer.valueOf(keyValues.get("Content-Length")) : 0;
                        body = new byte[len];
                        int count = 0;
                        while (count < len) {
                            int r = input.read(body, count, len - count);
                            if (r <= 0) break;
                            count += r;
                        }

                        Log.d(TAG, "收到 RTSP 响应: " + sb.toString() + "\r\n" + new String(body));
                        synchronized (lock) {
                            lock.notify();
                        }

                        try {
                            if (callback != null) callback.onResponse(headers, body);
                        } catch (Exception e) {
                            Log.w(TAG, "RTP 包回调错误: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "RTP Over TCP收包错误: " + e.getMessage());
                    break;
                }
        }
    };

    /**
     * 内部构造函数
     */
    private void FooRtspClient(String server, int port, String user, String password, String resource, RtspClientCallback callback) {
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        this.callback = callback;
        this.uri = String.format("rtsp://%s:%d/%s", server, port, resource);

        try {
            // 计算Basic鉴权数据，即 md5(user:password) 的值
            authBasic = "Basic " + Base64.encodeToString((user + ":" + password).getBytes(), Base64.NO_WRAP);
        } catch (Exception e) {
            authBasic = "";
        }
    }

    /**
     * 构造函数，初始化RtspClient实例
     *
     * @param server   RTSP流服务器地址或IP
     * @param port     RTSP流服务器端口
     * @param user     用户名
     * @param password 密码
     * @param resource 流URL地址，不包含前面的IP等，不能以 / 开头
     * @param callback 回调接口，可以为空
     */
    public RtspClient(String server, int port, String user, String password, String resource, RtspClientCallback callback) {
        FooRtspClient(server, port, user, password, resource, callback);
    }

    /**
     * 构造函数，初始化RTSP实例
     *
     * @param uri      完整URL，例如: rtsp://admin:password{@literal @}192.168.1.123:554/channel=0&stream=1&codec=h264
     * @param callback 回调接口，可以为空
     * @throws URISyntaxException
     */
    public RtspClient(String uri, RtspClientCallback callback) throws URISyntaxException {
        Matcher matcher = REG_URI.matcher(uri);
        if (!matcher.find()) throw new URISyntaxException(uri, "无效RTSP地址格式");

        String user_pwd = left(matcher.group(1), "@");
        String usr = left(user_pwd, ":");
        String pwd = right(user_pwd, ":");

        String host = right(matcher.group(1), "@");
        int idx = host.indexOf("/");
        if (idx > 0) host = host.substring(0, idx);

        idx = host.indexOf(":");
        int port = 554;
        if (idx > 0) {
            port = Integer.valueOf(host.substring(idx + 1, host.length()));
            host = host.substring(0, idx);
        }

        String res = uri.substring("rtsp://".length());
        idx = res.indexOf("/");
        res = idx == -1 ? "/" : res.substring(idx + 1);
        FooRtspClient(host, port, usr, pwd, res, callback);
    }

    /**
     * 连接到RTSP服务器
     *
     * @return 成功返回 true，失败返回 false
     */
    public boolean connect() {
        if (socket == null) {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(server, port), TIMEOUT);
                input = new DataInputStream(socket.getInputStream());
                output = new BufferedOutputStream(socket.getOutputStream());
            } catch (Exception e) {
                socket = null;
                Log.e(TAG, String.format("连接 RTSP 服务器 %s:****@%s:%d 失败 => %s", user, server, port, e.getMessage()));
                return false;
            }
        }

        return socket != null && socket.isConnected();
    }

    /**
     * 从字符串中取分隔符左边的字符，例如 left("aaaa=1234", "=") == > "aaa"
     *
     * @param s
     * @param separator 分隔字符串
     * @return 如果有分隔符返回分隔符左边的，否则返回空
     */
    public static String left(String s, String separator) {
        int idx = s.indexOf(separator);
        return idx == -1 ? "" : s.substring(0, idx);
    }

    /**
     * 从字符串中取分隔符右边的字符，例如 right("aaaa=1234", "=") == > "1234"
     *
     * @param s
     * @param separator 分隔字符串
     * @return 如果有分隔符返回分隔符右边的，否则返回原字符串
     */
    public static String right(String s, String separator) {
        int idx = s.indexOf(separator);
        return s.substring(idx == -1 ? 0 : idx + separator.length(), s.length());
    }

    /**
     * 计算字符串的MDL5值
     *
     * @param msg 待计算的字符串
     * @return 返回字符串的hex格式的md5值
     * @throws NoSuchAlgorithmException
     */
    public static String md5(String msg) throws NoSuchAlgorithmException {
        byte[] bytes = MessageDigest.getInstance("MD5").digest(msg.getBytes());

        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 根据不同指令返回不同鉴权信息
     *
     * @param cmd
     * @return
     */
    private String getAuth(String cmd) {
        if (user.equals("")) return "";
        if (!authDigest) return authBasic;

        try {
            String hash2 = md5(cmd + ":" + uri);
            String hash3 = md5(hash1 + ":" + nonce + ":" + hash2);
            return String.format("Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"",
                    user, realm, nonce, uri, hash3);
        } catch (NoSuchAlgorithmException e) {
            return authBasic;
        }
    }

    private boolean doSend(String request, String cmd) throws IOException, InterruptedException {
        String s1 = session.equals("") ? "" : ("Session: " + session + "\r\n");
        String s2 = user.equals("") ? "" : ("Authorization: " + getAuth(cmd) + "\r\n");
        String buf = String.format("%s%s%sCSeq: %d\r\nUser-Agent: JRTSPLib/1.0\r\n\r\n", request, s1, s2, ++seq);
        Log.d(TAG, "发送 RTSP 请求: " + buf);
        keyValues.clear();
        headers.clear();
        body = new byte[0];

        output.write(buf.getBytes("UTF-8"));
        output.flush();
        synchronized (lock) {
            lock.wait(TIMEOUT);
        }
        return headers.size() > 0;
    }

    /**
     * 发送数据到RTSP服务器，并等待结果返回，如果鉴权错误，会自动使用Digest重试一次
     * 例如: send("SETUP rtsp://192.168.1.1:554/abc\r\nTest: 1234", "SETUP")
     *
     * @param request 待发送的RTSP请求，只需要必须的内容即可，CSeq之类无需设置
     * @param cmd     请求的指令，例如 PLAY, PAUSE, SETUP等，必须大写
     * @return 成功返回 true，否则返回 false
     */
    public boolean send(String request, String cmd) {
        try {
            if (!doSend(request, cmd)) return false;

            if (getResponseCode() == 401) {  // 不支持 Basic 鉴权，使用 Digest 鉴权再试一次
                updateAuthorization();
                if (!doSend(request, cmd)) return false;
            }
            return headers.size() > 0;
        } catch (Exception e) {
            Log.e(TAG, "发送 RTSP 请求错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送RTSP Setup指令并等待返回结果
     *
     * @return 若服务器应答代码200，返回 true，发送失败或者服务器非200代码，返回 false
     */
    private boolean setup() {
        try {
            String request;
            if (udpMode) {
                rtpSocket = new DatagramSocket();
                request = String.format("SETUP %s RTSP/1.0\r\nTransport: RTP/AVP;unicast;client_port=%d-%d\r\n",
                        control, rtpSocket.getLocalPort(), rtpSocket.getLocalPort() + 1);
            } else {
                // interleaved 指明了Channel的个数，默认 0 = RTP， 1 = RTCP 两个通道
                // 如果需要多个通道，请自定义对应格式处理即可
                request = String.format("SETUP %s RTSP/1.0\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", control);
            }
            if (!send(request, "SETUP")) return false;
            if (getResponseCode() != 200) return false;

            session = keyValues.get("Session");
            heart.schedule(new TimerTask() {
                @Override
                public void run() {
                    option();
                }
            }, 40 * 1000, 40 * 1000);

            if (udpMode) {
                Matcher matcher = REXEG_TRANSPORT.matcher(keyValues.get("Transport"));
                if (!matcher.find()) {
                    heart.cancel();
                    return false;
                }
                int serverPort = Integer.valueOf(matcher.group(1));
                rtpSocket.connect(new InetSocketAddress(InetAddress.getByName(server), serverPort));
                udpThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[2000];
                        DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                        while (!Thread.currentThread().isInterrupted() && rtpSocket != null) {
                            try {
                                rtpSocket.receive(receivePacket);
                                if (callback == null) continue;

                                try {
                                    callback.onPacket(0, receivePacket.getData(), receivePacket.getLength());
                                } catch (Exception e) {
//                                    Log.e(TAG, "RTP 包回调错误: " + e.getMessage());
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "RTP 收包错误: " + e.getMessage());
                                break;
                            }
                        }
                    }
                });
                udpThread.start();
                return rtpSocket.isConnected();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "RTSP Setup错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送心跳，部分RTSP服务器需要保活
     *
     * @return 若服务器返回200代码，返回 true，否则返回 false
     */
    private boolean option() {
        try {
            String request = String.format("OPTIONS %s RTSP/1.0\r\n", uri);
            if (!send(request, "OPTIONS")) return false;
            return getResponseCode() == 200;
        } catch (Exception e) {
            Log.e(TAG, "RTSP OPTIONS错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 开始取流
     *
     * @return 成功返回 true，服务器任意环节失败返回 false
     */
    public boolean start() {
        if (!connect()) return false;
        this.thread = new Thread(runnable);
        this.thread.start();

        if (!describe()) return false;
        if (!setup()) return false;
        if (!play("0.000-", 1)) return false;

        return true;
    }

    /**
     * 暂停播放
     *
     * @return 若服务器返回应答代码200则返回 true，否则返回 false
     */
    public boolean pause() {
        String request = String.format("PAUSE %s RTSP/1.0\r\n", uri);
        try {
            if (!send(request, "PAUSE")) return false;
            return getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取RTSP响应的状态码，状态码200表示服务器正常响应，具体代码请参考RFC文档
     *
     * @return 若没有或者出错返回 -1，否则返回服务器响应的状态码
     */
    private int getResponseCode() {
        Matcher matcher;
        for (String s : headers) {
            matcher = REGEX_STATUS.matcher(s);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    /**
     * 发送RTSP Describe指令，并处理返回结果
     *
     * @return 若服务器响应200，返回 true，否则返回 false
     */
    private boolean describe() {
        String request = String.format("DESCRIBE %s RTSP/1.0\r\nAccept: application/sdp\r\n", uri);
        try {
            if (!send(request, "DESCRIBE")) return false;
            if (getResponseCode() != 200) return false;

            // SDP 中包含了控制信息，需要提取对应信息
            // 有的RTSP服务器返回的 a=control:rtsp://192.168.1.248:554/h264/ch1/main/av_stream/trackID=1
            // 有的服务器返回的是 a=control:trackID=1
            String sdp = new String(body);
            String[] ss = sdp.split("\r\n");
            for (String s : ss) {
                if (s.startsWith("a=control:"))
                    control = s.replace("a=control:", "");
            }
            if (!control.toLowerCase().startsWith("rtsp://")) control = uri + "/" + control;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "RTSP Describe错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 更新鉴权信息
     */
    private void updateAuthorization() throws Exception {
        // 服务器响应格式: WWW-Authenticate: Digest realm="value", nonce="value", key="value", ...
        HashMap<String, String> kvs = new HashMap<>();
        for (String s : headers) {
            if (s.startsWith("WWW-Authenticate: Digest ")) {
                String ss[] = s.replace("WWW-Authenticate: Digest ", "").split(",");
                for (String kv : ss) {
                    String ss2[] = kv.split("=");
                    kvs.put(ss2[0].trim(), ss2[1].trim().replace("\"", ""));
                }

                nonce = kvs.get("nonce");
                realm = kvs.get("realm");
                if (nonce == null || realm == null) throw new Exception("无效 Digest 应答");

                hash1 = md5(user + ":" + realm + ":" + password);
                authDigest = true;
                return;
            } else if (s.startsWith("WWW-Authenticate: Basic ")) {
                realm = s.replace("WWW-Authenticate: Basic ", "");
                authDigest = false;
                return;
            }
        }
        throw new Exception("无效 Digest 应答");
    }

    /**
     * 播放流，带范围和速度控制
     *
     * @param range 开始范围：例如 0.000-8.000，表示开始的0~8秒，null表示暂停后恢复播放或默认播放
     * @param speed 播放速度，1 = 正常播放， 0.5表示慢速一倍，0.25表示慢速4倍，2表示两倍速，4表示四倍速，以此类推
     * @return true = 响应成功， false = 服务器端返回失败
     */
    public boolean play(String range, float speed) {
        String request = String.format("PLAY %s RTSP/1.0\r\n" +
                (range == null || range.equals("") ? "" : "Range: npt=" + range + "\r\n") +
                "Speed: %3.2f\r\n" +
                "Scale: %3.2f\r\n", uri, speed, speed);
        try {
            if (!send(request, "PLAY")) return false;
            return getResponseCode() == 200;
        } catch (Exception e) {
            Log.e(TAG, "RTSP Play错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 停止取流
     *
     * @return 总是返回 true
     */
    public boolean stop() {
        teardown();
        if (heart != null) heart.cancel();
        if (rtpSocket != null) rtpSocket.close();

        if (udpThread != null) udpThread.interrupt();
        if (this.thread != null) this.thread.interrupt();
        try {
            if (socket != null) socket.close();
        } catch (Exception e) {
        }

        socket = null;
        rtpSocket = null;
        thread = null;
        udpThread = null;
        return true;
    }

    /**
     * 停止播放，不检测服务器端返回结果，只要发送请求成功就返回true
     *
     * @return
     */
    private boolean teardown() {
        if (socket == null || !socket.isConnected()) return true;

        try {
            String request = String.format("TEARDOWN %s RTSP/1.0\r\n", uri);
            if (send(request, "TEARDOWN")) return false;
            return getResponseCode() == 200;
        } catch (Exception e) {
            Log.e(TAG, "RTSP Teardown错误: " + e.getMessage());
            return false;
        }
    }
}
