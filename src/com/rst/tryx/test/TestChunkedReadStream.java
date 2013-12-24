package com.rst.tryx.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.rst.tryx.SftpUtil;
import com.rst.tryx.SftpUtil.ChunkLimitInputStream;

public class TestChunkedReadStream {

	public static void main(String[] args) throws Exception {
		byte[] buf = new byte[2];
		byte[] buf2 = new byte[2];
		byte[] buf3 = new byte[3];
		
		ByteArrayInputStream bais = new ByteArrayInputStream("1234567890".getBytes());
		
		read(bais, buf);
		System.out.println(new String(buf));
		
		ChunkLimitInputStream cs = new ChunkLimitInputStream(bais, 10);
		
		read(cs, buf2);
		System.out.println(new String(buf2));
		
		read(bais, buf3);
		System.out.println(new String(buf3));
	}

	private static void read(InputStream is, byte[] buf) throws IOException {
		int count = 0;
		while (count < buf.length
				&& (count += is.read(buf, count, buf.length - count)) > -1)
			;
	}
}
