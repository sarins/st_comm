package com.rst.tryx;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.activation.MimetypesFileTypeMap;
import javax.swing.JApplet;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

import com.rst.tryx.SftpUtil.RespHeader;



public class ResumeUploadApplet extends JApplet {

	/**
	 * serialVersionUID
	 */
	private static final long serialVersionUID = 4745155080907340562L;

	public static final String FIELD_NAME_UPLOAD_FILE_INSTANCE = "UPLOAD_FILE_INSTANCE";
	public static final String FIELD_NAME_UPLOAD_THUMBNAIL_INSTANCE = "UPLOAD_THUMBNAIL_INSTANCE";

	public static final String PARAM_FILECSID = "fileCsId";
	public static final String PARAM_FILENAME = "fileName";
	public static final String PARAM_LASTMODIFYDATE = "lastModifyDate";
	public static final String PARAM_FILESIZE = "fileSize";
	public static final String PARAM_FILETYPE = "fileType";
	public static final String PARAM_FORCE_OP = "forceOperation";
	public static final String PARAM_REQUESTTOKEN = "requestToken";
	public static final String PARAM_THUMBNAIL_UPLOAD = "thumbnailUpload";

	// RESP FLAG DEFINE
	public static final String RESP_FLAG_OK = "OK";
	public static final String RESP_FLAG_PARAM_ERR = "PARAM_ERR";
	public static final String RESP_FLAG_INPROGRESS_ERR = "INPROGRESS_ERR";
	public static final String RESP_FLAG_COMPLETED_WARN = "COMPLETED_WARN";
	public static final String RESP_FLAG_NOT_COMPLETED_ERR = "NOT_COMPLETED_ERR";
	public static final String RESP_FLAG_FILEBYTES_ERR = "FILEBYTES_ERR";
	public static final String RESP_FLAG_REQUEST_FMT_ERR = "REQUEST_FMT_ERR";
	public static final String RESP_FLAG_REQUEST_CONTENT_ERR = "REQUEST_CONTENT_ERR";
	public static final String RESP_FLAG_REQUEST_TOKEN_ERR = "REQUEST_TOKEN_ERR";
	public static final String RESP_FLAG_IO_ERR = "IO_ERR";

	private static final String OP_INIT = "[上传初始化操作]";
	private static final String OP_DELETE = "[删除文件操作]";
	private static final String OP_UPLOAD = "[上传文件操作]";
	private static final String OP_CANCEL = "[取消上传操作]";
	private static final String OP_STOP = "[中断上传操作]";
	private static final String OP_ST_OK = "处理成功";
	private static final String OP_ST_FAIL = "处理失败";

	public static final String PARAM_UPLOAD_FILE_ELE_ID = "uploadFileElementId";
	private static final String DEFAULT_UPLOAD_FILE_ELE_ID = "uploadFileId";

	private static final byte MAX_RETRY_TIMES = 3;

	private static final String BLANK_STR = "";

	private static final int READ_TIMEOUT = 10000; // never timeout

	private static final int SOCK_PF_CT = 0; // connection time.
	private static final int SOCK_PF_LL = 0; // low latency.
	private static final int SOCK_PF_HB = 1;

	private static final int FILENAME_MAX_LEN = 25;

	private static final String FILENAME_ELLIPSIS = "...";

	private static String HOST = "localhost";

	private static int PORT = 62953;

	private static String SERVICE_URL = "http://localhost:8080/cl/ResumeUploadServlet";

	private static final MimetypesFileTypeMap MIMETYPE_MAPPER = new MimetypesFileTypeMap();

//	private static CloseableHttpClient HTTP_CLIENT;

	private JPanel mainPanel;
	// private JPanel centerPanel;
	private JPanel bottomPanel;
	private JPanel statusPanel;

	private JLabel statusLabel;

