package com.bukkit.sharkiller.MCSignOnDoorRTK;

import com.bukkit.sharkiller.MCSignOnDoorRTK.Config.*;
import com.drdanick.McRKit.module.Module;
import com.drdanick.McRKit.module.ModuleLoader;
import com.drdanick.McRKit.module.ModuleMetadata;
import com.drdanick.McRKit.ToolkitEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.InvalidParameterException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//This class defines a skeleton module that will be enabled when the server is stopped, and disabled when the server is started.
@SuppressWarnings("unused")
public class MCSignOnDoorRTK extends Module{
	private static final Logger LOG = Logger.getLogger("MCSOD");
	private static final String VERSION = "1.3";
	private PropertiesFile ServerConfig = new PropertiesFile("server.properties");
	
	private static ServerSocket serve;
	private static int port;
	private static InetAddress ip = null;
	private static String holdMessage = "The server is not currently running.";
	
	public MCSignOnDoorRTK(ModuleMetadata meta, ModuleLoader moduleLoader, ClassLoader cLoader){
		super(meta,moduleLoader,cLoader,ToolkitEvent.ON_SERVER_HOLD,ToolkitEvent.ON_SERVER_RESTART);
		//the last two parameters define the events where the plugin is enabled and disabled respectively.
		
		//fix formatting on logger
		Logger rootlog = Logger.getLogger("");
		for (Handler h : rootlog.getHandlers()){ //remove all handlers
			h.setFormatter(new McSodFormatter());
		}

		registerCommand("msghold","<message>     Change the kick message when the server is on hold.");
	
	}
	
	public void onConsoleCommand(String command,String arguments){
		if(command.equals("msghold")){
			if(arguments.length() > 4){
				holdMessage = arguments;
				ServerConfig.setString("hold-message", arguments);
				if(arguments.length() > 80){
					LOG.warning("[MCSignOnDoorRTK] Message length exceeds 80 characters.");
					LOG.warning("[MCSignOnDoorRTK] Messages don't wrap on the client, even with newline characters, and may be cut off when shown.");
				}else
					LOG.info("[MCSignOnDoorRTK] Message changed successfully.");
			}else
				LOG.warning("[MCSignOnDoorRTK] Message is to short. Minimum 5 characters.");
		}
	}

	public static class ResponderThread extends Thread {
		private Socket sock;
		private BufferedInputStream in;
		private BufferedOutputStream out;

		ResponderThread(Socket s) {
			sock = s;
			try {
				in = new BufferedInputStream(s.getInputStream());
				out = new BufferedOutputStream(s.getOutputStream());
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "IOException while setting up a responder thread!", e);
			}
		}

