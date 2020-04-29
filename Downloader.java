import java.net.*;
import java.io.*;
import java.util.*;
import javax.net.ssl.*;
import java.security.cert.*;
import java.util.concurrent.locks.*;


public class Downloader {

	private static final int BUFFER_SIZE = 8192 * 32; // 32 * 8KB

	private static final int MAX_THREAD_COUNT = 64;

	private static final int TIME_OUT = 5000;

	private static final long SWITCH_NEW_THREAD = 1024 * 1024 * 2; //2MB

	private static void saveFilePart(URL url,long startIndex,long length,File outFile,String referer) throws Throwable {
		URLConnection connection = null;
		FileOutputStream fos = null;
		InputStream is = null;
		try {
			connection = url.openConnection();
			if(referer != null) {
        		connection.setRequestProperty("Referer",referer);
        	}
			connection.setConnectTimeout(TIME_OUT);
        	connection.setDoInput(true);
        	connection.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0");
        	connection.setRequestProperty("Accept-Language","zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3");
        	connection.setRequestProperty("Accept-Encoding","utf-8");
        	connection.setRequestProperty("Connection","keep-alive");
        	connection.setRequestProperty("Range","bytes=" + startIndex + "-" + (startIndex + length - 1));
        	connection.connect();
        	if(!outFile.exists()) {
        		outFile.createNewFile();
        	}
        	fos = new FileOutputStream(outFile);
        	is = connection.getInputStream();
        	byte[] buffer = new byte[BUFFER_SIZE];
        	int count;
        	while((count = is.read(buffer)) != -1) {
        		fos.write(buffer,0,(int)Math.min(count,length));
        		length -= count;
        		if(length <= 0) {
        			break;
        		}
        	}
        	fos.flush();
    	} finally {
    		if(is != null) {
    			is.close();
    		}
    		if(fos != null) {
    			fos.close();
    		}
    		if(connection != null) {
    			if(connection instanceof HttpURLConnection) {
    				((HttpURLConnection)connection).disconnect();
    			}
    			if(connection instanceof HttpsURLConnection) {
    				((HttpsURLConnection)connection).disconnect();
    			}
    		}
    	}
	}

	private static class ReadParams {

		private URL url;
		private long startIndex,length;
		private File outFile;
		private String referer;

		public ReadParams(URL url,long startIndex,long length,File outFile,String referer) {
			this.url = url;
			this.startIndex = startIndex;
			this.length = length;
			this.outFile = outFile;
			this.referer = referer;
		}

		public void invoke() throws Throwable {
			saveFilePart(url,startIndex,length,outFile,referer);
		}

	} 

	private static class Wrapper {

		private volatile int value = 0;
		private List<ReadParams> failedParts = new ArrayList<>();
		private Lock lock = new ReentrantLock(true);

		public void onSuccess() {
			lock.lock();
			value++;
			//print("Thread finish (Status:Succeeded):" + (value + failedParts.size()));
			lock.unlock();
		}

		public void onFailed(ReadParams params) {
			lock.lock();
			failedParts.add(params);
			//print("Thread finish (Status:Failed):" + (value + failedParts.size()));
			lock.unlock();
		}

		private boolean shouldWait(int target) {
			if(lock.tryLock()) {
				boolean result = (value + failedParts.size() != target);
				lock.unlock();
				return result;
			}else{
				return true;
			}
		}

		public void observe(int target) throws InterruptedException {
			while(shouldWait(target)) {
				Thread.sleep(50);
			}
		}

	}

	private static class SaveThread extends Thread {

		private Wrapper wrapper;
		private ReadParams params;

		public SaveThread(Wrapper state,ReadParams params) {
			wrapper = state;
			this.params = params;
		}

		@Override
		public void run() {
			try {
				params.invoke();
				wrapper.onSuccess();
			} catch(Throwable e) {
				e.printStackTrace();
				wrapper.onFailed(params);
			}
		}

	}

	private static long getFileSize(URL url,String referer) throws Exception {
		URLConnection connection = url.openConnection();
		if(referer != null) {
			connection.setRequestProperty("Referer",referer);
		}
		connection.setConnectTimeout(TIME_OUT);
        connection.setDoInput(true);
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0");
        connection.setRequestProperty("Accept-Language","zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3");
        connection.setRequestProperty("Accept-Encoding","utf-8");
        connection.setRequestProperty("Connection","keep-alive");
        long size = connection.getContentLength();
    	if(connection instanceof HttpURLConnection) {
    		((HttpURLConnection)connection).disconnect();
    	}
    	if(connection instanceof HttpsURLConnection) {
    		((HttpsURLConnection)connection).disconnect();
    	}
        return size;
	}