	private JPanel filenamePanel;
	private JPanel fileSizePanel;
	private JLabel filenameLabel;
	private JLabel fileSizeLabel;
	private JLabel filenameTitle;
	private JLabel fileSizeTitle;

	private JPanel progressPanel;
	private JProgressBar progressBar;

	private JButton browseButton;
	private JButton uploadButton;
	private JButton deleteButton;

	private JFileChooser fileChooser;
	private File uploadTargetFile;

	private JsonResponse currentResp;
	private byte reTryTime;
	private String uploadFileElementId;

	private SftpUploadWorker sftpUploadWorker;

	@Override
	public void init() {
		super.init();

		InputStream propIs = null;
		try {
			propIs = ResumeUploadApplet.class
					.getResourceAsStream("/upload_conf.properties");
			Properties prop = new Properties();
			prop.load(propIs);
			if (null != propIs) {
				propIs.close();
			}
			SERVICE_URL = prop.getProperty("service_url");
			HOST = prop.getProperty("uploadServerHost");
			PORT = Integer.valueOf(prop.getProperty("uploadServerPort"));

		} catch (IOException ioe) {
		} finally {
			try {
				if (null != propIs) {
					propIs.close();
				}
			} catch (IOException innerIoe) {
				propIs = null;
			}
		}

		if (null == SERVICE_URL) {
			System.exit(1);
		}

		reTryTime = MAX_RETRY_TIMES;

		if (null != this.getParameter(PARAM_UPLOAD_FILE_ELE_ID)
				&& !BLANK_STR.equals(this
						.getParameter(PARAM_UPLOAD_FILE_ELE_ID))) {
			uploadFileElementId = this.getParameter(PARAM_UPLOAD_FILE_ELE_ID);
		} else {
			uploadFileElementId = DEFAULT_UPLOAD_FILE_ELE_ID;
		}

		// 初始化面板设置
		this.mainPanel = new JPanel();
		this.mainPanel.setLayout(new GridLayout(5, 1));
		this.setContentPane(mainPanel);

		initCenterPanel();
		mainPanel.add(filenamePanel);
		mainPanel.add(fileSizePanel);
		mainPanel.add(progressPanel);

		initBottomPanel();
		this.mainPanel.add(bottomPanel);

		initStatusPanel();
		this.mainPanel.add(statusPanel);

//		HTTP_CLIENT = HttpClients.createDefault();
//		if (null == HTTP_CLIENT) {
//			statusFail("HTTP引擎初始化操作");
//		}
	}

	private void initCenterPanel() {

		filenamePanel = new JPanel();
		filenamePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		filenameTitle = new JLabel(" 文件名称: ");
		filenameLabel = new JLabel();
		filenamePanel.add(filenameTitle);
		filenamePanel.add(filenameLabel);

		fileSizePanel = new JPanel();
		fileSizePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		fileSizeTitle = new JLabel(" 文件大小: ");
		fileSizeLabel = new JLabel();
		fileSizePanel.add(fileSizeTitle);
		fileSizePanel.add(fileSizeLabel);

		progressPanel = new JPanel();
		progressPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		progressPanel.add(initProgressBar());
		progressPanel.add(initBrowseButton());
	}

	private JProgressBar initProgressBar() {
		progressBar = new JProgressBar(0, 100);
		progressBar.setBackground(Color.WHITE);
		progressBar.setStringPainted(true);
		return progressBar;
	}

	private void initBottomPanel() {
		bottomPanel = new JPanel();
		bottomPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		bottomPanel.add(initUploadButton());
		bottomPanel.add(initDeleteButton());
	}

	private void initStatusPanel() {
		statusPanel = new JPanel();
		statusPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		statusLabel = new JLabel("准备就绪。");
		statusLabel.setForeground(Color.BLACK);
		statusPanel.add(statusLabel);
	}

	private JButton initBrowseButton() {
		browseButton = new JButton("浏览文件");
		browseButton.addActionListener(new BrowseButtonActionListener());
		return browseButton;
	}

