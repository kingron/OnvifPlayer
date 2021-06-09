package zhjinrui.com.OnvifPlayer;


/*
 Type Name
    0 [invalid]
    1 Coded slice
    2 Data Partition A
    3 Data Partition B
    4 Data Partition C
    5 IDR (Instantaneous Decoding Refresh) Picture
    6 SEI (Supplemental Enhancement Information)
    7 SPS (Sequence Parameter Set)
    8 PPS (Picture Parameter Set)
    9 Access Unit Delimiter
   10 EoS (End of Sequence)
   11 EoS (End of Stream)
   12 Filter Data
13-23 [extended]
24-31 [unspecified]
*/

/**
 * Encodes/Decodes RTP/H264 packets
 * http://tools.ietf.org/html/rfc3984
 *
 * @author pquiring, improved by Kingron
 */

import java.util.ArrayList;
import java.util.Arrays;

public class RTPH264 {
    /**
     * RTP包的时间序列，若解码，则为RTP包中的提取的时间序列；若编码，则自动按30fps递增，外部可以根据需要调整该值
     */
    public long timestamp;
    /**
     * RTP帧号，若解码，则为解码出来的RTP帧号，若编码，则自动递增
     */
    public int seqnum = 0;

    /**
     * HAL 帧类型， 0 ~ 32 之间
     * 5 = IDR, 6 = SEI, 7 = SPS, 8 = PPS etc....
     */
    public int type = 0;

    //mtu = 1500 - 14(ethernet) - 20(ip) - 8(udp) - 12(rtp) = 1446 bytes payload per packet
    private static final int mtu = 1440;
    private final int ssrc;
    private byte partial[] = new byte[0];
    private int lastseqnum = -1;
    private long last_timestamp = -1;

    public RTPH264(int mssrc) {
        ssrc = mssrc;
    }

    /**
     * Builds RTP header in first 12 bytes of data[].
     */
    private static void buildHeader(byte data[], int id, int seqnum, long timestamp, int ssrc, boolean last) {
        //build RTP header
        data[0] = (byte) 0x80;  //version
        data[1] = (byte) id;    //0=g711u 3=gsm 8=g711a 18=g729a 26=JPEG 34=H.263 etc.
        if (last) {
            data[1] |= 0x80;
        }
        BE.setuint16(data, 2, seqnum);
        BE.setuint32(data, 4, (int) timestamp);
        BE.setuint32(data, 8, ssrc);
    }

    private int find_best_length(byte data[], int offset, int length) {
        //see if there is a 0,0,1 and return a length to that
        //this way the next packet will start at a resync point
        for (int a = 1; a < length - 3; a++) {
            if (data[offset + a] == 0 && data[offset + a + 1] == 0 && data[offset + a + 2] == 1)
                return a;
        }
        return length;
    }

    /*
     * NAL Header : F(1) NRI(2) TYPE(5) : F=0 NRI=0-3 TYPE=1-23:full_packet 28=FU-A
     * FUA Header : S(1) E(1) R(1) TYPE(5) : S=start E=end R=reserved TYPE=???
     */

    /**
     * Encodes raw H.264 data into multiple RTP packets.
     * 编码H264裸数据为多个RTP包，可以使用UDP或者RTP Over TCP发送
     *
     * @param data        待编码的原始H264数据，data为 H264裸帧数据，可包含001等帧同步数据，若有会自动跳过
     * @param payloadType 编码后的RTP Payload代码，一般H264为96
     */
    public byte[][] encode(byte data[], int payloadType) {
        ArrayList<byte[]> packets = new ArrayList<byte[]>();
        int len = data.length;
        int packetLength;
        int offset = 0;
        byte packet[];
        while (len > 0) {
            //skip 0,0,1
            while (data[offset] == 0) {
                offset++;
                len--;
            }
            offset++;
            len--;  //skip 1
            if (len > mtu) {
                packetLength = find_best_length(data, offset, len);
            } else {
                packetLength = len;
            }
            if (packetLength > mtu) {
                //need to split up into Frag Units (mode A)
                int nalLength = mtu - 2;
                byte type = (byte) (data[offset] & 0x1f);
                byte nri = (byte) (data[offset] & 0x60);
                offset++;
                len--;
                packetLength--;
                boolean first = true;
                while (packetLength > nalLength) {
                    packet = new byte[12 + 2 + nalLength];
                    buildHeader(packet, payloadType, seqnum++, timestamp, ssrc, false);
                    packet[12] = 28;  //FU-A
                    packet[12] |= nri;
                    packet[13] = type;
                    if (first) {
                        packet[13] |= 0x80;  //first FU packet
                        first = false;
                    }
                    System.arraycopy(data, offset, packet, 14, nalLength);
                    offset += nalLength;
                    len -= nalLength;
                    packetLength -= nalLength;
                    packets.add(packet);
                }
                //add last NAL packet
                nalLength = packetLength;
                packet = new byte[12 + 2 + nalLength];
                buildHeader(packet, payloadType, seqnum++, timestamp, ssrc, len == nalLength);

                packet[12] = 28;  //F=0 TYPE=28 (FU-A)
                packet[12] |= nri;
                packet[13] = type;
                packet[13] |= 0x40;  //last FU packet
                System.arraycopy(data, offset, packet, 14, nalLength);
                offset += nalLength;
                len -= nalLength;
                packets.add(packet);
            } else {
                packet = new byte[packetLength + 12];  //12=RTP.length
                buildHeader(packet, payloadType, seqnum++, timestamp, ssrc, len == packetLength);
                System.arraycopy(data, offset, packet, 12, packetLength);
                packets.add(packet);
                offset += packetLength;
                len -= packetLength;
            }
        }
        timestamp += 90000 / 30;  //??? 10 fps ???
        return packets.toArray(new byte[0][0]);
    }

