package com.rst.tryx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import com.rst.tryx.SftpUtil.ChunkLimitInputStream;
import com.rst.tryx.SftpUtil.ReqHeader;
import com.rst.tryx.SftpUtil.RespHeader;

public class TestClient {

	private static String HOST = "localhost";

	private static int PORT = 62953;
	
	private static final int READ_TIMEOUT = 20000; // 20s
	
	private static final int SOCK_PF_CT = 0; // connection time.
	private static final int SOCK_PF_LL = 0; // low latency.
	private static final int SOCK_PF_HB = 1; // high bandwidth.

	public static void main(String[] args) {
		Socket sock = null;
		OutputStream sockOutputStream = null;
		InputStream sockInputStream = null;
		FileInputStream fis = null;
		ChunkLimitInputStream chunkedStream = null;
		try {
			sock = new Socket(HOST, PORT);
			sock.setPerformancePreferences(
					SOCK_PF_CT, 
					SOCK_PF_LL,
					SOCK_PF_HB);
			sock.setKeepAlive(true);
			sock.setSoTimeout(READ_TIMEOUT);
			sock.setTcpNoDelay(true);

			sockOutputStream = sock.getOutputStream();
			sockInputStream = sock.getInputStream();

			String csId = "";
			String opToken = "";
			long lastUploadedBytes = 0;
			//File srcFile = new File("c:/ubuntu-13.04-desktop-i386.iso");
			File srcFile = new File("c:/project/webframe-master.zip");
			long fileLen = srcFile.length();
			
			ReqHeader header = ReqHeader.create(csId, opToken, fileLen, fileLen - lastUploadedBytes);
			SftpUtil.sendReq(sockOutputStream, header);

//			byte[] cmdStx = new byte[SftpUtil.CMD_LEN];
//			SftpUtil.setCmd(cmdStx, 0, SftpUtil.CMD_STX);
//
//			os.write(cmdStx);
//			os.flush();
			
			SftpUtil.sendCmd(sockOutputStream, SftpUtil.CMD_STX);

//			byte[] cmdEtb = new byte[SftpUtil.CMD_LEN];
//			SftpUtil.setCmd(cmdEtb, 0, SftpUtil.CMD_ETB);

			fis = new FileInputStream(srcFile);
			chunkedStream = new ChunkLimitInputStream(fis, SftpUtil.CHUNK_SIZE);
			byte[] buf = new byte[10240];
			int countOfClip = 0;
			int countBytesCurrentSession = 0;

			// int countOfChunk = 0;
			while ((countOfClip = chunkedStream.read(buf)) > -1) {

				if (0 < countOfClip) {
					// countOfChunk += countOfClip;
					countBytesCurrentSession += countOfClip;
					sockOutputStream.write(buf, 0, countOfClip);
					sockOutputStream.flush();
				}

				if (chunkedStream.isChunkLimit()) {
					
					SftpUtil.sendCmd(sockOutputStream, SftpUtil.CMD_ETB);
					
					// countOfChunk = 0;
					chunkedStream.resetChunkLimit();
				}
			}

//			System.out.println("read file total: " + countBytesCurrentSession);
			SftpUtil.sendCmd(sockOutputStream, SftpUtil.CMD_EOT);
			
			RespHeader resp = SftpUtil.receiveResp(sockInputStream);
			if (resp.isSuccess()) {
				System.out.println("TRANSFER SUCCESS.");
				return;
			}
			
			System.out.println("TRANSFER FAILURE.");

		} catch (IOException ioe) {
			ioe.printStackTrace();
		} finally {
			try {
				if (null != sockOutputStream) {
					sockOutputStream.close();
				}
			} catch (IOException ioe) {
				sockOutputStream = null;
			}
			try {
				if (null != sockInputStream) {
					sockInputStream.close();
				}
			} catch (IOException ioe) {
				sockInputStream = null;
			}
			try {
				if (null != chunkedStream) {
					chunkedStream.close();
				}
			} catch (IOException ioe) {
				chunkedStream = null;
			}
			try {
				if (null != fis) {
					fis.close();
				}
			} catch (IOException ioe) {
				fis = null;
			}
			try {
				if (null != sock) {
					sock.close();
				}
			} catch (IOException ioe) {
				sock = null;
			}
		}
	}
}
