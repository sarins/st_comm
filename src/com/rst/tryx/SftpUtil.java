package com.rst.tryx;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 *  //根据ascii控制字符确定, 首字节置0x9d,次字节填充ascii控制字符,如下:
 *	private static final byte UPLOAD_REQ    = (byte)0xff; //0000 0101 | 0x05 | ENQ (enquiry)
 *	private static final byte UPLOAD_ST_OK  = (byte)0xfe; //0000 0110 | 0x06 | ACK (acknowledge)
 *	private static final byte UPLOAD_ST_ERR = (byte)0xfd; //0001 0101 | 0x15 | NAK (negative acknowledge)
 *	
 *  request header size = 2 + 64 + 64 + 8 + 8 + 4
 *	private static String request = "" +
 *			"|CMD_SOH[2bytes]" +
 *			"|fileCsId[64bytes]" +
 *			"|operationToken[64bytes]" +
 *          "|fileLength[8bytes]" +
 *			"|transferContentLength[8bytes]" +
 *          "|trunkLength[4bytes]" +
 *          "|CMD_STX[2bytes]"
 *			"|fileTrunkContent..."
 *          "|CMD_ETB[2bytes]"
 *          "|fileTrunkContent..."
 *          "|CMD_ETB[2bytes]"
 *          "|fileTrunkContent..."
 *          "|CMD_ETB[2bytes]"
 *          ....behind last block....
 *          "|CMD_EOT[2bytes]"
 *          ;
 *	
 *  response header size = 2
 *	private static String response = "" +
 *			"|cmd_st[2bytes]" +
 *			remove---"|fileCsId[64bytes]" +
 *			remove---"|operationToken[64bytes]" +
 *          remove---"|message[64bytes]" +
 *
 * CMD format:  
 *     
 *     00000000 0x00 success
 *     00000001 0x01 failure
 * 
 * 7   : none,
 * 6   : is failure,
 * 5   : is warn,
 * 4   : is success,
 * 3-0 : command code.
 * ================================
 * bits[7|6|5|4|3210] Hex
 *     [x|?|?|?|0000] 0x00  CMD_UNKNOW
 *     [x|?|?|?|0001] 0x01  (reverse)
 *     [x|?|?|?|0010] 0x02  CMD_SOH
 *     [x|?|?|?|0011] 0x03  CMD_STX
 *     [x|?|?|?|0100] 0x04  CMD_EOT
 *     [x|?|?|?|0101] 0x05  CMD_ETB
 *     [x|?|?|?|0110] 0x06  CMD_CAN
 *     [x|?|?|?|0111] 0x07  CMD_STOP
 *     [x|?|?|?|1000] 0x08  
 *     [x|?|?|?|1001] 0x09
 *     [x|?|?|?|1010] 0x0a
 *     [x|?|?|?|1011] 0x0b
 *     [x|?|?|?|1100] 0x0c  
 *     [x|?|?|?|1101] 0x0d
 *     [x|?|?|?|1110] 0x0e
 *     [x|?|?|?|1111] 0x0f
 * =================================
 * 
 * 
 * 
 * @author axl
 *
 */
public class SftpUtil {

	public static final byte CMD_PREFIX = (byte) 0x9d;
	
	private static final byte CMD_F_MASK = (byte)0x0f;
	
	private static final byte CMD_SUCCESS_MASK = (byte)0xef;
	private static final byte CMD_WARN_MASK = (byte)0xdf;
	private static final byte CMD_FAILURE_MASK = (byte)0xbf;
	
	public static final byte CMD_SUCCESS = 1 << 4;
	public static final byte CMD_WARN = 1 << 5;
	public static final byte CMD_FAILURE = 1 << 6;
	
	// request command define
	public static final byte CMD_UNKNOW = 0x0; // null command
	public static final byte CMD_SOH = 0x02; // Start of Heading
	
	public static final byte CMD_STX = 0x03; // Start of Text

	public static final byte CMD_EOT = 0x04; // End of Transmission

	public static final byte CMD_ETB = 0x05; // End of transmit block.

	public static final byte CMD_CAN = 0x06; // Cancel.
	
	public static final byte CMD_STOP = 0x07; // Stop transfer.