	private JButton initUploadButton() {
		uploadButton = new JButton("开始上传");
		uploadButton.setEnabled(false);
		uploadButton.addActionListener(new UploadButtonActionListener());
		return uploadButton;
	}

	private JButton initDeleteButton() {
		deleteButton = new JButton("删除文件");
		deleteButton.setEnabled(false);
		deleteButton.addActionListener(new DeleteButtonActionListener());
		return deleteButton;
	}

	private void uploadFileSelected(File file) {
		reTryTime = MAX_RETRY_TIMES;
		String filePath = file.getAbsolutePath();
		if (FILENAME_MAX_LEN < filePath.length()) {
			filenameLabel.setText(filePath.substring(0, FILENAME_MAX_LEN)
					+ FILENAME_ELLIPSIS);
		} else {
			filenameLabel.setText(filePath);
		}
		filenameLabel.setToolTipText(filePath);
		fileSizeLabel.setText(FileAnalyse.number(file.length()));
		queryStatus(file, true);
	}

	void statusConnFail() {
		wran("网络连接异常，请重新选择上传文件。");
		browseButton.setEnabled(true);
	}

	void statusOk(String operation) {
		statusLabel.setForeground(Color.BLACK);
		statusLabel
				.setText(null == operation ? OP_ST_OK : operation + OP_ST_OK);
	}

	void statusFail(String operation) {
		statusLabel.setForeground(Color.RED);
		statusLabel.setText(null == operation ? OP_ST_FAIL : operation
				+ OP_ST_FAIL);
	}

	void info(String msg) {
		status(true, msg);
	}

	void wran(String msg) {
		status(false, msg);
	}

	void status(boolean isInfo, String msg) {
		statusLabel.setForeground(isInfo ? Color.BLACK : Color.RED);
		statusLabel.setText(msg);
	}

	private boolean queryStatus(File file, boolean isNoise) {
		return queryStatus(file, isNoise, false);
	}

	private boolean queryStatus(File file, boolean isNoise, boolean force) {
		String resp = httpGet(initQueryStatusUri(file, force));
		if (null == resp) {
			statusConnFail();
			return false;
		}
		try {
//			currentResp = GSON.fromJson(resp, JsonResponse.class);
		} catch (Exception e) {
			// parse response error.
			statusConnFail();
			return false;
		}
		if (null == currentResp || null == currentResp.flag
				|| BLANK_STR.equals(currentResp.flag.trim())) {
			// parse response error.
			statusConnFail();
			return false;
		}

		switch (currentResp.flag) {
		case RESP_FLAG_OK: {
			uploadTargetFile = file;
			progressBar.setValue(calcProgress(
					currentResp.files.get(0).uploadedBytes,
					uploadTargetFile.length()));
			statusOk(OP_INIT);
			uploadButton.setEnabled(true);
			return true;
		}
		case RESP_FLAG_COMPLETED_WARN: {
			statusOk(OP_INIT);
			if (isNoise) {
				if (confirm("该文件已经上传成功，是否删除该文件后重新上传。")) {
					// re upload.
					queryStatus(file, false, true);
				} else {
					// same with complete upload
					uploadTargetFile = file;
					jquery$Val(uploadFileElementId, currentResp.files.get(0).csid);
					progressBar.setValue(calcProgress(
							currentResp.files.get(0).uploadedBytes,
							uploadTargetFile.length()));
					deleteButton.setEnabled(true);
					return false;
				}
			}
			break;
		}
		case RESP_FLAG_INPROGRESS_ERR: {
			if (isNoise)
				alert("该文件正在上传，请勿重复操作。");
			break;
		}
		default: {
			if (isNoise)
				alert("上传文件数据不合法，系统无法处理。");
			statusFail(OP_INIT);
		}
		}
		return false;
	}