		@Override public void run() {
			try {
				byte[] inbyte = new byte[256];
				// char[] inchars = new char[128];
				/*
					sendConnect();
					sendMessage("123456789101112");
				 */
				in.read(inbyte, 0, 1); //read connect byte

				in.read(inbyte, 1, 2); //read message length
				int len = parseChar(inbyte, 1);
				in.read(inbyte, 3, len*2); //read message

				{
					ByteBuffer bb = ByteBuffer.wrap(Arrays.copyOfRange(inbyte, 3, (len+1)*2+1));
					CharsetDecoder d = Charset.forName("UTF-16BE").newDecoder();
					CharBuffer cb = d.decode(bb);
					LOG.info("[MCSignOnDoorRTK] Reported client name: "+ cb.toString());
				}

				// LOG.info("Reported client name: "+ new String(Arrays.copyOfRange(inbyte, 3, 2+len), Charset.forName("UTF-8")));

				sendDisconnect(holdMessage);
				// sendMessage(awayMessage);
				sock.close();
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "IOException while processing client!", e);
			}
		}

		public short parseChar(byte[] arr, int off){
			final int LEN = 2; //long are 8 bytes long
			if (arr.length < LEN) throw new InvalidParameterException();

			byte[] b = Arrays.copyOfRange(arr, off, off+LEN);
			short value = 0;

			for (int i = 0; i < LEN; i++) {
				int offset = (b.length - 1 - i) * 8;
				long v = 0xFF & b[i]; //see note above
				value |= v << offset;
			}
			return value;
		}

		private void sendDisconnect(String message) throws IOException{
			ByteBuilder bb = new ByteBuilder();
			bb.append((byte)0xFF);
			bb.appendSpecial(message.length(), 2, false);
			bb.append(message);

			// System.out.println(bb.toString());
			out.write(bb.toByteArray());
			out.flush();
		}
		/*
		@SuppressWarnings("unused")
		private void sendConnect() throws IOException{
		out.write(new byte[]{(byte) 0x02});
		out.flush();
		}
		@SuppressWarnings("unused")
		private void sendAck() throws IOException{
		out.write(new byte[]{(byte) 0x01});
		out.flush();
		}
		private void sendMessage(String message) throws IOException{
		ByteBuilder bb = new ByteBuilder();
		bb.append((byte)0x0);
		bb.appendSpecial(message.length(), 1, false);
		bb.append(message);
		// System.out.println(bb.toString());
		out.write(bb.toByteArray());
		out.flush();
		}*/
	}

	public static class LoginListener extends Thread {

		public void run(){
			try{
				if (ip == null){
					serve = new ServerSocket(port);
				} else {
					serve = new ServerSocket(port, 50, ip);
				}
				
				while(!serve.isClosed()){
					try {
						Socket s = serve.accept();
						LOG.info("[MCSignOnDoorRTK] Received connection from: "+s.getInetAddress().getHostAddress());
						new ResponderThread(s).start();
					} catch (IOException e){}
				}
				
			} catch (SecurityException e){
				LOG.severe("Security exception while binding socket! Cannot start server.");
				System.exit(-1);
			} catch (BindException e){
				LOG.severe("Cannot bind to port "+port+".");
				if (ip != null) LOG.severe("Make sure the IP address entered is a valid IP address for this computer!");
				LOG.severe("Make sure the Minecraft Server is not still running and no other instances of MCSignOnDoor are running!");
				System.exit(-1);
			} catch (Exception e) {
				LOG.log(Level.SEVERE, "Unhandled exception in main loop!", e);
			} finally {
				try {serve.close();} catch (IOException e) {}
			}
			
		}
	}
	
	protected void SetupConfig(){
		try {
			ServerConfig.load();

			if(!ServerConfig.keyExists("hold-message"))
				ServerConfig.setString("hold-message", "The server is not currently running.");

			holdMessage = ServerConfig.getString("hold-message", "The server is not currently running.");

			if(holdMessage.length() > 4){
				if(holdMessage.length() > 80){
					LOG.warning("[MCSignOnDoorRTK] Message length exceeds 80 characters.");
					LOG.warning("[MCSignOnDoorRTK] Messages don't wrap on the client, even with newline characters, and may be cut off when shown.");
				}
			}else{
				LOG.warning("[MCSignOnDoorRTK] Message is to short. Minimum 5 characters.");
				LOG.warning("[MCSignOnDoorRTK] Using default message.");
				holdMessage = "The server is not currently running.";
			}

			String ipaux = ServerConfig.getString("server-ip", null);
			if(ipaux != null && !ipaux.equals("")){
				LOG.info("ipaux: "+ipaux+" - "+ipaux.length());
				ip = InetAddress.getByName(ipaux);
			}
				

			port = ServerConfig.getInt("server-port", 25565);
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "[MCSignOnDoorRTK] Cannot load server properties.", e);
		}
	}

	protected void onEnable(){
		LOG.info("[MCSignOnDoorRTK] Module enabled! v"+VERSION);
		LOG.info(" - Developed by Tustin2121 and adapted by Sharkiller");
		
		SetupConfig();
		
		//Starting server
		if (ip != null)
			LOG.info("[MCSignOnDoorRTK] Starting server on "+ip+":"+port);
		else 
			LOG.info("[MCSignOnDoorRTK] Starting server on port "+port);
		
		LOG.info("[MCSignOnDoorRTK] Message: "+holdMessage);
		
		if (holdMessage.length() > 80){
			LOG.warning("[MCSignOnDoorRTK] Message length exceeds 80 characters.");
			LOG.warning("[MCSignOnDoorRTK] Messages don't wrap on the client, even with newline characters, and may be cut off when shown.");
		}

		new LoginListener().start();
	}
	
	protected void onDisable(){
		try {
			LOG.info("[MCSignOnDoorRTK] Stopping message server.");
			if (serve != null) serve.close();
		} catch (IOException e) {}
		LOG.info("[MCSignOnDoorRTK] Module disabled!");
	}
}

class McSodFormatter extends Formatter {
	SimpleDateFormat dformat;

	public McSodFormatter(){
		dformat = new SimpleDateFormat("HH:mm:ss");
	}

	@Override
	public String format(LogRecord record) {
		StringBuffer buf = new StringBuffer();
		buf.append(dformat.format(new Date(record.getMillis())))
		.append(" [").append(record.getLevel().getName()).append("] ")
		.append(this.formatMessage(record)).append('\n');
		if (record.getThrown() != null){
			buf.append('\t').append(record.getThrown().toString()).append('\n');
		}
		return buf.toString();
	}

}