	public static final int REQ_HEADER_LEN = 150;

	public static final int RESP_HEADER_LEN = 2;

	public static final int CHUNK_SIZE = 1024 * 1024 * 10; // 10M

	public static final int CMD_LEN = 2;

	public static final int FILECSID_LEN = 64;

	public static final int OPTOKEN_LEN = 64;
	
	public static final int MSG_LEN = 64;

	public static final int TYPE_LONG_LEN = 8;

	public static final int TYPE_INT_LEN = 4;

	
	private static void boundsCheck(byte[] buf, int startIdx, int accessLen) {
		if (null == buf) {
			throw new SftpException("buf was a null pointer.");
		}
		if (startIdx + accessLen > buf.length) {
			throw new SftpException("buf access was out of bounds.");
		}
	}
	
	public static boolean validateCmd(byte[] buf, byte targetCmd) {
		return validateCmd(getCmd(buf), targetCmd);
	}
	
	public static boolean validateCmd(Byte cmd, byte targetCmd) {
		if (null == cmd) return false;
		return cmd.equals(targetCmd);
	}
	
	public static byte getCmd(byte[] buf) {
		return getCmd(buf, 0);
	}
	
	public static byte getCmd(byte[] buf, int startIdx) {
		boundsCheck(buf, startIdx, CMD_LEN);
		if (CMD_PREFIX == buf[startIdx]) {
			return (byte)(buf[startIdx + 1] & CMD_F_MASK);
		}
		return CMD_UNKNOW;
	}
	
	public static byte getOrgCmd(byte[] buf) {
		return getOrgCmd(buf, 0);
	}
	
	public static byte getOrgCmd(byte[] buf, int startIdx) {
		boundsCheck(buf, startIdx, CMD_LEN);
		if (CMD_PREFIX == buf[startIdx]) {
			return buf[startIdx + 1];
		}
		return CMD_UNKNOW;
	}

	public static void setCmd(byte[] buf, int startIdx, byte cmd) {
		boundsCheck(buf, startIdx, CMD_LEN);
		buf[startIdx] = CMD_PREFIX;
		buf[startIdx + 1] = cmd;
	}
	
	
	// boost preference method
	public static void sendRequestHeader(OutputStream os) {
		
	}
	
	public static void sendCmd(OutputStream os, byte cmd) throws IOException {
		os.write(CMD_PREFIX);
		os.write(cmd);
		os.flush();
	}
	
	public static Byte receiveCmd(InputStream is) throws IOException {
		byte[] cmdBuf = new byte[CMD_LEN];
		blockRead(is, cmdBuf);
		return getCmd(cmdBuf, 0);
	}
	
	public static boolean receiveCmdVerify(InputStream is, byte targetCmd)
			throws IOException {
		return validateCmd(receiveCmd(is), targetCmd);
	}
	
	public static void sendReq(OutputStream os, ReqHeader req) throws IOException {
		safeWritf(os, req.header);
	}
	
	public static ReqHeader receiveReq(InputStream is) throws IOException {
		ReqHeader req = new ReqHeader();
		blockRead(is, req.header);
		return req;
	}
	
	public static void sendResp(OutputStream os, RespHeader resp) throws IOException {
		safeWritf(os, resp.header);
	}
	
	public static RespHeader receiveResp(InputStream is) throws IOException {
		RespHeader resp = new RespHeader();
		blockRead(is, resp.header);
		return resp;
	}

	public static String getFileCsId(byte[] buf, int startIdx) {
		return getString(buf, startIdx, FILECSID_LEN);
	}

	public static void setFileCsId(byte[] buf, int startIdx, String fileCsId) {
		setString(buf, fileCsId, startIdx, FILECSID_LEN);
	}

	public static String getOperationToken(byte[] buf, int startIdx) {
		return getString(buf, startIdx, OPTOKEN_LEN);
	}

	public static void setOperationToken(byte[] buf, int startIdx,
			String operationToken) {
		setString(buf, operationToken, startIdx, OPTOKEN_LEN);
	}