	private void deleteCallback(String resp) {
		if (null == resp) {
			statusConnFail();
			return;
		}
		try {
//			currentResp = GSON.fromJson(resp, JsonResponse.class);
		} catch (Exception e) {
			// parse response error.
			statusConnFail();
			return;
		}
		if (null == currentResp || null == currentResp.flag
				|| BLANK_STR.equals(currentResp.flag.trim())) {
			// parse response error.
			statusConnFail();
			return;
		}
		progressBar.setValue(0);
		browseButton.setEnabled(true);
		deleteButton.setEnabled(false);
		uploadButton.setEnabled(false);
		switch (currentResp.flag) {
		case RESP_FLAG_OK: {
			alert("删除文件完成。");
			filenameLabel.setText(BLANK_STR);
			fileSizeLabel.setText(BLANK_STR);
			statusOk(OP_DELETE);
			break;
		}
		case RESP_FLAG_INPROGRESS_ERR: {
			alert("该文件正在上传，无法删除，请勿重复操作，可选择其他文件进行操作。");
			statusFail(OP_DELETE);
			break;
		}
		case RESP_FLAG_PARAM_ERR: {
			alert("删除文件数据不合法，系统无法处理，可选择其他文件进行操作。");
			statusFail(OP_DELETE);
			break;
		}
		default: {
			alert("未知错误，系统无法处理，请联系系统管理员，可选择其他文件进行操作。");
			statusFail(OP_DELETE);
		}
		}
	}

	private void uploadDoneCallback(SftpUtil.RespHeader resp) {
		if (null == resp) {
			statusConnFail();
			return;
		}

		switch (resp.getCmd()) {
		case SftpUtil.CMD_EOT: {
			if (resp.isSuccess()) {
				alert("文件上传完成。");
				statusOk(OP_UPLOAD);
				jquery$Val(uploadFileElementId, currentResp.files.get(0).csid);
				deleteButton.setEnabled(true);
				return;
			} else {
				// upload failure
				// alert("文件上传失败。");
				break;
			}
		}
		case SftpUtil.CMD_CAN: {
			if (resp.isSuccess()) {
				alert("取消上传成功。");
				statusOk(OP_CANCEL);
				jquery$Val(uploadFileElementId, BLANK_STR);
				return;
			}
			statusFail(OP_CANCEL);
			alert("系统级异常，请联系系统管理员处理。");
			return;
		}
		case SftpUtil.CMD_STOP: {
			if (resp.isSuccess()) {
				alert("中断上传成功。");
				statusOk(OP_STOP);
				jquery$Val(uploadFileElementId, BLANK_STR);
				if (queryStatus(uploadTargetFile, false, true)) {
					uploadButton.setEnabled(true);
					statusOk(OP_INIT);
					return;
				}
			}
			statusFail(OP_STOP);
			alert("系统级异常，请联系系统管理员处理。");
			return;
		}
		case SftpUtil.CMD_ETB: {
			break;
		}
		default: {
			break;
		}
		}
		statusFail(OP_UPLOAD);
		alert("系统级异常，请联系系统管理员处理。");
	}

	private URI initDeleteUri(JsonResponse uploadedResp) {

		return null;
	}

	private URI initQueryStatusUri(File file, boolean force) {

		return null;
	}

	private static final String parseResponse(Object obj) throws IOException {

		return null;
	}

	private String httpDelete(URI uri) {

		return null;
	}

	private String httpGet(URI uri) {

		return null;
	}

	class ReceiveThread extends Thread {

		private InputStream sockInputStream;

		ReceiveThread(InputStream is) {
			sockInputStream = is;
		}

