/*
This source code is copyrighted by Christophe Cerisara, CNRS, France.

It is licensed under the terms of the INRIA Cecill-C licence, as described in:
http://www.cecill.info/licences/Licence_CeCILL-C_V1-en.html
 */

package fr.loria.synalp.jtrans.utils;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;

public class FileUtils {
	/** @deprecated use getUTF8Reader instead */
	public static BufferedReader openFileUTF(String nom) throws FileNotFoundException {
		return getUTF8Reader(new File(nom));
	}

	public static BufferedReader openFileISO(String nom) throws UnsupportedEncodingException, FileNotFoundException {
		FileInputStream fis = new FileInputStream(nom);
		return new BufferedReader(new InputStreamReader(fis, "ISO-8859-1"));
	}

	/** @deprecated use getUTF8Writer instead */
	public static PrintWriter writeFileUTF(String nom) throws FileNotFoundException {
		return new PrintWriter(getUTF8Writer(new File(nom)));
	}

	public static BufferedReader getUTF8Reader(File f) throws FileNotFoundException {
		return new BufferedReader(new InputStreamReader(
				new FileInputStream(f),
				Charset.forName("UTF-8").newDecoder()));
	}

	public static Writer getUTF8Writer(File f) throws FileNotFoundException {
		// http://stackoverflow.com/a/9853261
		return new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(f),
				Charset.forName("UTF-8").newEncoder()));
	}

	/**
	 * enleve l'extension d'un nom de fichier
	 */
	public static String noExt(String fich) {
		int i = fich.lastIndexOf('.');
		if (i < 0) {
			return fich;
		}
		return fich.substring(0, i);
	}



	private static long bytesToLong(byte[] b, int len) {
		long magic = 0;
		for (int i = 0; i < len; i++)
			magic = (magic << 8) | (b[i]&0xff);
		return magic;
	}


	/**
	 * Returns a BufferedReader ready to use with the right encoding for the
	 * given file. Any byte order marks are skipped automatically.
	 *
	 * Detects Unicode byte order marks and falls back to juniversalchardet's
	 * detection if needed.
	 */
	public static BufferedReader openFileAutoCharset(File file) throws IOException {
		String encoding = null;
		int skip = 0;

		InputStream bis = new FileInputStream(file);

		byte[] b = new byte[4];
		int len = bis.read(b);

		if (len >= 2) {
			long bom = bytesToLong(b, 2);
			if (0xFFFE == bom) {
				encoding = "UnicodeLittleUnmarked";
				skip = 2;
			} else if (0xFEFF == bom) {
				encoding = "UnicodeBigUnmarked";
				skip = 2;
			}
		}

		if (len >= 3 && 0xEFBBBF == bytesToLong(b, 3)) {
			encoding = "UTF8";
			skip = 3;
		}

		if (encoding == null) {
			b = new byte[4096];

			UniversalDetector detector = new UniversalDetector(null);

			int nread;
			while ((nread = bis.read(b)) > 0 && !detector.isDone()) {
				detector.handleData(b, 0, nread);
			}

			detector.dataEnd();
			encoding = detector.getDetectedCharset();
		}

		bis.close();
		bis = new FileInputStream(file);
		if (skip > 0)
			bis.skip(skip);

		if (encoding != null) {
			System.out.println("Detected encoding: " + encoding);
			return new BufferedReader(new InputStreamReader(bis, encoding));
		} else
			return new BufferedReader(new InputStreamReader(bis));
	}


	/**
	 * Downloads a file. The thread may be interrupted.
	 * @param url         Data will be fetched from this address
	 * @param target      Data will be saved in this file
	 * @param progress    Receives periodic updates about completion rate
	 *                    and transfer speed
	 */
	public static void downloadFile(URL url, File target, ProgressDisplay progress)
			throws IOException, InterruptedException
	{
		URLConnection con = url.openConnection();
		con.connect();

		// do not rely on getContentLength ? It's often wrong
		long len = con.getContentLength();
		System.out.println("downloading "+url);
		long downloadedLen = 0;

		byte[] buf = new byte[8192];
		int read;

		InputStream in = null;
		FileOutputStream os = null;

		long lastSpeedUpdate = System.currentTimeMillis();
		long accumBytes = 0;
		int bps = 0;

		try {
			in = con.getInputStream();
			os = new FileOutputStream(target);

			while ((read = in.read(buf)) > 0) {
				os.write(buf, 0, read);
				downloadedLen += read;

				long now = System.currentTimeMillis();
				long window = now - lastSpeedUpdate;

				if (window > 50) {
					bps = (int)(accumBytes / (window / 1000f));

					lastSpeedUpdate = now;
					accumBytes = 0;
				} else {
					accumBytes += read;
				}

				progress.setProgress(
					String.format("Downloading " + url + "... (%d KB/s)", bps/1024),
					(float)downloadedLen/(float)len);

				// Allow cancellation
				Thread.sleep(0);
			}
			System.out.println("read nbytes "+read);
		} finally {
			closeQuietly(os);
			closeQuietly(in);
			System.out.println("downloaded "+target.length());
		}
	}


	/**
	 * Closes a stream without throwing an exception.
	 */
	public static void closeQuietly(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}


	/**
	 * Detects a file in the same directory as a reference file, bearing the
	 * same name except for the extension.
	 * @param file reference file
	 * @param extensions allowed extensions, without the initial period, sorted
	 *                   by priority (highest priority first)
	 * @return a file, or null if no homonymous file was found
	 */
	public static File detectHomonymousFile(File file, String... extensions) {
		// Try to detect audio file from the project's file name
		String pfn = file.getName();
		for (String ext: extensions) {
			File f = new File(file.getParentFile(),
					pfn.substring(0, pfn.lastIndexOf('.')) + "." + ext);
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}


	/**
	 * Creates a temporary file that will be deleted when the JVM shuts down.
	 * @throws IOException
	 */
	public static File createVanishingTempFile(String prefix, String suffix)
			throws IOException
	{
		File tempFile = File.createTempFile(prefix, suffix);
		tempFile.deleteOnExit();
		System.out.println("createVanishingTempFile: " + tempFile);
		return tempFile;
	}

}