	public static void main(String[] args) {
//		byte[] buf = new byte[120];
//		setString(buf, "xy", 0, 3);
		byte f = CMD_CAN | CMD_FAILURE;
		byte s = CMD_CAN | CMD_SUCCESS;
		byte w = CMD_CAN | CMD_WARN;
//		
		System.out.println(((f | CMD_FAILURE_MASK) & CMD_FAILURE) == CMD_FAILURE);
		System.out.println(((s | CMD_SUCCESS_MASK) & CMD_SUCCESS) == CMD_SUCCESS);
		System.out.println(((w | CMD_WARN_MASK) & CMD_WARN) == CMD_WARN);
	}
	
	private static String getString(byte[] buf, int startIdx, int len) {
		boundsCheck(buf, startIdx, len);
		return new String(buf, startIdx, len);
	}

	private static void setString(byte[] buf, String s, int startIdx, int len) {
		if (null == s) return;
		boundsCheck(buf, startIdx, len);
		byte[] sb = s.getBytes();
		if(sb.length < len) {
			System.arraycopy(sb, 0, buf, startIdx, sb.length);
			Arrays.fill(buf, startIdx + sb.length, startIdx + len, (byte)0);
			return;
		}
		System.arraycopy(sb, 0, buf, startIdx, len);
	}

	public static Integer getInt(byte[] buf, int startIdx) {
		boundsCheck(buf, startIdx, TYPE_INT_LEN);
		return ByteBuffer.wrap(buf).getInt(startIdx);
	}

	public static void setInt(byte[] buf, int startIdx, int val) {
		boundsCheck(buf, startIdx, TYPE_INT_LEN);
		ByteBuffer.wrap(buf).putInt(startIdx, val);
	}

	public static Long getLong(byte[] buf, int startIdx) {
		boundsCheck(buf, startIdx, TYPE_LONG_LEN);
		return ByteBuffer.wrap(buf).getLong(startIdx);
	}

	public static void setLong(byte[] buf, int startIdx,  long val) {
		boundsCheck(buf, startIdx, TYPE_LONG_LEN);
		ByteBuffer.wrap(buf).putLong(startIdx, val);
	}

	public static class SftpException extends RuntimeException {
		private static final long serialVersionUID = 5734463839431053027L;

		public SftpException(String msg) {
			super(msg);
		}
	}

	public static class ReqHeaderDefine {
		public static final int CMD_IDX = 0;
		public static final int FILECSID_IDX = 2;
		public static final int OPTOKEN_IDX = 66;
		public static final int FILE_LEN_IDX = 130;
		public static final int TRANSFER_LEN_IDX = 138;
		public static final int CHUNK_LEN_IDX = 146;
	}

	public static class RespHeaderDefine {
		public static final int CMD_IDX = 0;
	}
	
	public static boolean blockRead(InputStream is, byte[] buf) throws IOException {
		if (null == is || null == buf) {
			return false;
		}
		int count = 0;
		while (count < buf.length
				&& (count += is.read(buf, count, buf.length - count)) > -1);
		return true;
	}
	
	public static boolean safeWritf(OutputStream os, byte[] data) throws IOException {
		if (null == os || null == data) {
			return false;
		}
		os.write(data);
		os.flush();
		return true;
	}
	
	public static boolean safeWrite(OutputStream os, byte[] data) throws IOException {
		if (null == os || null == data) {
			return false;
		}
		os.write(data);
		return true;
	}
	
	public static class ReqHeader {
		private byte[] header;
		
		private ReqHeader() {
			this(new byte[REQ_HEADER_LEN]);
		}
		private ReqHeader(byte[] header) {
			this.header = header;
		}
		
		public static ReqHeader create() {
			ReqHeader req = new ReqHeader();
			req.setCmd(CMD_SOH);
			return req;
		}
		
		public static ReqHeader create(String csId, String opToken,
			long fileLength, long transferLength) {
			ReqHeader req = create();
			req.setCsId(csId);
			req.setOpToken(opToken);
			req.setFileLength(fileLength);
			req.setTransferLength(transferLength);
			req.setChunkLength(CHUNK_SIZE);
			return req;
		}
		