		@Override
		public void run() {
			try {
				if (null != sockInputStream) {
					long lastProgress = sftpUploadWorker.countBytesCurrentSession;
					while (true) {
						try {
							RespHeader resp = SftpUtil
									.receiveResp(sockInputStream);
							uploadDoneCallback(resp);
							return;
						} catch (SocketTimeoutException ste) {
							if (sftpUploadWorker.countBytesCurrentSession > lastProgress) {
								lastProgress = sftpUploadWorker.countBytesCurrentSession;
								continue;
							}
							uploadDoneCallback(null);
							break;
						}
					}
				}
			} catch (IOException ioe) {
				uploadDoneCallback(null);
			} finally {
				try {
					if (null != sockInputStream) {
						sockInputStream.close();
					}
				} catch (IOException ioe) {
					sockInputStream = null;
				}
			}
		}
	}

	class SftpUploadWorker extends SwingWorker<SftpUtil.RespHeader, Integer> {

		long countBytesCurrentSession;
		
		SftpUploadWorker() {
			countBytesCurrentSession = 0;
		}

		@Override
		protected SftpUtil.RespHeader doInBackground() throws Exception {

			browseButton.setEnabled(false);
			uploadButton.setEnabled(false);
			deleteButton.setEnabled(false);

			Socket sock = null;
			OutputStream sockOutputStream = null;
			InputStream sockInputStream = null;
			FileInputStream fis = null;
			SftpUtil.ChunkLimitInputStream chunkedStream = null;
			try {
				sock = new Socket(HOST, PORT);
				sock.setPerformancePreferences(SOCK_PF_CT, SOCK_PF_LL,
						SOCK_PF_HB);
				sock.setKeepAlive(true);
				sock.setSoTimeout(READ_TIMEOUT);
				sock.setTcpNoDelay(true);

				sockOutputStream = sock.getOutputStream();

				Thread recevieThread = new ReceiveThread(sock.getInputStream());
				recevieThread.start();

				long lastUploadedBytes = currentResp.files.get(0).uploadedBytes;
				long fileLen = uploadTargetFile.length();

				SftpUtil.ReqHeader header = SftpUtil.ReqHeader.create(
						currentResp.files.get(0).csid.trim(),
						currentResp.token.trim(), fileLen, fileLen
								- lastUploadedBytes);
				SftpUtil.sendReq(sockOutputStream, header);

				SftpUtil.sendCmd(sockOutputStream, SftpUtil.CMD_STX);

				fis = new FileInputStream(uploadTargetFile);
				fis.skip(lastUploadedBytes);
				chunkedStream = new SftpUtil.ChunkLimitInputStream(fis,
						SftpUtil.CHUNK_SIZE);
				byte[] buf = new byte[10240];
				int countOfClip = 0;

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

					publish(calcProgress(lastUploadedBytes
							+ countBytesCurrentSession, fileLen));
				}

				SftpUtil.sendCmd(sockOutputStream, SftpUtil.CMD_EOT);

				recevieThread.join();

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

			return null;
		}

		@Override
		protected void process(List<Integer> chunks) {
			for (Integer p : chunks) {
				progressBar.setValue(p);
			}
		}

		@Override
		protected void done() {
			// try {
			// //progressBar.setValue(calcProgress());
			// uploadDoneCallback(get());
			// } catch (Exception e) {
			// uploadDoneCallback(null);
			// }
		}
	}

	int calcProgress(float uploaded, float total) {
		return (int) (uploaded / total * 100);
	}