    /**
     * 获取一个RTP包的类型
     *
     * @param rtp 待判断的RTP包
     * @return 如果包类型代码，例如 28， 6，7，8等，包类型参考前述SPS，PPS等注释
     */
    public int getType(byte rtp[]) {
        if (rtp.length < 14) return 0;

        int fu_header_len = 12;
        int extension = (rtp[0] >> 4) & 1;  // X: 扩展为是否为1
        if (extension > 0) {
            // 计算扩展头的长度
            int extLen = (rtp[14] << 8) + rtp[15];
            fu_header_len += (extLen + 1) * 4;
        }

        int type = rtp[fu_header_len] & 0x1f;
        return type;
    }

    private static int gettimestamp(byte[] data, int off) {
        return BE.getuint32(data, 4 + off);
    }

    private static int getseqnum(byte[] data, int off) {
        return BE.getuint16(data, 2 + off);
    }

    /**
     * Returns last full packet.
     * 解码RTP包，获取完整的H264帧，返回的数据可以送入解码器解码
     *
     * @param rtp    待解码的RTP包，包含12字节RTP包头的包
     * @param length 包长度
     */
    public byte[] decode(byte rtp[], int length) {
        int fu_header_len = 12;
        if (length < 12 + 2) return null;  // bad packet

        int extension = (rtp[0] >> 4) & 1;  // X: 扩展为是否为1
        if (extension > 0) {
            if (length < fu_header_len + 4) return null;
            // 计算扩展头的长度
            int extLen = (rtp[14] << 8) + rtp[15];
            fu_header_len += (extLen + 1) * 4;
        }

        int h264Length = length - fu_header_len;
        if (h264Length == 0) return null; // 空包，原样返回一个空包！

        type = rtp[fu_header_len] & 0x1f;
        timestamp = gettimestamp(rtp, 0) & 0xFFFFFFFFL;
        if (partial == null) partial = new byte[0];
        byte[] ret = null;
        if (type == 28) {
            if (last_timestamp == -1) last_timestamp = timestamp;

            if (timestamp != last_timestamp && partial.length > 0) {
                ret = partial;
                partial = new byte[0];
            }

            //FU-A Packet
            if ((rtp[13] & 0x80) == 0x80) {
                //first NAL packet (restore first byte)
                int nri = rtp[12] & 0x60;
                type = rtp[13] & 0x1f;
                partial = Arrays.copyOf(partial, partial.length + 5);
                partial[partial.length - 2] = 1;  //0,0,0,1
                partial[partial.length - 1] = (byte) (nri + type);  //NRI TYPE (first byte)
                lastseqnum = getseqnum(rtp, 0);
            } else {
                seqnum = getseqnum(rtp, 0);
                if (seqnum != lastseqnum + 1) {
                    partial = null;
                    lastseqnum = -1;
                    last_timestamp = -1;
                    return null;
                }
                lastseqnum = seqnum;
            }

            last_timestamp = timestamp;
            int partialLength = partial.length;
            h264Length -= 2;
            partial = Arrays.copyOf(partial, partial.length + h264Length);
            System.arraycopy(rtp, fu_header_len + 2, partial, partialLength, h264Length);
        } else {
            ret = new byte[h264Length + 3];
            ret[0] = 0;
            ret[1] = 0;
            ret[2] = 1;
            System.arraycopy(rtp, fu_header_len, ret, 3, h264Length);
        }

        return ret;
    }

    private boolean leak = false;
    private int leak_len = 0;

    /**
     * 从裸数据中，提取 0xE0的PES包
     *
     * @param data 待提取的原始二进制数据流，可不间断输入
     * @return 返回提取的多个PES包，每个PES包为一个数组元素
     */
    public byte[][] decode2(byte data[]) {
        ArrayList<byte[]> packets = new ArrayList<>();

        int start = 2;
        if (leak) {  // 缺少包
            if (leak_len > data.length) {  /// 仍然不够
                int partialLength = partial.length;
                partial = Arrays.copyOf(partial, partial.length + data.length);
                System.arraycopy(data, 0, partial, partialLength, data.length);
                return null;
            } else {
                int partialLength = partial.length;
                partial = Arrays.copyOf(partial, partial.length + leak_len);
                System.arraycopy(data, 0, partial, partialLength, leak_len);
                byte[] full = partial;
                packets.add(full);

                partial = new byte[0];
                start = leak_len;
                leak_len = 0;
                leak = false;
            }
        }

        while (start < data.length) {
            start++;
            if (data[start - 3] == 0 && data[start - 2] == 0 && data[start - 1] == 1 && data[start] == -32) { // 找到 0x000001E0
                int len = ((data[start + 1] & 0xFF) << 8) | (data[start + 2] & 0xff);
                start += 3;
                if (start + len > data.length) { // 本次不足长度
                    leak_len = data.length - start;
                    len = data.length - start;
                    int partialLength = partial.length;
                    partial = Arrays.copyOf(partial, partial.length + len);
                    System.arraycopy(data, start, partial, partialLength, len);
                    leak = true;
                    break;
                } else // 本次足够长度
                {
                    byte[] full = new byte[len];
                    System.arraycopy(data, start, full, 0, len);
                    packets.add(full);
                    start += len;
                } // end 本次足够长度
            }  // if 找到 0x000001E0
        } // end while start < rtp.length

        return packets.toArray(new byte[0][0]);
    }

}