	private static void saveFile(String urlStr) throws Exception {
		long startTime = System.currentTimeMillis();
		urlStr = urlStr.replace("i.pximg.net","i.pixiv.cat");
		URL url = new URL(urlStr);
		String fileName = urlStr.substring(urlStr.lastIndexOf("/") + 1);
		String referer = null;
		try {
			String artworkId = urlStr.substring(urlStr.lastIndexOf("/") + 1,urlStr.lastIndexOf("_"));
			referer = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=" + artworkId;
		}catch(StringIndexOutOfBoundsException ignored) {}
		long size = getFileSize(url,referer);
		if(size <= 0) {
			throw new IllegalStateException("Stream return zero or negative number for size");
		}
		print("File size:" + size + " bytes");
		int threadCount = (int)Math.min(MAX_THREAD_COUNT,size / SWITCH_NEW_THREAD + (size % SWITCH_NEW_THREAD != 0 ? 1 : 0));
		print("Thread count:" + threadCount);
		Wrapper wrapper = new Wrapper();
		long sizeEach = size / threadCount;
		for(int i = 0;i < threadCount - 1;i++) {
			new SaveThread(wrapper,new ReadParams(url,i * sizeEach,sizeEach,new File(fileName + "." + i),referer)).start();
		}
		new SaveThread(wrapper,new ReadParams(url,(threadCount - 1) * sizeEach,size - (threadCount - 1) * sizeEach,new File(fileName + "." + (threadCount - 1)),referer)).start();
		wrapper.observe(threadCount);
		List<ReadParams> failedParts = wrapper.failedParts;
		boolean success = false;
		if(failedParts.size() != 0) {
			print("Failed to save " + wrapper.failedParts.size() + " part(s) of image.");
			print("Do you want to retry?(Y for yes)");
			boolean retry = input.next().toLowerCase().equals("y");
			while(retry) {
				while(failedParts.size() > 0) {
					new SaveThread(wrapper,failedParts.remove(0)).start();
				}
				wrapper.observe(threadCount);
				if(failedParts.size() != 0) {
					print("Failed to save " + wrapper.failedParts.size() + " part(s) of image.");
					print("Do you want to retry?(Y for yes)");
					retry = input.next().toLowerCase().equals("y");
				}else{
					retry = false;
					success = true;
				}
			}
		}else{
			success = true;
		}
		if(success) {
			float seconds = (System.currentTimeMillis() - startTime) / 1000F;
			print("Succeeded at speed of " + (size / 1024 / seconds) + " KB/S");
			startTime = System.currentTimeMillis();
			print("Merging...");
			FileOutputStream fos = null;
			InputStream is = null;
			boolean closed = true;
			try {
				File out = new File(fileName);
				if(!out.exists()) {
					out.createNewFile();
				}
				fos = new FileOutputStream(out);
				byte[] buffer = new byte[BUFFER_SIZE];
				for(int i = 0;i < threadCount;i++) {
					File sub = new File(fileName + "." + i);
					is = new FileInputStream(sub);
					closed = false;
					int count;
					while((count = is.read(buffer)) != -1) {
						fos.write(buffer,0,count);
					}
					is.close();
					sub.delete();
					closed = true;
				}
				fos.flush();
			}finally{
				if(fos != null) {
					fos.close();
				}
				if(is != null && !closed) {
					is.close();
				}
			}
			seconds = (System.currentTimeMillis() - startTime) / 1000F;
			print("Merged in " + seconds + " s");
		}else{
			for(int i = 0;i < threadCount;i++) {
				File file = new File(fileName + "." + i);
				if(file.exists()) {
					file.delete();
				}
			}
			float seconds = (System.currentTimeMillis() - startTime) / 1000F;
			print("Failed after " + seconds + " s");
		}
	}

	private static Scanner input = null;

	public static void main(String[] args) throws Exception {
		input = new Scanner(System.in);
		print("You must place a text file named 'urls.txt' under current directory.");
		String[] urls = readFile(new File("urls.txt")).split("\n");
		print("URL count:" + urls.length);
		ignoreSsl();
		for(int i = 0;i < urls.length;i++) {
			String url = urls[i];
			print("Downloading " + (i + 1) + " of " + urls.length + "...");
			try {
				saveFile(url);
			}catch(Exception e) {
				e.printStackTrace();
				print("Failed to save file.");
				print("Do you want to retry?(Y for yes)");
				if(input.next().toLowerCase().equals("y")) {
					i--;
					continue;
				}
			}
			Thread.sleep(200);
		}
		input.close();
		print("All tasks finished.");
	}

	public static void trustAllHttpsCertificates() throws Exception {  
        TrustManager[] trustAllCerts = new TrustManager[1];  
        TrustManager tm = new TrustAllManager();  
        trustAllCerts[0] = tm;  
        SSLContext sc = SSLContext.getInstance("SSL");  
        sc.init(null, trustAllCerts, null);  
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());  
    }

    private static class TrustAllManager implements TrustManager,X509TrustManager {  
        public X509Certificate[] getAcceptedIssuers() {  
            return null;  
        }  
   
        public boolean isServerTrusted(X509Certificate[] certs) {  
            return true;  
        }  
   
        public boolean isClientTrusted(X509Certificate[] certs) {  
            return true;  
        }  
   
        public void checkServerTrusted(X509Certificate[] certs, String authType)  
                throws CertificateException {
        }  
   
        public void checkClientTrusted(X509Certificate[] certs, String authType)  
                throws CertificateException {
        }  
    }  

    private static class MyVerifier implements HostnameVerifier {

		public boolean verify(String urlHostName, SSLSession session) {  
        	return true;  
        }  

    }

    public static void ignoreSsl() throws Exception {  
        HostnameVerifier hv = new MyVerifier();
        trustAllHttpsCertificates();  
        HttpsURLConnection.setDefaultHostnameVerifier(hv);  
    }  

    private static String readFile(File file) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(file));
		StringBuilder sb = new StringBuilder();
		String line;
		while((line = br.readLine()) != null) {
			sb.append(line).append('\n');
		}
		if(sb.length() != 0) {
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}

	private static void print(Object info) {
		System.out.println(info);
	}

}