	class BrowseButtonActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent event) {
			fileChooser = new JFileChooser();
			fileChooser.setMultiSelectionEnabled(false);
			fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			if (JFileChooser.APPROVE_OPTION == fileChooser.showOpenDialog(null)) {
				jquery$Val(uploadFileElementId, BLANK_STR);
				uploadFileSelected(fileChooser.getSelectedFile());
			}
		}
	}

	class UploadButtonActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent event) {
			jquery$Val(uploadFileElementId, BLANK_STR);
			sftpUploadWorker = new SftpUploadWorker();
			sftpUploadWorker.execute();
		}
	}

	class DeleteButtonActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent event) {
			jquery$Val(uploadFileElementId, BLANK_STR);
			deleteCallback(httpDelete(initDeleteUri(currentResp)));
		}
	}

	public void alert(String msg) {
		JOptionPane.showMessageDialog(this, msg);
	}

	public boolean confirm(String msg) {
		return JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(this,
				msg) ? true : false;
	}

	@Override
	public void stop() {
		super.stop();
//		try {
//			HTTP_CLIENT.close();
//		} catch (IOException ioe) {
//		} finally {
//			try {
//				if (null != HTTP_CLIENT) {
//					HTTP_CLIENT.close();
//				}
//			} catch (Exception e) {
//				HTTP_CLIENT = null;
//			}
//		}
	}

	void jquery$Val(String eleId, String val) {
//		JSObject win = JSObject.getWindow(this);
//		win.eval("$('#" + eleId + "').val('" + val.trim() + "');");
	}

	public static class JsonResponse {
		public String token;
		public String flag;
		public String csId;
		public List<FileRefrence> files = new ArrayList<FileRefrence>();

		JsonResponse addFile(FileRefrence file) {
			files.add(file);
			return this;
		}
	}

	public static class FileRefrence {
		public String csid;
		public String name;
		public String url;
		public String thumbnailUrl;
		public long size;
		public long uploadedBytes;
		public String error;
		public String mimeType;
	}

	public static class FileAnalyse {

		private static final long MODULUS_B = 0;
		private static final long MODULUS_KB = 10;
		private static final long MODULUS_MB = 20;
		private static final long MODULUS_GB = 30;
		private static final long MODULUS_TB = 40;
		private static final long SIZE_B = 1;
		private static final long SIZE_KB = 1 << MODULUS_KB;
		private static final long SIZE_MB = 1 << MODULUS_MB;
		private static final long SIZE_GB = 1 << MODULUS_GB;
		private static final long SIZE_TB = SIZE_GB << MODULUS_KB;

		private static final String NUM_POINT = ".";
		private static final int DECIMAL_LIMIT = 3;

		private static final String UNIT_TB = "T ";
		private static final String UNIT_GB = "G ";
		private static final String UNIT_MB = "M ";
		private static final String UNIT_KB = "K ";
		private static final String UNIT_B = "B ";

		private static final long[] MODULUS = { MODULUS_TB, MODULUS_GB,
				MODULUS_MB, MODULUS_KB, MODULUS_B };
		private static final long[] SIZES = { SIZE_TB, SIZE_GB, SIZE_MB,
				SIZE_KB, SIZE_B };
		private static final String[] UNITS = { UNIT_TB, UNIT_GB, UNIT_MB,
				UNIT_KB, UNIT_B };

		private static final String ZERO_SEQ_TEMPLATE = "000";

		private static final String ZERO_BYTE = "0B";

		public static String number(long len) {
			if (1 > len)
				return ZERO_BYTE;
			boolean flag = false;
			StringBuilder integer = new StringBuilder();
			StringBuilder decimal = new StringBuilder();
			String unit = null;
			long x = 0;
			for (int i = 0; i < SIZES.length; i++) {
				x = len >> MODULUS[i];
				if (x < 1)
					continue;
				if (flag) {
					char[] sx = String.valueOf(x).toCharArray();
					char[] subDecimal = ZERO_SEQ_TEMPLATE.toCharArray();
					int limit = sx.length > subDecimal.length ? subDecimal.length
							: sx.length;
					for (int k = 0; k < sx.length && k < subDecimal.length; k++) {
						subDecimal[subDecimal.length - limit + k] = sx[k];
					}
					decimal.append(subDecimal);
					if (decimal.length() > 1)
						break;
				} else {
					integer.append(x);
					unit = UNITS[i];
					flag = true;
				}
				len = len - (x * SIZES[i]);
			}
			return integer.toString()
					+ (decimal.length() > 0 ? NUM_POINT
							+ (decimal.length() > 1 ? decimal.substring(0,
									DECIMAL_LIMIT) : decimal.toString()) : "")
					+ unit;
		}
	}
}
