package com.rst.tryx;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileTransferServer {

	private static final Logger LOGGER = Logger
			.getLogger(FileTransferServer.class.getName());

	private static final long PROBE_PERIOD = 5000; // unit (ms)
	
	private int srvPort = 62953;

	private int readTimeout = 20000; // 20s

	private int maxCountOfActiveProcess = 50;

	private static final String THREADGROUP_NAME = "PT_GROUP";
	
	private static final String PROBE_LOG_PREFIX = "PROBE LOG";
	
	private static final int MAX_PROBE_RESTART_TIMES = 10;
	
	private static final long DEFAULT_THREAD_SWITCH_DELAY = 6000; //unit (ms)

	private static final int SOCK_PF_CT = 0; // connection time.
	private static final int SOCK_PF_LL = 0; // low latency.
	private static final int SOCK_PF_HB = 1; // high bandwidth.
	
	private static FileTransferServer instance;

	private boolean isServerInstanceThreadStart = false;
	
	private boolean isProbeThreadStart = false;

	private boolean enableAccept = false;

	private ServerSocket serverSocket;

	private ThreadGroup processThreadPool;

	private Thread serviceThread;
	
	private Thread serverInstanceThread;
	
	private Thread probeThread;

	private int countOfActiveProcess;
	
	private UploadProcessor uploadProcessor;
	
	FileTransferServer() {}
	
	public synchronized static FileTransferServer getInstance() {
		if(null == instance) {
			instance = new FileTransferServer();
		}
		return instance;
	}
	
	public static interface UploadProcessor {
		public void process(Socket socket) throws IOException;
	}

	private class InitServiceThread extends Thread {

		private Object lock = new Object();

		@Override
		public void run() {

			// initial socket
			if (LOGGER.isLoggable(Level.INFO))
				LOGGER.log(Level.INFO, "init socket starting...");
			if (startServerSocket()) {
				if (LOGGER.isLoggable(Level.INFO))
					LOGGER.log(Level.INFO, "init socket success.");
			} else {
				if (LOGGER.isLoggable(Level.INFO))
					LOGGER.log(Level.INFO, "init socket failure.");
			}

			initThreadPool();

			// initial accept process
			switchServerInstanceThread(true);

			enableAccept = true;
			// block thread.
			try {
				synchronized (lock) {
					lock.wait();
				}
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
		}

		@Override
		public void interrupt() {
			super.interrupt();

			// disable accept connection..
			enableAccept = false;
			
			switchServerInstanceThread(false);
			//isServerInstanceThreadStart = false;
			
			destoryThreadPool();
			
			// close server instance thread.
			//isStart = false;
			if (LOGGER.isLoggable(Level.INFO))
				LOGGER.log(Level.INFO, "close socket starting...");
			if (closeServerSocket()) {
				if (LOGGER.isLoggable(Level.INFO))
					LOGGER.log(Level.INFO, "close socket success.");
			} else {
				if (LOGGER.isLoggable(Level.INFO))
					LOGGER.log(Level.INFO, "close socket failure.");
			}
		}

	};
	
	public boolean startup(int port, int maxConnection, int timout, UploadProcessor processor) {
		srvPort = port;
		readTimeout = timout;
		maxCountOfActiveProcess = maxConnection;
		
		return startup(processor);
	}
	
	public boolean startup(UploadProcessor processor) {
		try {
			if (null == processor) {
				uploadProcessor = new SimpleUploadProcessor();
			} else {
				uploadProcessor = processor;
			}
			// service start and wait connect
			switchServiceThread(true);
			switchProbeThread(true);
		} catch (Exception e) {
			return false;
		}
		return true;
	}

	public void shutdown() {
		//probeThread.interrupt();
		switchProbeThread(false);
		// stop server thread.
		switchServiceThread(false);
	}
	
	private synchronized void switchServiceThread(boolean status) {
		if ((status && null != serviceThread && serviceThread.isAlive())
				|| !status && null == serviceThread) {
			// keep status, do not need change.
			return;
		}
		if (null != serviceThread) {
			serviceThread.interrupt();
			try {
				Thread.sleep(DEFAULT_THREAD_SWITCH_DELAY);
			} catch (InterruptedException ie) {
			}
		}
		if (status) {
			serviceThread = new InitServiceThread();
			serviceThread.start();
			return;
		}
		//serviceThread = null;
	}
	
	private synchronized void initThreadPool() {
		processThreadPool = new ThreadGroup(THREADGROUP_NAME);
		countOfActiveProcess = 0;
	}
	
	private synchronized void destoryThreadPool() {
		while (processThreadPool.activeCount() > 0) {
			processThreadPool.interrupt();
		}
	}
	
	private synchronized void switchServerInstanceThread(boolean status) {
		if ((status && null != serverInstanceThread && serverInstanceThread.isAlive()) || !status
				&& null == serverInstanceThread) {
			// keep status, do not need change.
			return;
		}

		isServerInstanceThreadStart = false;
		try {
			Thread.sleep(DEFAULT_THREAD_SWITCH_DELAY);
		} catch (InterruptedException ie) {
		}
		if (status) {
			isServerInstanceThreadStart = true;
			serverInstanceThread = new ServerInstance();
			serverInstanceThread.start();
			return;
		}
		serverInstanceThread = null;
	}
	
	private synchronized void switchProbeThread(boolean status) {
		if ((status && null != probeThread && probeThread.isAlive()) || !status
				&& null == probeThread) {
			// keep status, do not need change.
			return;
		}

		isProbeThreadStart = false;
		try {
			Thread.sleep(DEFAULT_THREAD_SWITCH_DELAY);
		} catch (InterruptedException ie) {
		}
		if (status) {
			isProbeThreadStart = true;
			probeThread = new ProbeThread(null);
			probeThread.start();
			return;
		}
		probeThread = null;
	}
	
	private class ProbeThread extends Thread {
		
		private int restartFailCount = 0;
		
		private SystemFailListenter failListenter;
		
		private ProbeThread(SystemFailListenter listener) {
			this.setDaemon(true);
			failListenter = listener;
		}
		
		@Override
		public void run() {
			while(isProbeThreadStart) {
				try {
					sleep(PROBE_PERIOD);
				} catch (InterruptedException ie) {
				}
				try {
					switchServerInstanceThread(true);
					restartFailCount = 0;
					//LOGGER.log(Level.INFO, PROBE_LOG_PREFIX + " INFO: [success]");
				} catch (Exception e) {
					restartFailCount ++;
					LOGGER.log(Level.WARNING, PROBE_LOG_PREFIX + " WARN: [" + e.getMessage() + "]");
					if (restartFailCount > MAX_PROBE_RESTART_TIMES
							&& null != failListenter) {
						failListenter.fail(e.getMessage());
					}
				}
//				System.out.println("serviceThread: [alive: "
//						+ serviceThread.isAlive() + "][interrupted: "
//						+ serviceThread.isInterrupted() + "]");
//				System.out.println("serviceInstance: [alive: "
//						+ serverInstanceThread.isAlive() + "][interrupted: "
//						+ serverInstanceThread.isInterrupted() + "]");
//				System.out.println("socket: [closed: "
//						+ serverSocket.isClosed() + "][bound: "
//						+ serverSocket.isBound() + "]");
			}
		}
	}

	public static void main(String[] args) throws Exception {
		FileTransferServer.getInstance().startup(null);
		// Thread.sleep(5000);
		// shutdown();
	}
	
	private class ProcessThread extends Thread {

		private Socket socket;

		ProcessThread(Socket socket, ThreadGroup threadGroup, String tname) {
			super(threadGroup, tname);
			this.socket = socket;
			try {
				// prefers high bandwidth above latency and connection time.
				this.socket.setPerformancePreferences(SOCK_PF_CT, SOCK_PF_LL,
						SOCK_PF_HB);
				this.socket.setKeepAlive(true);
				this.socket.setSoTimeout(readTimeout);
				this.socket.setTcpNoDelay(true);
			} catch (SocketException se) {

			}
			processCounter(1);
		}

		@Override
		public void run() {
			try {
				uploadProcessor.process(socket);
			} catch (IOException ioe) {
			} finally {
				try {
					if (null != this.socket) {
						this.socket.close();
						if (LOGGER.isLoggable(Level.INFO))
							LOGGER.log(Level.INFO, "close connection.");
					}
				} catch (IOException ioe) {
					this.socket = null;
					if (LOGGER.isLoggable(Level.INFO))
						LOGGER.log(Level.INFO,
								"close connection with exception.");
				}
				processCounter(-1);
			}
		}
	}
	
	private class SimpleUploadProcessor implements UploadProcessor {
		@Override
		public void process(Socket socket) throws IOException {
		}
	}

	private synchronized void __initServerSocket() throws IOException {
		if (null == serverSocket || serverSocket.isClosed()) {
			serverSocket = new ServerSocket(srvPort);
		}
	}
	
	private synchronized void __destoryServerSocket() throws IOException {
		if (null != serverSocket && serverSocket.isBound()) {
			serverSocket.close();
		}
	}
	
	private boolean startServerSocket() {
		try {
			__initServerSocket();
			return true;
		} catch (IOException ioe) {
		}
		try {
			__destoryServerSocket();
			__initServerSocket();
			return true;
		} catch (IOException ioe) {
			System.exit(-1);
		}
		return false;
	}

	private boolean closeServerSocket() {
		try {
			__destoryServerSocket();
			return true;
		} catch (IOException ioe) {
			return false;
		} finally {
			if (null != serverSocket)
				serverSocket = null;
		}
	}

	private synchronized void processCounter(int count) {
		countOfActiveProcess += count;
	}

	private class ServerInstance extends Thread {
		ServerInstance() {
		}

		@Override
		public void run() {
			while (isServerInstanceThreadStart) {
				try {

					if (enableAccept
							&& countOfActiveProcess < maxCountOfActiveProcess) {
						Socket sock = serverSocket.accept();
						new ProcessThread(sock, processThreadPool, UUID
								.randomUUID().toString()).start();
					}

				} catch (IOException ioe) {
					if (null == serverSocket || serverSocket.isClosed()) {
						if (!startServerSocket()) {
							isServerInstanceThreadStart = false;
						}
					}
				}
			}
		}
	}
	
	interface SystemFailListenter {
		void fail(String errorMsg);
	}
}
