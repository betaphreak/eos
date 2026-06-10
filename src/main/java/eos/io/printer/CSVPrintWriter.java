package eos.io.printer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import lombok.Getter;

/**
 * A print writer to write output to a CSV file
 * 
 * @author zhihongx
 * 
 */
public class CSVPrintWriter {

	
	// name of the output file
	@Getter
	private final String fileName;

	// output stream
	private final PrintWriter out;

	/**
	 * Create a new CSVPrinter
	 * 
	 * @param fileName
	 *            name of the output file
	 */
	public CSVPrintWriter(String fileName) {
		if (!fileName.endsWith(".csv"))
			fileName = fileName + ".csv";
		int i = fileName.lastIndexOf('\\');
		i = Math.max(i, fileName.lastIndexOf('/'));
		String dirName;
		if (i == -1) {
			dirName = "output";
			fileName = dirName + "/" + fileName;
		} else
			dirName = fileName.substring(0, i);
		this.fileName = fileName;
		File file = new File(dirName);
		if (!file.exists())
			file.mkdirs();
		// UTF-8, autoflush, system line separator (matches the prior writer)
		try {
			this.out = new PrintWriter(new OutputStreamWriter(
					new FileOutputStream(fileName), StandardCharsets.UTF_8), true);
		} catch (FileNotFoundException e) {
			throw new RuntimeException("could not open " + fileName, e);
		}
	}

	/**
	 * Print <tt>args</tt> separated by comma, followed by a new line character
	 * 
	 * @param args
	 *            arguments to be printed
	 */
	public void println(Object... args) {
		if (args.length == 0) {
			out.println();
			return;
		}
		for (int i = 0; i < args.length - 1; i++)
			out.print(args[i] + ",");
		out.println(args[args.length - 1]);
	}

	/**
	 * Print <tt>arg</tt>
	 * 
	 * @param arg
	 *            argument to be printed
	 */
	public void print(Object arg) {
		out.print(arg);
	}

	/**
	 * Print a delimiter
	 */
	public void printDelimiter() {
		out.print(",");
	}

	/**
	 * Clean up the print writer
	 */
	public void cleanup() {
		out.close();
	}
}