		public Byte getCmd() {
			return SftpUtil.getCmd(header);
		}
		public void setCmd(byte cmd) {
			SftpUtil.setCmd(header, ReqHeaderDefine.CMD_IDX, cmd);
		}
		public String getCsId() {
			return SftpUtil.getString(header, ReqHeaderDefine.FILECSID_IDX, FILECSID_LEN);
		}
		public void setCsId(String csId) {
			SftpUtil.setString(header, csId, ReqHeaderDefine.FILECSID_IDX, FILECSID_LEN);
		}
		public String getOpToken() {
			return SftpUtil.getString(header, ReqHeaderDefine.OPTOKEN_IDX, OPTOKEN_LEN);
		}
		public void setOpToken(String opToken) {
			SftpUtil.setString(header, opToken, ReqHeaderDefine.OPTOKEN_IDX, OPTOKEN_LEN);
		}
		public long getFileLength() {
			return SftpUtil.getLong(header, ReqHeaderDefine.FILE_LEN_IDX);
		}
		public void setFileLength(long fileLength) {
			SftpUtil.setLong(header, ReqHeaderDefine.FILE_LEN_IDX, fileLength);
		}
		public long getTransferLength() {
			return SftpUtil.getLong(header, ReqHeaderDefine.TRANSFER_LEN_IDX);
		}
		public void setTransferLength(long transferLength) {
			SftpUtil.setLong(header, ReqHeaderDefine.TRANSFER_LEN_IDX, transferLength);
		}
		public int getChunkLength() {
			return SftpUtil.getInt(header, ReqHeaderDefine.CHUNK_LEN_IDX);
		}
		public void setChunkLength(int chunkLength) {
			SftpUtil.setInt(header, ReqHeaderDefine.CHUNK_LEN_IDX, chunkLength);
		}
	}
	
	public static class RespHeader {
		private byte[] header;
		
		private RespHeader() {
			this(new byte[RESP_HEADER_LEN]);
		}
		private RespHeader(byte[] header) {
			this.header = header;
		}
		
		public static RespHeader success(byte cmd) {
			return __create((byte)(cmd | CMD_SUCCESS));
		}
		
		public static RespHeader wran(byte cmd) {
			return __create((byte)(cmd | CMD_WARN));
		}
		
		public static RespHeader failure(byte cmd) {
			return __create((byte)(cmd | CMD_FAILURE));
		}
		
		private static RespHeader __create(byte status) {
			RespHeader resp = new RespHeader();
			resp.setCmd(status);
			return resp;
		}
		
		public boolean isSuccess() {
			return ((getOrgCmd(header) | CMD_SUCCESS_MASK) & CMD_SUCCESS) == CMD_SUCCESS;
		}
		public boolean isWran() {
			return ((getOrgCmd(header) | CMD_WARN_MASK) & CMD_WARN) == CMD_WARN;
		}
		public boolean isFailure() {
			return ((getOrgCmd(header) | CMD_FAILURE_MASK) & CMD_FAILURE) == CMD_FAILURE;
		}
		public byte getCmd() {
			return SftpUtil.getCmd(header);
		}
		public void setCmd(byte cmd) {
			SftpUtil.setCmd(header, RespHeaderDefine.CMD_IDX, cmd);
		}
	}
	
//	public static class Message {
//		
//	}
	
	public static class ChunkLimitInputStream extends FilterInputStream {
		
		private int chunkSize;
		private int countOfChunkRead;
		
		public ChunkLimitInputStream(InputStream is, int chunkSize) {
			super(is);
			this.chunkSize = chunkSize;
			resetChunkLimit();
		}
		
		public void resetChunkLimit() {
			countOfChunkRead = 0;
		}
		
		public boolean isChunkLimit() {
			return countOfChunkRead >= chunkSize;
		}

		@Override
		public int read(byte[] b) throws IOException {
			return read(b, 0, b.length);
		}
		
		@Override
		public synchronized int read(byte[] b, int off, int len)
				throws IOException {
			int readCount;
			if(countOfChunkRead + len <= chunkSize) {
				readCount = super.read(b, off, len);
			} else {
				readCount = super.read(b, off, chunkSize - countOfChunkRead);
			}
			countOfChunkRead += readCount;
			return readCount;
		}
	}
}
